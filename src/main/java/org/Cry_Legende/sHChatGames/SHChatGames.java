package org.Cry_Legende.sHChatGames;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class SHChatGames extends JavaPlugin implements Listener, TabExecutor {
    private FileConfiguration config;
    private List<String> words;
    private final Random random = new Random();
    // Lu par le thread de chat (asynchrone) et écrit par le thread principal
    private volatile Game currentGame = null;
    private BukkitTask timeoutTask;

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
        scheduleNext();
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) timeoutTask.cancel();
        saveWins();
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
        Bukkit.getScheduler().runTaskLater(this, () -> {
            startRandomGame();
            scheduleNext();
        }, delaySec * 20L);
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
        if (Bukkit.getOnlinePlayers().size() < config.getInt("min-players", 1)) return;
        List<String> types = config.getStringList("enabled_games");
        if (types.isEmpty()) return;
        String type = types.get(random.nextInt(types.size())).toLowerCase(Locale.ROOT);
        String content, answer;

        switch (type) {
            case "type":
                answer = content = getRandomWord();
                if (content.isEmpty()) return; // aucun mot chargé : partie impossible à gagner
                break;
            case "unreverse":
                String w = getRandomWord();
                if (w.isEmpty()) return;
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
                return;
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
        }, 60 * 20L);

        if (type.equals("click")) {
            broadcastClickable("games.click.start", content, answer);
        } else {
            broadcast("games." + type + ".start", Map.of("%GAME_CONTENT%", content));
        }
        playSound(config.getString("game_start_sound", ""));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
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
            runRewards(winner);
        });
    }

    private void broadcast(String path, Map<String, String> vars) {
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
    private void broadcastClickable(String path, String symbol, String token) {
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
        final Game game = this.currentGame;
        boolean valid = args.length >= 1 && game != null
            && game.type.equals("click") && game.answer.equals(args[0]);

        if (!valid || !claimGame(game)) {
            for (String line : config.getStringList("games.click.too_late")) {
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
            }
            return;
        }
        handleWin(p, game);
    }

    private void playSound(String soundName) {
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

    private void runRewards(Player player) {
        var section = config.getConfigurationSection("rewards");
        if (section == null) return;
        List<List<String>> all = new ArrayList<>();
        for (String key : section.getKeys(false)) all.add(config.getStringList("rewards." + key));
        if (all.isEmpty()) return;
        List<String> chosen = all.get(random.nextInt(all.size()));
        Bukkit.getScheduler().runTask(this, () -> chosen.forEach(cmd ->
            getServer().dispatchCommand(getServer().getConsoleSender(),
                cmd.replace("%PLAYER%", player.getName()).replace("%player%", player.getName())
            )));
    }

    private String getRandomWord() {
        return words.isEmpty() ? "" : words.get(random.nextInt(words.size()));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("shcgclick")) {
            handleClick(s, a);
            return true;
        }
        if (!s.hasPermission("shchatgames.reload")) {
            s.sendMessage(ChatColor.RED + "Permission refusée.");
            return true;
        }
        reloadConfig();
        config = getConfig();
        loadWords();
        s.sendMessage(ChatColor.GREEN + "Configuration rechargée !");
        return true;
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
            if (identifier.equals("wins")) return String.valueOf(plugin.getWins(player.getName()));
            return null;
        }
    }
}
