package org.Cry_Legende.sHDataPlaceholders.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHDataPlaceholders.DataManager.Operation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Valeurs des joueurs en MariaDB, partagées par tous les serveurs : une ligne par joueur et par clé. Chargée
 * seulement avec SHNetwork (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Les ajouts et retraits sont faits par la base ({@code value = value + ?}) : deux serveurs qui modifient la
 * même valeur au même moment s'additionnent, aucune ne repart d'une valeur lue plus tôt.</p>
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class ValuesDatabase {

    private static final String TABLE = "shdataplaceholders_values";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?");

    private final HikariDataSource pool;

    public ValuesDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHDataPlaceholders");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : le plugin reste alors en mode local
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
                + "stat VARCHAR(100) NOT NULL, "
                + "value INT NOT NULL, "
                + "PRIMARY KEY (uuid, stat)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    /** Toutes les valeurs d'un joueur : clé -> valeur. */
    public Map<String, Integer> loadAll(UUID uuid) throws SQLException {
        Map<String, Integer> result = new HashMap<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT stat, value FROM " + TABLE + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return result;
    }

    /** Valeur d'une clé pour un joueur, ou null s'il n'y en a pas. */
    public Integer load(UUID uuid, String stat) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM " + TABLE + " WHERE uuid = ? AND stat = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, stat);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /**
     * Modifie une valeur et rend la nouvelle. Ajout et retrait sont atomiques (la ligne est verrouillée le temps de
     * l'opération) ; une clé absente part de la valeur par défaut, comme en mode local. Le résultat reste dans les
     * bornes d'un entier, sans dépassement vers un nombre négatif.
     */
    public int modify(UUID uuid, String stat, int amount, Operation op, int defaultValue) throws SQLException {
        String id = uuid.toString();
        try (Connection c = pool.getConnection()) {
            if (op == Operation.SET) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, stat, value) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE value = VALUES(value)")) {
                    ps.setString(1, id);
                    ps.setString(2, stat);
                    ps.setInt(3, amount);
                    ps.executeUpdate();
                }
                return amount;
            }
            long delta = op == Operation.ADD ? amount : -(long) amount;
            // Valeur d'une clé encore absente : la valeur par défaut, plus le changement, dans les bornes d'un entier
            int initial = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) defaultValue + delta));
            c.setAutoCommit(false);
            try {
                // Une seule instruction : la ligne est verrouillée par elle, deux serveurs qui ajoutent en même temps
                // passent l'un après l'autre sans jamais s'écraser ni se bloquer
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (uuid, stat, value) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE value = LEAST(GREATEST(CAST(value AS SIGNED) + ?, -2147483648), 2147483647)")) {
                    ps.setString(1, id);
                    ps.setString(2, stat);
                    ps.setInt(3, initial);
                    ps.setLong(4, delta);
                    ps.executeUpdate();
                }
                int result;
                // Lue dans la même transaction, avant que la ligne soit relâchée : c'est bien le résultat de cette opération
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM " + TABLE + " WHERE uuid = ? AND stat = ?")) {
                    ps.setString(1, id);
                    ps.setString(2, stat);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("valeur introuvable après modification");
                        }
                        result = rs.getInt(1);
                    }
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // la connexion est déjà perdue
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Ajoute les valeurs d'un joueur qui ne sont pas déjà en base ; rend le nombre de lignes ajoutées. */
    public int importValues(UUID uuid, Map<String, Integer> values) throws SQLException {
        int added = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + TABLE + " (uuid, stat, value) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Integer> e : values.entrySet()) {
                ps.setString(1, uuid.toString());
                ps.setString(2, e.getKey().toLowerCase());
                ps.setInt(3, e.getValue());
                added += ps.executeUpdate();
            }
        }
        return added;
    }

    /**
     * Pseudo exact d'un compte connu du réseau, retrouvé dans la table des comptes (colonnes {@code name} en
     * minuscules et {@code display_name}), ou null.
     */
    public String knownName(String table, String lowerName) throws SQLException {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new SQLException("Nom de table invalide : " + table);
        }
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT display_name FROM " + table + " WHERE name = ?")) {
            ps.setString(1, lowerName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void close() {
        pool.close();
    }
}
