package com.wellsetups.wellviptime.integration;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.node.types.InheritanceNode;

import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LuckPermsSync implements AutoCloseable {

    private record Lease(UUID uuid, long revision, String owner) {}

    private final LuckPerms luckPerms;

    private final Database database;

    private final VipRepository repository;

    private final String contextValue;

    private final ContextCalculator<Player> calculator;

    private final DebugLog debug;

    public LuckPermsSync(
            LuckPerms luckPerms,
            Database database,
            VipRepository repository,
            String networkId,
            DebugLog debug) {
        this.debug = debug;
        this.luckPerms = luckPerms;
        this.database = database;
        this.repository = repository;
        contextValue = networkId;
        calculator =
                new ContextCalculator<>() {

                    @Override
                    public void calculate(Player target, ContextConsumer consumer) {
                        consumer.accept("vipmanager", contextValue);
                    }

                    @Override
                    public ContextSet estimatePotentialContexts() {
                        return ImmutableContextSet.of("vipmanager", contextValue);
                    }
                };
        luckPerms.getContextManager().registerCalculator(calculator);
    }

    public void process(Settings settings) throws SQLException {
        List<UUID> candidates =
                database.read(
                        c ->
                                Sql.query(
                                        c,
                                        "SELECT uuid FROM permission_work WHERE lease_until<=? AND"
                                                + " updated_at<=? ORDER BY updated_at LIMIT ?",
                                        r -> UUID.fromString(r.getString(1)),
                                        database.now(c),
                                        database.now(c),
                                        settings.core().batchSize()));
        if (!candidates.isEmpty()) {
            debug.write(
                    "luckperms", "Processing " + candidates.size() + " reconciliation candidates");
        }
        for (UUID uuid : candidates) {
            Lease lease =
                    database.transaction(
                            c -> {
                                long now = database.now(c);
                                String owner = UUID.randomUUID().toString();
                                int won =
                                        Sql.update(
                                                c,
                                                "UPDATE permission_work SET"
                                                    + " lease_owner=?,lease_until=? WHERE uuid=?"
                                                    + " AND lease_until<=? AND updated_at<=?",
                                                owner,
                                                now + 60000,
                                                uuid.toString(),
                                                now,
                                                now);
                                if (won == 0) {
                                    return null;
                                }
                                long revision =
                                        Sql.query(
                                                        c,
                                                        "SELECT revision FROM permission_work WHERE"
                                                                + " uuid=?",
                                                        r -> r.getLong(1),
                                                        uuid.toString())
                                                .get(0);
                                return new Lease(uuid, revision, owner);
                            });
            if (lease == null) {
                continue;
            }
            try {
                synchronize(uuid, settings);
                database.transaction(
                        c -> {
                            Sql.update(
                                    c,
                                    "UPDATE permission_work SET"
                                            + " lease_owner=NULL,lease_until=0,updated_at=CASE WHEN"
                                            + " revision=? THEN ? ELSE 0 END WHERE uuid=? AND"
                                            + " lease_owner=?",
                                    lease.revision(),
                                    database.now(c) + 60000,
                                    uuid.toString(),
                                    lease.owner());
                            return null;
                        });
            } catch (ExecutionException | TimeoutException e) {
                throw new SQLException(
                        "LuckPerms synchronization failed for "
                                + uuid
                                + "; durable retry remains pending",
                        e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("LuckPerms synchronization interrupted", e);
            }
        }
    }

    public List<String> validateGroups(Settings settings) throws SQLException {
        return validateGroups(luckPerms, settings);
    }

    public static List<String> validateGroups(LuckPerms luckPerms, Settings settings)
            throws SQLException {
        List<String> provisioned = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        long deadline = System.nanoTime() + settings.database().timeoutSeconds() * 1000000000L;
        for (var type : settings.types().values()) {
            if (!checked.add(type.group())) {
                continue;
            }
            if (luckPerms.getGroupManager().getGroup(type.group()) != null) {
                continue;
            }
            try {
                if (luckPerms
                        .getGroupManager()
                        .loadGroup(type.group())
                        .get(remaining(deadline), TimeUnit.NANOSECONDS)
                        .isEmpty()) {
                    if (!settings.core().createMissingGroups()) {
                        throw new com.wellsetups.wellviptime.configuration.ConfigError(
                                "vip-types.yml",
                                "vip-types." + type.id() + ".luckperms-group",
                                type.group(),
                                "existing LuckPerms group: run /lp creategroup "
                                        + type.group()
                                        + " or enable config.yml luckperms.create-missing-groups");
                    }
                    // Creates only if absent, including concurrent network startup.
                    luckPerms
                            .getGroupManager()
                            .createAndLoadGroup(type.group())
                            .get(remaining(deadline), TimeUnit.NANOSECONDS);
                    provisioned.add(type.group());
                }
            } catch (ExecutionException | TimeoutException e) {
                throw new SQLException("Could not validate LuckPerms group " + type.group(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("LuckPerms group validation interrupted", e);
            }
        }
        return List.copyOf(provisioned);
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Group validation deadline exceeded");
        }
        return remaining;
    }

    private void synchronize(UUID uuid, Settings settings)
            throws SQLException, ExecutionException, InterruptedException, TimeoutException {
        var balances = database.read(c -> repository.all(c, uuid));
        long now = System.currentTimeMillis();
        Map<String, Long> groups = new HashMap<>();
        for (var balance : balances) {
            var type = settings.types().get(balance.type());
            if (type != null && balance.active(now)) {
                groups.merge(type.group(), balance.expiresAt(), Math::max);
            }
        }
        var user = luckPerms.getUserManager().loadUser(uuid).get(10, TimeUnit.SECONDS);
        try {
            user.data()
                    .clear(
                            node ->
                                    node instanceof InheritanceNode
                                            && node.getContexts()
                                                    .contains("vipmanager", contextValue));
            groups.forEach(
                    (group, expiry) ->
                            user.data()
                                    .add(
                                            InheritanceNode.builder(group)
                                                    .withContext("vipmanager", contextValue)
                                                    .expiry(Instant.ofEpochMilli(expiry))
                                                    .build()));
            luckPerms.getUserManager().saveUser(user).get(10, TimeUnit.SECONDS);
            luckPerms.getMessagingService().ifPresent(service -> service.pushUserUpdate(user));
        } finally {
            luckPerms.getUserManager().cleanupUser(user);
        }
    }

    @Override
    public void close() {
        luckPerms.getContextManager().unregisterCalculator(calculator);
    }
}
