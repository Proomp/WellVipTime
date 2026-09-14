package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.audit.DiscordAudit;
import com.wellsetups.wellviptime.configuration.ConfigLoader;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.language.TimeFormat;
import com.wellsetups.wellviptime.notification.ThresholdRules;
import com.wellsetups.wellviptime.vip.Cooldowns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;

class PresentationTest {

    @TempDir Path directory;

    @Test
    void durationFormattingSupportsSingularPluralAndZero() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        var snapshot = loader.load();
        var format = new TimeFormat(snapshot.languages(), snapshot.settings().display());
        assertEquals("1d 2h 3m 4s", format.duration("en_US", 86400 + 7200 + 180 + 4));
        assertEquals("0s", format.duration("en_US", -1));
        Path config = directory.resolve("config.yml");
        Files.writeString(
                config,
                Files.readString(config).replace("duration-style: short", "duration-style: long"));
        snapshot = loader.load();
        format = new TimeFormat(snapshot.languages(), snapshot.settings().display());
        assertEquals("1 day 2 hours", format.duration("en_US", 86400 + 7200));
    }

    @Test
    void newLanguagesAndFallbackDoNotRequireCompilation() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        loader.load();
        Files.writeString(
                directory.resolve("languages/custom.yml"), "vip:\n  none: 'Custom none'\n");
        var loaded = loader.load().languages();
        assertEquals("Custom none", loaded.raw("custom", "vip.none"));
        assertTrue(loaded.raw("custom", "error.duration").contains("30d"));
        assertFalse(
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(
                                loaded.render(
                                        "custom",
                                        "error.player",
                                        Map.of("input", "<red>Untrusted</red>")))
                        .equals("Untrusted"));
    }

    @Test
    void notificationCrossingsRespectHistoryAndLoginMode() {
        var hour = new Settings.Threshold("hour", 3600, Set.of(Settings.Channel.CHAT));
        var ten = new Settings.Threshold("ten", 600, Set.of(Settings.Channel.CHAT));
        var all = List.of(hour, ten);
        var crossed = ThresholdRules.crossed(all, 500000, 3600000, Set.of());
        assertEquals(List.of(ten, hour), crossed);
        assertEquals(
                List.of(ten), ThresholdRules.display(crossed, true, Settings.LoginMode.NEAREST));
        assertEquals(List.of(), ThresholdRules.display(crossed, true, Settings.LoginMode.IGNORE));
        assertEquals(crossed, ThresholdRules.display(crossed, true, Settings.LoginMode.MISSED));
        assertEquals(List.of(hour), ThresholdRules.crossed(all, 500000, 3600000, Set.of("ten")));
        assertEquals(List.of(), ThresholdRules.crossed(all, 0, 3600000, Set.of()));
    }

    @Test
    void cooldownAndRetryCalculationsHaveSafeBounds() {
        assertEquals(0, Cooldowns.remaining(1000, 1001));
        assertEquals(500, Cooldowns.remaining(1500, 1000));
        assertEquals(1250, DiscordAudit.retryMillis("1.25"));
        assertEquals(5000, DiscordAudit.retryMillis("NaN"));
        assertEquals(5000, DiscordAudit.retryMillis("nonsense"));
        assertEquals(86400000, DiscordAudit.retryMillis("99999999"));
    }

    @Test
    void daylightSavingDoesNotChangeAbsoluteRemainingTime() {
        Instant before = Instant.parse("2026-10-25T00:30:00Z");
        Instant after = before.plusSeconds(3600);
        var berlin = ZoneId.of("Europe/Berlin");
        assertEquals(before.atZone(berlin).getHour(), after.atZone(berlin).getHour());
        assertEquals(3600, Duration.between(before, after).toSeconds());
    }
}
