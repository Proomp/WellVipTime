package com.wellsetups.wellviptime.ui;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.integration.ServerTasks;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.util.*;
import java.util.function.*;

/** Profile I/O is bounded; only the viewer's entity task applies inventory changes. */
public final class HeadSkins implements AutoCloseable {

    private record Cached(PlayerProfile profile, long until) {}

    private record Request(Player viewer, UUID uuid, Consumer<PlayerProfile> consumer) {}

    private final JavaPlugin plugin;

    private final ServerTasks main;

    private final Supplier<Snapshot> settings;

    private final Map<UUID, Cached> cache = new LinkedHashMap<>();

    private final Queue<Request> queue = new ArrayDeque<>();

    private int active;

    private boolean closed;

    public HeadSkins(JavaPlugin plugin, ServerTasks main, Supplier<Snapshot> settings) {
        this.plugin = plugin;
        this.main = main;
        this.settings = settings;
    }

    public void request(Player viewer, UUID uuid, Consumer<PlayerProfile> consumer) {
        Cached cached;
        synchronized (this) {
            if (closed || !settings.get().settings().menus().skins()) {
                return;
            }
            cached = cache.get(uuid);
            if (cached == null || cached.until() <= System.currentTimeMillis()) {
                if (queue.size() >= 256) {
                    return;
                }
                queue.add(new Request(viewer, uuid, consumer));
                cached = null;
            }
        }
        if (cached != null) {
            consumer.accept(cached.profile());
        } else {
            pump();
        }
    }

    private void pump() {
        Request request;
        synchronized (this) {
            if (closed || active >= 4 || queue.isEmpty()) {
                return;
            }
            request = queue.remove();
            active++;
        }
        try {
            plugin.getServer()
                    .createPlayerProfile(request.uuid())
                    .update()
                    .whenComplete((profile, error) -> complete(request, profile, error));
        } catch (RuntimeException failure) {
            complete(request, null, failure);
        }
        pump();
    }

    private void complete(Request request, PlayerProfile profile, Throwable error) {
        boolean available = error == null && profile != null && !profile.getTextures().isEmpty();
        synchronized (this) {
            active--;
            if (closed) {
                return;
            }
            if (available) {
                if (cache.size() >= 512) {
                    cache.remove(cache.keySet().iterator().next());
                }
                cache.put(
                        request.uuid(),
                        new Cached(
                                profile,
                                System.currentTimeMillis()
                                        + settings.get().settings().menus().skinCacheMinutes()
                                                * 60000L));
            }
        }
        try {
            if (available) {
                main.execute(
                        request.viewer(),
                        () -> {
                            if (request.viewer().isOnline()) {
                                request.consumer().accept(profile);
                            }
                        });
            }
        } finally {
            pump();
        }
    }

    public synchronized void forget(Player viewer) {
        queue.removeIf(request -> request.viewer() == viewer);
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
        cache.clear();
    }
}
