package com.wellsetups.wellviptime.audit;

import com.wellsetups.wellviptime.storage.Sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        UUID operation,
        String action,
        UUID actor,
        String actorName,
        UUID target,
        String targetName,
        String type,
        long duration,
        long previousExpiry,
        long newExpiry,
        UUID voucher,
        long timestamp) {

    public void insert(Connection c, boolean webhook) throws SQLException {
        Sql.update(
                c,
                "INSERT INTO"
                    + " audit_log(id,operation_id,action,actor_uuid,actor_name,target_uuid,target_name,vip_type,duration_ms,previous_expiry,new_expiry,voucher_id,created_at,webhook_state,webhook_attempts,webhook_next)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0)",
                id.toString(),
                operation.toString(),
                action,
                actor.toString(),
                actorName,
                target.toString(),
                targetName,
                type,
                duration,
                previousExpiry,
                newExpiry,
                voucher == null ? null : voucher.toString(),
                timestamp,
                webhook ? "PENDING" : "DISABLED");
    }
}
