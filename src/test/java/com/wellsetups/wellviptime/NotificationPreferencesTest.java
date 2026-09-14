package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.integration.DebugLog;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.notification.*;
import com.wellsetups.wellviptime.vip.*;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

class NotificationPreferencesTest {

    @TempDir Path directory;

    private final UUID uuid = UUID.randomUUID();

    private final List<String> chat = new ArrayList<>();

    private Player player() {
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class, net.kyori.adventure.audience.Audience.class},
                        (self, method, args) ->
                                switch (method.getName()) {
                                    case "getUniqueId" -> uuid;
                                    case "getName" -> "TestPlayer";
                                    case "getLocale" -> "en_US";
                                    case "sendMessage" -> {
                                        chat.add(
                                                PlainTextComponentSerializer.plainText()
                                                        .serialize((Component) args[0]));
                                        yield null;
                                    }
                                    default ->
                                            throw new AssertionError(
                                                    "Disabled visual channel was invoked: "
                                                            + method);
                                });
    }

    @Test
    void disabledVisualChannelsStillAllowChat() throws Exception {
        var snapshot = new ConfigLoader(directory, ignored -> {}, m -> true).load();
        var preferences = new PlayerPreferences(null, snapshot::settings);
        preferences.initialize(
                uuid,
                Map.of(
                        Settings.Channel.TITLE,
                        false,
                        Settings.Channel.ACTIONBAR,
                        false,
                        Settings.Channel.BOSSBAR,
                        false));
        var display =
                new NotificationDisplay(
                        () -> snapshot,
                        new Messages(() -> snapshot),
                        p -> fail("Unexpected menu"),
                        new DebugLog(() -> snapshot, Logger.getAnonymousLogger()),
                        preferences);
        var vip =
                VipRules.grant(
                        uuid,
                        "vip",
                        null,
                        3600000,
                        System.currentTimeMillis(),
                        uuid,
                        snapshot.settings());
        display.show(
                player(),
                new NotificationService.Notice(
                        vip,
                        Set.of(
                                Settings.Channel.CHAT,
                                Settings.Channel.TITLE,
                                Settings.Channel.BOSSBAR,
                                Settings.Channel.ACTIONBAR),
                        false));
        assertEquals(1, chat.size());
        assertTrue(chat.get(0).contains("expires"));
    }

    @Test
    void loginSummaryShowsEveryActiveRankAndRespectsItsOwnSwitch() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        var snapshot = loader.load();
        var display =
                new NotificationDisplay(
                        () -> snapshot,
                        new Messages(() -> snapshot),
                        p -> {},
                        new DebugLog(() -> snapshot, Logger.getAnonymousLogger()),
                        new PlayerPreferences(null, snapshot::settings));
        long now = System.currentTimeMillis();
        var vip = VipRules.grant(uuid, "vip", null, 3600000, now, uuid, snapshot.settings());
        var plus = VipRules.grant(uuid, "vip-plus", null, 7200000, now, uuid, snapshot.settings());
        display.loginSummary(player(), List.of(vip, plus));
        assertEquals(4, chat.size());
        assertTrue(chat.get(0).contains("TestPlayer"));
        assertTrue(chat.get(2).contains("VIP+"));
        chat.clear();
        display.loginSummary(player(), List.of());
        assertTrue(chat.isEmpty());
        Path file = directory.resolve("notifications.yml");
        Files.writeString(
                file,
                Files.readString(file).replace("login-summary: true", "login-summary: false"));
        var disabled = loader.load();
        new NotificationDisplay(
                        () -> disabled,
                        new Messages(() -> disabled),
                        p -> {},
                        new DebugLog(() -> disabled, Logger.getAnonymousLogger()),
                        new PlayerPreferences(null, disabled::settings))
                .loginSummary(player(), List.of(vip));
        assertTrue(chat.isEmpty());
    }
}
