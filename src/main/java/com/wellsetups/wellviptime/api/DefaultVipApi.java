package com.wellsetups.wellviptime.api;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.integration.ServerTasks;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;
import com.wellsetups.wellviptime.voucher.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class DefaultVipApi implements VipApi {

    private final Supplier<Snapshot> configuration;

    private final Database database;

    private final VipRepository repository;

    private final VipService vip;

    private final VoucherService vouchers;

    private final VoucherSigner signer;

    private final AsyncWork work;

    private final ServerTasks main;

    private final Consumer<VipService.Change> changed;

    public DefaultVipApi(
            Supplier<Snapshot> configuration,
            Database database,
            VipRepository repository,
            VipService vip,
            VoucherService vouchers,
            VoucherSigner signer,
            AsyncWork work,
            ServerTasks main,
            Consumer<VipService.Change> changed) {
        this.configuration = configuration;
        this.database = database;
        this.repository = repository;
        this.vip = vip;
        this.vouchers = vouchers;
        this.signer = signer;
        this.work = work;
        this.main = main;
        this.changed = changed;
    }

    @Override
    public CompletionStage<List<VipBalance>> getActiveVips(UUID player) {
        return work.submit(
                () ->
                        database.read(
                                c -> {
                                    long now = database.now(c);
                                    return repository.all(c, player).stream()
                                            .filter(b -> b.active(now))
                                            .toList();
                                }));
    }

    @Override
    public CompletionStage<VipBalance> grant(
            UUID operation, Actor actor, UUID target, String type, Duration duration) {
        var settings = configuration.get().settings();
        return work.submit(
                        () ->
                                vip.grant(
                                        operation,
                                        actor(actor),
                                        target,
                                        type,
                                        duration.toMillis(),
                                        settings))
                .thenApply(this::committed);
    }

    @Override
    public CompletionStage<VipBalance> removeTime(
            UUID operation, Actor actor, UUID target, String type, Duration duration) {
        var settings = configuration.get().settings();
        return work.submit(
                        () ->
                                vip.remove(
                                        operation,
                                        actor(actor),
                                        target,
                                        type,
                                        duration.toMillis(),
                                        settings))
                .thenApply(this::committed);
    }

    @Override
    public CompletionStage<VoucherToken> freeze(
            UUID operation, Actor actor, UUID target, String type, boolean bypassCooldown) {
        var settings = configuration.get().settings();
        return work.submit(
                        () ->
                                vouchers.freeze(
                                        operation,
                                        actor(actor),
                                        target,
                                        type,
                                        bypassCooldown,
                                        settings))
                .thenApply(
                        result -> {
                            committed(result.change());
                            byte[] payload = signer.encode(result.voucher());
                            return new VoucherToken(payload, signer.sign(payload));
                        });
    }

    @Override
    public CompletionStage<VipBalance> redeem(UUID operation, Actor actor, VoucherToken token) {
        var settings = configuration.get().settings();
        return work.submit(
                        () ->
                                vouchers.redeem(
                                        operation,
                                        actor(actor),
                                        signer.verify(token.payload(), token.signature()),
                                        settings))
                .thenApply(this::committed);
    }

    private VipBalance committed(VipService.Change change) {
        main.execute(() -> changed.accept(change));
        return change.balance();
    }

    private static VipService.Actor actor(Actor actor) {
        return new VipService.Actor(actor.uuid(), actor.name());
    }
}
