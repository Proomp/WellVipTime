package com.wellsetups.wellviptime.vip;

import com.wellsetups.wellviptime.configuration.Settings;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VipCache {

    private final Map<UUID, List<VipBalance>> balances = new ConcurrentHashMap<>();

    private final Map<UUID, Locale> locales = new ConcurrentHashMap<>();

    public void put(UUID player, List<VipBalance> values) {
        balances.compute(
                player,
                (uuid, previous) -> {
                    Map<String, VipBalance> merged = new HashMap<>();
                    if (previous != null) {
                        previous.forEach(b -> merged.put(b.type(), b));
                    }
                    values.forEach(
                            b ->
                                    merged.merge(
                                            b.type(),
                                            b,
                                            (oldValue, newValue) ->
                                                    oldValue.revision() > newValue.revision()
                                                            ? oldValue
                                                            : newValue));
                    return List.copyOf(merged.values());
                });
    }

    public void remove(UUID player) {
        balances.remove(player);
        locales.remove(player);
    }

    public void locale(UUID player, Locale locale) {
        locales.put(player, locale);
    }

    public Locale locale(UUID player) {
        return locales.get(player);
    }

    public List<VipBalance> get(UUID player) {
        return balances.getOrDefault(player, List.of());
    }

    public Optional<VipBalance> primary(UUID player, Settings settings, long now) {
        return get(player).stream()
                .filter(b -> b.active(now))
                .max(
                        Comparator.comparingInt(
                                        (VipBalance b) ->
                                                settings.types().containsKey(b.type())
                                                        ? settings.types().get(b.type()).priority()
                                                        : -1)
                                .thenComparingLong(VipBalance::expiresAt)
                                .thenComparing(VipBalance::type));
    }
}
