package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.audit.AuditEntry;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;

import java.sql.SQLException;
import java.util.*;

public final class VoucherService {

    public record Frozen(Voucher voucher, VipService.Change change) {}

    private final Database database;

    private final VipRepository balances;

    private final VoucherRepository vouchers;

    private final VipService vip;

    private final Cooldowns cooldowns = new Cooldowns();

    public VoucherService(
            Database database, VipRepository balances, VoucherRepository vouchers, VipService vip) {
        this.database = database;
        this.balances = balances;
        this.vouchers = vouchers;
        this.vip = vip;
    }

    public List<Voucher> create(
            UUID operation,
            VipService.Actor actor,
            String type,
            long duration,
            int amount,
            Settings settings)
            throws SQLException {
        return createFor(operation, actor, List.of(actor.uuid()), type, duration, amount, settings);
    }

    public List<Voucher> createFor(
            UUID operation,
            VipService.Actor actor,
            List<UUID> recipients,
            String type,
            long duration,
            int amount,
            Settings settings)
            throws SQLException {
        enabled(settings);
        VipService.requireType(type, settings);
        validateCreation(amount, duration, settings);
        var origin =
                new VoucherOrigin(
                        actor.uuid().equals(new UUID(0, 0))
                                ? VoucherOrigin.Kind.CONSOLE
                                : VoucherOrigin.Kind.ADMIN,
                        actor.name());
        boolean ownerOnly = settings.voucher().ownership() == Settings.Ownership.OWNER_ONLY;
        boolean webhook =
                settings.discord().enabled()
                        && settings.discord().events().contains("VIP_VOUCHER_CREATED");
        return database.transaction(
                c -> {
                    var targets = recipients.stream().distinct().toList();
                    var locks = new ArrayList<>(targets);
                    locks.add(actor.uuid());
                    database.lockPlayers(c, locks);
                    long now = database.now(c);
                    List<Voucher> created = new ArrayList<>();
                    for (UUID recipient : targets) {
                        UUID owner = ownerOnly ? recipient : null;
                        String name =
                                recipient.equals(actor.uuid())
                                        ? actor.name()
                                        : balances.name(c, recipient);
                        for (int index = 0; index < amount; index++) {
                            var voucher =
                                    new Voucher(
                                            UUID.randomUUID(),
                                            1,
                                            type,
                                            duration,
                                            now,
                                            actor.uuid(),
                                            owner,
                                            true);
                            vouchers.issue(c, voucher, recipient);
                            vouchers.origin(c, voucher.id(), origin);
                            created.add(voucher);
                            UUID auditOperation =
                                    UUID.nameUUIDFromBytes(
                                            (operation + ":" + recipient + ":" + index)
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                            var audit =
                                    new AuditEntry(
                                            UUID.randomUUID(),
                                            auditOperation,
                                            "VIP_VOUCHER_CREATED",
                                            actor.uuid(),
                                            actor.name(),
                                            recipient,
                                            name,
                                            type,
                                            duration,
                                            0,
                                            0,
                                            voucher.id(),
                                            now);
                            audit.insert(c, webhook);
                        }
                    }
                    return List.copyOf(created);
                });
    }

    private static void validateCreation(int amount, long duration, Settings settings) {
        if (amount < 1 || amount > settings.voucher().maxAmount()) {
            throw new DomainFailure(
                    "error.amount",
                    Map.of("maximum", Integer.toString(settings.voucher().maxAmount())));
        }
        if (duration < 1 || duration > settings.core().maxDays() * 86400000L) {
            throw new DomainFailure("error.policy");
        }
    }

    public Frozen freeze(
            UUID operation,
            VipService.Actor actor,
            UUID target,
            String type,
            boolean bypass,
            Settings settings)
            throws SQLException {
        return freeze(
                operation,
                actor,
                target,
                type,
                bypass,
                settings,
                new VoucherOrigin(
                        actor.uuid().equals(new UUID(0, 0))
                                ? VoucherOrigin.Kind.CONSOLE
                                : actor.uuid().equals(target)
                                        ? VoucherOrigin.Kind.PLAYER
                                        : VoucherOrigin.Kind.ADMIN,
                        actor.name()));
    }

    public Frozen freeze(
            UUID operation,
            VipService.Actor actor,
            UUID target,
            String type,
            boolean bypass,
            Settings settings,
            VoucherOrigin origin)
            throws SQLException {
        enabled(settings);
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(actor.uuid(), target));
                    long now = database.now(c);
                    var active =
                            balances.all(c, target).stream()
                                    .filter(
                                            b ->
                                                    b.active(now)
                                                            && (type == null
                                                                    || b.type().equals(type)))
                                    .toList();
                    if (active.isEmpty()) {
                        throw new DomainFailure(
                                "error.no-vip", Map.of("player", balances.name(c, target)));
                    }
                    if (active.size() > 1) {
                        throw new DomainFailure(
                                "error.multiple",
                                Map.of(
                                        "types",
                                        String.join(
                                                ", ",
                                                active.stream().map(VipBalance::type).toList())));
                    }
                    VipBalance balance = active.get(0);
                    VipService.requireType(balance.type(), settings);
                    long duration = balance.remaining(now);
                    if (duration < settings.voucher().minFreeze() * 1000
                            || duration > settings.voucher().maxFreeze() * 1000) {
                        throw new DomainFailure("error.freeze-range");
                    }
                    cooldowns.take(
                            c,
                            actor.uuid(),
                            now,
                            settings.voucher().cooldown() * 1000,
                            settings.voucher().persistentCooldown(),
                            bypass);
                    var voucher =
                            new Voucher(
                                    UUID.randomUUID(),
                                    1,
                                    balance.type(),
                                    duration,
                                    now,
                                    actor.uuid(),
                                    settings.voucher().ownership() == Settings.Ownership.OWNER_ONLY
                                            ? target
                                            : null,
                                    false);
                    vouchers.issue(
                            c,
                            voucher,
                            actor.uuid().equals(new UUID(0, 0)) ? target : actor.uuid());
                    vouchers.origin(c, voucher.id(), origin);
                    var frozen = VipRules.deactivate(balance, VipBalance.Status.FROZEN, now);
                    balances.save(c, frozen);
                    balances.dirty(c, target, now);
                    var change =
                            vip.audited(
                                    c,
                                    operation,
                                    actor,
                                    frozen,
                                    "VIP_FROZEN",
                                    duration,
                                    balance.expiresAt(),
                                    voucher.id(),
                                    settings);
                    return new Frozen(voucher, change);
                });
    }

    public VipService.Change redeem(
            UUID operation, VipService.Actor actor, Voucher voucher, Settings settings)
            throws SQLException {
        enabled(settings);
        VipService.requireType(voucher.type(), settings);
        if (voucher.schema() != 1) {
            throw new DomainFailure("error.voucher-version");
        }
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(actor.uuid()));
                    vouchers.claim(c, voucher, actor.uuid(), database.now(c));
                    return vip.grantIn(
                            c,
                            operation,
                            actor,
                            actor.uuid(),
                            voucher.type(),
                            voucher.durationMs(),
                            voucher.id(),
                            voucher.historicalCredit(),
                            settings);
                });
    }

    public Voucher administrate(
            UUID operation, VipService.Actor actor, UUID id, boolean recover, Settings settings)
            throws SQLException {
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(actor.uuid()));
                    var state =
                            Sql.query(
                                    c,
                                    "SELECT state FROM vouchers WHERE id=?"
                                            + (database.sqlite() ? "" : " FOR UPDATE"),
                                    r -> r.getString(1),
                                    id.toString());
                    if (state.isEmpty()) {
                        throw new DomainFailure("error.voucher-invalid");
                    }
                    if (!state.get(0).equals("ISSUED")) {
                        throw new DomainFailure("error.voucher-used");
                    }
                    var voucher =
                            vouchers.find(c, id)
                                    .orElseThrow(() -> new DomainFailure("error.voucher-invalid"));
                    if (recover) {
                        recoverDelivery(c, id, actor.uuid());
                    } else {
                        Sql.update(
                                c,
                                "UPDATE vouchers SET state='REVOKED' WHERE id=? AND state='ISSUED'",
                                id.toString());
                    }
                    String action = recover ? "VIP_VOUCHER_RECOVERED" : "VIP_REVOKED";
                    UUID target = voucher.owner() == null ? voucher.issuer() : voucher.owner();
                    var audit =
                            new AuditEntry(
                                    UUID.randomUUID(),
                                    operation,
                                    action,
                                    actor.uuid(),
                                    actor.name(),
                                    target,
                                    balances.name(c, target),
                                    voucher.type(),
                                    voucher.durationMs(),
                                    0,
                                    0,
                                    id,
                                    database.now(c));
                    audit.insert(
                            c,
                            settings.discord().enabled()
                                    && settings.discord().events().contains(action));
                    return voucher;
                });
    }

    private void recoverDelivery(java.sql.Connection c, UUID id, UUID recipient)
            throws SQLException {
        Sql.update(
                c,
                (database.sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                        + " INTO deliveries(voucher_id,recipient,delivered_at)"
                        + " VALUES(?,?,NULL)",
                id.toString(),
                recipient.toString());
        Sql.update(
                c,
                "UPDATE deliveries SET recipient=?,delivered_at=NULL WHERE" + " voucher_id=?",
                recipient.toString(),
                id.toString());
    }

    private static void enabled(Settings settings) {
        if (!settings.voucher().enabled()) {
            throw new DomainFailure("error.vouchers-disabled");
        }
    }
}
