package com.wellsetups.wellviptime.configuration;

import com.wellsetups.wellviptime.configuration.Settings.Action;
import com.wellsetups.wellviptime.configuration.Settings.Channel;
import com.wellsetups.wellviptime.configuration.Settings.Click;
import com.wellsetups.wellviptime.configuration.Settings.Command;
import com.wellsetups.wellviptime.configuration.Settings.Core;
import com.wellsetups.wellviptime.configuration.Settings.Database;
import com.wellsetups.wellviptime.configuration.Settings.DifferentPolicy;
import com.wellsetups.wellviptime.configuration.Settings.Discord;
import com.wellsetups.wellviptime.configuration.Settings.DiscordEmbed;
import com.wellsetups.wellviptime.configuration.Settings.DiscordField;
import com.wellsetups.wellviptime.configuration.Settings.Discovery;
import com.wellsetups.wellviptime.configuration.Settings.Display;
import com.wellsetups.wellviptime.configuration.Settings.FullInventory;
import com.wellsetups.wellviptime.configuration.Settings.Gui;
import com.wellsetups.wellviptime.configuration.Settings.ListSort;
import com.wellsetups.wellviptime.configuration.Settings.LoginMode;
import com.wellsetups.wellviptime.configuration.Settings.MenuItem;
import com.wellsetups.wellviptime.configuration.Settings.Menus;
import com.wellsetups.wellviptime.configuration.Settings.Notifications;
import com.wellsetups.wellviptime.configuration.Settings.Ownership;
import com.wellsetups.wellviptime.configuration.Settings.ProgressSource;
import com.wellsetups.wellviptime.configuration.Settings.Ranking;
import com.wellsetups.wellviptime.configuration.Settings.RemovePolicy;
import com.wellsetups.wellviptime.configuration.Settings.SamePolicy;
import com.wellsetups.wellviptime.configuration.Settings.Threshold;
import com.wellsetups.wellviptime.configuration.Settings.VipType;
import com.wellsetups.wellviptime.configuration.Settings.Voucher;
import com.wellsetups.wellviptime.language.Languages;

import net.kyori.adventure.bossbar.BossBar;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

public final class ConfigLoader {

    public record Snapshot(Settings settings, Languages languages) {}

    private static final List<String> FILES =
            List.of(
                    "config.yml",
                    "commands.yml",
                    "vip-types.yml",
                    "database.yml",
                    "notifications.yml",
                    "discord.yml",
                    "gui.yml",
                    "menus.yml",
                    "languages/en_US.yml");

    public static final Set<String> COMMAND_IDS =
            Set.of(
                    "vipmenu",
                    "viptime",
                    "viptime.others",
                    "viptime.give",
                    "viptime.remove",
                    "viptime.item",
                    "viptime.item.give",
                    "viptime.item.giveall",
                    "vipfreeze",
                    "vipfreeze.others",
                    "vipleaderboard",
                    "viplist",
                    "vipadmin",
                    "vipadmin.reload",
                    "vipadmin.recover",
                    "vipadmin.revoke");

    private final Path directory;

    private final Consumer<String> warning;

    private final java.util.function.Predicate<Material> itemMaterial;

    public ConfigLoader(Path directory, Consumer<String> warning) {
        this(directory, warning, Material::isItem);
    }

    public ConfigLoader(
            Path directory,
            Consumer<String> warning,
            java.util.function.Predicate<Material> itemMaterial) {
        this.directory = directory;
        this.warning = warning;
        this.itemMaterial = itemMaterial;
    }

    public Snapshot load() throws IOException {
        copyDefaults();
        var config = file("config.yml");
        var core = core(config);
        var database = database(file("database.yml"));
        var typesFile = file("vip-types.yml");
        var discovery = discovery(typesFile);
        var types = types(typesFile, discovery);
        var commands = commands(file("commands.yml"));
        var voucher = voucher(config);
        var notifications = notifications(file("notifications.yml"));
        var display = display(config);
        var discord = discord(file("discord.yml"));
        var gui = gui(file("gui.yml"));
        var menus = menus(file("menus.yml"));
        var settings =
                new Settings(
                        core,
                        database,
                        commands,
                        types,
                        voucher,
                        notifications,
                        display,
                        discord,
                        gui,
                        discovery,
                        menus);
        return new Snapshot(
                settings, Languages.load(directory.resolve("languages"), display, warning));
    }

    private void copyDefaults() throws IOException {
        var resources = new ArrayList<>(FILES);
        Languages.BUNDLED_LOCALES.forEach(locale -> resources.add("languages/" + locale + ".yml"));
        for (String name : resources) {
            Path target = directory.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            try (var input = ConfigLoader.class.getClassLoader().getResourceAsStream(name)) {
                if (input == null) {
                    throw new IOException("Missing bundled configuration " + name);
                }
                Files.copy(input, target);
            }
        }
    }

    private Core core(YamlFile c) {
        c.number("config-version", 1, 1);
        var core =
                new Core(
                        identifier(c, "network-id"),
                        identifier(c, "server-id"),
                        c.number("execution.workers", 1, 16),
                        c.number("execution.queue-size", 16, 10000),
                        c.number("execution.shutdown-seconds", 1, 30),
                        c.number("expiration.check-seconds", 1, 300),
                        c.number("expiration.batch-size", 1, 1000),
                        c.number("policies.maximum-duration-days", 1, 36500),
                        c.bool("policies.allow-multiple-vip-types"),
                        c.choice("policies.same-type", SamePolicy.class),
                        c.choice("policies.different-type", DifferentPolicy.class),
                        c.choice("policies.excess-removal", RemovePolicy.class),
                        c.number("listing.page-size", 1, 50),
                        c.number("listing.maximum-leaderboard-size", 1, 10000),
                        c.choice("listing.ranking", Ranking.class),
                        c.choice("listing.sort", ListSort.class),
                        c.number("listing.cache-seconds", 0, 300),
                        c.text("listing.hidden-permission"),
                        Set.copyOf(c.strings("listing.hidden-players")),
                        c.bool("luckperms.reconcile-startup"),
                        c.bool("luckperms.create-missing-groups"),
                        Set.copyOf(c.strings("debug")),
                        Set.copyOf(c.strings("broadcast.events")),
                        c.optional("broadcast.permission"));
        if (!core.multiple() && core.different() == DifferentPolicy.PARALLEL) {
            throw c.error(
                    "policies.different-type",
                    "REPLACE, REJECT or PRIORITY_BASED when multiple types are disabled");
        }
        for (String uuid : core.hiddenPlayers()) {
            try {
                if (!UUID.fromString(uuid).toString().equals(uuid)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw c.error("listing.hidden-players", "canonical UUID strings");
            }
        }
        if (core.hiddenPlayers().size() > 1000) {
            throw c.error(
                    "listing.hidden-players",
                    "at most 1000 explicit UUID exclusions; use the permission for larger sets");
        }
        if (!Set.of(
                        "database",
                        "luckperms",
                        "commands",
                        "vouchers",
                        "notifications",
                        "discord",
                        "placeholders")
                .containsAll(core.debug())) {
            throw c.error("debug", "known debug category names");
        }
        if (!Set.of("VIP_GRANTED", "VIP_EXTENDED", "VIP_VOUCHER_REDEEMED", "VIP_EXPIRED")
                .containsAll(core.broadcasts())) {
            throw c.error(
                    "broadcast.events",
                    "VIP_GRANTED, VIP_EXTENDED, VIP_VOUCHER_REDEEMED or VIP_EXPIRED");
        }
        return core;
    }

    private Database database(YamlFile d) {
        String engine = d.text("engine");
        if (!Set.of("SQLITE", "MYSQL", "MARIADB").contains(engine)) {
            throw d.error("engine", "SQLITE, MYSQL or MARIADB");
        }
        String dbName = identifier(d, "mysql.database");
        if (!d.text("mysql.host").matches("[a-zA-Z0-9.-]+|\\[[a-fA-F0-9:]+\\]")) {
            throw d.error("mysql.host", "hostname, IPv4 address or bracketed IPv6 address");
        }
        String sqliteFile = d.text("sqlite.file");
        if (!sqliteFile.matches("[a-zA-Z0-9_-]+\\.db")) {
            throw d.error("sqlite.file", "a simple .db filename");
        }
        return new Database(
                engine,
                d.text("mysql.host"),
                d.number("mysql.port", 1, 65535),
                dbName,
                d.text("mysql.username"),
                d.optional("mysql.password"),
                sqliteFile,
                d.number("pool-size", 1, 16),
                d.number("timeout-seconds", 1, 30),
                d.bool("mysql.tls"));
    }

    private Discovery discovery(YamlFile typesFile) {
        var discovery =
                new Discovery(
                        typesFile.bool("sync.enabled"),
                        typesFile.strings("sync.include"),
                        Set.copyOf(typesFile.strings("sync.exclude")),
                        typesFile.optional("sync.track"),
                        typesFile.number("sync.refresh-seconds", 10, 3600),
                        typesFile.text("sync.display-format"),
                        typesFile.number("sync.default-priority", 0, 100000));
        for (String pattern : discovery.include()) {
            if (!pattern.matches("[a-z0-9_*?-]{1,64}")) {
                throw typesFile.error("sync.include", "lowercase group globs using * and ?");
            }
        }
        return discovery;
    }

    private Map<String, VipType> types(YamlFile typesFile, Discovery discovery) {
        Map<String, VipType> types = new LinkedHashMap<>();
        for (String id : typesFile.keys("vip-types")) {
            if (!id.matches("[a-z0-9_-]{1,48}")) {
                throw typesFile.error("vip-types." + id, "lowercase identifier, max 48 characters");
            }
            String base = "vip-types." + id;
            types.put(
                    id,
                    new VipType(
                            id,
                            typesFile.text(base + ".display-name"),
                            identifier(typesFile, base + ".luckperms-group"),
                            typesFile.number(base + ".priority", 0, 100000)));
        }
        if (types.isEmpty() && !discovery.enabled()) {
            throw typesFile.error("vip-types", "at least one VIP type or enabled LuckPerms sync");
        }
        return Map.copyOf(types);
    }

    private Map<String, Command> commands(YamlFile commandFile) {
        Map<String, Command> commands = new LinkedHashMap<>();
        Map<String, Set<String>> scopes = new HashMap<>();
        for (String id : COMMAND_IDS.stream().sorted().toList()) {
            String base = id.replace('.', '-') + ".";
            String name = identifier(commandFile, base + "name");
            List<String> aliases = commandFile.strings(base + "aliases");
            String scope = id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : "root";
            var used = scopes.computeIfAbsent(scope, ignored -> new HashSet<>());
            for (String literal :
                    java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(name), aliases.stream())
                            .toList()) {
                if (!literal.matches("[a-z0-9_-]{1,48}") || !used.add(literal)) {
                    throw commandFile.error(
                            base + "aliases", "unique lowercase labels within " + scope);
                }
            }
            commands.put(
                    id,
                    new Command(
                            name,
                            aliases,
                            commandFile.text(base + "permission"),
                            commandFile.text(base + "description")));
        }
        return Map.copyOf(commands);
    }

    private Voucher voucher(YamlFile c) {
        List<ItemFlag> flags = new ArrayList<>();
        for (String flag : c.strings("voucher.item.flags")) {
            try {
                flags.add(ItemFlag.valueOf(flag));
            } catch (IllegalArgumentException e) {
                throw c.error("voucher.item.flags", "valid ItemFlag names");
            }
        }
        List<Float> model =
                c.strings("voucher.item.model-data").stream()
                        .map(
                                value -> {
                                    try {
                                        float f = Float.parseFloat(value);
                                        if (!Float.isFinite(f)) {
                                            throw new NumberFormatException();
                                        }
                                        return f;
                                    } catch (NumberFormatException e) {
                                        throw c.error(
                                                "voucher.item.model-data",
                                                "finite numeric strings");
                                    }
                                })
                        .toList();
        String activation = c.text("voucher.activation");
        if (!Set.of("RIGHT_CLICK", "LEFT_CLICK").contains(activation)) {
            throw c.error("voucher.activation", "RIGHT_CLICK or LEFT_CLICK");
        }
        var voucher =
                new Voucher(
                        c.bool("voucher.enabled"),
                        c.choice("voucher.ownership", Ownership.class),
                        material(c, "voucher.item.material"),
                        c.bool("voucher.item.glow"),
                        List.copyOf(flags),
                        model,
                        c.number("voucher.maximum-amount", 1, 64),
                        c.number("voucher.freeze.minimum-seconds", 1, Integer.MAX_VALUE),
                        c.number("voucher.freeze.maximum-seconds", 1, Integer.MAX_VALUE),
                        c.number("voucher.freeze.cooldown-seconds", 0, Integer.MAX_VALUE),
                        c.text("voucher.freeze.bypass-permission"),
                        c.bool("voucher.freeze.persist-cooldown"),
                        Set.copyOf(c.strings("voucher.freeze.disabled-worlds")),
                        c.choice("voucher.inventory-full", FullInventory.class),
                        c.bool("voucher.allow-drops"),
                        c.number("voucher.debounce-millis", 100, 10000),
                        activation);
        if (voucher.minFreeze() > voucher.maxFreeze()) {
            throw c.error("voucher.freeze.maximum-seconds", ">= minimum-seconds");
        }
        if (voucher.full() == FullInventory.DROP && !voucher.allowDrops()) {
            throw c.error("voucher.inventory-full", "DEFER or REJECT when drops are disabled");
        }
        return voucher;
    }

    private Notifications notifications(YamlFile n) {
        List<Threshold> thresholds = new ArrayList<>();
        Set<Long> thresholdValues = new HashSet<>();
        for (String id : n.keys("thresholds")) {
            long seconds = n.number("thresholds." + id + ".seconds", 1, Integer.MAX_VALUE);
            if (!id.matches("[a-z0-9_-]{1,48}") || !thresholdValues.add(seconds)) {
                throw n.error("thresholds." + id, "unique threshold and lowercase ID");
            }
            Set<Channel> channels = new HashSet<>();
            for (String channel : n.strings("thresholds." + id + ".channels")) {
                try {
                    channels.add(Channel.valueOf(channel));
                } catch (IllegalArgumentException e) {
                    throw n.error(
                            "thresholds." + id + ".channels",
                            "CHAT, ACTIONBAR, TITLE, BOSSBAR, GUI, SOUND");
                }
            }
            thresholds.add(new Threshold(id, seconds, Set.copyOf(channels)));
        }
        thresholds.sort(Comparator.comparingLong(Threshold::seconds));
        if (!n.text("sound.key").matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw n.error("sound.key", "namespaced sound key");
        }
        return new Notifications(
                n.bool("enabled"),
                List.copyOf(thresholds),
                n.choice("login-mode", LoginMode.class),
                n.number("visible-seconds", 1, 60),
                n.text("sound.key"),
                n.number("sound.volume-percent", 0, 200) / 100f,
                n.number("sound.pitch-percent", 50, 200) / 100f,
                n.bool("expiration-message"),
                n.bool("login-summary"));
    }

    private Discord discord(YamlFile discordFile) {
        String webhook = discordFile.optional("webhook-url");
        boolean discordEnabled = discordFile.bool("enabled");
        if (discordEnabled) {
            URI uri = safeUrl(discordFile, "webhook-url", webhook);
            if (!Set.of("discord.com", "discordapp.com").contains(uri.getHost())
                    || !uri.getPath().startsWith("/api/webhooks/")) {
                throw discordFile.error("webhook-url", "official Discord HTTPS webhook URL");
            }
        }
        String avatar = discordFile.optional("avatar-url");
        if (!avatar.isEmpty()) {
            safeUrl(discordFile, "avatar-url", avatar);
        }
        Map<String, DiscordEmbed> embeds = new LinkedHashMap<>();
        for (String event : discordFile.keys("templates")) {
            String base = "templates." + event + ".";
            var fields = new ArrayList<DiscordField>();
            for (String field : discordFile.keys(base + "fields")) {
                String f = base + "fields." + field + ".";
                fields.add(
                        new DiscordField(
                                discordFile.text(f + "name"),
                                discordFile.text(f + "value"),
                                discordFile.bool(f + "inline")));
            }
            if (fields.size() > 8) {
                throw discordFile.error(base + "fields", "at most 8 embed fields");
            }
            embeds.put(
                    event,
                    new DiscordEmbed(
                            discordFile.text(base + "title"),
                            discordFile.text(base + "description"),
                            discordFile.number(base + "color", 0, 16777215),
                            List.copyOf(fields)));
        }
        return new Discord(
                discordEnabled,
                webhook,
                discordFile.text("username"),
                avatar,
                discordFile.number("embed-color", 0, 16777215),
                discordFile.bool("timestamps"),
                discordFile.text("footer"),
                Set.copyOf(discordFile.strings("events")),
                discordFile.number("timeout-seconds", 1, 30),
                discordFile.number("maximum-attempts", 1, 5),
                Map.copyOf(embeds));
    }

    private Gui gui(YamlFile g) {
        int size = g.number("size", 9, 54);
        if (size % 9 != 0) {
            throw g.error("size", "multiple of 9");
        }
        List<MenuItem> items = new ArrayList<>();
        Set<Integer> slots = new HashSet<>();
        for (String id : g.keys("items")) {
            String base = "items." + id + ".";
            int slot = g.number(base + "slot", 0, size - 1);
            if (!slots.add(slot)) {
                throw g.error(base + "slot", "unique slot");
            }
            Action action = g.choice(base + "action", Action.class);
            String value = g.optional(base + "value");
            if (action == Action.OPEN_URL) {
                safeUrl(g, base + "value", value);
            }
            if ((action == Action.PLAYER_COMMAND || action == Action.CONSOLE_COMMAND)
                    && (value.isBlank() || value.contains("\n") || value.contains("\r"))) {
                throw g.error(base + "value", "single nonempty command");
            }
            items.add(
                    new MenuItem(
                            slot,
                            material(g, base + "material"),
                            g.text(base + "name-key"),
                            g.text(base + "lore-key"),
                            action,
                            value));
        }
        return new Gui(
                g.bool("enabled"),
                size,
                g.text("title-key"),
                g.bool("open-on-login"),
                g.bool("repeat-on-login"),
                g.bool("close-after-action"),
                List.copyOf(items));
    }

    private Menus menus(YamlFile m) {
        return new Menus(
                m.bool("personal.enabled"),
                m.bool("vip-list.enabled"),
                m.bool("leaderboard.enabled"),
                m.number("browsing.page-size", 1, 28),
                m.bool("skins.enabled"),
                m.number("skins.cache-minutes", 1, 1440),
                m.bool("personal.defaults.bossbar"),
                m.bool("personal.defaults.actionbar"),
                m.bool("personal.defaults.title"),
                material(m, "appearance.filler"),
                material(m, "appearance.bossbar"),
                material(m, "appearance.actionbar"),
                material(m, "appearance.title"));
    }

    private Display display(YamlFile c) {
        ZoneId zone;
        DateTimeFormatter dates;
        try {
            zone = ZoneId.of(c.text("display.timezone"));
        } catch (DateTimeException e) {
            throw c.error("display.timezone", "IANA timezone, e.g. Europe/Istanbul");
        }
        String pattern = c.text("display.date-pattern");
        try {
            dates = DateTimeFormatter.ofPattern(pattern).withZone(zone);
            dates.format(java.time.Instant.EPOCH);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw c.error("display.date-pattern", "valid date/time pattern");
        }
        Map<String, String> locales = new HashMap<>();
        for (String key : c.keys("language.locale-mapping")) {
            locales.put(
                    key.toLowerCase(Locale.ROOT).replace('-', '_'),
                    c.text("language.locale-mapping." + key));
        }
        String style = c.text("display.duration-style");
        if (!Set.of("short", "long").contains(style)) {
            throw c.error("display.duration-style", "short or long");
        }
        Click action = c.choice("renewal.action", Click.class);
        String value = c.text("renewal.value");
        if (action == Click.OPEN_URL) {
            safeUrl(c, "renewal.value", value);
        } else if (!value.startsWith("/") || value.contains("\n") || value.contains("\r")) {
            throw c.error("renewal.value", "single command beginning with /");
        }
        return new Display(
                c.text("language.default"),
                c.text("language.fallback"),
                c.bool("language.automatic-client-locale"),
                Map.copyOf(locales),
                zone,
                dates,
                pattern,
                style,
                c.number("display.duration-units", 1, 4),
                c.number("display.progress.length", 1, 100),
                c.text("display.progress.filled"),
                c.text("display.progress.empty"),
                c.number("display.progress.precision", 0, 3),
                c.choice("display.progress.source", ProgressSource.class),
                c.bool("display.bossbar.enabled"),
                c.bool("display.actionbar.enabled"),
                c.number("display.refresh-seconds", 1, 300),
                c.choice("display.bossbar.color", BossBar.Color.class),
                c.choice("display.bossbar.overlay", BossBar.Overlay.class),
                Set.copyOf(c.strings("display.bossbar.worlds")),
                c.bool("display.bossbar.hide-when-no-vip"),
                action,
                value);
    }

    private YamlFile file(String name) throws IOException {
        return new YamlFile(directory.resolve(name));
    }

    private static String identifier(YamlFile file, String path) {
        String value = file.text(path);
        if (!value.matches("[a-z0-9_-]{1,48}")) {
            throw file.error(path, "lowercase identifier, 1-48 characters");
        }
        return value;
    }

    private Material material(YamlFile file, String path) {
        Material value = Material.matchMaterial(file.text(path));
        if (value == null
                || value == Material.AIR
                || value == Material.CAVE_AIR
                || value == Material.VOID_AIR
                || !itemMaterial.test(value)) {
            throw file.error(path, "valid non-air item Material");
        }
        return value;
    }

    private static URI safeUrl(YamlFile file, String path, String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw file.error(path, "HTTPS URL without credentials or custom port");
        }
    }
}
