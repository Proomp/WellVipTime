package com.wellsetups.wellviptime.storage;

import com.wellsetups.wellviptime.configuration.Settings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface Work<T> {

        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource pool;

    private final boolean sqlite;

    public Database(Settings.Database settings, Path directory) {
        sqlite = settings.engine().equals("SQLITE");
        var config = new HikariConfig();
        config.setPoolName("WellVipTime-SQL");
        config.setConnectionTimeout(settings.timeoutSeconds() * 1000L);
        config.setValidationTimeout(Math.min(5000, settings.timeoutSeconds() * 1000L));
        config.setInitializationFailTimeout(settings.timeoutSeconds() * 1000L);
        config.setMaximumPoolSize(sqlite ? 1 : settings.poolSize());
        config.setMinimumIdle(1);
        config.setMaxLifetime(600000);
        if (sqlite) {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl(
                    "jdbc:sqlite:" + directory.resolve(settings.sqliteFile()).toAbsolutePath());
            config.addDataSourceProperty("foreign_keys", "true");
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "FULL");
            config.addDataSourceProperty(
                    "busy_timeout", Integer.toString(settings.timeoutSeconds() * 1000));
        } else {
            config.setDriverClassName("org.mariadb.jdbc.Driver");
            config.setJdbcUrl(
                    "jdbc:mariadb://"
                            + settings.host()
                            + ":"
                            + settings.port()
                            + "/"
                            + settings.name());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.addDataSourceProperty("sslMode", settings.tls() ? "verify-full" : "disable");
            config.addDataSourceProperty("connectTimeout", settings.timeoutSeconds() * 1000);
            config.addDataSourceProperty("socketTimeout", settings.timeoutSeconds() * 1000);
        }
        pool = new HikariDataSource(config);
    }

    public boolean sqlite() {
        return sqlite;
    }

    public <T> T read(Work<T> work) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return work.run(connection);
        }
    }

    public <T> T transaction(Work<T> work) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            if (sqlite) {
                Sql.update(connection, "BEGIN IMMEDIATE");
            } else {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
            }
            try {
                T value = work.run(connection);
                if (sqlite) {
                    Sql.update(connection, "COMMIT");
                } else {
                    connection.commit();
                }
                return value;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    if (sqlite) {
                        Sql.update(connection, "ROLLBACK");
                    } else {
                        connection.rollback();
                    }
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
            // Hikari resets connection state on close. Resetting auto-commit in a finally
            // block here could mask the original failure or commit after a failed rollback.
        }
    }

    public long now(Connection connection) throws SQLException {
        String expression =
                sqlite
                        ? "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
                        : "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
        return Sql.query(connection, "SELECT " + expression, row -> row.getLong(1)).get(0);
    }

    public void lockPlayers(Connection connection, List<UUID> players) throws SQLException {
        for (String uuid : players.stream().map(UUID::toString).distinct().sorted().toList()) {
            Sql.update(
                    connection,
                    (sqlite ? "INSERT OR IGNORE" : "INSERT IGNORE")
                            + " INTO players(uuid,name,name_lower,last_seen,hidden)"
                            + " VALUES(?,NULL,NULL,0,0)",
                    uuid);
            Sql.query(
                    connection,
                    "SELECT uuid FROM players WHERE uuid=?" + (sqlite ? "" : " FOR UPDATE"),
                    row -> row.getString(1),
                    uuid);
        }
    }

    public void migrate() throws SQLException {
        new Schema(this).migrate();
    }

    @Override
    public void close() {
        pool.close();
    }
}
