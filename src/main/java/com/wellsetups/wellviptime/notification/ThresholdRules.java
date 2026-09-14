package com.wellsetups.wellviptime.notification;

import com.wellsetups.wellviptime.configuration.Settings;

import java.util.List;
import java.util.Set;

public final class ThresholdRules {

    private ThresholdRules() {}

    public static List<Settings.Threshold> crossed(
            List<Settings.Threshold> thresholds,
            long remainingMs,
            long originalMs,
            Set<String> sent) {
        if (remainingMs <= 0) {
            return List.of();
        }
        return thresholds.stream()
                .filter(
                        t ->
                                t.seconds() * 1000 >= remainingMs
                                        && t.seconds() * 1000 <= originalMs
                                        && !sent.contains(t.id()))
                .sorted(java.util.Comparator.comparingLong(Settings.Threshold::seconds))
                .toList();
    }

    public static List<Settings.Threshold> display(
            List<Settings.Threshold> crossed, boolean login, Settings.LoginMode mode) {
        if (crossed.isEmpty()) {
            return List.of();
        }
        if (login && mode == Settings.LoginMode.IGNORE) {
            return List.of();
        }
        if (!login || mode == Settings.LoginMode.NEAREST) {
            return List.of(crossed.get(0));
        }
        return crossed;
    }
}
