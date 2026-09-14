package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.notification.NotificationService;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;
import com.wellsetups.wellviptime.voucher.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class RepositoryRulesTest {

    @TempDir Path directory;

    private Settings settings() throws Exception {
        return new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true)
                .load()
                .settings();
    }

    @Test
    void leaderboardAggregatesPlayersExcludesHiddenAndPaginates() throws Exception {
        var settings = settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var vip = new VipService(db, repo);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            var actor = VipService.Actor.system();
            vip.grant(UUID.randomUUID(), actor, first, "vip", 7200000, settings);
            vip.grant(UUID.randomUUID(), actor, first, "vip-plus", 7200000, settings);
            vip.grant(UUID.randomUUID(), actor, second, "vip", 10800000, settings);
            var page =
                    db.read(
                            c ->
                                    repo.page(
                                            c,
                                            Settings.Ranking.REMAINING,
                                            null,
                                            0,
                                            1,
                                            db.now(c),
                                            true,
                                            Settings.ListSort.EXPIRATION,
                                            Set.of()));
            assertEquals(1, page.size());
            assertEquals(first, page.get(0).player().uuid());
            assertTrue(page.get(0).score() > 14300000);
            var next =
                    db.read(
                            c ->
                                    repo.page(
                                            c,
                                            Settings.Ranking.REMAINING,
                                            null,
                                            1,
                                            1,
                                            db.now(c),
                                            true,
                                            Settings.ListSort.EXPIRATION,
                                            Set.of()));
            assertEquals(second, next.get(0).player().uuid());
            var hidden =
                    db.read(
                            c ->
                                    repo.page(
                                            c,
                                            Settings.Ranking.HISTORICAL,
                                            null,
                                            0,
                                            10,
                                            db.now(c),
                                            true,
                                            Settings.ListSort.EXPIRATION,
                                            Set.of(first.toString())));
            assertEquals(1, hidden.size());
            assertEquals(second, hidden.get(0).player().uuid());
        }
    }

    @Test
    void repeatedOperationIdCannotExtendTwice() throws Exception {
        var settings = settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var vip = new VipService(db, repo);
            UUID operation = UUID.randomUUID();
            UUID player = UUID.randomUUID();
            vip.grant(operation, VipService.Actor.system(), player, "vip", 3600000, settings);
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            vip.grant(
                                    operation,
                                    VipService.Actor.system(),
                                    player,
                                    "vip",
                                    3600000,
                                    settings));
            assertEquals(
                    3600000, db.read(c -> repo.all(c, player).get(0).historicalMs()).longValue());
        }
    }

    @Test
    void concurrentAdminsDoNotLoseAnExtension() throws Exception {
        var settings = settings();
        try (var a = new Database(StorageTest.sqlite(), directory);
                var b = new Database(StorageTest.sqlite(), directory);
                var executor = Executors.newFixedThreadPool(2)) {
            a.migrate();
            b.migrate();
            UUID player = UUID.randomUUID();
            var gate = new CountDownLatch(1);
            var one = new VipService(a, new VipRepository(a));
            var two = new VipService(b, new VipRepository(b));
            List<Future<?>> requests = new ArrayList<>();
            for (var service : List.of(one, two)) {
                requests.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    service.grant(
                                            UUID.randomUUID(),
                                            VipService.Actor.system(),
                                            player,
                                            "vip",
                                            3600000,
                                            settings);
                                    return null;
                                }));
            }
            gate.countDown();
            for (var request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
            assertEquals(
                    7200000,
                    a.read(c -> new VipRepository(a).all(c, player).get(0).originalMs())
                            .longValue());
        }
    }

    @Test
    void revokedVoucherFailsAndOwnershipIsChecked() throws Exception {
        var settings = settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var vouchers = new VoucherRepository();
            var service = new VoucherService(db, repo, vouchers, new VipService(db, repo));
            var owner = new VipService.Actor(UUID.randomUUID(), "Owner");
            var other = new VipService.Actor(UUID.randomUUID(), "Other");
            var voucher =
                    new Voucher(
                            UUID.randomUUID(),
                            1,
                            "vip",
                            3600000,
                            1000,
                            owner.uuid(),
                            owner.uuid(),
                            true);
            db.transaction(
                    c -> {
                        vouchers.issue(c, voucher, owner.uuid());
                        return null;
                    });
            assertEquals(
                    "error.voucher-owner",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.redeem(
                                                    UUID.randomUUID(), other, voucher, settings))
                            .key());
            service.administrate(UUID.randomUUID(), owner, voucher.id(), false, settings);
            assertEquals(
                    "error.voucher-used",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.redeem(
                                                    UUID.randomUUID(), owner, voucher, settings))
                            .key());
        }
    }

    @Test
    void notificationReceiptsSurviveRestart() throws Exception {
        var settings = settings();
        UUID player = UUID.randomUUID();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            db.transaction(
                    c -> {
                        db.lockPlayers(c, List.of(player));
                        long now = db.now(c);
                        repo.save(
                                c,
                                new VipBalance(
                                        player,
                                        "vip",
                                        now - 3500000,
                                        now + 100000,
                                        3600000,
                                        3600000,
                                        VipBalance.Status.ACTIVE,
                                        1,
                                        player,
                                        now - 3500000,
                                        now));
                        return null;
                    });
            assertEquals(
                    1,
                    new NotificationService(db, repo)
                            .refresh(player, true, settings)
                            .notices()
                            .size());
        }
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            assertTrue(
                    new NotificationService(db, new VipRepository(db))
                            .refresh(player, true, settings)
                            .notices()
                            .isEmpty());
        }
    }
}
