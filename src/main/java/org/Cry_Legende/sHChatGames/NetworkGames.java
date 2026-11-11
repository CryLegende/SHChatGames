package org.Cry_Legende.sHChatGames;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.Cry_Legende.sHChatGames.network.NetBus;
import org.Cry_Legende.sHChatGames.network.WinsDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Un seul jeu du chat pour tout le réseau. Le meneur (tâche {@code shchatgames} de SHNetwork) tire les jeux et
 * arbitre ; tous les serveurs, le meneur compris, affichent la même question et envoient les bonnes réponses
 * de leurs joueurs au meneur.
 *
 * <pre>
 * canal                 meneur -> tous   start {id, type, content, answer, startedAt, endsAt}
 *                                        win   {id, type, content, answer, winner, name, time, wins}
 *                                        end   {id, type, content, answer}
 * canal.reponse         serveur -> meneur  {id, uuid, name, at}
 * </pre>
 *
 * La réponse attendue voyage dans le message de départ : chaque serveur doit la connaître pour juger le chat
 * sur place, sans attendre le bus. Un condensat n'apporterait rien (quelques centaines de mots, des
 * nombres à trois chiffres : il se retrouve en un instant), et le bus n'est lisible que par les plugins des serveurs.
 */
final class NetworkGames implements Listener {

    private static final String TASK = "shchatgames";

    /** Partie telle qu'annoncée par le meneur, la même sur tous les serveurs. */
    private static final class NetGame {
        final String id;
        final String type;
        final String content;
        final String answer;
        final long startedAt;
        final long endsAt;
        /** Joueurs de ce serveur qui ont déjà envoyé une réponse correcte : un seul envoi chacun. */
        final Set<UUID> tried = ConcurrentHashMap.newKeySet();
        /** Joueurs de ce serveur qui ont cliqué : si quelqu'un les a devancés, ils reçoivent « Trop tard ». */
        final Set<UUID> clicked = ConcurrentHashMap.newKeySet();

        NetGame(String id, String type, String content, String answer, long startedAt, long endsAt) {
            this.id = id;
            this.type = type;
            this.content = content;
            this.answer = answer;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
        }

        boolean isClick() {
            return type.equals("click");
        }

        /** Pour « clic », la réponse est un jeton interne : on affiche le symbole à la place. */
        String shownAnswer() {
            return isClick() ? content : answer;
        }

        static NetGame parse(JsonObject o) {
            String id = text(o, "id");
            String type = text(o, "type");
            String content = text(o, "content");
            String answer = text(o, "answer");
            long endsAt = number(o, "endsAt", 0L);
            if (id == null || type == null || content == null || answer == null || endsAt <= 0L) {
                return null;
            }
            return new NetGame(id, type, content, answer, number(o, "startedAt", endsAt), endsAt);
        }
    }

    private record CachedWins(int wins, long at) {
    }

    private final SHChatGames plugin;
    private final NetBus bus;
    private final WinsDatabase db;
    private final String channel;
    private final String attemptChannel;
    private final Referee referee = new Referee();
    private final RestartGuard guard = new RestartGuard();

    private volatile NetBus.Leader leader;
    /** Partie affichée sur ce serveur : lue par le fil du chat, écrite par le fil principal. */
    private volatile NetGame shown;
    /** Partie que ce serveur arbitre, tant qu'il est meneur. */
    private volatile NetGame leading;
    private BukkitTask endTask;

    private final ConcurrentHashMap<UUID, CachedWins> winsCache = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private volatile long lastDbWarning;

    NetworkGames(SHChatGames plugin, NetBus bus, WinsDatabase db, String channel) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
        this.attemptChannel = channel + ".reponse";
    }

    // ------------------------------------------------------------------ cycle de vie

    void enable() {
        bus.listen(channel, this::onAnnouncement, true);
        bus.listen(attemptChannel, this::onAttempt, false);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        leader = bus.leader(TASK, plugin::startScheduler, this::lost);
        runAsync(() -> {
            try {
                db.createTable();
            } catch (SQLException e) {
                warnDb("Table des victoires inaccessible", e);
            }
        });
        for (Player p : Bukkit.getOnlinePlayers()) {
            refresh(p.getUniqueId());
        }
    }

    void disable() {
        NetBus.Leader current = leader;
        if (current != null) {
            current.release();
        }
        referee.abandon();
        leading = null;
        if (endTask != null) {
            endTask.cancel();
        }
        db.close();
    }

    boolean isLeader() {
        NetBus.Leader current = leader;
        return current != null && current.isLeader();
    }

    /** Ce serveur n'est plus meneur : il arrête le planificateur et abandonne la partie, sans rien annoncer. */
    private void lost() {
        plugin.stopScheduler();
        referee.abandon();
        leading = null;
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
    }

    // ------------------------------------------------------------------ meneur

    /** Joueurs connectés sur tout le réseau. */
    int online() {
        return bus.online();
    }

    /** Millisecondes à attendre avant de lancer un jeu, tant que celui d'un meneur précédent n'est pas fini. */
    long waitBeforeStartMs() {
        long margin = Math.max(0, plugin.config().getInt("reseau.marge-reprise-secondes", 5)) * 1000L;
        return guard.waitMs(System.currentTimeMillis(), margin);
    }

    boolean isRunning() {
        return referee.isOpen();
    }

    /** Type du jeu affiché sur ce serveur, ou null s'il n'y en a pas (lecture seule, pour /shchatgames status). */
    String shownType() {
        NetGame game = shown;
        return game == null ? null : game.type;
    }

    /** Millisecondes avant la fin du jeu affiché sur ce serveur, 0 s'il n'y en a pas. */
    long shownRemainingMs() {
        NetGame game = shown;
        return game == null ? 0L : Math.max(0L, game.endsAt - System.currentTimeMillis());
    }

    /** Publie un nouveau jeu pour tout le réseau. Faux si ce serveur n'est pas (ou plus) le meneur. */
    boolean start(String type, String content, String answer) {
        if (!isLeader()) {
            return false;
        }
        int seconds = Math.max(1, plugin.config().getInt("game_timeout_seconds", 60));
        long now = System.currentTimeMillis();
        NetGame game = new NetGame(UUID.randomUUID().toString().substring(0, 8), type, content, answer, now, now + seconds * 1000L);
        referee.open(game.id);
        leading = game;
        if (endTask != null) {
            endTask.cancel();
        }
        endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> timeout(game), seconds * 20L);

        JsonObject message = new JsonObject();
        message.addProperty("event", "start");
        message.addProperty("id", game.id);
        message.addProperty("type", game.type);
        message.addProperty("content", game.content);
        message.addProperty("answer", game.answer);
        message.addProperty("startedAt", game.startedAt);
        message.addProperty("endsAt", game.endsAt);
        bus.publish(channel, message);
        return true;
    }

    /** Délai écoulé sans gagnant : si personne n'a fermé la partie entre-temps, tous les serveurs l'annoncent. */
    private void timeout(NetGame game) {
        if (!referee.close(game.id)) {
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("event", "end");
        message.addProperty("id", game.id);
        message.addProperty("type", game.type);
        message.addProperty("content", game.content);
        message.addProperty("answer", game.shownAnswer());
        bus.publish(channel, message);
    }

    /** Fil du bus : le premier essai reçu pour la partie ouverte gagne, les suivants sont ignorés. */
    private void onAttempt(JsonObject o) {
        try {
            if (!isLeader()) {
                return;
            }
            NetGame game = leading;
            String id = text(o, "id");
            if (game == null || id == null || !game.id.equals(id)) {
                return;
            }
            String uuid = text(o, "uuid");
            String name = text(o, "name");
            if (uuid == null || name == null || name.isBlank()) {
                return;
            }
            UUID winner = UUID.fromString(uuid);
            long at = number(o, "at", System.currentTimeMillis());
            if (!referee.close(id)) {
                return;
            }
            conclude(game, winner, name, at);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Essai illisible sur " + attemptChannel + " : " + e);
        }
    }

    /**
     * Le gagnant est connu : compte sa victoire en base, annonce, puis donne la récompense une seule fois, là où il est.
     * Tout part hors du fil du bus ; une base qui ne répond pas retarde l'annonce d'au plus le délai de connexion,
     * sans jamais priver le joueur de sa récompense.
     */
    private void conclude(NetGame game, UUID winner, String name, long at) {
        String time = String.format(Locale.ROOT, "%.2f", Math.max(0L, at - game.startedAt) / 1000.0);
        SHChatGames.Reward reward = plugin.pickReward();
        runAsync(() -> {
            int total = -1;
            try {
                total = db.increment(winner, name);
            } catch (SQLException | RuntimeException e) {
                warnDb("Victoire de " + name + " non comptée", e);
            }

            JsonObject message = new JsonObject();
            message.addProperty("event", "win");
            message.addProperty("id", game.id);
            message.addProperty("type", game.type);
            message.addProperty("content", game.content);
            message.addProperty("answer", game.shownAnswer());
            message.addProperty("winner", winner.toString());
            message.addProperty("name", name);
            message.addProperty("time", time);
            message.addProperty("wins", total);
            bus.publish(channel, message);

            if (reward == null) {
                return;
            }
            boolean given = false;
            for (String command : reward.commands()) {
                try {
                    bus.runFor(winner.toString(), command.replace("%PLAYER%", name).replace("%player%", name));
                    given = true;
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Commande de récompense refusée (" + command + ") : " + e.getMessage());
                }
            }
            if (given) {
                onMain(() -> plugin.logWin(name, game.type, reward.key()));
            }
        });
    }

    // ------------------------------------------------------------------ tous les serveurs

    /** Fil principal : début, victoire ou fin d'un jeu, annoncés par le meneur. */
    private void onAnnouncement(JsonObject o) {
        try {
            String event = text(o, "event");
            if (event == null) {
                return;
            }
            switch (event) {
                case "start" -> onStart(o);
                case "win" -> onWin(o);
                case "end" -> onEnd(o);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Message illisible sur " + channel + " : " + e);
        }
    }

    private void onStart(JsonObject o) {
        NetGame game = NetGame.parse(o);
        if (game == null) {
            return;
        }
        guard.started(game.id, game.endsAt);
        shown = game;
        // Le meneur annonce la fin ; s'il tombe, la partie s'éteint ici à l'heure prévue, sans rien annoncer.
        long ticks = Math.max(1L, (game.endsAt - System.currentTimeMillis()) / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (shown == game) {
                shown = null;
            }
        }, ticks);

        if (game.isClick()) {
            plugin.broadcastClickable("games.click.start", game.content, game.answer);
        } else {
            plugin.broadcast("games." + game.type + ".start", Map.of("%GAME_CONTENT%", game.content));
        }
        plugin.playSound(plugin.config().getString("game_start_sound", ""));
    }

    private void onWin(JsonObject o) {
        String id = text(o, "id");
        String type = text(o, "type");
        String content = text(o, "content");
        String answer = text(o, "answer");
        String name = text(o, "name");
        String time = text(o, "time");
        String winnerText = text(o, "winner");
        if (id == null || type == null || content == null || answer == null || name == null || time == null || winnerText == null) {
            return;
        }
        UUID winner = UUID.fromString(winnerText);
        int total = (int) number(o, "wins", -1L);

        guard.finished(id, System.currentTimeMillis());
        NetGame game = shown;
        boolean here = game != null && game.id.equals(id);
        if (here) {
            shown = null;
        }
        // Seul le total d'un joueur présent ici est gardé : celui du gagnant d'un autre serveur n'a rien à faire dans le cache
        if (total >= 0 && Bukkit.getPlayer(winner) != null) {
            winsCache.put(winner, new CachedWins(total, System.currentTimeMillis()));
        }

        plugin.broadcast("games." + type + ".win", Map.of(
            "%PLAYER%", name,
            "%ANSWER%", answer,
            "%GAME_CONTENT%", content,
            "%TIME%", time,
            "%WINS%", total >= 0 ? String.valueOf(total) : "?"
        ));
        plugin.playSound(plugin.config().getString("game_win_sound", ""));

        if (here && game.isClick()) {
            for (UUID uuid : game.clicked) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && !uuid.equals(winner)) {
                    plugin.sendTooLate(p);
                }
            }
        }
    }

    private void onEnd(JsonObject o) {
        String id = text(o, "id");
        String type = text(o, "type");
        String content = text(o, "content");
        String answer = text(o, "answer");
        if (id == null || type == null || content == null || answer == null) {
            return;
        }
        guard.finished(id, System.currentTimeMillis());
        NetGame game = shown;
        if (game != null && game.id.equals(id)) {
            shown = null;
        }
        plugin.broadcast("games." + type + ".timeout", Map.of(
            "%GAME_CONTENT%", content,
            "%ANSWER%", answer
        ));
    }

    // ------------------------------------------------------------------ réponses des joueurs

    /** Fil du chat (asynchrone) : une bonne réponse part vers le meneur, qui tranche. */
    void onChat(Player player, String message) {
        NetGame game = shown;
        if (game == null || game.isClick() || System.currentTimeMillis() > game.endsAt) {
            return;
        }
        if (!message.equalsIgnoreCase(game.answer)) {
            return;
        }
        attempt(game, player);
    }

    /** Fil principal : clic sur la case. Faux si le jeton ne correspond à aucun jeu de clic en cours (« Trop tard »). */
    boolean onClick(Player player, String token) {
        NetGame game = shown;
        if (game == null || !game.isClick() || token == null || !game.answer.equals(token)
            || System.currentTimeMillis() > game.endsAt) {
            return false;
        }
        game.clicked.add(player.getUniqueId());
        attempt(game, player);
        return true;
    }

    private void attempt(NetGame game, Player player) {
        if (!game.tried.add(player.getUniqueId())) {
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("id", game.id);
        message.addProperty("uuid", player.getUniqueId().toString());
        message.addProperty("name", player.getName());
        message.addProperty("at", System.currentTimeMillis());
        bus.publish(attemptChannel, message);
    }

    // ------------------------------------------------------------------ victoires

    /**
     * Victoires d'un joueur connecté, pour le placeholder : jamais d'attente. La valeur gardée est rendue tout de
     * suite ; si elle est trop vieille, une relecture en base part en arrière-plan.
     */
    int wins(UUID uuid) {
        CachedWins cached = winsCache.get(uuid);
        long ttl = Math.max(1, plugin.config().getInt("reseau.cache-victoires-secondes", 30)) * 1000L;
        if (cached == null || System.currentTimeMillis() - cached.at() > ttl) {
            refresh(uuid);
        }
        return cached == null ? 0 : cached.wins();
    }

    private void refresh(UUID uuid) {
        if (!loading.add(uuid)) {
            return;
        }
        runAsync(() -> {
            try {
                winsCache.put(uuid, new CachedWins(db.find(uuid), System.currentTimeMillis()));
            } catch (SQLException | RuntimeException e) {
                warnDb("Victoires illisibles", e);
            } finally {
                loading.remove(uuid);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        winsCache.remove(event.getPlayer().getUniqueId());
    }

    /** Reprend les victoires de wins.yml dans la base ; {@code done} reçoit {importés, déjà présents}, ou null en cas d'échec. */
    void importWins(Map<String, Integer> byName, Consumer<int[]> done) {
        runAsync(() -> {
            int[] result = null;
            try {
                result = db.importWins(byName);
            } catch (SQLException | RuntimeException e) {
                warnDb("Importation des victoires impossible", e);
            }
            int[] counts = result;
            onMain(() -> done.accept(counts));
        });
    }

    // ------------------------------------------------------------------ outils

    private void runAsync(Runnable task) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // plugin en cours d'arrêt
        }
    }

    private void onMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
            // plugin en cours d'arrêt
        }
    }

    /** Une base qui tombe ne doit pas remplir la console : un avertissement toutes les 30 secondes au plus. */
    private void warnDb(String what, Exception error) {
        long now = System.currentTimeMillis();
        if (now - lastDbWarning > 30_000L) {
            lastDbWarning = now;
            plugin.getLogger().warning(what + " : " + error.getMessage());
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement element = o.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static long number(JsonObject o, String key, long fallback) {
        JsonElement element = o.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
