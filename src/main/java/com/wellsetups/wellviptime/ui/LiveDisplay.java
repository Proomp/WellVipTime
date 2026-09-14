package com.wellsetups.wellviptime.ui;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.vip.VipCache;

import net.kyori.adventure.bossbar.BossBar;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Supplier;

public final class LiveDisplay implements AutoCloseable {

    private final JavaPlugin plugin;

    private final Supplier<Snapshot> configuration;

    private final VipCache cache;

    private final Messages messages;

    private final com.wellsetups.wellviptime.notification.PlayerPreferences preferences;

    private final Map<UUID, BossBar> bars = new java.util.concurrent.ConcurrentHashMap<>();

    private final com.wellsetups.wellviptime.integration.ServerTasks main;

    private final com.wellsetups.wellviptime.integration.ServerTasks.Task task;

    private volatile long nextRefresh;

    public LiveDisplay(
            JavaPlugin plugin,
            Supplier<Snapshot> configuration,
            VipCache cache,
            Messages messages,
            com.wellsetups.wellviptime.notification.PlayerPreferences preferences,
            com.wellsetups.wellviptime.integration.ServerTasks main) {
        this.main = main;
        this.preferences = preferences;
        this.plugin = plugin;
        this.configuration = configuration;
        this.cache = cache;
        this.messages = messages;
        task = main.repeat(this::tick, 20, 20);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now < nextRefresh) {
            return;
        }
        var settings = configuration.get().settings();
        var display = settings.display();
        nextRefresh = now + display.refreshSeconds() * 1000L;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            main.execute(player, () -> refresh(player, settings, now));
        }
    }

    private void refresh(
            Player player, com.wellsetups.wellviptime.configuration.Settings settings, long now) {
        if (!player.isOnline()) {
            return;
        }
        var display = settings.display();
        var primary = cache.primary(player.getUniqueId(), settings, now);
        boolean world =
                display.worlds().isEmpty()
                        || display.worlds().contains(player.getWorld().getName());
        if (!world
                || !display.bossbar()
                || !preferences.enabled(
                        player.getUniqueId(),
                        com.wellsetups.wellviptime.configuration.Settings.Channel.BOSSBAR)
                || primary.isEmpty() && display.hideNoVip()) {
            hide(player);
        } else {
            var title =
                    primary.map(b -> messages.vip(player, "display.bossbar", b, Map.of()))
                            .orElseGet(
                                    () -> messages.component(player, "display.no-vip", Map.of()));
            float progress = primary.map(b -> (float) b.percentage(now)).orElse(0f);
            if (primary.isPresent()
                    && display.progressSource()
                            == com.wellsetups.wellviptime.configuration.Settings.ProgressSource
                                    .ELAPSED) {
                progress = 1 - progress;
            }
            BossBar bar = bars.get(player.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(title, progress, display.color(), display.overlay());
                bars.put(player.getUniqueId(), bar);
                messages.audience(player).showBossBar(bar);
            } else {
                bar.name(title)
                        .progress(progress)
                        .color(display.color())
                        .overlay(display.overlay());
            }
        }
        if (display.actionbar()
                && preferences.enabled(
                        player.getUniqueId(),
                        com.wellsetups.wellviptime.configuration.Settings.Channel.ACTIONBAR)
                && world
                && primary.isPresent()) {
            messages.audience(player)
                    .sendActionBar(
                            messages.vip(player, "display.actionbar", primary.get(), Map.of()));
        }
    }

    public void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            messages.audience(player).hideBossBar(bar);
        }
    }

    public void preferencesChanged(Player player) {
        if (!preferences.enabled(
                player.getUniqueId(),
                com.wellsetups.wellviptime.configuration.Settings.Channel.BOSSBAR)) {
            hide(player);
        }
        if (!preferences.enabled(
                player.getUniqueId(),
                com.wellsetups.wellviptime.configuration.Settings.Channel.ACTIONBAR)) {
            messages.audience(player).sendActionBar(net.kyori.adventure.text.Component.empty());
        }
        nextRefresh = 0;
    }

    @Override
    public void close() {
        task.cancel();
        plugin.getServer()
                .getOnlinePlayers()
                .forEach(player -> main.cleanup(player, () -> hide(player)));
    }
}
