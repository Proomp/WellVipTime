package com.wellsetups.wellviptime.configuration;

import net.kyori.adventure.bossbar.BossBar;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record Settings(
        Core core,
        Database database,
        Map<String, Command> commands,
        Map<String, VipType> types,
        Voucher voucher,
        Notifications notifications,
        Display display,
        Discord discord,
        Gui gui,
        Discovery discovery,
        Menus menus) {

    public Settings {
        commands = Map.copyOf(commands);
        types = Map.copyOf(types);
    }

    public Settings withTypes(Map<String, VipType> next) {
        return new Settings(
                core,
                database,
                commands,
                Map.copyOf(next),
                voucher,
                notifications,
                display,
                discord,
                gui,
                discovery,
                menus);
    }

    public record Discovery(
            boolean enabled,
            List<String> include,
            Set<String> exclude,
            String track,
            int refreshSeconds,
            String displayFormat,
            int defaultPriority) {

        public Discovery {
            include = List.copyOf(include);
            exclude = Set.copyOf(exclude);
        }
    }

    public record Menus(
            boolean personal,
            boolean list,
            boolean leaderboard,
            int pageSize,
            boolean skins,
            int skinCacheMinutes,
            boolean defaultBossbar,
            boolean defaultActionbar,
            boolean defaultTitle,
            Material filler,
            Material bossbar,
            Material actionbar,
            Material title) {}

    public enum SamePolicy {
        EXTEND,
        REPLACE,
        REJECT
    }

    public enum DifferentPolicy {
        PARALLEL,
        REPLACE,
        REJECT,
        PRIORITY_BASED
    }

    public enum RemovePolicy {
        CLAMP,
        REJECT
    }

    public enum Ranking {
        REMAINING,
        HISTORICAL,
        EXPIRATION
    }

    public enum ListSort {
        EXPIRATION,
        NAME,
        TYPE
    }

    public enum ProgressSource {
        REMAINING,
        ELAPSED
    }

    public enum Ownership {
        OWNER_ONLY,
        TRANSFERABLE
    }

    public enum FullInventory {
        DEFER,
        DROP,
        REJECT
    }

    public enum LoginMode {
        MISSED,
        NEAREST,
        IGNORE
    }

    public enum Channel {
        CHAT,
        ACTIONBAR,
        TITLE,
        BOSSBAR,
        GUI,
        SOUND
    }

    public enum Action {
        CLOSE,
        PLAYER_COMMAND,
        CONSOLE_COMMAND,
        OPEN_URL
    }

    public enum Click {
        RUN_COMMAND,
        SUGGEST_COMMAND,
        OPEN_URL
    }

    public record Core(
            String networkId,
            String serverId,
            int workers,
            int queueSize,
            int shutdownSeconds,
            int checkSeconds,
            int batchSize,
            int maxDays,
            boolean multiple,
            SamePolicy same,
            DifferentPolicy different,
            RemovePolicy remove,
            int pageSize,
            int maxRanking,
            Ranking ranking,
            ListSort listSort,
            int cacheSeconds,
            String hiddenPermission,
            Set<String> hiddenPlayers,
            boolean reconcileStartup,
            boolean createMissingGroups,
            Set<String> debug,
            Set<String> broadcasts,
            String broadcastPermission) {

        public Core {
            hiddenPlayers = Set.copyOf(hiddenPlayers);
            debug = Set.copyOf(debug);
            broadcasts = Set.copyOf(broadcasts);
        }

        public Core withRuntimeFrom(Core active) {
            return new Core(
                    active.networkId,
                    active.serverId,
                    active.workers,
                    active.queueSize,
                    active.shutdownSeconds,
                    checkSeconds,
                    batchSize,
                    maxDays,
                    multiple,
                    same,
                    different,
                    remove,
                    pageSize,
                    maxRanking,
                    ranking,
                    listSort,
                    cacheSeconds,
                    hiddenPermission,
                    hiddenPlayers,
                    active.reconcileStartup,
                    createMissingGroups,
                    debug,
                    broadcasts,
                    broadcastPermission);
        }
    }

    public record Database(
            String engine,
            String host,
            int port,
            String name,
            String username,
            String password,
            String sqliteFile,
            int poolSize,
            int timeoutSeconds,
            boolean tls) {

        @Override
        public String toString() {
            return "Database[engine=" + engine + ", credentials=<redacted>]";
        }
    }

    public record Command(
            String name, List<String> aliases, String permission, String description) {

        public Command {
            aliases = List.copyOf(aliases);
        }
    }

    public record VipType(String id, String displayName, String group, int priority) {}

    public record Voucher(
            boolean enabled,
            Ownership ownership,
            Material material,
            boolean glow,
            List<ItemFlag> flags,
            List<Float> modelData,
            int maxAmount,
            long minFreeze,
            long maxFreeze,
            long cooldown,
            String bypass,
            boolean persistentCooldown,
            Set<String> disabledWorlds,
            FullInventory full,
            boolean allowDrops,
            int debounceMillis,
            String activation) {

        public Voucher {
            flags = List.copyOf(flags);
            modelData = List.copyOf(modelData);
            disabledWorlds = Set.copyOf(disabledWorlds);
        }
    }

    public record Threshold(String id, long seconds, Set<Channel> channels) {

        public Threshold {
            channels = Set.copyOf(channels);
        }
    }

    public record Notifications(
            boolean enabled,
            List<Threshold> thresholds,
            LoginMode login,
            int visibleSeconds,
            String sound,
            float volume,
            float pitch,
            boolean expirationMessage,
            boolean loginSummary) {

        public Notifications {
            thresholds = List.copyOf(thresholds);
        }
    }

    public record Display(
            String defaultLanguage,
            String fallbackLanguage,
            boolean autoLocale,
            Map<String, String> locales,
            ZoneId zone,
            DateTimeFormatter dates,
            String datePattern,
            String durationStyle,
            int durationUnits,
            int barLength,
            String filled,
            String empty,
            int precision,
            ProgressSource progressSource,
            boolean bossbar,
            boolean actionbar,
            int refreshSeconds,
            BossBar.Color color,
            BossBar.Overlay overlay,
            Set<String> worlds,
            boolean hideNoVip,
            Click renewalAction,
            String renewalValue) {

        public Display {
            locales = Map.copyOf(locales);
            worlds = Set.copyOf(worlds);
        }
    }

    public record DiscordField(String name, String value, boolean inline) {}

    public record DiscordEmbed(
            String title, String description, int color, List<DiscordField> fields) {

        public DiscordEmbed {
            fields = List.copyOf(fields);
        }
    }

    public record Discord(
            boolean enabled,
            String url,
            String username,
            String avatar,
            int color,
            boolean timestamp,
            String footer,
            Set<String> events,
            int timeoutSeconds,
            int maxAttempts,
            Map<String, DiscordEmbed> templates) {

        public Discord {
            events = Set.copyOf(events);
            templates = Map.copyOf(templates);
        }

        @Override
        public String toString() {
            return "Discord[enabled=" + enabled + ", url=<redacted>]";
        }
    }

    public record MenuItem(
            int slot,
            Material material,
            String nameKey,
            String loreKey,
            Action action,
            String value) {}

    public record Gui(
            boolean enabled,
            int size,
            String titleKey,
            boolean login,
            boolean repeatLogin,
            boolean closeAfterAction,
            List<MenuItem> items) {

        public Gui {
            items = List.copyOf(items);
        }
    }
}
