package org.Cry_Legende.sHChatGames.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * Victoires des jeux du chat en MariaDB, partagées par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal et hors du fil du bus.</p>
 */
public final class WinsDatabase {

    private static final String TABLE = "chatgames_wins";

    private final HikariDataSource pool;

    public WinsDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHChatGames");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les victoires s'affichent alors avec un « ? »
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée la table si elle manque. Une table déjà là ne demande aucun droit CREATE à l'utilisateur. */
    public void createTable() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + TABLE + " LIMIT 1")) {
                return;
            } catch (SQLException missing) {
                // la table n'existe pas encore : création ci-dessous
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "wins INT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /**
     * Ajoute une victoire au joueur (ligne créée à la première) et rend son total. L'ajout est une seule
     * instruction atomique : deux serveurs qui comptent le même joueur s'additionnent. Jamais rejouée.
     */
    public int increment(UUID uuid, String name) throws SQLException {
        try (Connection c = pool.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, name, wins) VALUES (?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE wins = wins + 1, name = VALUES(name)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return select(c, uuid);
        }
    }

    /** Total de victoires du joueur (0 sans ligne). */
    public int find(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return select(c, uuid);
        }
    }

    private static int select(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT wins FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Reprend les victoires de l'ancien wins.yml (pseudo -> total). Un joueur qui a déjà une ligne n'est pas touché,
     * donc l'importation peut être relancée sans rien compter deux fois.
     *
     * @return {importés, déjà présents}
     */
    public int[] importWins(Map<String, Integer> byName) throws SQLException {
        int imported = 0;
        int present = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (uuid, name, wins) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Integer> entry : byName.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                ps.setString(1, offlineUuid(entry.getKey()).toString());
                ps.setString(2, entry.getKey());
                ps.setInt(3, entry.getValue());
                if (ps.executeUpdate() > 0) {
                    imported++;
                } else {
                    present++;
                }
            }
        }
        return new int[]{imported, present};
    }

    /** UUID « hors ligne » d'un pseudo, celui de tout le réseau (pas de recherche Mojang). */
    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public void close() {
        pool.close();
    }
}
