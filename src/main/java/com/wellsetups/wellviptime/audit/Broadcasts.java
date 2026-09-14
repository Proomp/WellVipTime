package com.wellsetups.wellviptime.audit;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.vip.VipService;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Supplier;

public final class Broadcasts {

    private final Server server;

    private final Supplier<Snapshot> configuration;

    private final Messages messages;

    private final com.wellsetups.wellviptime.integration.ServerTasks main;

    public Broadcasts(
            Server server,
            Supplier<Snapshot> configuration,
            Messages messages,
            com.wellsetups.wellviptime.integration.ServerTasks main) {
        this.server = server;
        this.configuration = configuration;
        this.messages = messages;
        this.main = main;
    }

    public void publish(VipService.Change change) {
        var settings = configuration.get().settings();
        String action = change.audit().action();
        if (!settings.core().broadcasts().contains(action)) {
            return;
        }
        for (Player player : server.getOnlinePlayers()) {
            main.execute(
                    player,
                    () -> {
                        if (!settings.core().broadcastPermission().isEmpty()
                                && !player.hasPermission(settings.core().broadcastPermission())) {
                            return;
                        }
                        messages.sendComponent(
                                player,
                                messages.vip(
                                        player,
                                        "broadcast." + action,
                                        change.balance(),
                                        Map.of("player", change.audit().targetName())));
                    });
        }
    }
}
