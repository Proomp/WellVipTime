package com.wellsetups.wellviptime.notification;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class PlayerPreferences {

    private final Database database;

    private final Supplier<Settings> settings;

    private final Map<UUID, Map<Settings.Channel, Boolean>> cache = new ConcurrentHashMap<>();

    public PlayerPreferences(Database database, Supplier<Settings> settings) {
        this.database = database;
        this.settings = settings;
    }

    public Map<Settings.Channel, Boolean> load(UUID uuid) throws SQLException {
        return database.read(
                c -> {
                    Map<Settings.Channel, Boolean> result = new EnumMap<>(Settings.Channel.class);
                    for (var entry :
                            Sql.query(
                                    c,
                                    "SELECT channel,enabled FROM player_preferences WHERE uuid=?",
                                    r -> Map.entry(r.getString(1), r.getInt(2) != 0),
                                    uuid.toString())) {
                        try {
                            result.put(Settings.Channel.valueOf(entry.getKey()), entry.getValue());
                        } catch (IllegalArgumentException ignored) {
                            /* A newer version may add a channel. */
                        }
                    }
                    return Map.copyOf(result);
                });
    }

    public void apply(UUID uuid, Map<Settings.Channel, Boolean> value) {
        cache.put(uuid, Map.copyOf(value));
    }

    public void initialize(UUID uuid, Map<Settings.Channel, Boolean> value) {
        cache.putIfAbsent(uuid, Map.copyOf(value));
    }

    public boolean ready(UUID uuid) {
        return cache.containsKey(uuid);
    }

    public boolean enabled(UUID uuid, Settings.Channel channel) {
        var values = cache.get(uuid);
        if (values == null) {
            return false;
        }
        return values.getOrDefault(channel, defaultValue(settings.get().menus(), channel));
    }

    public static boolean defaultValue(Settings.Menus menus, Settings.Channel channel) {
        return switch (channel) {
            case BOSSBAR -> menus.defaultBossbar();
            case ACTIONBAR -> menus.defaultActionbar();
            case TITLE -> menus.defaultTitle();
            default -> true;
        };
    }

    public Map<Settings.Channel, Boolean> save(UUID uuid, Settings.Channel channel, boolean enabled)
            throws SQLException {
        if (!Set.of(Settings.Channel.BOSSBAR, Settings.Channel.ACTIONBAR, Settings.Channel.TITLE)
                .contains(channel)) {
            throw new IllegalArgumentException("Unsupported preference");
        }
        database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(uuid));
                    Sql.update(
                            c,
                            (database.sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                                    + " INTO player_preferences(uuid,channel,enabled)"
                                    + " VALUES(?,?,?)",
                            uuid.toString(),
                            channel.name(),
                            enabled ? 1 : 0);
                    Sql.update(
                            c,
                            "UPDATE player_preferences SET enabled=? WHERE uuid=? AND channel=?",
                            enabled ? 1 : 0,
                            uuid.toString(),
                            channel.name());
                    return null;
                });
        return load(uuid);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
    }
}
