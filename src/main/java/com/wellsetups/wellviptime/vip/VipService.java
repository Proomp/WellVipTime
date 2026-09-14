package com.wellsetups.wellviptime.vip;

import com.wellsetups.wellviptime.audit.AuditEntry;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.Database;
import com.wellsetups.wellviptime.storage.VipRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public final class VipService {

    public record Actor(UUID uuid, String name) {

        public static Actor system() {
            return new Actor(new UUID(0, 0), "SYSTEM");
        }
    }

    public record Change(AuditEntry audit, VipBalance balance) {}

    private final Database database;

    private final VipRepository repository;

    public VipService(Database database, VipRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    public Change grant(
            UUID operation, Actor actor, UUID target, String type, long duration, Settings settings)
            throws SQLException {
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(target));
                    return grantIn(
                            c, operation, actor, target, type, duration, null, true, settings);
                });
    }

    public Change grantIn(
            Connection c,
            UUID operation,
            Actor actor,
            UUID target,
            String type,
            long duration,
            UUID voucher,
            boolean historicalCredit,
            Settings settings)
            throws SQLException {
        requireType(type, settings);
        long now = database.now(c);
        List<VipBalance> balances = repository.all(c, target);
        VipBalance previous =
                balances.stream().filter(b -> b.type().equals(type)).findFirst().orElse(null);
        VipBalance next =
                VipRules.grant(target, type, previous, duration, now, actor.uuid(), settings);
        if (!historicalCredit) {
            next =
                    new VipBalance(
                            next.player(),
                            next.type(),
                            next.startedAt(),
                            next.expiresAt(),
                            next.originalMs(),
                            next.historicalMs() - duration,
                            next.status(),
                            next.revision(),
                            next.createdBy(),
                            next.createdAt(),
                            next.modifiedAt());
        }
        for (VipBalance replaced : VipRules.replaced(balances, type, now, settings)) {
            repository.save(c, VipRules.deactivate(replaced, VipBalance.Status.REMOVED, now));
        }
        repository.save(c, next);
        repository.dirty(c, target, now);
        String action =
                voucher != null
                        ? "VIP_VOUCHER_REDEEMED"
                        : previous != null && previous.active(now) ? "VIP_EXTENDED" : "VIP_GRANTED";
        return audited(
                c,
                operation,
                actor,
                next,
                action,
                duration,
                previous == null ? 0 : previous.expiresAt(),
                voucher,
                settings);
    }

    public Change remove(
            UUID operation, Actor actor, UUID target, String type, long duration, Settings settings)
            throws SQLException {
        requireType(type, settings);
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(target));
                    long now = database.now(c);
                    VipBalance previous =
                            repository.all(c, target).stream()
                                    .filter(b -> b.type().equals(type))
                                    .findFirst()
                                    .orElse(null);
                    VipBalance next =
                            VipRules.remove(previous, duration, now, settings.core().remove());
                    repository.save(c, next);
                    repository.dirty(c, target, now);
                    return audited(
                            c,
                            operation,
                            actor,
                            next,
                            "VIP_TIME_REMOVED",
                            Math.min(duration, previous.remaining(now)),
                            previous.expiresAt(),
                            null,
                            settings);
                });
    }

    public Optional<Change> expire(VipBalance candidate, Settings settings) throws SQLException {
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(candidate.player()));
                    long now = database.now(c);
                    VipBalance current =
                            repository.all(c, candidate.player()).stream()
                                    .filter(b -> b.type().equals(candidate.type()))
                                    .findFirst()
                                    .orElse(null);
                    if (current == null
                            || current.status() != VipBalance.Status.ACTIVE
                            || current.expiresAt() > now) {
                        return Optional.empty();
                    }
                    VipBalance next = VipRules.deactivate(current, VipBalance.Status.EXPIRED, now);
                    repository.save(c, next);
                    repository.dirty(c, next.player(), now);
                    return Optional.of(
                            audited(
                                    c,
                                    UUID.randomUUID(),
                                    Actor.system(),
                                    next,
                                    "VIP_EXPIRED",
                                    0,
                                    current.expiresAt(),
                                    null,
                                    settings));
                });
    }

    public Change audited(
            Connection c,
            UUID operation,
            Actor actor,
            VipBalance next,
            String action,
            long duration,
            long previousExpiry,
            UUID voucher,
            Settings settings)
            throws SQLException {
        var audit =
                new AuditEntry(
                        UUID.randomUUID(),
                        operation,
                        action,
                        actor.uuid(),
                        actor.name(),
                        next.player(),
                        repository.name(c, next.player()),
                        next.type(),
                        duration,
                        previousExpiry,
                        next.status() == VipBalance.Status.ACTIVE ? next.expiresAt() : 0,
                        voucher,
                        database.now(c));
        audit.insert(
                c, settings.discord().enabled() && settings.discord().events().contains(action));
        return new Change(audit, next);
    }

    public static void requireType(String type, Settings settings) {
        if (type == null || !settings.types().containsKey(type)) {
            throw new DomainFailure("error.vip", Map.of("input", String.valueOf(type)));
        }
    }
}
