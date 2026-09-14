package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class MenuLanguageTest {

    @TempDir Path directory;

    @Test
    void allNewMenuTemplatesRenderAndOlderLanguagesHaveFallbacks() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        var snapshot = loader.load();
        Map<String, String> values = new HashMap<>();
        for (String key :
                List.of(
                        "player",
                        "rank",
                        "score",
                        "status",
                        "remaining",
                        "expiry",
                        "percentage",
                        "count",
                        "page",
                        "ranking",
                        "state")) {
            values.put(key, "Example");
        }
        for (String key :
                List.of(
                        "menu.personal.inventory-title",
                        "menu.personal.profile.name",
                        "menu.personal.profile.lore",
                        "menu.personal.bossbar.lore",
                        "menu.personal.actionbar.lore",
                        "menu.personal.title.lore",
                        "menu.personal.vip.lore",
                        "menu.list.title",
                        "menu.list.entry.name",
                        "menu.list.entry.lore",
                        "menu.leaderboard.title",
                        "menu.leaderboard.entry.lore")) {
            String text =
                    PlainTextComponentSerializer.plainText()
                            .serialize(
                                    snapshot.languages()
                                            .render("en_US", key, values, "<gold>VIP</gold>"));
            assertFalse(text.contains("<"), key + " has an unresolved tag: " + text);
            assertFalse(text.equals(key));
        }
        Files.writeString(
                directory.resolve("languages/en_US.yml"), "vip:\n  none: 'My custom text'\n");
        var older = loader.load();
        assertEquals("My custom text", older.languages().raw("en_US", "vip.none"));
        assertTrue(
                older.languages().raw("en_US", "menu.personal.bossbar.name").contains("Bossbar"));
        assertFalse(Files.readString(directory.resolve("languages/en_US.yml")).contains("Bossbar"));
    }

    @Test
    void inventoryPageSizeIsValidatedBeforeMenusOpen() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, m -> true);
        loader.load();
        Path file = directory.resolve("menus.yml");
        Files.writeString(file, Files.readString(file).replace("page-size: 28", "page-size: 29"));
        assertTrue(
                assertThrows(ConfigError.class, loader::load)
                        .getMessage()
                        .contains("browsing.page-size"));
    }
}
