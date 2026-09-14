package com.wellsetups.wellviptime.integration;

import com.wellsetups.wellviptime.configuration.ConfigError;
import com.wellsetups.wellviptime.configuration.Settings;

import net.luckperms.api.LuckPerms;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Additive discovery: manual mappings win and retired IDs remain available for existing balances.
 */
public final class VipTypeDiscovery {

    private static final Pattern GROUP_ID = Pattern.compile("[a-z0-9_-]{1,48}");

    private final LuckPerms luckPerms;

    private final Path generated;

    public VipTypeDiscovery(LuckPerms luckPerms, Path directory) {
        this.luckPerms = luckPerms;
        generated = directory.resolve("vip-types.generated.yml");
    }

    public Settings resolve(Settings source, Map<String, Settings.VipType> previous)
            throws java.io.IOException {
        if (!source.discovery().enabled()) {
            return source;
        }
        int timeout = source.database().timeoutSeconds();
        await(luckPerms.getGroupManager().loadAllGroups(), timeout);
        Set<String> track = null;
        if (!source.discovery().track().isBlank()) {
            var loaded =
                    await(
                            luckPerms.getTrackManager().loadTrack(source.discovery().track()),
                            timeout);
            if (loaded.isEmpty()) {
                throw new ConfigError(
                        "vip-types.yml",
                        "sync.track",
                        source.discovery().track(),
                        "existing LuckPerms track");
            }
            track = Set.copyOf(loaded.get().getGroups());
        }
        Map<String, Settings.VipType> result = new LinkedHashMap<>();
        result.putAll(loadGenerated());
        result.putAll(previous);
        Set<String> manualGroups = new HashSet<>();
        source.types().values().forEach(type -> manualGroups.add(type.group()));
        var selected = matchingGroups(source.discovery(), track);
        for (var group : luckPerms.getGroupManager().getLoadedGroups()) {
            String name = group.getName();
            if (!selected.test(name)
                    || manualGroups.contains(name)
                    || source.types().containsKey(name)) {
                continue;
            }
            int weight =
                    Platform.clamp(
                            group.getWeight().orElse(source.discovery().defaultPriority()),
                            0,
                            100000);
            result.put(
                    name,
                    new Settings.VipType(
                            name,
                            source.discovery().displayFormat().replace("<group>", name),
                            name,
                            weight));
        }
        // Prevent an auto-generated ID from duplicating a group explicitly given another ID.
        result.entrySet()
                .removeIf(
                        entry ->
                                !source.types().containsKey(entry.getKey())
                                        && manualGroups.contains(entry.getValue().group()));
        result.putAll(source.types());
        saveGenerated(result);
        return source.withTypes(result);
    }

    private Map<String, Settings.VipType> loadGenerated() throws java.io.IOException {
        Map<String, Settings.VipType> result = new LinkedHashMap<>();
        if (!Files.exists(generated)) {
            return result;
        }
        var yaml = new YamlConfiguration();
        try {
            yaml.load(generated.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new java.io.IOException(
                    "Invalid generated VIP YAML; restore its backup", failure);
        }
        var section = yaml.getConfigurationSection("vip-types");
        if (section == null) {
            return result;
        }
        for (String id : section.getKeys(false)) {
            String group = section.getString(id + ".luckperms-group", "");
            String display = section.getString(id + ".display-name", "");
            int priority = section.getInt(id + ".priority", -1);
            if (!GROUP_ID.matcher(id).matches()
                    || !GROUP_ID.matcher(group).matches()
                    || display.isBlank()
                    || priority < 0
                    || priority > 100000) {
                throw new ConfigError(
                        generated.getFileName().toString(),
                        "vip-types." + id,
                        "invalid",
                        "valid generated type definition");
            }
            result.put(id, new Settings.VipType(id, display, group, priority));
        }
        return result;
    }

    private void saveGenerated(Map<String, Settings.VipType> result) throws java.io.IOException {
        var yaml = new YamlConfiguration();
        yaml.options()
                .setHeader(
                        List.of(
                                "Generated by WellVipTime. Manual overrides belong in"
                                        + " vip-types.yml.",
                                "Discovery adds and updates ranks. Old IDs are retained to protect"
                                        + " balances and vouchers.",
                                "To retire an ID, first remove its active balances/vouchers, then"
                                        + " stop the server and remove it here."));
        result.values().stream()
                .sorted(Comparator.comparing(Settings.VipType::id))
                .forEach(
                        type -> {
                            String path = "vip-types." + type.id();
                            yaml.set(path + ".display-name", type.displayName());
                            yaml.set(path + ".luckperms-group", type.group());
                            yaml.set(path + ".priority", type.priority());
                            yaml.setComments(
                                    path,
                                    List.of(
                                            "Stable VIP type ID. Override this definition in"
                                                    + " vip-types.yml; discovery preserves retired"
                                                    + " IDs."));
                            yaml.setComments(
                                    path + ".display-name",
                                    List.of(
                                            "MiniMessage label generated from sync.display-format"
                                                    + " or a manual override."));
                            yaml.setComments(
                                    path + ".luckperms-group",
                                    List.of(
                                            "LuckPerms group receiving this type's timed"
                                                    + " inheritance."));
                            yaml.setComments(
                                    path + ".priority",
                                    List.of(
                                            "Policy priority, 0-100000; from group weight or a"
                                                    + " manual override."));
                        });
        String content = yaml.saveToString();
        if (!Files.exists(generated) || !Files.readString(generated).equals(content)) {
            Path temporary = Files.createTempFile(generated.getParent(), "vip-types-", ".tmp");
            try {
                Files.writeString(temporary, content);
                try {
                    Files.move(
                            temporary,
                            generated,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, generated, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static boolean matches(String group, Settings.Discovery discovery, Set<String> track) {
        return matchingGroups(discovery, track).test(group);
    }

    private static java.util.function.Predicate<String> matchingGroups(
            Settings.Discovery discovery, Set<String> track) {
        var patterns =
                discovery.include().stream()
                        .map(
                                glob ->
                                        Pattern.compile(
                                                "\\Q"
                                                        + glob.replace("*", "\\E.*\\Q")
                                                                .replace("?", "\\E.\\Q")
                                                        + "\\E"))
                        .toList();
        return group -> {
            if (!GROUP_ID.matcher(group).matches() || discovery.exclude().contains(group)) {
                return false;
            }
            if (track != null) {
                return track.contains(group);
            }
            return patterns.stream().anyMatch(pattern -> pattern.matcher(group).matches());
        };
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future, int timeout)
            throws java.io.IOException {
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("LuckPerms discovery interrupted", e);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new java.io.IOException("LuckPerms discovery could not load groups/track", e);
        }
    }
}
