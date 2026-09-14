package com.wellsetups.wellviptime.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class Schema {

    private final Database database;

    Schema(Database database) {
        this.database = database;
    }

    void migrate() throws SQLException {
        // MySQL DDL auto-commits; an advisory lock serializes restartable migrations.
        database.read(
                connection -> {
                    if (database.sqlite()) {
                        applyMigration(connection);
                    } else {
                        migrateWithLock(connection);
                    }
                    return null;
                });
    }

    private void migrateWithLock(Connection connection) throws SQLException {
        if (Sql.query(connection, "SELECT GET_LOCK('vipmanager_schema',10)", row -> row.getInt(1))
                        .get(0)
                != 1) {
            throw new SQLException("Timed out waiting for WellVipTime schema migration lock");
        }
        Throwable primary = null;
        try {
            applyMigration(connection);
        } catch (SQLException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            releaseLock(connection, primary);
        }
    }

    private void releaseLock(Connection connection, Throwable primary) throws SQLException {
        try {
            Sql.query(connection, "SELECT RELEASE_LOCK('vipmanager_schema')", row -> row.getInt(1));
        } catch (SQLException failure) {
            if (primary == null) {
                throw failure;
            }
            primary.addSuppressed(failure);
        }
    }

    private void applyMigration(Connection connection) throws SQLException {
        String suffix = database.sqlite() ? "" : " ENGINE=InnoDB";
        Sql.update(
                connection,
                "CREATE TABLE IF NOT EXISTS database_schema_version (version"
                        + " INTEGER NOT NULL)"
                        + suffix);
        var versions =
                Sql.query(
                        connection,
                        "SELECT version FROM database_schema_version",
                        r -> r.getInt(1));
        if (versions.size() > 1
                || (!versions.isEmpty()
                        && versions.get(0) != 1
                        && versions.get(0) != 2
                        && versions.get(0) != 3)) {
            throw new SQLException("Unsupported or corrupt VipManager schema version: " + versions);
        }
        for (String ddl : TABLES) {
            Sql.update(connection, ddl + suffix);
        }
        for (String[] index : INDEXES) {
            ensureIndex(connection, index[0], index[1], index[2]);
        }
        if (versions.isEmpty()) {
            Sql.update(connection, "INSERT INTO database_schema_version(version) VALUES(3)");
        } else if (versions.get(0) < 3) {
            Sql.update(connection, "UPDATE database_schema_version SET version=3 WHERE version<3");
        }
    }

    private void ensureIndex(Connection c, String table, String name, String columns)
            throws SQLException {
        boolean exists = false;
        try (var indexes =
                c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    exists = true;
                }
            }
        }
        if (!exists) {
            Sql.update(c, "CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        }
    }

    private static final List<String> TABLES =
            List.of(
                    "CREATE TABLE IF NOT EXISTS player_preferences(uuid VARCHAR(36) NOT"
                            + " NULL,channel VARCHAR(16) NOT NULL,enabled INTEGER NOT NULL,PRIMARY"
                            + " KEY(uuid,channel))",
                    "CREATE TABLE IF NOT EXISTS security_state(id INTEGER PRIMARY KEY,network_id"
                            + " VARCHAR(48) NOT NULL,key_fingerprint VARCHAR(64) NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS webhook_gate(id INTEGER PRIMARY KEY,until_at BIGINT"
                            + " NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS players(uuid VARCHAR(36) PRIMARY KEY,name"
                            + " VARCHAR(64),name_lower VARCHAR(64),last_seen BIGINT NOT NULL,hidden"
                            + " INTEGER NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS vip_balances(uuid VARCHAR(36) NOT NULL,vip_type"
                        + " VARCHAR(48) NOT NULL,started_at BIGINT NOT NULL,expires_at BIGINT NOT"
                        + " NULL,original_ms BIGINT NOT NULL,historical_ms BIGINT NOT NULL,status"
                        + " VARCHAR(16) NOT NULL,revision BIGINT NOT NULL,created_by VARCHAR(36)"
                        + " NOT NULL,created_at BIGINT NOT NULL,modified_at BIGINT NOT NULL,PRIMARY"
                        + " KEY(uuid,vip_type),FOREIGN KEY(uuid) REFERENCES players(uuid))",
                    "CREATE TABLE IF NOT EXISTS vouchers(id VARCHAR(36) PRIMARY KEY,schema_version"
                        + " INTEGER NOT NULL,vip_type VARCHAR(48) NOT NULL,duration_ms BIGINT NOT"
                        + " NULL,created_at BIGINT NOT NULL,issuer VARCHAR(36) NOT NULL,owner"
                        + " VARCHAR(36),historical_credit INTEGER NOT NULL,state VARCHAR(16) NOT"
                        + " NULL,redeemed_by VARCHAR(36),redeemed_at BIGINT)",
                    "CREATE TABLE IF NOT EXISTS voucher_origins(voucher_id VARCHAR(36) PRIMARY"
                        + " KEY,kind VARCHAR(16) NOT NULL,display_name VARCHAR(64) NOT NULL,FOREIGN"
                        + " KEY(voucher_id) REFERENCES vouchers(id))",
                    "CREATE TABLE IF NOT EXISTS deliveries(voucher_id VARCHAR(36) PRIMARY"
                            + " KEY,recipient VARCHAR(36) NOT NULL,delivered_at BIGINT,FOREIGN"
                            + " KEY(voucher_id) REFERENCES vouchers(id))",
                    "CREATE TABLE IF NOT EXISTS cooldowns(uuid VARCHAR(36) NOT NULL,kind"
                        + " VARCHAR(80) NOT NULL,until_at BIGINT NOT NULL,PRIMARY KEY(uuid,kind))",
                    "CREATE TABLE IF NOT EXISTS notification_receipts(uuid VARCHAR(36) NOT"
                            + " NULL,vip_type VARCHAR(48) NOT NULL,revision BIGINT NOT"
                            + " NULL,threshold_id VARCHAR(48) NOT NULL,created_at BIGINT NOT"
                            + " NULL,PRIMARY KEY(uuid,vip_type,revision,threshold_id))",
                    "CREATE TABLE IF NOT EXISTS audit_log(id VARCHAR(36) PRIMARY KEY,operation_id"
                        + " VARCHAR(36) NOT NULL UNIQUE,action VARCHAR(40) NOT NULL,actor_uuid"
                        + " VARCHAR(36) NOT NULL,actor_name VARCHAR(64) NOT NULL,target_uuid"
                        + " VARCHAR(36) NOT NULL,target_name VARCHAR(64) NOT NULL,vip_type"
                        + " VARCHAR(48) NOT NULL,duration_ms BIGINT NOT NULL,previous_expiry BIGINT"
                        + " NOT NULL,new_expiry BIGINT NOT NULL,voucher_id VARCHAR(36),created_at"
                        + " BIGINT NOT NULL,webhook_state VARCHAR(16) NOT NULL,webhook_attempts"
                        + " INTEGER NOT NULL,webhook_next BIGINT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS permission_work(uuid VARCHAR(36) PRIMARY"
                            + " KEY,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL,lease_owner"
                            + " VARCHAR(36),lease_until BIGINT NOT NULL)");

    private static final List<String[]> INDEXES =
            List.of(
                    new String[] {"players", "idx_player_name", "name_lower"},
                    new String[] {"vip_balances", "idx_vip_due", "status,expires_at"},
                    new String[] {"vip_balances", "idx_vip_type_due", "vip_type,status,expires_at"},
                    new String[] {"vip_balances", "idx_vip_history", "historical_ms"},
                    new String[] {"vip_balances", "idx_vip_modified", "modified_at"},
                    new String[] {"deliveries", "idx_pending_delivery", "recipient,delivered_at"},
                    new String[] {"audit_log", "idx_webhook_due", "webhook_state,webhook_next"},
                    new String[] {
                        "permission_work", "idx_permission_due", "lease_until,updated_at"
                    });
}
