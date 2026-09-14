package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.audit.*;
import com.wellsetups.wellviptime.command.CommandTree;
import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;
import com.wellsetups.wellviptime.voucher.*;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

class DistributionTest {

    @TempDir Path directory;

    @Test
    void nestedGiveCommandsParseAndSuggestWithSeparatePermissions() throws Exception {
        var snapshot = new ConfigLoader(directory, ignored -> {}, m -> true).load();
        var permission = snapshot.settings().commands().get("viptime.item.give").permission();
        var sender =
                (CommandSender)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {CommandSender.class},
                                (self, method, args) ->
                                        method.getName().equals("hasPermission")
                                                && permission.equals(args[0]));
        var tree =
                new CommandTree(
                        snapshot.settings().commands(),
                        snapshot::settings,
                        () -> List.of("Alex"),
                        r -> {});
        var gift =
                tree.parse(
                        sender, "viptime", new String[] {"item", "give", "vip", "7d", "Alex", "3"});
        assertEquals("viptime.item.give", gift.id());
        assertEquals("Alex", gift.player());
        assertEquals(3, gift.amount());
        assertEquals("vip", gift.vip());
        assertEquals("7d", gift.duration());
        assertEquals(
                "viptime.item.giveall",
                tree.parse(sender, "viptime", new String[] {"item", "giveall", "vip", "7d"}).id());
        assertEquals(
                "viptime.item",
                tree.parse(sender, "viptime", new String[] {"item", "vip", "7d"}).id());
        assertThrows(
                DomainFailure.class,
                () -> tree.parse(sender, "viptime", new String[] {"item", "give", "vip", "7d"}));
        assertThrows(
                DomainFailure.class,
                () ->
                        tree.parse(
                                sender,
                                "viptime",
                                new String[] {"item", "giveall", "vip", "7d", "0"}));
        assertEquals(List.of("item"), tree.suggest(sender, "viptime", new String[] {""}));
        assertEquals(List.of("give"), tree.suggest(sender, "viptime", new String[] {"item", ""}));
        assertEquals(
                List.of("Alex"),
                tree.suggest(sender, "viptime", new String[] {"item", "give", "vip", "7d", "A"}));
        assertTrue(tree.suggest(sender, "viptime", new String[] {"item", "giveall", ""}).isEmpty());
    }

    @Test
    void bulkVouchersAreUniqueBoundToRecipientsAndDurablyQueued() throws Exception {
        var loader = new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true);
        loader.load();
        Path file = directory.resolve("config/config.yml");
        Files.writeString(
                file,
                Files.readString(file).replace("ownership: TRANSFERABLE", "ownership: OWNER_ONLY"));
        var settings = loader.load().settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var records = new VoucherRepository();
            var service = new VoucherService(db, repo, records, new VipService(db, repo));
            var console = new VipService.Actor(new UUID(0, 0), "CONSOLE");
            UUID alex = UUID.randomUUID();
            UUID sam = UUID.randomUUID();
            var created =
                    service.createFor(
                            UUID.randomUUID(),
                            console,
                            List.of(alex, sam, alex),
                            "vip",
                            60000,
                            2,
                            settings);
            assertEquals(4, created.size());
            assertEquals(4, created.stream().map(Voucher::id).distinct().count());
            assertEquals(2, db.read(c -> records.pending(c, alex, 36)).size());
            var gift = db.read(c -> records.pending(c, sam, 36)).get(0);
            assertEquals(sam, gift.owner());
            assertEquals(VoucherOrigin.Kind.CONSOLE, db.read(c -> records.origin(c, gift)).kind());
            assertEquals(
                    "error.voucher-owner",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.redeem(
                                                    UUID.randomUUID(),
                                                    new VipService.Actor(alex, "Alex"),
                                                    gift,
                                                    settings))
                            .key());
            service.redeem(UUID.randomUUID(), new VipService.Actor(sam, "Sam"), gift, settings);
            assertEquals(60000, db.read(c -> repo.all(c, sam)).get(0).historicalMs());
        }
    }

    @Test
    void freezeOriginLabelsSurviveDatabaseReloadWithoutChangingSignedOwner() throws Exception {
        var snapshot =
                new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true).load();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var records = new VoucherRepository();
            var vip = new VipService(db, repo);
            var service = new VoucherService(db, repo, records, vip);
            var alex = new VipService.Actor(UUID.randomUUID(), "Alex");
            vip.grant(UUID.randomUUID(), alex, alex.uuid(), "vip", 120000, snapshot.settings());
            var frozen =
                    service.freeze(
                            UUID.randomUUID(), alex, alex.uuid(), "vip", true, snapshot.settings());
            var origin = db.read(c -> records.origin(c, frozen.voucher()));
            assertEquals("Alex", origin.label(snapshot.languages(), "en_US"));
            assertNull(frozen.voucher().owner());
            assertEquals(
                    "Admin",
                    new VoucherOrigin(VoucherOrigin.Kind.ADMIN, "SomeAdmin")
                            .label(snapshot.languages(), "en_US"));
            assertEquals(
                    "Console",
                    new VoucherOrigin(VoucherOrigin.Kind.CONSOLE, "CONSOLE")
                            .label(snapshot.languages(), "en_US"));
            vip.grant(UUID.randomUUID(), alex, alex.uuid(), "vip", 120000, snapshot.settings());
            var console = new VipService.Actor(new UUID(0, 0), "CONSOLE");
            var byConsole =
                    service.freeze(
                            UUID.randomUUID(),
                            console,
                            alex.uuid(),
                            "vip",
                            true,
                            snapshot.settings());
            assertTrue(
                    db.read(c -> records.pending(c, alex.uuid(), 36))
                            .contains(byConsole.voucher()));
            assertEquals(
                    VoucherOrigin.Kind.CONSOLE,
                    db.read(c -> records.origin(c, byConsole.voucher())).kind());
        }
    }

    @Test
    void bulkCreationRollsBackEveryRecipientOnMidBatchFailure() throws Exception {
        var settings =
                new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true)
                        .load()
                        .settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var service =
                    new VoucherService(db, repo, new VoucherRepository(), new VipService(db, repo));
            UUID first = UUID.randomUUID();
            UUID blocked = UUID.randomUUID();
            db.transaction(
                    c -> {
                        Sql.update(
                                c,
                                "CREATE TRIGGER fail_delivery BEFORE INSERT ON deliveries WHEN"
                                        + " NEW.recipient='"
                                        + blocked
                                        + "' BEGIN SELECT RAISE(ABORT,'injected'); END");
                        return null;
                    });
            assertThrows(
                    java.sql.SQLException.class,
                    () ->
                            service.createFor(
                                    UUID.randomUUID(),
                                    new VipService.Actor(UUID.randomUUID(), "Admin"),
                                    List.of(first, blocked),
                                    "vip",
                                    60000,
                                    2,
                                    settings));
            assertEquals(
                    0,
                    db.read(c -> Sql.query(c, "SELECT id FROM vouchers", r -> r.getString(1)))
                            .size());
            assertEquals(
                    0,
                    db.read(
                                    c ->
                                            Sql.query(
                                                    c,
                                                    "SELECT voucher_id FROM voucher_origins",
                                                    r -> r.getString(1)))
                            .size());
        }
    }

    @Test
    void discordEventsHaveDistinctTemplatesAndSafeBoundedPayloads() throws Exception {
        var snapshot = new ConfigLoader(directory, ignored -> {}, m -> true).load();
        var titles = new HashSet<String>();
        for (String event : snapshot.settings().discord().events()) {
            UUID id = UUID.randomUUID();
            var audit =
                    new AuditEntry(
                            id,
                            id,
                            event,
                            id,
                            "**Admin**",
                            id,
                            "Alex",
                            "vip",
                            60000,
                            0,
                            0,
                            id,
                            System.currentTimeMillis());
            var body = DiscordEmbeds.body(audit, snapshot);
            assertEquals(Map.of("parse", List.of()), body.get("allowed_mentions"));
            Object embeds = body.get("embeds");
            assertInstanceOf(List.class, embeds);
            var embed = (Map<?, ?>) ((List<?>) embeds).get(0);
            assertTrue(titles.add((String) embed.get("title")), event);
            assertFalse(body.toString().contains("1970-01-01"));
            assertFalse(body.toString().contains("<player>"));
            assertTrue(body.toString().contains("\\*\\*Admin\\*\\*"));
        }
        assertEquals(9, titles.size());
    }

    @Test
    void customDiscordFieldsCanBeEmptyAndSchemaTwoUpgradesPreserveVouchers() throws Exception {
        var loader = new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true);
        loader.load();
        Path config = directory.resolve("config/discord.yml");
        var yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(config.toFile());
        yaml.set("templates.VIP_GRANTED.fields", new LinkedHashMap<>());
        yaml.save(config.toFile());
        var snapshot = loader.load();
        assertTrue(snapshot.settings().discord().templates().get("VIP_GRANTED").fields().isEmpty());
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var records = new VoucherRepository();
            var service = new VoucherService(db, repo, records, new VipService(db, repo));
            var voucher =
                    service.create(
                                    UUID.randomUUID(),
                                    new VipService.Actor(UUID.randomUUID(), "Admin"),
                                    "vip",
                                    60000,
                                    1,
                                    snapshot.settings())
                            .get(0);
            db.transaction(
                    c -> {
                        Sql.update(c, "DROP TABLE voucher_origins");
                        Sql.update(c, "UPDATE database_schema_version SET version=2");
                        return null;
                    });
            db.migrate();
            db.migrate();
            assertEquals(voucher, db.read(c -> records.find(c, voucher.id()).orElseThrow()));
            assertEquals(VoucherOrigin.Kind.ADMIN, db.read(c -> records.origin(c, voucher)).kind());
        }
    }
}
