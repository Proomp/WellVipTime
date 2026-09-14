package com.wellsetups.wellviptime.vip;

import java.util.Map;
import java.util.regex.Pattern;

public final class DurationParser {

    private static final Pattern PART = Pattern.compile("([1-9][0-9]*)([dhms])");

    private DurationParser() {}

    public static long parseMillis(String input, int maximumDays) {
        if (input == null || input.isEmpty() || input.length() > 64) {
            throw invalid(input, maximumDays);
        }
        var matcher = PART.matcher(input);
        long seconds = 0;
        int end = 0;
        int previous = -1;
        while (matcher.find()) {
            int order = "dhms".indexOf(matcher.group(2));
            if (matcher.start() != end || order <= previous) {
                throw invalid(input, maximumDays);
            }
            previous = order;
            end = matcher.end();
            long multiplier =
                    switch (order) {
                        case 0 -> 86400;
                        case 1 -> 3600;
                        case 2 -> 60;
                        default -> 1;
                    };
            try {
                seconds =
                        Math.addExact(
                                seconds,
                                Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier));
            } catch (NumberFormatException | ArithmeticException e) {
                throw invalid(input, maximumDays);
            }
        }
        if (end != input.length() || seconds < 1 || seconds > maximumDays * 86400L) {
            throw invalid(input, maximumDays);
        }
        return seconds * 1000;
    }

    private static DomainFailure invalid(String input, int maximum) {
        return new DomainFailure(
                "error.duration",
                Map.of("input", String.valueOf(input), "maximum", Integer.toString(maximum)));
    }
}
