package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.notification.PlayerPreferences;
import com.wellsetups.wellviptime.storage.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class PlayerPreferencesTest {

    @TempDir Path directory;

    @Test
    void preferencesPersistAndIndependentChannelsDoNotOverwriteEachOther() throws Exception {
        var settings = new ConfigLoader(directory, ignored -> {}, m -> true).load().settings();
        var uuid = UUID.randomUUID();
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var preferences = new PlayerPreferences(db, () -> settings);
            assertFalse(preferences.ready(uuid));
            preferences.initialize(uuid, preferences.load(uuid));
            assertTrue(preferences.enabled(uuid, Settings.Channel.BOSSBAR));
            preferences.apply(uuid, preferences.save(uuid, Settings.Channel.BOSSBAR, false));
            preferences.initialize(uuid, Map.of(Settings.Channel.BOSSBAR, true));
            assertFalse(preferences.enabled(uuid, Settings.Channel.BOSSBAR));
            preferences.save(uuid, Settings.Channel.TITLE, false);
            assertFalse(preferences.load(uuid).get(Settings.Channel.BOSSBAR));
        }
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var preferences = new PlayerPreferences(db, () -> settings);
            preferences.initialize(uuid, preferences.load(uuid));
            assertFalse(preferences.enabled(uuid, Settings.Channel.BOSSBAR));
            assertFalse(preferences.enabled(uuid, Settings.Channel.TITLE));
            assertTrue(preferences.enabled(uuid, Settings.Channel.ACTIONBAR));
            assertTrue(preferences.enabled(uuid, Settings.Channel.CHAT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preferences.save(uuid, Settings.Channel.CHAT, false));
        }
    }

    @Test
    void schemaOneUpgradePreservesExistingPlayersAndIsRepeatable() throws Exception {
        try (var db = new Database(StorageTest.sqlite(), directory)) {
            db.migrate();
            var uuid = UUID.randomUUID();
            db.transaction(
                    c -> {
                        db.lockPlayers(c, List.of(uuid));
                        Sql.update(c, "DROP TABLE player_preferences");
                        Sql.update(c, "UPDATE database_schema_version SET version=1");
                        return null;
                    });
            db.migrate();
            db.migrate();
            assertEquals(
                    3,
                    db.read(
                                    c ->
                                            Sql.query(
                                                    c,
                                                    "SELECT version FROM database_schema_version",
                                                    r -> r.getInt(1)))
                            .get(0));
            assertEquals(
                    uuid.toString(),
                    db.read(c -> Sql.query(c, "SELECT uuid FROM players", r -> r.getString(1)))
                            .get(0));
            assertTrue(
                    db.read(
                                    c ->
                                            Sql.query(
                                                    c,
                                                    "SELECT channel FROM player_preferences",
                                                    r -> r.getString(1)))
                            .isEmpty());
        }
    }
}
