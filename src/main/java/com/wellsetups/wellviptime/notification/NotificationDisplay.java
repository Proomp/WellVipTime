package com.wellsetups.wellviptime.notification;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.Messages;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;
import java.util.function.*;

public final class NotificationDisplay {

    private record Visible(BossBar bar, long until) {}

    private final Supplier<Snapshot> configuration;

    private final Messages messages;

    private final Consumer<Player> menu;

    private final Map<UUID, Visible> bars = new java.util.concurrent.ConcurrentHashMap<>();

    private final com.wellsetups.wellviptime.integration.DebugLog debug;

    private final PlayerPreferences preferences;

    private final Map<UUID, Long> titles = new java.util.concurrent.ConcurrentHashMap<>();

    public NotificationDisplay(
            Supplier<Snapshot> configuration,
            Messages messages,
            Consumer<Player> menu,
            com.wellsetups.wellviptime.integration.DebugLog debug,
            PlayerPreferences preferences) {
        this.preferences = preferences;
        this.debug = debug;
        this.configuration = configuration;
        this.messages = messages;
        this.menu = menu;
    }

    public void show(Player player, NotificationService.Notice notice) {
        debug.write(
                "notifications",
                "Displaying notification for "
                        + player.getUniqueId()
                        + " channels="
                        + notice.channels());
        var settings = configuration.get().settings();
        var balance = notice.vip();
        for (var channel : notice.channels()) {
            if (!preferences.enabled(player.getUniqueId(), channel)) {
                continue;
            }
            switch (channel) {
                case CHAT ->
                        messages.sendComponent(
                                player,
                                messages.vip(
                                        player,
                                        notice.expired() ? "vip.expired" : "notification.chat",
                                        balance,
                                        Map.of()));
                case ACTIONBAR ->
                        messages.audience(player)
                                .sendActionBar(
                                        messages.vip(
                                                player,
                                                "notification.actionbar",
                                                balance,
                                                Map.of()));
                case TITLE -> {
                    titles.put(
                            player.getUniqueId(),
                            System.currentTimeMillis()
                                    + settings.notifications().visibleSeconds() * 1000L);
                    messages.audience(player)
                            .showTitle(
                                    Title.title(
                                            messages.vip(
                                                    player,
                                                    "notification.title",
                                                    balance,
                                                    Map.of()),
                                            messages.vip(
                                                    player,
                                                    "notification.subtitle",
                                                    balance,
                                                    Map.of()),
                                            Title.Times.times(
                                                    Duration.ZERO,
                                                    Duration.ofSeconds(
                                                            settings.notifications()
                                                                    .visibleSeconds()),
                                                    Duration.ZERO)));
                }
                case BOSSBAR -> {
                    hide(player);
                    var bar =
                            BossBar.bossBar(
                                    messages.vip(player, "notification.bossbar", balance, Map.of()),
                                    (float) balance.percentage(System.currentTimeMillis()),
                                    settings.display().color(),
                                    settings.display().overlay());
                    bars.put(
                            player.getUniqueId(),
                            new Visible(
                                    bar,
                                    System.currentTimeMillis()
                                            + settings.notifications().visibleSeconds() * 1000L));
                    messages.audience(player).showBossBar(bar);
                }
                case GUI -> menu.accept(player);
                case SOUND ->
                        messages.audience(player)
                                .playSound(
                                        Sound.sound(
                                                Key.key(settings.notifications().sound()),
                                                Sound.Source.MASTER,
                                                settings.notifications().volume(),
                                                settings.notifications().pitch()));
            }
        }
    }

    public void loginSummary(
            Player player, List<com.wellsetups.wellviptime.vip.VipBalance> balances) {
        if (!configuration.get().settings().notifications().loginSummary()) {
            return;
        }
        var active = balances.stream().filter(b -> b.active(System.currentTimeMillis())).toList();
        if (active.isEmpty()) {
            return;
        }
        messages.send(player, "notification.login-header", Map.of("player", player.getName()));
        active.forEach(
                balance ->
                        messages.sendComponent(
                                player,
                                messages.vip(
                                                player,
                                                "notification.login-row",
                                                balance,
                                                Map.of("player", player.getName()))
                                        .hoverEvent(
                                                messages.vip(
                                                        player, "vip.hover", balance, Map.of()))
                                        .clickEvent(messages.renewalClick())));
        messages.send(player, "notification.login-footer");
    }

    public void preferencesChanged(Player player) {
        if (!preferences.enabled(
                player.getUniqueId(),
                com.wellsetups.wellviptime.configuration.Settings.Channel.BOSSBAR)) {
            hide(player);
        }
        if (!preferences.enabled(
                        player.getUniqueId(),
                        com.wellsetups.wellviptime.configuration.Settings.Channel.TITLE)
                && titles.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) {
            messages.audience(player).clearTitle();
            titles.remove(player.getUniqueId());
        }
    }

    public void tick(Collection<? extends Player> online) {
        long now = System.currentTimeMillis();
        for (Player player : online) {
            UUID uuid = player.getUniqueId();
            Long titleUntil = titles.get(uuid);
            if (titleUntil != null && titleUntil <= now) {
                titles.remove(uuid, titleUntil);
            }
            Visible visible = bars.get(uuid);
            if (visible != null && visible.until() <= now) {
                hide(player);
            }
        }
    }

    public void hide(Player player) {
        Visible visible = bars.remove(player.getUniqueId());
        if (visible != null) {
            messages.audience(player).hideBossBar(visible.bar());
        }
    }

    public void forget(Player player) {
        titles.remove(player.getUniqueId());
        hide(player);
    }
}
