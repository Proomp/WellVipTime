package com.wellsetups.wellviptime.integration;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.vip.VipCache;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

public final class VipPlaceholders extends PlaceholderExpansion {

    private final Supplier<Snapshot> configuration;

    private final VipCache cache;

    private final Messages messages;

    private final String version;

    private final DebugLog debug;

    public VipPlaceholders(
            Supplier<Snapshot> configuration,
            VipCache cache,
            Messages messages,
            String version,
            DebugLog debug) {
        this.debug = debug;
        this.configuration = configuration;
        this.cache = cache;
        this.messages = messages;
        this.version = version;
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "vipmanager";
    }

    @Override
    @NotNull
    public String getAuthor() {
        return "WellSetups";
    }

    @Override
    @NotNull
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        var snapshot = configuration.get();
        var settings = snapshot.settings();
        String locale =
                player == null
                        ? settings.display().defaultLanguage()
                        : snapshot.languages().locale(cache.locale(player.getUniqueId()));
        var primary =
                player == null
                        ? Optional.<com.wellsetups.wellviptime.vip.VipBalance>empty()
                        : cache.primary(player.getUniqueId(), settings, System.currentTimeMillis());
        if (params.equals("has_vip")) {
            return snapshot.languages()
                    .raw(locale, primary.isPresent() ? "placeholder.true" : "placeholder.false");
        }
        if (!Set.of("type", "remaining", "remaining_seconds", "expiry", "percentage", "progressbar")
                .contains(params)) {
            return null;
        }
        debug.write("placeholders", "Resolved cached placeholder " + params);
        if (primary.isEmpty()) {
            return params.equals("remaining_seconds") || params.equals("percentage")
                    ? "0"
                    : snapshot.languages().raw(locale, "placeholder.none");
        }
        var balance = primary.get();
        var values = messages.values(locale, balance, System.currentTimeMillis());
        if (params.equals("type")) {
            String name =
                    settings.types().containsKey(balance.type())
                            ? settings.types().get(balance.type()).displayName()
                            : balance.type();
            return LegacyComponentSerializer.legacySection()
                    .serialize(MiniMessage.miniMessage().deserialize(name));
        }
        if (params.equals("progressbar")) {
            return LegacyComponentSerializer.legacySection()
                    .serialize(
                            snapshot.languages().render(locale, "placeholder.progressbar", values));
        }
        return values.get(params);
    }
}
