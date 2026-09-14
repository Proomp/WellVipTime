package com.wellsetups.wellviptime.ui;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.ServerTasks;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.vip.VipCache;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Supplier;

public final class ExpiryMenu implements Listener, AutoCloseable {

    private static final class Session implements InventoryHolder {

        private final UUID player;

        private final Settings.Gui definition;

        private Inventory inventory;

        private boolean executing;

        Session(UUID player, Settings.Gui definition) {
            this.player = player;
            this.definition = definition;
        }

        @Override
        @NotNull
        public Inventory getInventory() {
            return Objects.requireNonNull(inventory);
        }
    }

    private final JavaPlugin plugin;

    private final Supplier<Snapshot> configuration;

    private final Messages messages;

    private final VipCache cache;

    private final ServerTasks main;

    public ExpiryMenu(
            JavaPlugin plugin,
            Supplier<Snapshot> configuration,
            Messages messages,
            VipCache cache,
            ServerTasks main) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.messages = messages;
        this.cache = cache;
        this.main = main;
    }

    public void open(Player player) {
        var snapshot = configuration.get();
        var definition = snapshot.settings().gui();
        if (!definition.enabled()
                || !player.isOnline()
                || com.wellsetups.wellviptime.integration.Platform.top(player.getOpenInventory())
                                .getType()
                        != InventoryType.CRAFTING) {
            return;
        }
        var primary =
                cache.primary(
                        player.getUniqueId(), snapshot.settings(), System.currentTimeMillis());
        var values =
                primary.map(
                                b ->
                                        messages.values(
                                                messages.locale(player),
                                                b,
                                                System.currentTimeMillis()))
                        .orElseGet(HashMap::new);
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        String vip =
                primary.map(
                                b ->
                                        snapshot.settings().types().containsKey(b.type())
                                                ? snapshot.settings()
                                                        .types()
                                                        .get(b.type())
                                                        .displayName()
                                                : b.type())
                        .orElse(snapshot.languages().raw(messages.locale(player), "vip.none"));
        Component title =
                snapshot.languages()
                        .render(messages.locale(player), definition.titleKey(), values, vip);
        Session session = new Session(player.getUniqueId(), definition);
        session.inventory =
                plugin.getServer()
                        .createInventory(
                                session,
                                definition.size(),
                                com.wellsetups.wellviptime.integration.Platform.legacy(title));
        for (var item : definition.items()) {
            var stack = new ItemStack(item.material());
            var meta = stack.getItemMeta();
            com.wellsetups.wellviptime.integration.Platform.name(
                    meta,
                    snapshot.languages()
                            .render(messages.locale(player), item.nameKey(), values, vip));
            com.wellsetups.wellviptime.integration.Platform.lore(
                    meta,
                    snapshot.languages()
                            .lines(messages.locale(player), item.loreKey(), values, vip));
            stack.setItemMeta(meta);
            session.inventory.setItem(item.slot(), stack);
        }
        player.openInventory(session.inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(com.wellsetups.wellviptime.integration.Platform.top(event.getView()).getHolder()
                instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !session.player.equals(player.getUniqueId())
                || session.executing
                || event.getClickedInventory() != session.inventory
                || !event.isLeftClick()
                || event.isShiftClick()) {
            return;
        }
        var selected =
                session.definition.items().stream()
                        .filter(i -> i.slot() == event.getRawSlot())
                        .findFirst();
        if (selected.isEmpty()) {
            return;
        }
        session.executing = true;
        main.execute(player, () -> activate(player, session, selected.get()));
    }

    private void activate(Player player, Session session, Settings.MenuItem item) {
        if (!player.isOnline()
                || com.wellsetups.wellviptime.integration.Platform.top(player.getOpenInventory())
                        != session.inventory) {
            return;
        }
        String value =
                item.value()
                        .replace("<player>", player.getName())
                        .replace("<uuid>", player.getUniqueId().toString());
        if (session.definition.closeAfterAction() || item.action() == Settings.Action.CLOSE) {
            player.closeInventory();
        }
        try {
            switch (item.action()) {
                case CLOSE -> {}
                case PLAYER_COMMAND ->
                        plugin.getServer()
                                .dispatchCommand(
                                        player, value.startsWith("/") ? value.substring(1) : value);
                case CONSOLE_COMMAND ->
                        main.execute(
                                () ->
                                        plugin.getServer()
                                                .dispatchCommand(
                                                        plugin.getServer().getConsoleSender(),
                                                        value.startsWith("/")
                                                                ? value.substring(1)
                                                                : value));
                case OPEN_URL ->
                        messages.sendComponent(
                                player,
                                messages.component(player, "gui.url", Map.of())
                                        .clickEvent(ClickEvent.openUrl(value)));
            }
        } finally {
            session.executing = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (com.wellsetups.wellviptime.integration.Platform.top(event.getView()).getHolder()
                instanceof Session) {
            event.setCancelled(true);
        }
    }

    @Override
    public void close() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            main.cleanup(
                    player,
                    () -> {
                        if (com.wellsetups.wellviptime.integration.Platform.top(
                                                player.getOpenInventory())
                                        .getHolder()
                                instanceof Session) {
                            player.closeInventory();
                        }
                    });
        }
    }
}
