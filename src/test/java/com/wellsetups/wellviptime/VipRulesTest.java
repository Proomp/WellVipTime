package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.*;
import com.wellsetups.wellviptime.vip.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class VipRulesTest {

    @TempDir Path directory;

    private final UUID player = UUID.randomUUID();

    private Settings settings() throws Exception {
        return new ConfigLoader(directory, ignored -> {}, m -> true).load().settings();
    }

    @Test
    void extensionPreservesElapsedWindowAndAddsToDenominator() throws Exception {
        var settings = settings();
        var first = VipRules.grant(player, "vip", null, 100000, 1000, player, settings);
        var extended = VipRules.grant(player, "vip", first, 50000, 51000, player, settings);
        assertEquals(151000, extended.expiresAt());
        assertEquals(1000, extended.startedAt());
        assertEquals(150000, extended.originalMs());
        assertEquals(150000, extended.historicalMs());
        assertEquals(2d / 3, extended.percentage(51000), 0.000001);
    }

    @Test
    void exactAndExcessiveRemovalExpireOrRejectAtomically() throws Exception {
        var balance = VipRules.grant(player, "vip", null, 100000, 1000, player, settings());
        assertEquals(
                VipBalance.Status.REMOVED,
                VipRules.remove(balance, 100000, 1000, Settings.RemovePolicy.CLAMP).status());
        assertEquals(
                1000,
                VipRules.remove(balance, 200000, 1000, Settings.RemovePolicy.CLAMP).expiresAt());
        assertThrows(
                DomainFailure.class,
                () -> VipRules.remove(balance, 100001, 1000, Settings.RemovePolicy.REJECT));
        assertThrows(
                DomainFailure.class,
                () -> VipRules.remove(balance, 1000, 101000, Settings.RemovePolicy.CLAMP));
        assertEquals(0, balance.percentage(101000));
        assertEquals(1, balance.percentage(0));
    }

    @Test
    void replaceRejectParallelAndPriorityPoliciesAreExplicit() throws Exception {
        var original = settings();
        var vip = VipRules.grant(player, "vip", null, 100000, 1000, player, original);
        assertTrue(VipRules.replaced(List.of(vip), "vip-plus", 2000, original).isEmpty());
        Path path = directory.resolve("config.yml");
        String config = Files.readString(path);
        Files.writeString(
                path, config.replace("different-type: PARALLEL", "different-type: PRIORITY_BASED"));
        var priority = settings();
        assertEquals(List.of(vip), VipRules.replaced(List.of(vip), "vip-plus", 2000, priority));
        var higher = VipRules.grant(player, "vip-plus", null, 100000, 1000, player, priority);
        assertThrows(
                DomainFailure.class,
                () -> VipRules.replaced(List.of(higher), "vip", 2000, priority));
        Files.writeString(path, config.replace("same-type: EXTEND", "same-type: REPLACE"));
        var replaced = VipRules.grant(player, "vip", vip, 50000, 51000, player, settings());
        assertEquals(101000, replaced.expiresAt());
        assertEquals(51000, replaced.startedAt());
        assertEquals(50000, replaced.originalMs());
    }

    @Test
    void expiredBalanceStartsNewWindowAndCorruptionFailsClosed() throws Exception {
        var settings = settings();
        var old = VipRules.grant(player, "vip", null, 1000, 1000, player, settings);
        var newBalance = VipRules.grant(player, "vip", old, 1000, 3000, player, settings);
        assertEquals(3000, newBalance.startedAt());
        assertEquals(1000, newBalance.originalMs());
        assertEquals(2000, newBalance.historicalMs());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new VipBalance(
                                player,
                                "vip",
                                5000,
                                1000,
                                1000,
                                1000,
                                VipBalance.Status.ACTIVE,
                                1,
                                player,
                                0,
                                0));
    }

    @Test
    void lateCacheRefreshCannotRegressRevisions() throws Exception {
        var settings = settings();
        var cache = new VipCache();
        var first = VipRules.grant(player, "vip", null, 100000, 1000, player, settings);
        var second = VipRules.grant(player, "vip", first, 100000, 1000, player, settings);
        cache.put(player, List.of(second));
        cache.put(player, List.of(first));
        assertEquals(second, cache.get(player).get(0));
    }
}
