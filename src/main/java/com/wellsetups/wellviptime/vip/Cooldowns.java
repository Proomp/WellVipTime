package com.wellsetups.wellviptime.vip;

import com.wellsetups.wellviptime.storage.Sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public final class Cooldowns {

    private final String session = UUID.randomUUID().toString();

    public static long remaining(long until, long now) {
        return Math.max(0, until - now);
    }

    public void take(
            Connection c, UUID uuid, long now, long duration, boolean persistent, boolean bypass)
            throws SQLException {
        if (bypass || duration == 0) {
            return;
        }
        String kind = persistent ? "freeze" : "freeze-" + session;
        var rows =
                Sql.query(
                        c,
                        "SELECT until_at FROM cooldowns WHERE uuid=? AND kind=?",
                        r -> r.getLong(1),
                        uuid.toString(),
                        kind);
        long remaining = rows.isEmpty() ? 0 : remaining(rows.get(0), now);
        if (remaining > 0) {
            throw new DomainFailure(
                    "error.cooldown", Map.of("remaining_ms", Long.toString(remaining)));
        }
        int changed =
                Sql.update(
                        c,
                        "UPDATE cooldowns SET until_at=? WHERE uuid=? AND kind=?",
                        Math.addExact(now, duration),
                        uuid.toString(),
                        kind);
        if (changed == 0) {
            Sql.update(
                    c,
                    "INSERT INTO cooldowns(uuid,kind,until_at) VALUES(?,?,?)",
                    uuid.toString(),
                    kind,
                    Math.addExact(now, duration));
        }
    }
}
