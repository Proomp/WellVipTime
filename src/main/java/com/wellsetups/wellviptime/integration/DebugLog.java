package com.wellsetups.wellviptime.integration;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DebugLog {

    private final Supplier<Snapshot> configuration;

    private final Logger logger;

    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    public DebugLog(Supplier<Snapshot> configuration, Logger logger) {
        this.configuration = configuration;
        this.logger = logger;
    }

    public void write(String category, String message) {
        if (!configuration.get().settings().core().debug().contains(category)) {
            return;
        }
        long now = System.nanoTime();
        last.compute(
                category,
                (key, previous) -> {
                    if (previous == null || now - previous >= 30000000000L) {
                        logger.info(
                                "[debug:"
                                        + category
                                        + "][server:"
                                        + configuration.get().settings().core().serverId()
                                        + "] "
                                        + message);
                        return now;
                    }
                    return previous;
                });
    }
}
