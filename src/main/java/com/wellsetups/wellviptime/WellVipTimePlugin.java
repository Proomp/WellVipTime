package com.wellsetups.wellviptime;

import com.wellsetups.wellviptime.api.*;
import com.wellsetups.wellviptime.audit.*;
import com.wellsetups.wellviptime.command.*;
import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.integration.*;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.notification.*;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.ui.ExpiryMenu;
import com.wellsetups.wellviptime.ui.LiveDisplay;
import com.wellsetups.wellviptime.vip.VipCache;
import com.wellsetups.wellviptime.vip.VipService;
import com.wellsetups.wellviptime.voucher.*;

import net.luckperms.api.LuckPerms;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class WellVipTimePlugin extends JavaPlugin implements Listener {

    private final AtomicReference<ConfigLoader.Snapshot> configuration = new AtomicReference<>();

    private final AtomicReference<ConfigLoader.Snapshot> sourceConfiguration =
            new AtomicReference<>();

    private VipTypeDiscovery discovery;

    private ServerTasks.Task discoveryTask;

    private CommandTree commandTree;

    private net.kyori.adventure.platform.bukkit.BukkitAudiences audiences;

    private org.bstats.bukkit.Metrics metrics;

    private long nextDiscovery;

    private volatile PlayerPreferences preferences;

    private volatile NotificationDisplay notificationDisplay;

    private com.wellsetups.wellviptime.ui.VipMenus management;

    private volatile List<String> names = List.of();

    private volatile Database database;

    private final Object startupLock = new Object();

    private boolean stopping;

    private ConfigLoader loader;

    private AsyncWork work;

    private ServerTasks main;

    private Messages messages;

    private volatile VipRepository repository;

    private VipService service;

    private LuckPermsSync permissions;

    private volatile VipCommands commands;

    private VoucherSigner signer;

    private volatile VoucherController vouchers;

    private VoucherService voucherService;

    private final VipCache cache = new VipCache();

    private volatile ExpirationEngine expiration;

    private ExpiryMenu menu;

    private volatile LiveDisplay liveDisplay;

    private VipPlaceholders placeholders;

    private ListingService listings;

    private DiscordAudit discord;

    private Broadcasts broadcasts;

    private EventPublisher events;

    private boolean reloading;

    private volatile boolean ready;

    private DebugLog debug;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("VipManager") != null) {
            getLogger().severe("Remove the old VipManager JAR before installing WellVipTime.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!loadInitialConfiguration()) {
            return;
        }
        var settings = configuration.get().settings();
        sourceConfiguration.set(configuration.get());
        LuckPerms provider = getServer().getServicesManager().load(LuckPerms.class);
        if (provider == null) {
            getLogger().severe("LuckPerms service unavailable; plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        discovery = new VipTypeDiscovery(provider, getDataFolder().toPath());
        messages = new Messages(configuration::get);
        if (!(getServer().getConsoleSender() instanceof net.kyori.adventure.audience.Audience)) {
            audiences = net.kyori.adventure.platform.bukkit.BukkitAudiences.create(this);
            messages.audiences(audiences::sender);
        }
        debug = new DebugLog(configuration::get, getLogger());
        main = new ServerTasks(this);
        work =
                new AsyncWork(
                        settings.core().workers(),
                        settings.core().queueSize(),
                        settings.core().shutdownSeconds());
        registerPermissions(settings);
        commandTree =
                new CommandTree(
                        settings.commands(),
                        () -> configuration.get().settings(),
                        () -> names,
                        request -> {
                            debug.write("commands", "Dispatch " + request.id());
                            if (!ready || commands == null) {
                                messages.send(request.sender(), "error.not-ready");
                            } else {
                                commands.execute(request);
                            }
                        });
        commandTree.register(this, messages);
        if (getConfig().getBoolean("metrics.enabled", true)) {
            metrics = new org.bstats.bukkit.Metrics(this, 33878);
        }
        getServer().getPluginManager().registerEvents(this, this);
        work.submit(() -> initializeStorage(settings, provider))
                .whenComplete(
                        (db, error) ->
                                main.execute(
                                        () -> {
                                            if (error != null) {
                                                getLogger()
                                                        .log(
                                                                Level.SEVERE,
                                                                "Initialization failed; plugin"
                                                                        + " disabled",
                                                                error);
                                                getServer().getPluginManager().disablePlugin(this);
                                                return;
                                            }
                                            try {
                                                start(db);
                                            } catch (RuntimeException failure) {
                                                getLogger()
                                                        .log(
                                                                Level.SEVERE,
                                                                "Plugin services could not start",
                                                                failure);
                                                getServer().getPluginManager().disablePlugin(this);
                                            }
                                        }));
    }

    private boolean loadInitialConfiguration() {
        try {
            migrateFolder();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Old data could not be migrated", e);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        loader = new ConfigLoader(getDataFolder().toPath(), getLogger()::warning);
        try {
            configuration.set(loader.load());
        } catch (IOException | IllegalArgumentException e) {
            getLogger().severe("Configuration rejected: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private Database initializeStorage(Settings settings, LuckPerms provider)
            throws IOException, java.sql.SQLException {
        var resolved = discovery.resolve(settings, Map.of());
        configuration.set(
                new ConfigLoader.Snapshot(resolved, sourceConfiguration.get().languages()));
        Database opened = new Database(settings.database(), getDataFolder().toPath());
        synchronized (startupLock) {
            if (stopping) {
                opened.close();
                throw new java.util.concurrent.RejectedExecutionException(
                        "WellVipTime stopped during database startup");
            }
            database = opened;
        }
        database.migrate();
        database.read(
                c -> {
                    var persisted =
                            Sql.query(
                                    c,
                                    "SELECT vip_type FROM vip_balances WHERE status='ACTIVE' UNION"
                                            + " SELECT vip_type FROM vouchers WHERE state='ISSUED'",
                                    r -> r.getString(1));
                    for (String type : persisted) {
                        if (!resolved.types().containsKey(type)) {
                            throw new ConfigError(
                                    "vip-types.yml",
                                    "vip-types." + type,
                                    "missing",
                                    "definition required by an active VIP or issued voucher");
                        }
                    }
                    return null;
                });
        LuckPermsSync.validateGroups(provider, resolved)
                .forEach(
                        group ->
                                getLogger()
                                        .info(
                                                "Ensured missing LuckPerms group '"
                                                        + group
                                                        + "' exists. New groups are empty;"
                                                        + " configure their permissions in"
                                                        + " LuckPerms."));
        signer =
                VoucherSigner.load(
                        getDataFolder().toPath().resolve("signing-key.bin"),
                        settings.core().networkId());
        database.transaction(
                c -> {
                    Sql.update(
                            c,
                            (database.sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                                    + " INTO security_state(id,network_id,key_fingerprint)"
                                    + " VALUES(1,?,?)",
                            settings.core().networkId(),
                            signer.fingerprint());
                    var key =
                            Sql.query(
                                            c,
                                            "SELECT network_id,key_fingerprint FROM security_state"
                                                    + " WHERE id=1",
                                            r -> List.of(r.getString(1), r.getString(2)))
                                    .get(0);
                    if (!key.equals(List.of(settings.core().networkId(), signer.fingerprint()))) {
                        throw new java.sql.SQLException(
                                "Network identity/signing key does not match this database; restore"
                                        + " the correct signing-key.bin.");
                    }
                    return null;
                });
        return database;
    }

    private void start(Database db) {
        LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        if (luckPerms == null) {
            getLogger().severe("LuckPerms API service is unavailable; plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repository = new VipRepository(db);
        service = new VipService(db, repository);
        permissions =
                new LuckPermsSync(
                        luckPerms,
                        db,
                        repository,
                        configuration.get().settings().core().networkId(),
                        debug);
        listings = new ListingService(db, repository);
        preferences = new PlayerPreferences(db, () -> configuration.get().settings());
        commands =
                new VipCommands(
                        configuration::get,
                        db,
                        repository,
                        service,
                        work,
                        main,
                        messages,
                        getLogger(),
                        this::changed,
                        sender -> main.execute(() -> reload(sender)),
                        request -> vouchers.execute(request),
                        listings,
                        request -> management.open(request));
        management =
                new com.wellsetups.wellviptime.ui.VipMenus(
                        this,
                        configuration::get,
                        messages,
                        db,
                        repository,
                        listings,
                        work,
                        main,
                        preferences,
                        player -> {
                            liveDisplay.preferencesChanged(player);
                            notificationDisplay.preferencesChanged(player);
                        },
                        commands::failure);
        getServer().getPluginManager().registerEvents(management, this);
        var voucherRepository = new VoucherRepository();
        voucherService = new VoucherService(db, repository, voucherRepository, service);
        vouchers =
                new VoucherController(
                        configuration::get,
                        db,
                        repository,
                        voucherRepository,
                        voucherService,
                        new VoucherItems(this, signer, configuration::get),
                        work,
                        main,
                        messages,
                        commands::failure,
                        this::changed,
                        getServer()::getPlayer,
                        getServer()::getOnlinePlayers);
        getServer().getPluginManager().registerEvents(vouchers, this);
        menu = new ExpiryMenu(this, configuration::get, messages, cache, main);
        getServer().getPluginManager().registerEvents(menu, this);
        notificationDisplay =
                new NotificationDisplay(
                        configuration::get, messages, menu::open, debug, preferences);
        expiration =
                new ExpirationEngine(
                        this,
                        configuration::get,
                        db,
                        repository,
                        service,
                        permissions,
                        work,
                        main,
                        cache,
                        notificationDisplay,
                        this::changed,
                        menu::open,
                        vouchers::deliver,
                        preferences);
        liveDisplay = new LiveDisplay(this, configuration::get, cache, messages, preferences, main);
        discoveryTask = main.repeat(this::syncTypes, 200, 20);
        discord = new DiscordAudit(this, db, configuration::get, main);
        broadcasts = new Broadcasts(getServer(), configuration::get, messages, main);
        events = new EventPublisher(getServer().getPluginManager());
        getServer()
                .getServicesManager()
                .register(
                        VipApi.class,
                        new DefaultVipApi(
                                configuration::get,
                                db,
                                repository,
                                service,
                                voucherService,
                                signer,
                                work,
                                main,
                                this::publishChange),
                        this,
                        org.bukkit.plugin.ServicePriority.Normal);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders =
                    new VipPlaceholders(
                            configuration::get,
                            cache,
                            messages,
                            getDescription().getVersion(),
                            debug);
            placeholders.register();
        }
        if (configuration.get().settings().core().reconcileStartup()) {
            work.submit(
                            () ->
                                    database.transaction(
                                            c -> {
                                                repository.dirtyAll(c, database.now(c));
                                                return null;
                                            }))
                    .whenComplete(
                            (ignored, error) -> {
                                if (error != null) {
                                    getLogger()
                                            .log(
                                                    Level.WARNING,
                                                    "Startup reconciliation could not be queued",
                                                    error);
                                }
                            });
        }
        ready = true;
        for (Player player : getServer().getOnlinePlayers()) {
            main.execute(player, () -> remember(player));
        }
        refreshNames();
        getLogger()
                .info(
                        "WellVipTime storage ready; schema 3; server="
                                + configuration.get().settings().core().serverId());
    }

    private void registerPermissions(Settings settings) {
        Set<String> publicIds =
                Set.of("vipmenu", "viptime", "vipfreeze", "vipleaderboard", "viplist");
        for (var entry : settings.commands().entrySet()) {
            String permission = entry.getValue().permission();
            if (getServer().getPluginManager().getPermission(permission) == null) {
                getServer()
                        .getPluginManager()
                        .addPermission(
                                new Permission(
                                        permission,
                                        publicIds.contains(entry.getKey())
                                                ? PermissionDefault.TRUE
                                                : PermissionDefault.OP));
            }
        }
        for (String permission :
                new HashSet<>(
                        List.of(settings.voucher().bypass(), settings.core().hiddenPermission()))) {
            if (getServer().getPluginManager().getPermission(permission) == null) {
                getServer()
                        .getPluginManager()
                        .addPermission(new Permission(permission, PermissionDefault.FALSE));
            }
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (ready) {
            remember(event.getPlayer());
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        if (expiration != null) {
            expiration.quit(event.getPlayer());
        }
        if (liveDisplay != null) {
            liveDisplay.hide(event.getPlayer());
        }
        if (preferences != null) {
            preferences.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void locale(PlayerLocaleChangeEvent event) {
        cache.locale(
                event.getPlayer().getUniqueId(),
                Locale.forLanguageTag(event.getLocale().replace('_', '-')));
    }

    private void remember(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        cache.locale(uuid, Platform.locale(player));
        expiration.login(uuid);
        boolean hidden =
                player.hasPermission(configuration.get().settings().core().hiddenPermission())
                        || configuration
                                .get()
                                .settings()
                                .core()
                                .hiddenPlayers()
                                .contains(uuid.toString());
        work.submit(
                        () ->
                                database.transaction(
                                        c -> {
                                            repository.remember(
                                                    c, uuid, name, hidden, database.now(c));
                                            repository.dirty(c, uuid, database.now(c));
                                            return null;
                                        }))
                .whenComplete(
                        (ignored, error) -> {
                            if (error != null) {
                                getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Could not cache identity " + uuid,
                                                error);
                            } else {
                                refreshNames();
                                main.execute(
                                        player,
                                        () -> {
                                            if (player.isOnline()) {
                                                vouchers.deliver(player);
                                            }
                                        });
                            }
                        });
    }

    private void refreshNames() {
        work.submit(() -> database.read(repository::suggestions))
                .whenComplete(
                        (result, error) -> {
                            if (error == null) {
                                names = result;
                            } else {
                                getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Name suggestion refresh failed",
                                                error);
                            }
                        });
    }

    private void changed(VipService.Change change) {
        main.execute(() -> publishChange(change));
    }

    private void publishChange(VipService.Change change) {
        listings.invalidate();
        broadcasts.publish(change);
        events.publish(change);
        work.submit(() -> database.read(c -> repository.all(c, change.balance().player())))
                .whenComplete(
                        (balances, error) -> {
                            if (error == null) {
                                main.execute(
                                        () -> {
                                            if (getServer().getPlayer(change.balance().player())
                                                    != null) {
                                                cache.put(change.balance().player(), balances);
                                            }
                                        });
                            } else {
                                getLogger().log(Level.WARNING, "VIP cache refresh failed", error);
                            }
                        });
        debug.write(
                "database",
                "Committed " + change.audit().action() + " audit=" + change.audit().id());
        if (change.audit().voucher() != null) {
            debug.write(
                    "vouchers", change.audit().action() + " voucher=" + change.audit().voucher());
        }
    }

    private record Reloaded(ConfigLoader.Snapshot source, ConfigLoader.Snapshot effective) {}

    private void reload(CommandSender sender) {
        if (reloading) {
            main.execute(sender, () -> messages.send(sender, "error.busy"));
            return;
        }
        reloading = true;
        var current = configuration.get();
        var actor = VipCommands.actor(sender);
        work.submit(() -> loadReload(current))
                .whenComplete(
                        (loaded, error) ->
                                main.execute(
                                        () -> {
                                            reloading = false;
                                            if (error != null) {
                                                getLogger()
                                                        .warning(
                                                                "Reload rejected: "
                                                                        + error.getMessage());
                                                main.execute(
                                                        sender,
                                                        () ->
                                                                messages.send(
                                                                        sender, "error.reload"));
                                                return;
                                            }
                                            applyReload(sender, actor, current, loaded);
                                        }));
    }

    private Reloaded loadReload(ConfigLoader.Snapshot current)
            throws IOException, java.sql.SQLException {
        var raw = loader.load();
        var candidate =
                new ConfigLoader.Snapshot(
                        discovery.resolve(raw.settings(), current.settings().types()),
                        raw.languages());
        Set<String> removed = new HashSet<>(current.settings().types().keySet());
        removed.removeAll(candidate.settings().types().keySet());
        for (String type : removed) {
            boolean used =
                    database.read(
                            c ->
                                    !Sql.query(
                                                            c,
                                                            "SELECT vip_type FROM vip_balances"
                                                                    + " WHERE vip_type=? AND"
                                                                    + " status='ACTIVE' LIMIT 1",
                                                            r -> r.getString(1),
                                                            type)
                                                    .isEmpty()
                                            || !Sql.query(
                                                            c,
                                                            "SELECT vip_type FROM vouchers WHERE"
                                                                + " vip_type=? AND state='ISSUED'"
                                                                + " LIMIT 1",
                                                            r -> r.getString(1),
                                                            type)
                                                    .isEmpty());
            if (used) {
                throw new ConfigError(
                        "vip-types.yml",
                        "vip-types." + type,
                        "removed",
                        "preserved type while active balances or issued vouchers exist");
            }
        }
        if (!removed.isEmpty()) {
            throw new ConfigError(
                    "vip-types.yml",
                    "vip-types",
                    removed,
                    "restart for type removals, so in-flight operations cannot create orphaned"
                            + " balances");
        }
        permissions
                .validateGroups(candidate.settings())
                .forEach(
                        group ->
                                getLogger()
                                        .info(
                                                "Ensured missing LuckPerms group '"
                                                        + group
                                                        + "' exists. New groups are empty;"
                                                        + " configure their permissions in"
                                                        + " LuckPerms."));
        return new Reloaded(raw, candidate);
    }

    private void applyReload(
            CommandSender sender,
            VipService.Actor actor,
            ConfigLoader.Snapshot current,
            Reloaded loaded) {
        var candidate = loaded.effective();
        var next = candidate.settings();
        var old = current.settings();
        var core = next.core().withRuntimeFrom(old.core());
        boolean restart =
                !next.commands().equals(old.commands())
                        || !next.database().equals(old.database())
                        || !core.equals(next.core());
        var applied =
                new Settings(
                        core,
                        old.database(),
                        old.commands(),
                        next.types(),
                        next.voucher(),
                        next.notifications(),
                        next.display(),
                        next.discord(),
                        next.gui(),
                        next.discovery(),
                        next.menus());
        configuration.set(new ConfigLoader.Snapshot(applied, candidate.languages()));
        sourceConfiguration.set(loaded.source());
        management.closeInventories();
        listings.invalidate();
        work.submit(
                        () ->
                                database.transaction(
                                        c -> {
                                            repository.dirtyAll(c, database.now(c));
                                            return null;
                                        }))
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure != null) {
                                getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Reload reconciliation remains pending",
                                                failure);
                            }
                        });
        main.execute(
                sender, () -> messages.send(sender, restart ? "reload.restart" : "reload.success"));
        work.submit(
                        () ->
                                database.transaction(
                                        c -> {
                                            var audit =
                                                    new AuditEntry(
                                                            UUID.randomUUID(),
                                                            UUID.randomUUID(),
                                                            "CONFIG_RELOADED",
                                                            actor.uuid(),
                                                            actor.name(),
                                                            actor.uuid(),
                                                            actor.name(),
                                                            "",
                                                            0,
                                                            0,
                                                            0,
                                                            null,
                                                            database.now(c));
                                            audit.insert(
                                                    c,
                                                    applied.discord().enabled()
                                                            && applied.discord()
                                                                    .events()
                                                                    .contains(audit.action()));
                                            return null;
                                        }))
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure != null) {
                                getLogger()
                                        .log(
                                                Level.WARNING,
                                                "Reload audit persistence failed; runtime"
                                                        + " configuration was applied.",
                                                failure);
                            }
                        });
    }

    @Override
    public void onDisable() {
        synchronized (startupLock) {
            stopping = true;
        }
        ready = false;
        commands = null;
        cleanup("metrics", metrics == null ? null : metrics::shutdown);
        cleanup("commands", commandTree == null ? null : commandTree::close);
        cleanup("discovery", discoveryTask == null ? null : discoveryTask::cancel);
        cleanup("management menus", management == null ? null : management::close);
        cleanup("public API", () -> getServer().getServicesManager().unregisterAll(this));
        cleanup("expiration", expiration == null ? null : expiration::close);
        cleanup("live displays", liveDisplay == null ? null : liveDisplay::close);
        cleanup("placeholders", placeholders == null ? null : placeholders::unregister);
        cleanup("Discord worker", discord == null ? null : discord::close);
        cleanup("expiry menu", menu == null ? null : menu::close);
        cleanup("server tasks", main == null ? null : main::close);
        cleanup("database worker", work == null ? null : work::close);
        cleanup("LuckPerms", permissions == null ? null : permissions::close);
        cleanup("database pool", database == null ? null : database::close);
        cleanup("audiences", audiences == null ? null : audiences::close);
    }

    private void cleanup(String resource, Runnable close) {
        if (close == null) {
            return;
        }
        try {
            close.run();
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not close " + resource, failure);
        }
    }

    private void migrateFolder() throws IOException {
        if (DataMigration.copyLegacy(getDataFolder().toPath())) {
            getLogger()
                    .info(
                            "Copied existing VipManager data into WellVipTime; the original folder"
                                    + " remains as a backup.");
        }
    }

    private void syncTypes() {
        var current = configuration.get();
        if (reloading
                || !current.settings().discovery().enabled()
                || System.currentTimeMillis() < nextDiscovery) {
            return;
        }
        nextDiscovery =
                System.currentTimeMillis()
                        + current.settings().discovery().refreshSeconds() * 1000L;
        reloading = true;
        work.submit(
                        () ->
                                discovery.resolve(
                                        sourceConfiguration.get().settings(),
                                        current.settings().types()))
                .whenComplete(
                        (resolved, error) ->
                                main.execute(
                                        () -> {
                                            reloading = false;
                                            if (error != null) {
                                                getLogger()
                                                        .warning(
                                                                "LuckPerms VIP discovery failed;"
                                                                    + " retaining current types: "
                                                                        + error.getMessage());
                                                return;
                                            }
                                            if (!resolved.types()
                                                    .equals(current.settings().types())) {
                                                configuration.set(
                                                        new ConfigLoader.Snapshot(
                                                                current.settings()
                                                                        .withTypes(
                                                                                resolved.types()),
                                                                current.languages()));
                                                listings.invalidate();
                                                getLogger()
                                                        .info(
                                                                "Synchronized "
                                                                        + resolved.types().size()
                                                                        + " VIP definitions from"
                                                                        + " LuckPerms and manual"
                                                                        + " overrides.");
                                            }
                                        }));
    }
}
