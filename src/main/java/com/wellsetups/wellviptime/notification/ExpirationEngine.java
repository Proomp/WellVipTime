package com.wellsetups.wellviptime.notification;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.*;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.logging.Level;

public final class ExpirationEngine implements AutoCloseable {

    private final JavaPlugin plugin;

    private final Supplier<Snapshot> configuration;

    private final Database database;

    private final VipRepository repository;

    private final VipService vip;

    private final LuckPermsSync permissions;

    private final AsyncWork work;

    private final ServerTasks main;

    private final VipCache cache;

    private final NotificationService notifications;

    private final NotificationDisplay display;

    private final PlayerPreferences preferences;

    private final Consumer<VipService.Change> changed;

    private final Consumer<Player> menu;

    private final Consumer<Player> delivery;

    private final AtomicBoolean expiring = new AtomicBoolean();

    private final AtomicBoolean syncing = new AtomicBoolean();

    private final Set<UUID> logins = ConcurrentHashMap.newKeySet();

    private final Set<Player> refreshing = ConcurrentHashMap.newKeySet();

    private final ServerTasks.Task task;

    private int cursor;

    private long nextCheck;

    private long lastError;

    public ExpirationEngine(
            JavaPlugin plugin,
            Supplier<Snapshot> configuration,
            Database database,
            VipRepository repository,
            VipService vip,
            LuckPermsSync permissions,
            AsyncWork work,
            ServerTasks main,
            VipCache cache,
            NotificationDisplay display,
            Consumer<VipService.Change> changed,
            Consumer<Player> menu,
            Consumer<Player> delivery,
            PlayerPreferences preferences) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.database = database;
        this.repository = repository;
        this.vip = vip;
        this.permissions = permissions;
        this.work = work;
        this.main = main;
        this.cache = cache;
        this.display = display;
        this.preferences = preferences;
        this.changed = changed;
        this.menu = menu;
        this.delivery = delivery;
        notifications = new NotificationService(database, repository);
        task = main.repeat(this::tick, 20, 20);
    }

    public void login(UUID player) {
        logins.add(player);
    }

    public void quit(Player player) {
        UUID id = player.getUniqueId();
        logins.remove(id);
        refreshing.remove(player);
        cache.remove(id);
        display.forget(player);
    }

    private void tick() {
        var online = List.copyOf(plugin.getServer().getOnlinePlayers());
        online.forEach(player -> main.execute(player, () -> display.tick(List.of(player))));
        long now = System.currentTimeMillis();
        if (now < nextCheck) {
            return;
        }
        var settings = configuration.get().settings();
        nextCheck = now + settings.core().checkSeconds() * 1000L;
        if (expiring.compareAndSet(false, true)) {
            work.submit(
                            () -> {
                                List<VipService.Change> changes = new ArrayList<>();
                                for (var candidate :
                                        database.read(
                                                c ->
                                                        repository.due(
                                                                c,
                                                                database.now(c),
                                                                settings.core().batchSize()))) {
                                    vip.expire(candidate, settings).ifPresent(changes::add);
                                }
                                return changes;
                            })
                    .whenComplete(
                            (changes, error) -> {
                                expiring.set(false);
                                if (error != null) {
                                    report(error);
                                } else {
                                    changes.forEach(changed);
                                }
                            });
        }
        if (syncing.compareAndSet(false, true)) {
            work.submit(
                            () -> {
                                permissions.process(settings);
                                return null;
                            })
                    .whenComplete(
                            (ignored, error) -> {
                                syncing.set(false);
                                if (error != null) {
                                    report(error);
                                }
                            });
        }
        for (int i = 0; i < Math.min(settings.core().batchSize(), online.size()); i++) {
            Player player = online.get(Math.floorMod(cursor, online.size()));
            cursor++;
            main.execute(player, () -> refresh(player, settings));
        }
    }

    private void refresh(Player player, Settings settings) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || !refreshing.add(player)) {
            return;
        }
        boolean login = logins.remove(uuid);
        boolean hidden = player.hasPermission(settings.core().hiddenPermission());
        work.submit(
                        () ->
                                Map.entry(
                                        notifications.refresh(uuid, login, hidden, settings),
                                        preferences.ready(uuid)
                                                ? Map.<Settings.Channel, Boolean>of()
                                                : preferences.load(uuid)))
                .whenComplete(
                        (loaded, error) -> {
                            if (error != null) {
                                report(error);
                            }
                            main.execute(
                                    player, () -> refreshed(player, uuid, login, loaded, error));
                        });
    }

    private void refreshed(
            Player player,
            UUID uuid,
            boolean login,
            Map.Entry<NotificationService.Refresh, Map<Settings.Channel, Boolean>> loaded,
            Throwable error) {
        refreshing.remove(player);
        if (!player.isOnline()) {
            return;
        }
        if (error != null) {
            if (login) {
                logins.add(uuid);
            }
            return;
        }
        var result = loaded.getKey();
        preferences.initialize(uuid, loaded.getValue());
        cache.put(uuid, result.balances());
        if (login) {
            display.loginSummary(player, result.balances());
        }
        result.notices().forEach(notice -> display.show(player, notice));
        if (result.loginMenu()) {
            menu.accept(player);
        }
        delivery.accept(player);
    }

    private synchronized void report(Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastError >= 30000) {
            lastError = now;
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "Background VIP work failed; durable state will retry",
                            error);
        }
    }

    @Override
    public void close() {
        task.cancel();
        plugin.getServer()
                .getOnlinePlayers()
                .forEach(player -> main.cleanup(player, () -> display.hide(player)));
    }
}
