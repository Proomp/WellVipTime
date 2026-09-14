package com.wellsetups.wellviptime.storage;

import com.wellsetups.wellviptime.configuration.Settings.Ranking;
import com.wellsetups.wellviptime.vip.VipBalance;

import java.sql.*;
import java.util.*;

public final class VipRepository {

    public record Identity(UUID uuid, String name) {}

    public record Ranked(Identity player, VipBalance vip, long score) {}

    private final Database database;

    public VipRepository(Database database) {
        this.database = database;
    }

    public List<VipBalance> all(Connection c, UUID player) throws SQLException {
        return Sql.query(
                c,
                "SELECT * FROM vip_balances WHERE uuid=? ORDER BY vip_type",
                VipRepository::balance,
                player.toString());
    }

    public List<VipBalance> due(Connection c, long now, int limit) throws SQLException {
        return Sql.query(
                c,
                "SELECT * FROM vip_balances WHERE status='ACTIVE' AND expires_at<=? ORDER BY"
                        + " expires_at,uuid,vip_type LIMIT ?",
                VipRepository::balance,
                now,
                limit);
    }

    public void save(Connection c, VipBalance b) throws SQLException {
        int changed =
                Sql.update(
                        c,
                        "UPDATE vip_balances SET"
                            + " started_at=?,expires_at=?,original_ms=?,historical_ms=?,status=?,revision=?,modified_at=?"
                            + " WHERE uuid=? AND vip_type=?",
                        b.startedAt(),
                        b.expiresAt(),
                        b.originalMs(),
                        b.historicalMs(),
                        b.status().name(),
                        b.revision(),
                        b.modifiedAt(),
                        b.player().toString(),
                        b.type());
        if (changed == 0) {
            Sql.update(
                    c,
                    "INSERT INTO"
                        + " vip_balances(uuid,vip_type,started_at,expires_at,original_ms,historical_ms,status,revision,created_by,created_at,modified_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    b.player().toString(),
                    b.type(),
                    b.startedAt(),
                    b.expiresAt(),
                    b.originalMs(),
                    b.historicalMs(),
                    b.status().name(),
                    b.revision(),
                    b.createdBy().toString(),
                    b.createdAt(),
                    b.modifiedAt());
        }
    }

    public void remember(Connection c, UUID uuid, String name, boolean hidden, long now)
            throws SQLException {
        database.lockPlayers(c, List.of(uuid));
        Sql.update(
                c,
                "UPDATE players SET name=?,name_lower=?,last_seen=?,hidden=? WHERE uuid=?",
                name,
                name.toLowerCase(Locale.ROOT),
                now,
                hidden ? 1 : 0,
                uuid.toString());
    }

    public Optional<Identity> resolve(Connection c, String input) throws SQLException {
        try {
            UUID uuid = UUID.fromString(input);
            if (!uuid.toString().equalsIgnoreCase(input)) {
                return Optional.empty();
            }
            return Optional.of(new Identity(uuid, name(c, uuid)));
        } catch (IllegalArgumentException ignored) {
            if (!input.matches("[a-zA-Z0-9_]{1,16}")) {
                return Optional.empty();
            }
            var names =
                    Sql.query(
                            c,
                            "SELECT uuid,name FROM players WHERE name_lower=? LIMIT 2",
                            r -> new Identity(UUID.fromString(r.getString(1)), r.getString(2)),
                            input.toLowerCase(Locale.ROOT));
            return names.size() == 1 ? Optional.of(names.get(0)) : Optional.empty();
        }
    }

    public String name(Connection c, UUID uuid) throws SQLException {
        var rows =
                Sql.query(
                        c,
                        "SELECT COALESCE(name,uuid) FROM players WHERE uuid=?",
                        r -> r.getString(1),
                        uuid.toString());
        return rows.isEmpty() ? uuid.toString() : rows.get(0);
    }

    public List<String> suggestions(Connection c) throws SQLException {
        return Sql.query(
                c,
                "SELECT name FROM players WHERE name IS NOT NULL ORDER BY last_seen DESC LIMIT"
                        + " 1000",
                r -> r.getString(1));
    }

    public List<Ranked> page(
            Connection c,
            Ranking ranking,
            String filter,
            int offset,
            int size,
            long now,
            boolean leaderboard,
            com.wellsetups.wellviptime.configuration.Settings.ListSort sort,
            Set<String> hidden)
            throws SQLException {
        String score =
                switch (ranking) {
                    case HISTORICAL -> "b.historical_ms";
                    case EXPIRATION -> "b.expires_at";
                    case REMAINING -> "b.expires_at - ?";
                };
        String conditions =
                leaderboard && ranking == Ranking.HISTORICAL
                        ? "b.historical_ms>0"
                        : "b.status='ACTIVE' AND b.expires_at>?";
        List<Object> args = new ArrayList<>();
        if (ranking == Ranking.REMAINING) {
            args.add(now);
        }
        if (!(leaderboard && ranking == Ranking.HISTORICAL)) {
            args.add(now);
        }
        if (leaderboard) {
            conditions += " AND p.hidden=0";
        }
        if (leaderboard && !hidden.isEmpty()) {
            conditions +=
                    " AND p.uuid NOT IN ("
                            + String.join(",", Collections.nCopies(hidden.size(), "?"))
                            + ")";
            args.addAll(hidden);
        }
        if (filter != null) {
            conditions += " AND b.vip_type=?";
            args.add(filter);
        }
        args.add(size);
        args.add(offset);
        String listOrder =
                switch (sort) {
                    case NAME -> "player_name,b.uuid,b.vip_type";
                    case TYPE -> "b.vip_type,b.expires_at DESC,b.uuid";
                    case EXPIRATION -> "score DESC,b.uuid,b.vip_type";
                };
        String query =
                leaderboard
                        ? "SELECT * FROM (SELECT b.*,COALESCE(p.name,p.uuid) AS player_name,"
                                + (ranking == Ranking.EXPIRATION ? "MAX(" : "SUM(")
                                + score
                                + ") OVER(PARTITION BY b.uuid) AS score,ROW_NUMBER() OVER(PARTITION"
                                + " BY b.uuid ORDER BY b.expires_at DESC,b.vip_type) AS position"
                                + " FROM vip_balances b JOIN players p ON p.uuid=b.uuid WHERE "
                                + conditions
                                + ") ranked WHERE position=1 ORDER BY score DESC,uuid LIMIT ?"
                                + " OFFSET ?"
                        : "SELECT b.*,COALESCE(p.name,p.uuid) AS player_name,"
                                + score
                                + " AS score FROM vip_balances b JOIN players p ON p.uuid=b.uuid"
                                + " WHERE "
                                + conditions
                                + " ORDER BY "
                                + listOrder
                                + " LIMIT ? OFFSET ?";
        return Sql.query(
                c,
                query,
                r ->
                        new Ranked(
                                new Identity(
                                        UUID.fromString(r.getString("uuid")),
                                        r.getString("player_name")),
                                balance(r),
                                r.getLong("score")),
                args.toArray());
    }

    public void dirty(Connection c, UUID player, long now) throws SQLException {
        int changed =
                Sql.update(
                        c,
                        "UPDATE permission_work SET revision=revision+1,updated_at=? WHERE uuid=?",
                        now,
                        player.toString());
        if (changed == 0) {
            Sql.update(
                    c,
                    "INSERT INTO permission_work(uuid,revision,updated_at,lease_owner,lease_until)"
                            + " VALUES(?,1,?,NULL,0)",
                    player.toString(),
                    now);
        }
    }

    public void dirtyAll(Connection c, long now) throws SQLException {
        for (UUID uuid :
                Sql.query(
                        c,
                        "SELECT DISTINCT uuid FROM vip_balances ORDER BY uuid",
                        r -> UUID.fromString(r.getString(1)))) {
            database.lockPlayers(c, List.of(uuid));
            dirty(c, uuid, now);
        }
    }

    private static VipBalance balance(ResultSet r) throws SQLException {
        return new VipBalance(
                UUID.fromString(r.getString("uuid")),
                r.getString("vip_type"),
                r.getLong("started_at"),
                r.getLong("expires_at"),
                r.getLong("original_ms"),
                r.getLong("historical_ms"),
                VipBalance.Status.valueOf(r.getString("status")),
                r.getLong("revision"),
                UUID.fromString(r.getString("created_by")),
                r.getLong("created_at"),
                r.getLong("modified_at"));
    }
}
