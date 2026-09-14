package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.integration.VipTypeDiscovery;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.*;
import net.luckperms.api.track.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class DiscoveryTest {

    @TempDir Path directory;

    private Settings settings() throws Exception {
        return new ConfigLoader(directory, ignored -> {}, m -> true).load().settings();
    }

    @Test
    void importsSixtyRanksWithoutManualEntriesAndPreservesOverrides() throws Exception {
        var settings = settings();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (int i = 1; i <= 60; i++) {
            groups.put("vip" + i, i);
        }
        groups.put("vipplus", 999);
        groups.put("admin", 999);
        var discovery = new VipTypeDiscovery(api(groups, null, false), directory);
        var result = discovery.resolve(settings, Map.of());
        assertEquals(63, result.types().size());
        assertEquals(60, result.types().get("vip60").priority());
        assertFalse(result.types().containsKey("admin"));
        assertFalse(result.types().containsKey("vipplus"));
        assertEquals(settings.types().get("vip-plus"), result.types().get("vip-plus"));
        assertTrue(
                Files.readString(directory.resolve("vip-types.generated.yml")).contains("vip60"));
        assertEquals(result.types(), discovery.resolve(settings, result.types()).types());
        // Saved IDs survive restart and group deletion so existing vouchers still have a
        // definition.
        assertTrue(
                new VipTypeDiscovery(api(Map.of(), null, false), directory)
                        .resolve(settings, Map.of())
                        .types()
                        .containsKey("vip60"));
    }

    @Test
    void namedTrackSupportsUnrelatedRankNamesAndStillExcludesStaff() throws Exception {
        settings();
        var file = directory.resolve("vip-types.yml");
        Files.writeString(file, Files.readString(file).replace("track: ''", "track: donors"));
        var result =
                new VipTypeDiscovery(
                                api(
                                        Map.of("bronze", 5, "silver", 10, "admin", 100),
                                        List.of("bronze", "silver", "admin"),
                                        false),
                                directory)
                        .resolve(settings(), Map.of());
        assertTrue(result.types().containsKey("bronze"));
        assertTrue(result.types().containsKey("silver"));
        assertFalse(result.types().containsKey("admin"));
    }

    @Test
    void discoveryFailureDoesNotReplaceGeneratedFile() throws Exception {
        var settings = settings();
        new VipTypeDiscovery(api(Map.of("vip1", 1), null, false), directory)
                .resolve(settings, Map.of());
        var before = Files.readString(directory.resolve("vip-types.generated.yml"));
        assertThrows(
                java.io.IOException.class,
                () ->
                        new VipTypeDiscovery(api(Map.of(), null, true), directory)
                                .resolve(settings, Map.of()));
        assertEquals(before, Files.readString(directory.resolve("vip-types.generated.yml")));
    }

    @Test
    void matchingAndEmptyOverridesAreValidated() throws Exception {
        var settings = settings();
        assertTrue(VipTypeDiscovery.matches("supervip", settings.discovery(), null));
        assertFalse(VipTypeDiscovery.matches("moderator", settings.discovery(), null));
        assertFalse(VipTypeDiscovery.matches("vip.bad", settings.discovery(), null));
        var file = directory.resolve("vip-types.yml");
        String original = Files.readString(file);
        Files.writeString(
                file,
                original.substring(0, original.indexOf("\nvip-types:") + 1) + "vip-types: {}\n");
        assertTrue(settings().types().isEmpty());
        Files.writeString(file, Files.readString(file).replace("enabled: true", "enabled: false"));
        assertThrows(ConfigError.class, this::settings);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static LuckPerms api(
            Map<String, Integer> definitions, List<String> trackGroups, boolean fail) {
        Set<Group> groups = new HashSet<>();
        definitions.forEach(
                (name, weight) ->
                        groups.add(
                                proxy(
                                        Group.class,
                                        (self, method, args) ->
                                                switch (method.getName()) {
                                                    case "getName" -> name;
                                                    case "getWeight" -> OptionalInt.of(weight);
                                                    case "hashCode" ->
                                                            System.identityHashCode(self);
                                                    case "equals" -> self == args[0];
                                                    default ->
                                                            throw new AssertionError(
                                                                    "Unexpected group mutation: "
                                                                            + method);
                                                })));
        var manager =
                proxy(
                        GroupManager.class,
                        (self, method, args) ->
                                switch (method.getName()) {
                                    case "loadAllGroups" ->
                                            fail
                                                    ? CompletableFuture.failedFuture(
                                                            new IllegalStateException(
                                                                    "Storage unavailable"))
                                                    : CompletableFuture.completedFuture(null);
                                    case "getLoadedGroups" -> groups;
                                    default ->
                                            throw new AssertionError(
                                                    "Unexpected group call: " + method);
                                });
        var track =
                proxy(
                        Track.class,
                        (self, method, args) -> {
                            if (method.getName().equals("getGroups")) {
                                return trackGroups;
                            }
                            throw new AssertionError(method);
                        });
        var tracks =
                proxy(
                        TrackManager.class,
                        (self, method, args) -> {
                            if (method.getName().equals("loadTrack")) {
                                return CompletableFuture.completedFuture(
                                        trackGroups == null
                                                ? Optional.empty()
                                                : Optional.of(track));
                            }
                            throw new AssertionError(method);
                        });
        return proxy(
                LuckPerms.class,
                (self, method, args) ->
                        switch (method.getName()) {
                            case "getGroupManager" -> manager;
                            case "getTrackManager" -> tracks;
                            default -> throw new AssertionError(method);
                        });
    }
}
