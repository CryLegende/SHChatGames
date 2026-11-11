package org.Cry_Legende.sHChatGames;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHChatGames.network.NetBus;
import org.Cry_Legende.sHChatGames.network.ShNetworkBus;
import org.Cry_Legende.sHChatGames.network.WinsDatabase;
import org.Cry_Legende.sHChatGames.utils.ActionLog;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;

public class SHChatGames extends JavaPlugin implements Listener, TabExecutor {
    // Lue aussi par le fil du bus et les fils asynchrones : remplacée en entier à chaque rechargement
    private volatile FileConfiguration config;
    private FileConfiguration messages;
    private List<String> words;
    private final Random random = new Random();
    // Lu par le thread de chat (asynchrone) et écrit par le thread principal
    private volatile Game currentGame = null;
    private BukkitTask timeoutTask;
    private BukkitTask scheduleTask;
    // Heure (ms) du prochain lancement prévu par ce serveur, 0 s'il n'en planifie pas
    private volatile long nextGameAt;
    // Non nul seulement avec SHNetwork : un seul jeu pour tout le réseau, victoires en base
    private NetworkGames net;

    private static final Set<String> GAME_TYPES = new LinkedHashSet<>(List.of("type", "unreverse", "solve", "sequence", "click"));

    /** Résultat d'une tentative de lancement. */
    private enum Start { STARTED, TOO_FEW_PLAYERS, NO_GAME_ENABLED, NOT_PLAYABLE, NOT_LEADER, BUSY, UNKNOWN_TYPE }

    /** Énoncé d'un jeu : ce qui est affiché et ce qui est attendu. */
    private record Puzzle(String type, String content, String answer) {
    }

    /** Groupe de récompenses tiré au sort : sa clé dans config.yml et ses commandes. */
    record Reward(String key, List<String> commands) {
    }

    private File winsFile;
    private FileConfiguration winsConfig;
    // winsConfig est lu/écrit par le chat (async), les clics (principal) et PlaceholderAPI : tout passe par ce verrou
    private final Object winsLock = new Object();
    private final Object winsFileLock = new Object();
    private long winsSeq;
    private long winsWrittenSeq;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        loadMessages();
        loadWords();
        loadWins();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("shchatgames");
        if (cmd != null) cmd.setExecutor(this);
        PluginCommand clickCmd = getCommand("shcgclick");
        if (clickCmd != null) clickCmd.setExecutor(this);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new ShChatGamesExpansion(this).register();
        }
        enableNetwork();
        // Avec SHNetwork, seul le meneur planifie les jeux : le planificateur démarre quand il est désigné
        if (net == null) scheduleNext();
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) timeoutTask.cancel();
        if (net != null) {
            net.disable();
        } else {
            saveWins();
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private void enableNetwork() {
        if (!config.getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        WinsDatabase db = null;
        try {
            NetBus bus = ShNetworkBus.open();
            db = new WinsDatabase(
                config.getString("reseau.base-de-donnees.url"),
                config.getString("reseau.base-de-donnees.utilisateur"),
                config.getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, config.getInt("reseau.base-de-donnees.delai-ms", 3000)));
            NetworkGames games = new NetworkGames(this, bus, db, config.getString("reseau.canal", "chatgames"));
            net = games;
            games.enable();
            getLogger().info("SHNetwork détecté : un seul jeu du chat pour tout le réseau, victoires en base.");
        } catch (RuntimeException | LinkageError e) {
            net = null;
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, jeux locaux à ce serveur : " + e);
        }
    }

    /** Appelé par le meneur quand il est désigné : le planificateur repart d'un délai neuf. */
    void startScheduler() {
        stopScheduler();
        scheduleNext();
    }

    /** Appelé quand ce serveur n'est plus meneur. */
    void stopScheduler() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
        nextGameAt = 0L;
    }

    FileConfiguration config() {
        return config;
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        // Un messages.yml plus ancien n'a pas les textes ajoutés depuis : ils viennent du fichier du jar
        try (InputStream in = getResource("messages.yml")) {
            if (in != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().warning("Impossible de lire messages.yml du jar: " + e.getMessage());
        }
    }

    private String message(String key, String def) {
        return ChatColor.translateAlternateColorCodes('&', messages.getString(key, def));
    }

    /** Texte de messages.yml (ou, à défaut, celui du jar). */
    private String message(String key) {
        String raw = messages.getString(key);
        return ChatColor.translateAlternateColorCodes('&', raw != null ? raw : key);
    }

    private void loadWins() {
        winsFile = new File(getDataFolder(), "wins.yml");
        if (!winsFile.exists()) saveResource("wins.yml", false);
        winsConfig = YamlConfiguration.loadConfiguration(winsFile);
    }

    /** Écriture synchrone (arrêt du plugin). */
    private void saveWins() {
        String data;
        long seq;
        synchronized (winsLock) {
            data = winsConfig.saveToString();
            seq = ++winsSeq;
        }
        writeWins(data, seq);
    }

    /** L'instantané est pris sous le verrou, l'écriture du fichier part hors du thread appelant. */
    private void saveWinsAsync() {
        String data;
        long seq;
        synchronized (winsLock) {
            data = winsConfig.saveToString();
            seq = ++winsSeq;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeWins(data, seq));
    }

    private void writeWins(String data, long seq) {
        synchronized (winsFileLock) {
            if (seq < winsWrittenSeq) return; // un instantané plus récent a déjà été écrit
            // Fichier temporaire puis remplacement : un arrêt en plein milieu ne tronque pas wins.yml
            File tmp = new File(winsFile.getParentFile(), winsFile.getName() + ".tmp");
            try {
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), winsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                winsWrittenSeq = seq;
            } catch (IOException e) {
                getLogger().warning("Impossible de sauvegarder wins.yml: " + e.getMessage());
            }
        }
    }

    int incrementWins(String player) {
        int wins;
        synchronized (winsLock) {
            wins = winsConfig.getInt(player, 0) + 1;
            winsConfig.set(player, wins);
        }
        saveWinsAsync();
        return wins;
    }

    int getWins(String player) {
        synchronized (winsLock) {
            return winsConfig.getInt(player, 0);
        }
    }

    /** Victoires d'un joueur connecté : celles du réseau (base) avec SHNetwork, wins.yml sinon. */
    int getWins(Player player) {
        return net != null ? net.wins(player.getUniqueId()) : getWins(player.getName());
    }

    private void scheduleNext() {
        String intervalStr = config.getString("interval", "300-300").trim();
        int delaySec;
        try {
            if (intervalStr.contains("-")) {
                String[] p = intervalStr.split("-");
                int a = Integer.parseInt(p[0].trim());
                int b = Integer.parseInt(p[1].trim());
                int min = Math.min(a, b);
                int max = Math.max(a, b);
                delaySec = random.nextInt(max - min + 1) + min;
            } else {
                delaySec = Integer.parseInt(intervalStr);
            }
        } catch (RuntimeException e) {
            // Un « interval » illisible ne doit pas arrêter définitivement les jeux
            getLogger().warning("Valeur 'interval' invalide (" + intervalStr + "), 300 s utilisées.");
            delaySec = 300;
        }
        delaySec = Math.max(1, delaySec);
        scheduleIn(delaySec * 20L);
    }

    private void scheduleIn(long ticks) {
        nextGameAt = System.currentTimeMillis() + ticks * 50L;
        scheduleTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (net != null) {
                // Plus meneur : le planificateur s'arrête, le nouveau meneur prend la suite
                if (!net.isLeader()) return;
                // Le jeu d'un meneur précédent n'est pas fini : on attend sa fin plus la marge, sans entamer un nouvel intervalle
                long wait = net.waitBeforeStartMs();
                if (wait > 0) {
                    scheduleIn(wait / 50L + 1L);
                    return;
                }
            }
            startRandomGame();
            scheduleNext();
        }, ticks);
    }

    private void loadWords() {
        File file = new File(getDataFolder(), config.getString("words-file", "words.yml"));
        if (!file.exists()) saveResource(config.getString("words-file", "words.yml"), false);
        words = new ArrayList<>();
        try (var br = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) words.add(line);
            }
        } catch (IOException e) {
            getLogger().warning("Impossible de lire le fichier de mots: " + e.getMessage());
        }
    }

    private void startRandomGame() {
        startGame(null, false);
    }

    /**
     * Lance un jeu. {@code forcedType} vient de /shchatgames start (null = tirage au hasard parmi enabled_games) ;
     * {@code forced} ignore min-players et refuse de remplacer un jeu en cours.
     */
    private Start startGame(String forcedType, boolean forced) {
        // Avec SHNetwork, seul le meneur lance des jeux
        if (net != null && !net.isLeader()) return Start.NOT_LEADER;
        if (!forced && playersCount() < config.getInt("min-players", 1)) return Start.TOO_FEW_PLAYERS;
        if (forced && (net != null ? net.isRunning() : currentGame != null)) return Start.BUSY;
        String type;
        if (forcedType != null) {
            if (!GAME_TYPES.contains(forcedType)) return Start.UNKNOWN_TYPE;
            type = forcedType;
        } else {
            List<String> types = config.getStringList("enabled_games");
            if (types.isEmpty()) return Start.NO_GAME_ENABLED;
            type = types.get(random.nextInt(types.size())).toLowerCase(Locale.ROOT);
        }
        Puzzle puzzle = makePuzzle(type);
        if (puzzle == null) return Start.NOT_PLAYABLE;
        String content = puzzle.content(), answer = puzzle.answer();

        if (net != null) {
            // Le meneur publie le jeu : tous les serveurs, le sien compris, l'affichent à partir du message
            return net.start(type, content, answer) ? Start.STARTED : Start.NOT_LEADER;
        }

        final Game game = new Game(type, content, answer, System.currentTimeMillis());
        currentGame = game;
        if (timeoutTask != null) timeoutTask.cancel();

        // On crée une copie finale pour le scheduler
        // Pour "click", la réponse est un jeton interne : on n'affiche jamais sa valeur.
        final String finalAnswer = type.equals("click") ? content : answer;

        timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            // claimGame : si un joueur vient de gagner, le délai écoulé ne s'annonce pas en plus
            if (claimGame(game)) {
                broadcast("games." + type + ".timeout", Map.of(
                    "%GAME_CONTENT%", content,
                    "%ANSWER%", finalAnswer
                ));
            }
        }, Math.max(1, config.getInt("game_timeout_seconds", 60)) * 20L);

        if (type.equals("click")) {
            broadcastClickable("games.click.start", content, answer);
        } else {
            broadcast("games." + type + ".start", Map.of("%GAME_CONTENT%", content));
        }
        playSound(config.getString("game_start_sound", ""));
        return Start.STARTED;
    }

    /** Joueurs comptés pour min-players : tout le réseau avec SHNetwork, ce serveur sinon. */
    private int playersCount() {
        return net != null ? net.online() : Bukkit.getOnlinePlayers().size();
    }

    /** Tire l'énoncé d'un jeu ; null si le jeu est impossible (aucun mot chargé) ou inconnu. */
    private Puzzle makePuzzle(String type) {
        String content, answer;

        switch (type) {
            case "type":
                answer = content = getRandomWord();
                if (content.isEmpty()) return null; // aucun mot chargé : partie impossible à gagner
                break;
            case "unreverse":
                String w = getRandomWord();
                if (w.isEmpty()) return null;
                content = new StringBuilder(w).reverse().toString();
                answer = w;
                break;
            case "solve":
                int a = random.nextInt(99) + 1;
                int b = random.nextInt(99) + 1;
                char op;
                int res;
                int pick = random.nextInt(3);
                if (pick == 0) {
                    op = '+';
                    res = a + b;
                } else if (pick == 1) {
                    op = '-';
                    if (b > a) {
                        int t = a;
                        a = b;
                        b = t;
                    }
                    res = a - b;
                } else {
                    op = 'x';
                    a = random.nextInt(12) + 1;
                    b = random.nextInt(12) + 1;
                    res = a * b;
                }
                content = a + " " + op + " " + b;
                answer = String.valueOf(res);
                break;
            case "sequence":
                int startNum = random.nextInt(10) + 1;
                int step = random.nextInt(5) + 2;
                int seqType = random.nextInt(2); // 0 = addition, 1 = multiplication
                StringBuilder sb = new StringBuilder();
                int current = startNum;

                for (int i = 0; i < 4; i++) {
                    sb.append(current).append(", ");
                    if (seqType == 0) current += step;
                    else current *= 2; // On reste sur x2 pour éviter des nombres trop grands
                }
                sb.append("?");
                content = sb.toString();
                answer = String.valueOf(current);
                break;
            case "click":
                content = pickClickSymbol();
                // Jeton aléatoire : impossible à deviner ou à taper à l'avance dans le chat.
                answer = UUID.randomUUID().toString().substring(0, 8);
                break;
            default:
                return null;
        }
        return new Puzzle(type, content, answer);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        // Avec SHNetwork, la réponse est jugée ici et envoyée au meneur, qui désigne l'unique gagnant du réseau
        if (net != null) {
            net.onChat(e.getPlayer(), e.getMessage());
            return;
        }
        // On crée une copie locale de l'objet du jeu en cours.
        // C'est crucial car cela "capture" l'état du jeu au moment de l'événement.
        final Game game = this.currentGame;

        // Si aucun jeu n'est en cours, ou si la réponse est fausse, on s'arrête.
        // Le jeu "click" ne se gagne jamais par le chat : sa réponse est un jeton interne.
        if (game == null || game.type.equals("click") || !e.getMessage().equalsIgnoreCase(game.answer)) {
            return;
        }

        if (!claimGame(game)) return; // Un autre joueur a déjà gagné.
        handleWin(e.getPlayer(), game);
    }

    /**
     * Termine le jeu de façon atomique : seul le premier appelant reçoit true.
     * C'est ce qui empêche les victoires multiples (chat asynchrone + clics simultanés).
     */
    private synchronized boolean claimGame(Game game) {
        if (currentGame != game) return false;
        currentGame = null;
        return true;
    }

    private void handleWin(Player winner, Game game) {
        String player = winner.getName();
        double dt = (System.currentTimeMillis() - game.startTime) / 1000.0;
        String timeStr = String.format(Locale.ROOT, "%.2f", dt);
        int wins = incrementWins(player);
        // Pour "click", game.answer est un jeton interne : on affiche le symbole à la place.
        String shownAnswer = game.type.equals("click") ? game.content : game.answer;
        Map<String, String> vars = Map.of(
            "%PLAYER%", player,
            "%ANSWER%", shownAnswer,
            "%GAME_CONTENT%", game.content,
            "%TIME%", timeStr,
            "%WINS%", String.valueOf(wins)
        );

        Bukkit.getScheduler().runTask(this, () -> {
            broadcast("games." + game.type + ".win", vars);
            playSound(config.getString("game_win_sound", ""));
            runRewards(winner, game.type);
        });
    }

    void broadcast(String path, Map<String, String> vars) {
        for (String line : config.getStringList(path)) {
            String msg = line;
            for (var entry : vars.entrySet()) msg = msg.replace(entry.getKey(), entry.getValue());
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    /**
     * Diffuse un message dont le %GAME_CONTENT% est un bouton cliquable.
     * Le clic exécute /shcgclick <token> ; le token est aléatoire et jamais affiché,
     * donc impossible de gagner en tapant la commande à la main.
     */
    void broadcastClickable(String path, String symbol, String token) {
        String coloredSymbol = ChatColor.translateAlternateColorCodes('&', symbol);
        String hover = ChatColor.translateAlternateColorCodes('&',
            config.getString("games.click.hover", "&aCliquez vite !"));

        for (String line : config.getStringList(path)) {
            String colored = ChatColor.translateAlternateColorCodes('&', line);
            int idx = colored.indexOf("%GAME_CONTENT%");
            if (idx < 0) {
                Bukkit.broadcastMessage(colored);
                continue;
            }
            String before = colored.substring(0, idx);
            String after = colored.substring(idx + "%GAME_CONTENT%".length());

            LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
            Component button = legacy.deserialize(coloredSymbol)
                .clickEvent(ClickEvent.runCommand("/shcgclick " + token))
                .hoverEvent(HoverEvent.showText(legacy.deserialize(hover)));
            // Racine vide : le style de « before » ne déborde pas sur le bouton ni sur « after »
            Bukkit.broadcast(Component.empty()
                .append(legacy.deserialize(before))
                .append(button)
                .append(legacy.deserialize(after)));
        }
    }

    private String pickClickSymbol() {
        List<String> symbols = config.getStringList("games.click.symbols");
        if (symbols.isEmpty()) return "&f&l[ &e&l⬜&f&l ]";
        return symbols.get(random.nextInt(symbols.size()));
    }

    private void handleClick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) return;
        if (net != null) {
            // Avec SHNetwork, le clic valable part vers le meneur, qui désigne l'unique gagnant du réseau
            if (!net.onClick(p, args.length >= 1 ? args[0] : null)) sendTooLate(p);
            return;
        }
        final Game game = this.currentGame;
        boolean valid = args.length >= 1 && game != null
            && game.type.equals("click") && game.answer.equals(args[0]);

        if (!valid || !claimGame(game)) {
            sendTooLate(p);
            return;
        }
        handleWin(p, game);
    }

    void sendTooLate(Player p) {
        for (String line : config.getStringList("games.click.too_late")) {
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    void playSound(String soundName) {
        if (soundName == null || soundName.isBlank()) return;
        Sound sound = sound(soundName);
        if (sound == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), sound, 1f, 1f);
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    /** Tire au hasard un groupe de récompenses ; null s'il n'y en a aucun. */
    Reward pickReward() {
        var section = config.getConfigurationSection("rewards");
        if (section == null) return null;
        List<String> keys = new ArrayList<>(section.getKeys(false));
        if (keys.isEmpty()) return null;
        String rewardKey = keys.get(random.nextInt(keys.size()));
        return new Reward(rewardKey, config.getStringList("rewards." + rewardKey));
    }

    private void runRewards(Player player, String gameType) {
        Reward reward = pickReward();
        if (reward == null) return;
        String rewardKey = reward.key();
        List<String> chosen = reward.commands();
        Bukkit.getScheduler().runTask(this, () -> {
            boolean given = false;
            for (String cmd : chosen) {
                if (getServer().dispatchCommand(getServer().getConsoleSender(),
                        cmd.replace("%PLAYER%", player.getName()).replace("%player%", player.getName()))) {
                    given = true;
                }
            }
            if (given) logWin(player.getName(), gameType, rewardKey);
        });
    }

    /** Une ligne SHLogs par gagnant, écrite une fois ses commandes de récompense acceptées. */
    void logWin(String player, String gameType, String rewardKey) {
        String line = config.getString("win_log_format", "%PLAYER% -> gagne le jeu du chat « %GAME% » (%REWARD%)")
            .replace("%PLAYER%", player)
            .replace("%GAME%", config.getString("games." + gameType + ".log_name", gameType))
            .replace("%REWARD%", config.getString("reward_log_names." + rewardKey, "récompense " + rewardKey));
        ActionLog.write(this, config.getString("log_file", "gagnants"), line);
    }

    private String getRandomWord() {
        return words.isEmpty() ? "" : words.get(random.nextInt(words.size()));
    }

    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "start", "import", "status");

    /** Permission de chaque sous-commande d'admin. */
    private static String permission(String sub) {
        return "shchatgames.admin." + sub;
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private List<String> usableSubcommands(CommandSender s) {
        List<String> usable = new ArrayList<>();
        for (String sub : ADMIN_SUBCOMMANDS) {
            if (s.hasPermission(permission(sub))) usable.add(sub);
        }
        return usable;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("shcgclick")) {
            handleClick(s, a);
            return true;
        }
        List<String> usable = usableSubcommands(s);
        if (usable.isEmpty()) {
            s.sendMessage(message("no_permission",
                "&7&l« &c&lSH &8&l» &7Vous n'avez pas la &cpermission&7 d'utiliser cette commande."));
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (!usable.contains(sub)) {
            s.sendMessage(message("usage").replace("%SUBCOMMANDS%", String.join("|", usable)));
            return true;
        }
        switch (sub) {
            case "start" -> commandStart(s, a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : null);
            case "import" -> commandImport(s);
            case "status" -> commandStatus(s);
            default -> commandReload(s);
        }
        return true;
    }

    /** Fichier invalide et cause, ou null si config.yml, messages.yml et la liste de mots existent et se lisent. */
    private String checkFiles() {
        for (String name : new String[] {"config.yml", "messages.yml"}) {
            File file = new File(getDataFolder(), name);
            if (!file.exists()) continue;
            try {
                new YamlConfiguration().load(file);
            } catch (IOException | InvalidConfigurationException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String text = String.valueOf(cause.getMessage()).replaceAll("\\s+", " ").trim();
                return name + "|" + (text.length() > 200 ? text.substring(0, 200) + "..." : text);
            }
        }
        return null;
    }

    /**
     * /shchatgames reload : config.yml et messages.yml sont d'abord lus en mode strict. Si l'un est invalide, rien
     * n'est remplacé. Le planificateur repart avec le nouvel intervalle ; la liste de mots est relue.
     */
    private void commandReload(CommandSender s) {
        String error = checkFiles();
        if (error != null) {
            String[] parts = error.split("\\|", 2);
            getLogger().warning("Rechargement refusé, configuration précédente gardée (" + parts[0] + " : " + parts[1] + ").");
            s.sendMessage(message("reload_error").replace("%FILE%", parts[0]).replace("%ERROR%", parts[1]));
            return;
        }
        reloadConfig();
        config = getConfig();
        loadMessages();
        loadWords();
        // Avec SHNetwork, seul le meneur planifie : le planificateur repart quand ce serveur est désigné
        if (net == null || net.isLeader()) {
            startScheduler();
        }
        s.sendMessage(message("reload_success",
            "&7&l« &a&lSH &8&l» &7Configuration &arechargée&7 avec succès !"));
    }

    /** /shchatgames status : jeu en cours, prochain lancement, joueurs comptés contre min-players. */
    private void commandStatus(CommandSender s) {
        int minPlayers = config.getInt("min-players", 1);
        String mode;
        String game;
        int players;
        if (net != null) {
            mode = message(net.isLeader() ? "status_mode_leader" : "status_mode_follower");
            String type = net.shownType();
            game = type == null ? message("status_no_game")
                : message("status_game").replace("%TYPE%", type)
                    .replace("%SECONDS%", String.valueOf((net.shownRemainingMs() + 999L) / 1000L));
            players = net.online();
        } else {
            mode = message("status_mode_local");
            Game current = currentGame;
            long left = current == null ? 0L : Math.max(0L,
                current.startTime + Math.max(1, config.getInt("game_timeout_seconds", 60)) * 1000L - System.currentTimeMillis());
            game = current == null ? message("status_no_game")
                : message("status_game").replace("%TYPE%", current.type)
                    .replace("%SECONDS%", String.valueOf((left + 999L) / 1000L));
            players = Bukkit.getOnlinePlayers().size();
        }
        long wait = nextGameAt - System.currentTimeMillis();
        String next = nextGameAt == 0L ? message("status_no_schedule")
            : message("status_next").replace("%SECONDS%", String.valueOf(Math.max(0L, (wait + 999L) / 1000L)));
        s.sendMessage(message("status_line1").replace("%MODE%", mode).replace("%GAME%", game));
        s.sendMessage(message("status_line2").replace("%NEXT%", next)
            .replace("%PLAYERS%", String.valueOf(players)).replace("%MIN%", String.valueOf(minPlayers)));
    }

    /** /shchatgames start [jeu] : lance un jeu tout de suite, sans tenir compte de min-players. */
    private void commandStart(CommandSender s, String type) {
        Start result = startGame(type, true);
        switch (result) {
            case STARTED -> s.sendMessage(message("start_success"));
            case BUSY -> s.sendMessage(message("start_busy"));
            case NOT_LEADER -> s.sendMessage(message("start_not_leader"));
            case UNKNOWN_TYPE -> s.sendMessage(message("start_unknown_type").replace("%TYPES%", String.join(", ", GAME_TYPES)));
            default -> s.sendMessage(message("start_impossible"));
        }
    }

    /** /shchatgames import : reprend les victoires de wins.yml dans la base du réseau. */
    private void commandImport(CommandSender s) {
        if (net == null) {
            s.sendMessage(message("import_unavailable"));
            return;
        }
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        synchronized (winsLock) {
            for (String name : winsConfig.getKeys(false)) snapshot.put(name, winsConfig.getInt(name, 0));
        }
        net.importWins(snapshot, counts -> {
            if (counts == null) {
                s.sendMessage(message("import_error"));
            } else {
                s.sendMessage(message("import_success")
                    .replace("%IMPORTED%", String.valueOf(counts[0]))
                    .replace("%PRESENT%", String.valueOf(counts[1])));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0 || c.getName().equalsIgnoreCase("shcgclick")) return List.of();
        List<String> options = new ArrayList<>();
        if (a.length == 1) {
            options.addAll(usableSubcommands(s));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("start") && s.hasPermission(permission("start"))) {
            options.addAll(GAME_TYPES);
        }
        String typed = a[a.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.startsWith(typed));
        return options;
    }

    private static class Game {
        final String type, content, answer;
        final long startTime;

        Game(String t, String c, String a, long s) {
            type = t;
            content = c;
            answer = a;
            startTime = s;
        }
    }

    public static class ShChatGamesExpansion extends PlaceholderExpansion {
        private final SHChatGames plugin;

        public ShChatGamesExpansion(SHChatGames plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "shchatgames";
        }

        @Override
        public String getAuthor() {
            return plugin.getDescription().getAuthors().get(0);
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "";
            if (identifier.equals("wins")) return String.valueOf(plugin.getWins(player));
            return null;
        }
    }
}
