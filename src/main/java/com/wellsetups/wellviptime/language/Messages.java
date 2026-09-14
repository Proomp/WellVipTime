package com.wellsetups.wellviptime.language;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.vip.VipBalance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public final class Messages {

    private final Supplier<Snapshot> configuration;

    private java.util.function.Function<CommandSender, net.kyori.adventure.audience.Audience>
            audiences =
                    sender ->
                            sender instanceof net.kyori.adventure.audience.Audience audience
                                    ? audience
                                    : net.kyori.adventure.audience.Audience.empty();

    public Messages(Supplier<Snapshot> configuration) {
        this.configuration = configuration;
    }

    public void audiences(
            java.util.function.Function<CommandSender, net.kyori.adventure.audience.Audience>
                    value) {
        audiences = value;
    }

    public net.kyori.adventure.audience.Audience audience(CommandSender sender) {
        return audiences.apply(sender);
    }

    public String locale(CommandSender sender) {
        return configuration
                .get()
                .languages()
                .locale(
                        sender instanceof Player p
                                ? com.wellsetups.wellviptime.integration.Platform.locale(p)
                                : null);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> values) {
        sendComponent(sender, component(sender, key, values));
    }

    public void sendComponent(CommandSender sender, Component value) {
        audience(sender)
                .sendMessage(
                        configuration
                                .get()
                                .languages()
                                .render(locale(sender), "prefix", Map.of())
                                .append(value));
    }

    public Component component(CommandSender sender, String key, Map<String, String> values) {
        return configuration.get().languages().render(locale(sender), key, values);
    }

    public Component vip(
            CommandSender sender, String key, VipBalance balance, Map<String, String> extra) {
        var snapshot = configuration.get();
        Map<String, String> values = values(locale(sender), balance, System.currentTimeMillis());
        values.putAll(extra);
        var type = snapshot.settings().types().get(balance.type());
        return snapshot.languages()
                .render(
                        locale(sender),
                        key,
                        values,
                        type == null ? balance.type() : type.displayName());
    }

    public Map<String, String> values(String locale, VipBalance balance, long now) {
        var snapshot = configuration.get();
        var display = snapshot.settings().display();
        var time = new TimeFormat(snapshot.languages(), display);
        long remaining = balance.remaining(now) / 1000;
        Map<String, String> values = new HashMap<>(time.parts(remaining));
        values.put("player", balance.player().toString());
        values.put("uuid", balance.player().toString());
        values.put("vip", balance.type());
        values.put("remaining", time.duration(locale, remaining));
        values.put("remaining_seconds", Long.toString(remaining));
        values.put("duration", time.duration(locale, balance.originalMs() / 1000));
        values.put(
                "expiry",
                display.dates()
                        .withLocale(Locale.forLanguageTag(locale.replace('_', '-')))
                        .format(Instant.ofEpochMilli(balance.expiresAt())));
        double progress =
                display.progressSource()
                                == com.wellsetups.wellviptime.configuration.Settings.ProgressSource
                                        .ELAPSED
                        ? 1 - balance.percentage(now)
                        : balance.percentage(now);
        values.put(
                "percentage",
                String.format(Locale.ROOT, "%." + display.precision() + "f", progress * 100));
        int filled = (int) Math.floor(progress * display.barLength());
        values.put("filled", display.filled().repeat(filled));
        values.put("empty", display.empty().repeat(display.barLength() - filled));
        values.put("progressbar", values.get("filled") + values.get("empty"));
        return values;
    }

    public String duration(CommandSender sender, long millis) {
        var c = configuration.get();
        return new TimeFormat(c.languages(), c.settings().display())
                .duration(locale(sender), millis / 1000);
    }

    public ClickEvent renewalClick() {
        var display = configuration.get().settings().display();
        return switch (display.renewalAction()) {
            case RUN_COMMAND -> ClickEvent.runCommand(display.renewalValue());
            case SUGGEST_COMMAND -> ClickEvent.suggestCommand(display.renewalValue());
            case OPEN_URL -> ClickEvent.openUrl(display.renewalValue());
        };
    }
}
