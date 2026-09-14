package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.ConfigError;
import com.wellsetups.wellviptime.configuration.ConfigLoader;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.LuckPermsSync;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class LuckPermsGroupsTest {

    @TempDir Path directory;

    @Test
    void missingDefaultsAreCreatedAndRepeatedValidationDoesNotMutateThem() throws Exception {
        var settings = settings();
        var fixture = new Groups();
        assertEquals(
                Set.of("vip", "vipplus", "mvip"),
                Set.copyOf(LuckPermsSync.validateGroups(fixture.api(), settings)));
        assertEquals(3, fixture.created.size());
        fixture.created.clear();
        assertTrue(LuckPermsSync.validateGroups(fixture.api(), settings).isEmpty());
        assertTrue(fixture.created.isEmpty());
    }

    @Test
    void loadedAndStoredGroupsArePreserved() throws Exception {
        var fixture = new Groups();
        fixture.loaded.put("vip", group());
        fixture.stored.put("vipplus", group());
        fixture.stored.put("mvip", group());
        var existing = fixture.stored.get("vipplus");
        assertTrue(LuckPermsSync.validateGroups(fixture.api(), settings()).isEmpty());
        assertTrue(fixture.created.isEmpty());
        assertSame(existing, fixture.loaded.get("vipplus"));
    }

    @Test
    void strictModeNamesTheMappingAndExplainsTheFix() throws Exception {
        settings();
        Path config = directory.resolve("config.yml");
        Files.writeString(
                config,
                Files.readString(config)
                        .replace("create-missing-groups: true", "create-missing-groups: false"));
        var fixture = new Groups();
        var error =
                assertThrows(
                        ConfigError.class,
                        () -> LuckPermsSync.validateGroups(fixture.api(), settings()));
        assertTrue(error.getMessage().contains("luckperms-group"));
        assertTrue(error.getMessage().contains("/lp creategroup"));
        assertTrue(fixture.created.isEmpty());
    }

    @Test
    void loadFailureIsNotMistakenForAMissingGroup() throws Exception {
        var fixture = new Groups();
        fixture.failLoad = true;
        var error =
                assertThrows(
                        SQLException.class,
                        () -> LuckPermsSync.validateGroups(fixture.api(), settings()));
        assertTrue(error.getMessage().contains("Could not validate LuckPerms group"));
        assertTrue(fixture.created.isEmpty());
    }

    @Test
    void creationFailureStopsInitialization() throws Exception {
        var fixture = new Groups();
        fixture.failCreate = true;
        assertThrows(
                SQLException.class, () -> LuckPermsSync.validateGroups(fixture.api(), settings()));
        assertTrue(fixture.loaded.isEmpty());
    }

    @Test
    void sharedMappingsAreProvisionedOnce() throws Exception {
        settings();
        Path types = directory.resolve("vip-types.yml");
        Files.writeString(
                types,
                Files.readString(types)
                        .replace("luckperms-group: vipplus", "luckperms-group: vip")
                        .replace("luckperms-group: mvip", "luckperms-group: vip"));
        var fixture = new Groups();
        assertEquals(List.of("vip"), LuckPermsSync.validateGroups(fixture.api(), settings()));
        assertEquals(List.of("vip"), fixture.created);
    }

    private Settings settings() throws Exception {
        return new ConfigLoader(directory, ignored -> {}, material -> true).load().settings();
    }

    private static Group group() {
        return proxy(
                Group.class,
                (instance, method, args) -> {
                    throw new AssertionError(
                            "Existing group data must not be accessed or changed: "
                                    + method.getName());
                });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static final class Groups {

        final Map<String, Group> loaded = new HashMap<>();

        final Map<String, Group> stored = new HashMap<>();

        final List<String> created = new ArrayList<>();

        boolean failLoad;

        boolean failCreate;

        LuckPerms api() {
            var manager =
                    proxy(
                            GroupManager.class,
                            (instance, method, args) -> {
                                String name = (String) args[0];
                                return switch (method.getName()) {
                                    case "getGroup" -> loaded.get(name);
                                    case "loadGroup" -> {
                                        if (failLoad) {
                                            yield CompletableFuture.failedFuture(
                                                    new IllegalStateException(
                                                            "LP storage unavailable"));
                                        }
                                        if (stored.containsKey(name)) {
                                            loaded.put(name, stored.get(name));
                                        }
                                        yield CompletableFuture.completedFuture(
                                                Optional.ofNullable(loaded.get(name)));
                                    }
                                    case "createAndLoadGroup" -> {
                                        if (failCreate) {
                                            yield CompletableFuture.failedFuture(
                                                    new IllegalStateException(
                                                            "LP storage write failed"));
                                        }
                                        created.add(name);
                                        var result =
                                                stored.computeIfAbsent(name, ignored -> group());
                                        loaded.put(name, result);
                                        yield CompletableFuture.completedFuture(result);
                                    }
                                    default ->
                                            throw new AssertionError(
                                                    "Unexpected LuckPerms mutation: "
                                                            + method.getName());
                                };
                            });
            return proxy(
                    LuckPerms.class,
                    (instance, method, args) -> {
                        if (method.getName().equals("getGroupManager")) {
                            return manager;
                        }
                        throw new AssertionError("Unexpected LuckPerms call: " + method.getName());
                    });
        }
    }
}
