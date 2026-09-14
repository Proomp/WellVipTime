package com.wellsetups.wellviptime.ui;

import com.wellsetups.wellviptime.command.CommandRequest;
import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.ServerTasks;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.notification.PlayerPreferences;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.VipBalance;

import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.*;
import java.util.function.*;

public final class VipMenus implements Listener, AutoCloseable {

    private static final int[] GRID = {
        10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38,
        39, 40, 41, 42, 43
    };

    private static final int[] PERSONAL = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private static final class Session implements InventoryHolder {

        final Player player;

        final String kind;

        final int page;

        final String filter;

        final Map<Integer, Runnable> actions = new HashMap<>();

        Inventory inventory;

        Session(Player player, String kind, int page, String filter) {
            this.player = player;
            this.kind = kind;
            this.page = page;
            this.filter = filter;
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

    private final Database database;

    private final VipRepository repository;

    private final ListingService listings;

    private final AsyncWork work;

    private final ServerTasks main;

    private final PlayerPreferences preferences;

    private final Consumer<Player> preferenceChanged;

    private final BiConsumer<org.bukkit.command.CommandSender, Throwable> failure;

    private final HeadSkins skins;

    private final Set<Player> saving = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Set<Player> loading = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public VipMenus(
            JavaPlugin plugin,
            Supplier<Snapshot> configuration,
            Messages messages,
            Database database,
            VipRepository repository,
            ListingService listings,
            AsyncWork work,
            ServerTasks main,
            PlayerPreferences preferences,
            Consumer<Player> preferenceChanged,
            BiConsumer<org.bukkit.command.CommandSender, Throwable> failure) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.messages = messages;
        this.database = database;
        this.repository = repository;
        this.listings = listings;
        this.work = work;
        this.main = main;
        this.preferences = preferences;
        this.preferenceChanged = preferenceChanged;
        this.failure = failure;
        skins = new HeadSkins(plugin, main, configuration);
    }

    public void open(CommandRequest request) {
        if (!(request.sender() instanceof Player player)) {
            messages.send(request.sender(), "error.player-only");
            return;
        }
        open(player, request.id(), request.page(), request.vip());
    }

    private void open(Player player, String kind, int page, String filter) {
        var settings = configuration.get().settings();
        var options = settings.menus();
        if (!player.isOnline()) {
            return;
        }
        if (!player.hasPermission(settings.commands().get(kind).permission())) {
            messages.send(player, "error.permission");
            return;
        }
        if (!(kind.equals("vipmenu")
                ? options.personal()
                : kind.equals("viplist") ? options.list() : options.leaderboard())) {
            messages.send(player, "menu.disabled");
            return;
        }
        if (!loading.add(player)) {
            messages.send(player, "error.busy");
            return;
        }
        var session = new Session(player, kind, Math.max(1, page), filter);
        String title =
                kind.equals("vipmenu")
                        ? "menu.personal.inventory-title"
                        : kind.equals("viplist") ? "menu.list.title" : "menu.leaderboard.title";
        session.inventory =
                plugin.getServer()
                        .createInventory(
                                session,
                                54,
                                com.wellsetups.wellviptime.integration.Platform.legacy(
                                        messages.component(
                                                player,
                                                title,
                                                Map.of("page", Integer.toString(session.page)))));
        for (int i = 0; i < 54; i++) {
            session.inventory.setItem(
                    i, item(player, options.filler(), "menu.filler", Map.of(), null));
        }
        session.inventory.setItem(22, item(player, Material.CLOCK, "menu.loading", Map.of(), null));
        player.openInventory(session.inventory);
        if (kind.equals("vipmenu")) {
            loadPersonal(session);
        } else {
            loadBrowser(session, settings);
        }
    }

    private void loadPersonal(Session session) {
        Player player = session.player;
        UUID uuid = player.getUniqueId();
        work.submit(
                        () ->
                                Map.entry(
                                        database.read(c -> repository.all(c, uuid)),
                                        preferences.load(uuid)))
                .whenComplete(
                        (result, error) ->
                                main.execute(
                                        session.player,
                                        () -> {
                                            loading.remove(player);
                                            if (!current(session)) {
                                                return;
                                            }
                                            if (error != null) {
                                                failed(session, error);
                                                return;
                                            }
                                            preferences.initialize(uuid, result.getValue());
                                            renderPersonal(session, result.getKey());
                                        }));
    }

    private void loadBrowser(Session session, Settings settings) {
        var player = session.player;
        var options = settings.menus();
        String kind = session.kind;
        String filter = session.filter;
        int size = options.pageSize();
        int offset = Math.multiplyExact(session.page - 1, size);
        boolean leaderboard = kind.equals("vipleaderboard");
        int limit =
                leaderboard
                        ? Math.max(0, Math.min(size + 1, settings.core().maxRanking() - offset))
                        : size + 1;
        work.submit(() -> listings.page(settings, filter, offset, limit, leaderboard))
                .whenComplete(
                        (rows, error) ->
                                main.execute(
                                        session.player,
                                        () -> {
                                            loading.remove(player);
                                            if (!current(session)) {
                                                return;
                                            }
                                            if (error != null) {
                                                failed(session, error);
                                                return;
                                            }
                                            renderBrowser(
                                                    session,
                                                    rows,
                                                    settings,
                                                    size,
                                                    offset,
                                                    leaderboard);
                                        }));
    }

    private void renderBrowser(
            Session session,
            List<VipRepository.Ranked> rows,
            Settings settings,
            int size,
            int offset,
            boolean leaderboard) {
        var player = session.player;
        clearContent(session, GRID);
        for (int i = 0; i < Math.min(size, rows.size()); i++) {
            var row = rows.get(i);
            var values =
                    messages.values(messages.locale(player), row.vip(), System.currentTimeMillis());
            values.put("player", row.player().name());
            values.put("rank", Integer.toString(offset + i + 1));
            values.put(
                    "score",
                    leaderboard && settings.core().ranking() == Settings.Ranking.EXPIRATION
                            ? settings.display().dates().format(Instant.ofEpochMilli(row.score()))
                            : messages.duration(
                                    player,
                                    leaderboard
                                            ? row.score()
                                            : row.vip().remaining(System.currentTimeMillis())));
            values.put(
                    "ranking",
                    configuration
                            .get()
                            .languages()
                            .raw(
                                    messages.locale(player),
                                    "menu.ranking."
                                            + settings.core()
                                                    .ranking()
                                                    .name()
                                                    .toLowerCase(Locale.ROOT)));
            values.put(
                    "status",
                    configuration
                            .get()
                            .languages()
                            .raw(
                                    messages.locale(player),
                                    plugin.getServer().getPlayer(row.player().uuid()) == null
                                            ? "menu.offline"
                                            : "menu.online"));
            int slot = GRID[i];
            session.inventory.setItem(
                    slot,
                    item(
                            player,
                            Material.PLAYER_HEAD,
                            leaderboard ? "menu.leaderboard.entry" : "menu.list.entry",
                            values,
                            row.vip()));
            head(session, slot, row.player().uuid());
        }
        if (rows.isEmpty()) {
            session.inventory.setItem(
                    22, item(player, Material.BARRIER, "menu.empty", Map.of(), null));
        }
        navigation(session, rows.size() > size);
        button(session, 48, Material.COMPASS, "menu.home", () -> open(player, "vipmenu", 1, null));
    }

    private void renderPersonal(Session session, List<VipBalance> balances) {
        if (!current(session)) {
            return;
        }
        var player = session.player;
        var options = configuration.get().settings().menus();
        var active =
                balances.stream()
                        .filter(b -> b.active(System.currentTimeMillis()))
                        .sorted(Comparator.comparing(VipBalance::type))
                        .toList();
        if (active.isEmpty()) {
            player.closeInventory();
            messages.send(player, "error.no-vip", Map.of("player", player.getName()));
            return;
        }
        session.actions.clear();
        clearContent(session, GRID);
        toggle(session, 10, Settings.Channel.BOSSBAR, options.bossbar());
        toggle(session, 12, Settings.Channel.ACTIONBAR, options.actionbar());
        toggle(session, 14, Settings.Channel.TITLE, options.title());
        session.inventory.setItem(
                16,
                item(
                        player,
                        Material.PLAYER_HEAD,
                        "menu.personal.profile",
                        Map.of(
                                "player",
                                player.getName(),
                                "count",
                                Integer.toString(active.size())),
                        null));
        head(session, 16, player.getUniqueId());
        int offset = (session.page - 1) * PERSONAL.length;
        for (int i = 0; i < PERSONAL.length && offset + i < active.size(); i++) {
            var balance = active.get(offset + i);
            session.inventory.setItem(
                    PERSONAL[i],
                    item(
                            player,
                            Material.EMERALD,
                            "menu.personal.vip",
                            messages.values(
                                    messages.locale(player), balance, System.currentTimeMillis()),
                            balance));
        }
        navigation(session, offset + PERSONAL.length < active.size());
        button(
                session,
                47,
                Material.PLAYER_HEAD,
                "menu.open-list",
                () -> open(player, "viplist", 1, null));
        button(
                session,
                51,
                Material.GOLD_INGOT,
                "menu.open-leaderboard",
                () -> open(player, "vipleaderboard", 1, null));
    }

    private void toggle(Session session, int slot, Settings.Channel channel, Material material) {
        String state =
                preferences.enabled(session.player.getUniqueId(), channel)
                        ? "menu.enabled"
                        : "menu.disabled-state";
        String channelKey = channel.name().toLowerCase(Locale.ROOT);
        session.inventory.setItem(
                slot,
                item(
                        session.player,
                        material,
                        "menu.personal." + channelKey,
                        Map.of(
                                "state",
                                configuration
                                        .get()
                                        .languages()
                                        .raw(messages.locale(session.player), state)),
                        null));
        var icon = session.inventory.getItem(slot);
        if (icon != null) {
            var meta = icon.getItemMeta();
            com.wellsetups.wellviptime.integration.Platform.glow(
                    meta, preferences.enabled(session.player.getUniqueId(), channel));
            icon.setItemMeta(meta);
            session.inventory.setItem(slot, icon);
        }
        session.actions.put(slot, () -> savePreference(session, slot, channel, material));
    }

    private void savePreference(
            Session session, int slot, Settings.Channel channel, Material material) {
        UUID uuid = session.player.getUniqueId();
        if (!saving.add(session.player)) {
            messages.send(session.player, "error.busy");
            return;
        }
        boolean enabled = !preferences.enabled(uuid, channel);
        work.submit(
                        () -> {
                            if (database.read(c -> repository.all(c, uuid)).stream()
                                    .noneMatch(b -> b.active(System.currentTimeMillis()))) {
                                throw new com.wellsetups.wellviptime.vip.DomainFailure(
                                        "error.no-vip", Map.of("player", uuid.toString()));
                            }
                            return preferences.save(uuid, channel, enabled);
                        })
                .whenComplete(
                        (value, error) ->
                                main.execute(
                                        session.player,
                                        () -> {
                                            saving.remove(session.player);
                                            if (error != null) {
                                                failure.accept(session.player, error);
                                                return;
                                            }
                                            if (plugin.getServer().getPlayer(uuid)
                                                    != session.player) {
                                                return;
                                            }
                                            preferences.apply(uuid, value);
                                            preferenceChanged.accept(session.player);
                                            messages.send(session.player, "menu.saved");
                                            if (current(session)) {
                                                toggle(session, slot, channel, material);
                                            }
                                        }));
    }

    private void navigation(Session session, boolean next) {
        button(session, 49, Material.BARRIER, "menu.close", session.player::closeInventory);
        if (session.page > 1) {
            button(
                    session,
                    45,
                    Material.ARROW,
                    "menu.previous",
                    () -> open(session.player, session.kind, session.page - 1, session.filter));
        }
        if (next) {
            button(
                    session,
                    53,
                    Material.ARROW,
                    "menu.next",
                    () -> open(session.player, session.kind, session.page + 1, session.filter));
        }
    }

    private void button(Session session, int slot, Material material, String key, Runnable action) {
        session.inventory.setItem(
                slot,
                item(
                        session.player,
                        material,
                        key,
                        Map.of("page", Integer.toString(session.page)),
                        null));
        session.actions.put(slot, action);
    }

    private ItemStack item(
            Player player,
            Material material,
            String key,
            Map<String, String> values,
            VipBalance balance) {
        var snapshot = configuration.get();
        String locale = messages.locale(player);
        var definition = balance == null ? null : snapshot.settings().types().get(balance.type());
        String vip = definition == null ? "" : definition.displayName();
        var stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        com.wellsetups.wellviptime.integration.Platform.name(
                meta,
                snapshot.languages()
                        .render(locale, key + ".name", values, vip)
                        .decoration(TextDecoration.ITALIC, false));
        com.wellsetups.wellviptime.integration.Platform.lore(
                meta,
                snapshot.languages().lines(locale, key + ".lore", values, vip).stream()
                        .map(line -> line.decoration(TextDecoration.ITALIC, false))
                        .toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private void head(Session session, int slot, UUID uuid) {
        skins.request(
                session.player,
                uuid,
                profile -> {
                    if (!current(session)) {
                        return;
                    }
                    var stack = session.inventory.getItem(slot);
                    if (stack == null || !(stack.getItemMeta() instanceof SkullMeta meta)) {
                        return;
                    }
                    meta.setOwnerProfile(profile);
                    stack.setItemMeta(meta);
                    session.inventory.setItem(slot, stack);
                });
    }

    private static void clearContent(Session session, int[] slots) {
        for (int slot : slots) {
            session.inventory.setItem(slot, null);
        }
    }

    private static boolean current(Session session) {
        return session.player.isOnline()
                && com.wellsetups.wellviptime.integration.Platform.top(
                                session.player.getOpenInventory())
                        == session.inventory;
    }

    private void failed(Session session, Throwable error) {
        if (current(session)) {
            session.player.closeInventory();
        }
        failure.accept(session.player, error);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(com.wellsetups.wellviptime.integration.Platform.top(event.getView()).getHolder()
                instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() != session.player
                || event.getClickedInventory() != session.inventory
                || !event.isLeftClick()
                || event.isShiftClick()) {
            return;
        }
        var action = session.actions.get(event.getRawSlot());
        if (action != null) {
            main.execute(
                    session.player,
                    () -> {
                        if (current(session)
                                && session.player.hasPermission(
                                        configuration
                                                .get()
                                                .settings()
                                                .commands()
                                                .get(session.kind)
                                                .permission())) {
                            action.run();
                        }
                    });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (com.wellsetups.wellviptime.integration.Platform.top(event.getView()).getHolder()
                instanceof Session) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        skins.forget(event.getPlayer());
        loading.remove(event.getPlayer());
        saving.remove(event.getPlayer());
    }

    @Override
    public void close() {
        skins.close();
        closeInventories();
    }

    public void closeInventories() {
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
