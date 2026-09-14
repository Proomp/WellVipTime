package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.command.CommandTree;
import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.language.*;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;
import com.wellsetups.wellviptime.voucher.*;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

class Upgrade20Test {

    @TempDir Path directory;

    private CommandSender sender(Set<String> permissions, List<String> messages) {
        return (CommandSender)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {CommandSender.class, Audience.class},
                        (self, method, args) ->
                                switch (method.getName()) {
                                    case "hasPermission" -> permissions.contains(args[0]);
                                    case "sendMessage" -> {
                                        messages.add(
                                                PlainTextComponentSerializer.plainText()
                                                        .serialize((Component) args[0]));
                                        yield null;
                                    }
                                    default -> throw new AssertionError(method);
                                });
    }

    @Test
    void adminParserAcceptsHeldVoucherUuidAndConfiguredAliases() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        var snapshot = loader.load();
        var definitions = new HashMap<>(snapshot.settings().commands());
        var recover = definitions.get("vipadmin.recover");
        definitions.put(
                "vipadmin.recover",
                new Settings.Command(
                        "restore",
                        List.of("recover"),
                        recover.permission(),
                        recover.description()));
        var commands = new CommandTree(definitions, snapshot::settings, List::of, request -> {});
        var user = sender(Set.of(recover.permission()), new ArrayList<>());
        assertEquals(
                "vipadmin.recover",
                commands.parse(user, "vipadmin", new String[] {"restore"}).id());
        assertNull(commands.parse(user, "vipadmin", new String[] {"recover"}).vip());
        String id = UUID.randomUUID().toString();
        assertEquals(id, commands.parse(user, "vipadmin", new String[] {"recover", id}).vip());
        assertEquals(
                "vipadmin.revoke",
                commands.parse(user, "vipadmin", new String[] {"revoke", id}).id());
        assertThrows(
                DomainFailure.class,
                () -> commands.parse(user, "vipadmin", new String[] {"revoke", id, "extra"}));
        assertEquals(
                List.of("recover", "restore"),
                commands.suggest(user, "vipadmin", new String[] {""}));
        assertTrue(
                commands.suggest(sender(Set.of(), new ArrayList<>()), "vipadmin", new String[] {""})
                        .isEmpty());
    }

    @Test
    void missingDeliveryIsRecoveredAndRevokedCopiesCannotBeUsed() throws Exception {
        var settings =
                new ConfigLoader(directory.resolve("config"), ignored -> {}, m -> true)
                        .load()
                        .settings();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var repo = new VipRepository(db);
            var vouchers = new VoucherRepository();
            var service = new VoucherService(db, repo, vouchers, new VipService(db, repo));
            var actor = new VipService.Actor(UUID.randomUUID(), "Administrator");
            var issued = service.create(UUID.randomUUID(), actor, "vip", 60000, 1, settings).get(0);
            db.transaction(
                    c -> {
                        Sql.update(
                                c,
                                "DELETE FROM deliveries WHERE voucher_id=?",
                                issued.id().toString());
                        return null;
                    });
            service.administrate(UUID.randomUUID(), actor, issued.id(), true, settings);
            assertEquals(List.of(issued), db.read(c -> vouchers.pending(c, actor.uuid(), 10)));
            service.administrate(UUID.randomUUID(), actor, issued.id(), false, settings);
            assertTrue(db.read(c -> vouchers.pending(c, actor.uuid(), 10)).isEmpty());
            assertEquals(
                    "error.voucher-used",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.redeem(
                                                    UUID.randomUUID(), actor, issued, settings))
                            .key());
            assertEquals(
                    "error.voucher-used",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.administrate(
                                                    UUID.randomUUID(),
                                                    actor,
                                                    issued.id(),
                                                    true,
                                                    settings))
                            .key());
            assertEquals(
                    "error.voucher-invalid",
                    assertThrows(
                                    DomainFailure.class,
                                    () ->
                                            service.administrate(
                                                    UUID.randomUUID(),
                                                    actor,
                                                    UUID.randomUUID(),
                                                    false,
                                                    settings))
                            .key());
        }
    }

    @Test
    void dataMigrationPreservesSigningKeyAndExistingNewFolder() throws Exception {
        Path old = Files.createDirectory(directory.resolve("VipManager"));
        byte[] secret = new byte[32];
        new Random(17).nextBytes(secret);
        Files.write(old.resolve("signing-key.bin"), secret);
        Files.writeString(old.resolve("vipmanager.db"), "database sentinel");
        Path target = Files.createDirectory(directory.resolve("WellVipTime"));
        assertTrue(DataMigration.copyLegacy(target));
        assertArrayEquals(secret, Files.readAllBytes(target.resolve("signing-key.bin")));
        assertArrayEquals(secret, Files.readAllBytes(old.resolve("signing-key.bin")));
        Files.writeString(target.resolve("custom.yml"), "preserve");
        assertFalse(DataMigration.copyLegacy(target));
        assertEquals("preserve", Files.readString(target.resolve("custom.yml")));
    }

    @Test
    void allBundledLanguagesHaveAllKeysAndCorrectClientSelection() throws Exception {
        var snapshot = new ConfigLoader(directory, ignored -> {}, m -> true).load();
        var english =
                YamlConfiguration.loadConfiguration(
                        directory.resolve("languages/en_US.yml").toFile());
        var keys =
                english.getKeys(true).stream()
                        .filter(k -> !english.isConfigurationSection(k))
                        .toList();
        assertEquals(26, Languages.BUNDLED_LOCALES.size());
        for (String code : Languages.BUNDLED_LOCALES) {
            var yaml =
                    YamlConfiguration.loadConfiguration(
                            directory.resolve("languages/" + code + ".yml").toFile());
            assertEquals("", yaml.getString("prefix"), code);
            for (String key : keys) {
                assertNotNull(yaml.get(key), code + " :: " + key);
                assertEquals(
                        parameters(english.get(key)),
                        parameters(yaml.get(key)),
                        code + " :: " + key);
            }
            assertEquals(
                    code,
                    snapshot.languages().locale(Locale.forLanguageTag(code.replace('_', '-'))));
            if (!code.equals("en_US")) {
                assertNotEquals(
                        english.getString("error.permission"),
                        yaml.getString("error.permission"),
                        code);
            }
        }
    }

    private Set<String> parameters(Object value) {
        var result = new HashSet<String>();
        var matcher = java.util.regex.Pattern.compile("<([a-z_]+)>").matcher(String.valueOf(value));
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        result.removeAll(Set.of("bold", "newline"));
        return result;
    }

    @Test
    void legacyPrefixesMigrateAndNewPrefixAppliesOnceOnlyToChat() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        loader.load();
        Path language = directory.resolve("languages/en_US.yml");
        String old = "<gradient:#F6D365:#FDA085><bold>VIP</bold></gradient> <#536171>›</#536171> ";
        Files.writeString(
                language,
                Files.readString(language)
                        .replace("\r\n", "\n")
                        .replace("prefix: ''\n", "")
                        .replace(
                                "You do not have permission to do that.",
                                old + "You do not have permission to do that."));
        var migrated = loader.load();
        assertEquals("", migrated.languages().raw("en_US", "prefix"));
        assertFalse(migrated.languages().raw("en_US", "error.permission").contains(old));
        assertTrue(Files.exists(language.resolveSibling("en_US.yml.pre-2.0.bak")));
        Files.writeString(
                language,
                Files.readString(language).replace("prefix: ''", "prefix: '<gold>[WVT] </gold>'"));
        var snapshot = loader.load();
        var chat = new ArrayList<String>();
        var user = sender(Set.of(), chat);
        var messages = new Messages(() -> snapshot);
        messages.send(user, "error.permission");
        assertEquals(List.of("[WVT] You do not have permission to do that."), chat);
        assertFalse(
                PlainTextComponentSerializer.plainText()
                        .serialize(messages.component(user, "gui.title", Map.of()))
                        .contains("[WVT]"));
    }
}
