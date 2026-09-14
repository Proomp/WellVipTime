package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.storage.Sql;
import com.wellsetups.wellviptime.vip.DomainFailure;

import java.sql.*;
import java.util.*;

public final class VoucherRepository {

    public void origin(Connection c, UUID voucher, VoucherOrigin origin) throws SQLException {
        Sql.update(
                c,
                "INSERT INTO voucher_origins(voucher_id,kind,display_name) VALUES(?,?,?)",
                voucher.toString(),
                origin.kind().name(),
                origin.name());
    }

    public VoucherOrigin origin(Connection c, Voucher voucher) throws SQLException {
        var stored =
                Sql.query(
                        c,
                        "SELECT kind,display_name FROM voucher_origins WHERE voucher_id=?",
                        r ->
                                new VoucherOrigin(
                                        VoucherOrigin.Kind.valueOf(r.getString(1)), r.getString(2)),
                        voucher.id().toString());
        if (!stored.isEmpty()) {
            return stored.get(0);
        }
        if (voucher.issuer().equals(new UUID(0, 0))) {
            return new VoucherOrigin(VoucherOrigin.Kind.CONSOLE, "Console");
        }
        var historical =
                Sql.query(
                        c,
                        "SELECT actor_name,actor_uuid,target_uuid,action FROM audit_log WHERE"
                                + " voucher_id=? AND action IN ('VIP_FROZEN','VIP_VOUCHER_CREATED')"
                                + " ORDER BY created_at LIMIT 1",
                        r ->
                                new VoucherOrigin(
                                        r.getString(4).equals("VIP_VOUCHER_CREATED")
                                                        || !r.getString(2).equals(r.getString(3))
                                                ? VoucherOrigin.Kind.ADMIN
                                                : VoucherOrigin.Kind.PLAYER,
                                        r.getString(1)),
                        voucher.id().toString());
        return historical.isEmpty()
                ? new VoucherOrigin(
                        voucher.historicalCredit()
                                ? VoucherOrigin.Kind.ADMIN
                                : VoucherOrigin.Kind.PLAYER,
                        voucher.issuer().toString())
                : historical.get(0);
    }

    public void issue(Connection c, Voucher voucher, UUID recipient) throws SQLException {
        Sql.update(
                c,
                "INSERT INTO"
                    + " vouchers(id,schema_version,vip_type,duration_ms,created_at,issuer,owner,historical_credit,state)"
                    + " VALUES(?,?,?,?,?,?,?,?,'ISSUED')",
                voucher.id().toString(),
                voucher.schema(),
                voucher.type(),
                voucher.durationMs(),
                voucher.createdAt(),
                voucher.issuer().toString(),
                voucher.owner() == null ? null : voucher.owner().toString(),
                voucher.historicalCredit() ? 1 : 0);
        Sql.update(
                c,
                "INSERT INTO deliveries(voucher_id,recipient,delivered_at) VALUES(?,?,NULL)",
                voucher.id().toString(),
                recipient.toString());
    }

    public Optional<Voucher> find(Connection c, UUID id) throws SQLException {
        return Sql.query(
                        c,
                        "SELECT * FROM vouchers WHERE id=?",
                        VoucherRepository::read,
                        id.toString())
                .stream()
                .findFirst();
    }

    public void claim(Connection c, Voucher presented, UUID player, long now) throws SQLException {
        Voucher stored =
                Sql.query(
                                c,
                                "SELECT * FROM vouchers WHERE id=?",
                                VoucherRepository::read,
                                presented.id().toString())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new DomainFailure("error.voucher-invalid"));
        if (!stored.equals(presented)) {
            throw new DomainFailure("error.voucher-invalid");
        }
        if (stored.owner() != null && !stored.owner().equals(player)) {
            throw new DomainFailure("error.voucher-owner");
        }
        int updated =
                Sql.update(
                        c,
                        "UPDATE vouchers SET state='REDEEMED',redeemed_by=?,redeemed_at=? WHERE"
                                + " id=? AND state='ISSUED'",
                        player.toString(),
                        now,
                        stored.id().toString());
        if (updated != 1) {
            throw new DomainFailure("error.voucher-used");
        }
    }

    public List<Voucher> pending(Connection c, UUID recipient, int limit) throws SQLException {
        return Sql.query(
                c,
                "SELECT v.* FROM vouchers v JOIN deliveries d ON v.id=d.voucher_id WHERE"
                        + " d.recipient=? AND d.delivered_at IS NULL AND v.state='ISSUED' ORDER BY"
                        + " v.created_at,v.id LIMIT ?",
                VoucherRepository::read,
                recipient.toString(),
                limit);
    }

    public void delivered(Connection c, UUID voucher, UUID recipient, long now)
            throws SQLException {
        Sql.update(
                c,
                "UPDATE deliveries SET delivered_at=? WHERE voucher_id=? AND recipient=? AND"
                        + " delivered_at IS NULL",
                now,
                voucher.toString(),
                recipient.toString());
    }

    private static Voucher read(ResultSet r) throws SQLException {
        String owner = r.getString("owner");
        return new Voucher(
                UUID.fromString(r.getString("id")),
                r.getInt("schema_version"),
                r.getString("vip_type"),
                r.getLong("duration_ms"),
                r.getLong("created_at"),
                UUID.fromString(r.getString("issuer")),
                owner == null ? null : UUID.fromString(owner),
                r.getInt("historical_credit") == 1);
    }
}
