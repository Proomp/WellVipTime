package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

class StorageTest {

    @TempDir Path directory;

    static Settings.Database sqlite() {
        return new Settings.Database(
                "SQLITE", "localhost", 3306, "test", "test", "", "test.db", 1, 5, false);
    }

    @Test
    void migrationIsRepeatableAndRollbackIsAtomic() throws Exception {
        try (var db = new Database(sqlite(), directory)) {
            db.migrate();
            db.migrate();
            UUID uuid = UUID.randomUUID();
            assertThrows(
                    SQLException.class,
                    () ->
                            db.transaction(
                                    c -> {
                                        db.lockPlayers(c, List.of(uuid));
                                        throw new SQLException("injected failure");
                                    }));
            assertEquals(
                    0,
                    db.read(
                                    c ->
                                            Sql.query(
                                                            c,
                                                            "SELECT uuid FROM players",
                                                            r -> r.getString(1))
                                                    .size())
                            .intValue());
            db.transaction(
                    c -> {
                        db.lockPlayers(c, List.of(uuid));
                        return null;
                    });
            assertEquals(
                    1,
                    db.read(
                                    c ->
                                            Sql.query(
                                                            c,
                                                            "SELECT uuid FROM players",
                                                            r -> r.getString(1))
                                                    .size())
                            .intValue());
        }
    }
}
