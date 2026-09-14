package com.wellsetups.wellviptime.storage;

import com.wellsetups.wellviptime.configuration.Settings;

import java.sql.SQLException;
import java.util.*;

public final class ListingService {

    private record Key(
            Settings.Ranking ranking,
            Settings.ListSort sort,
            Set<String> hidden,
            String type,
            int offset,
            int size,
            boolean leaderboard) {}

    private record Cached(long until, List<VipRepository.Ranked> rows) {}

    private final Database database;

    private final VipRepository repository;

    private final Map<Key, Cached> cache = new LinkedHashMap<>();

    private long revision;

    public ListingService(Database database, VipRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    public List<VipRepository.Ranked> page(
            Settings settings, String type, int offset, int size, boolean leaderboard)
            throws SQLException {
        if (size <= 0) {
            return List.of();
        }
        var key =
                new Key(
                        leaderboard ? settings.core().ranking() : Settings.Ranking.EXPIRATION,
                        settings.core().listSort(),
                        settings.core().hiddenPlayers(),
                        type,
                        offset,
                        size,
                        leaderboard);
        long before;
        synchronized (cache) {
            Cached existing = cache.get(key);
            before = revision;
            if (existing != null && existing.until() > System.nanoTime()) {
                return existing.rows();
            }
        }
        var rows =
                database.read(
                        c ->
                                repository.page(
                                        c,
                                        key.ranking(),
                                        type,
                                        offset,
                                        size,
                                        database.now(c),
                                        leaderboard,
                                        settings.core().listSort(),
                                        settings.core().hiddenPlayers()));
        synchronized (cache) {
            if (revision == before && settings.core().cacheSeconds() > 0) {
                if (cache.size() >= 128) {
                    cache.remove(cache.keySet().iterator().next());
                }
                cache.put(
                        key,
                        new Cached(
                                System.nanoTime() + settings.core().cacheSeconds() * 1000000000L,
                                rows));
            }
        }
        return rows;
    }

    public void invalidate() {
        synchronized (cache) {
            revision++;
            cache.clear();
        }
    }
}
