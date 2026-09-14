package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.ConfigLoader;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;
import com.wellsetups.wellviptime.voucher.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

class VoucherTest {

    @TempDir Path directory;

    @Test
    void signatureRejectsTamperingWrongNetworkAndUnknownSchema() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var signer = new VoucherSigner(key, "network");
        var voucher =
                new Voucher(UUID.randomUUID(), 1, "vip", 60000, 10, UUID.randomUUID(), null, true);
        byte[] payload = signer.encode(voucher);
        byte[] signature = signer.sign(payload);
        assertEquals(voucher, signer.verify(payload, signature));
        assertThrows(
                DomainFailure.class,
                () -> new VoucherSigner(key, "other").verify(payload, signature));
        byte[] changed = payload.clone();
        changed[changed.length - 1] ^= 1;
        assertThrows(DomainFailure.class, () -> signer.verify(changed, signature));
        byte[] future =
                signer.encode(
                        new Voucher(
                                voucher.id(), 2, "vip", 60000, 10, voucher.issuer(), null, true));
        assertEquals(
                "error.voucher-version",
                assertThrows(DomainFailure.class, () -> signer.verify(future, signer.sign(future)))
                        .key());
    }

    @Test
    void twoIndependentConnectionsRedeemOnlyOnce() throws Exception {
        var settings =
                new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true)
                        .load()
                        .settings();
        try (var first = new Database(StorageTest.sqlite(), directory);
                var second = new Database(StorageTest.sqlite(), directory)) {
            first.migrate();
            second.migrate();
            var repo = new VipRepository(first);
            var vouchers = new VoucherRepository();
            var a = new VoucherService(first, repo, vouchers, new VipService(first, repo));
            var otherRepo = new VipRepository(second);
            var b =
                    new VoucherService(
                            second, otherRepo, vouchers, new VipService(second, otherRepo));
            var issuer = new VipService.Actor(UUID.randomUUID(), "Issuer");
            var voucher = a.create(UUID.randomUUID(), issuer, "vip", 86400000, 1, settings).get(0);
            var buyer = new VipService.Actor(UUID.randomUUID(), "Buyer");
            var gate = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (var service : List.of(a, b)) {
                    attempts.add(
                            executor.submit(
                                    () -> {
                                        gate.await();
                                        try {
                                            service.redeem(
                                                    UUID.randomUUID(), buyer, voucher, settings);
                                            return true;
                                        } catch (DomainFailure failure) {
                                            assertEquals("error.voucher-used", failure.key());
                                            return false;
                                        }
                                    }));
                }
                gate.countDown();
                int successes = 0;
                for (var attempt : attempts) {
                    if (attempt.get(10, TimeUnit.SECONDS)) {
                        successes++;
                    }
                }
                assertEquals(1, successes);
            }
            assertEquals(
                    86400000,
                    first.read(c -> repo.all(c, buyer.uuid()).get(0).historicalMs()).longValue());
        }
    }

    @Test
    void rejectedGrantRollsBackVoucherClaimAndFreezeDoesNotInflateHistory() throws Exception {
        var loader = new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true);
        var settings = loader.load().settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var vip = new VipService(db, repo);
            var service = new VoucherService(db, repo, new VoucherRepository(), vip);
            var actor = new VipService.Actor(UUID.randomUUID(), "Player");
            vip.grant(UUID.randomUUID(), actor, actor.uuid(), "vip", 86400000, settings);
            var minted =
                    service.create(UUID.randomUUID(), actor, "vip", 3600000, 1, settings).get(0);
            Path file = directory.resolve("config/config.yml");
            Files.writeString(
                    file, Files.readString(file).replace("same-type: EXTEND", "same-type: REJECT"));
            var reject = loader.load().settings();
            assertThrows(
                    DomainFailure.class,
                    () -> service.redeem(UUID.randomUUID(), actor, minted, reject));
            assertEquals(
                    "ISSUED",
                    db.read(
                            c ->
                                    Sql.query(
                                                    c,
                                                    "SELECT state FROM vouchers WHERE id=?",
                                                    r -> r.getString(1),
                                                    minted.id().toString())
                                            .get(0)));
            var frozen =
                    service.freeze(UUID.randomUUID(), actor, actor.uuid(), "vip", true, settings);
            service.redeem(UUID.randomUUID(), actor, frozen.voucher(), settings);
            assertEquals(
                    86400000,
                    db.read(c -> repo.all(c, actor.uuid()).get(0).historicalMs()).longValue());
        }
    }
}
