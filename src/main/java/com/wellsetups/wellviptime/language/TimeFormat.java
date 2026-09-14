package com.wellsetups.wellviptime.language;

import com.wellsetups.wellviptime.configuration.Settings.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TimeFormat {

    private final Languages languages;

    private final Display display;

    public TimeFormat(Languages languages, Display display) {
        this.languages = languages;
        this.display = display;
    }

    public String duration(String locale, long seconds) {
        long rest = Math.max(0, seconds);
        long[] units = {86400, 3600, 60, 1};
        String[] names = {"day", "hour", "minute", "second"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < units.length && parts.size() < display.durationUnits(); i++) {
            long amount = rest / units[i];
            rest %= units[i];
            if (amount == 0 && (i != units.length - 1 || !parts.isEmpty())) {
                continue;
            }
            String key =
                    "duration."
                            + display.durationStyle()
                            + "."
                            + names[i]
                            + (amount == 1 ? ".one" : ".many");
            parts.add(languages.raw(locale, key).replace("<value>", Long.toString(amount)));
        }
        return String.join(languages.raw(locale, "duration.separator"), parts);
    }

    public Map<String, String> parts(long seconds) {
        long n = Math.max(0, seconds);
        return Map.of(
                "days",
                Long.toString(n / 86400),
                "hours",
                Long.toString(n % 86400 / 3600),
                "minutes",
                Long.toString(n % 3600 / 60),
                "seconds",
                Long.toString(n % 60));
    }
}
