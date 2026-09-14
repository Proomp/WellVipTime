package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.AsyncWork;
import com.wellsetups.wellviptime.storage.Sql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class ReliabilityTest {

    @Test
    void bindingFailureRemainsPrimaryWhenStatementCloseAlsoFails() {
        SQLException binding = new SQLException("binding failure");
        SQLException closing = new SQLException("close failure");
        AtomicBoolean closed = new AtomicBoolean();
        Connection connection = connection(binding, closing, closed);
        SQLException failure =
                assertThrows(
                        SQLException.class,
                        () -> Sql.update(connection, "UPDATE example SET value=?", "value"));
        assertSame(binding, failure);
        assertArrayEquals(new Throwable[] {closing}, failure.getSuppressed());
        assertTrue(closed.get());
    }

    @Test
    void runtimeBindingFailureAlsoClosesStatement() {
        IllegalArgumentException binding = new IllegalArgumentException("unsupported value");
        AtomicBoolean closed = new AtomicBoolean();
        Connection connection = connection(binding, null, closed);
        assertSame(
                binding,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                Sql.query(
                                        connection,
                                        "SELECT ?",
                                        row -> row.getString(1),
                                        new Object())));
        assertTrue(closed.get());
    }

    private static Connection connection(
            Throwable binding, SQLException closing, AtomicBoolean closed) {
        PreparedStatement statement =
                (PreparedStatement)
                        Proxy.newProxyInstance(
                                ReliabilityTest.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "setQueryTimeout":
                                            return null;
                                        case "setObject":
                                            throw binding;
                                        case "close":
                                            closed.set(true);
                                            if (closing != null) {
                                                throw closing;
                                            }
                                            return null;
                                        default:
                                            throw new AssertionError(
                                                    "Unexpected JDBC call: " + method.getName());
                                    }
                                });
        return (Connection)
                Proxy.newProxyInstance(
                        ReliabilityTest.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement")) {
                                return statement;
                            }
                            throw new AssertionError(
                                    "Unexpected connection call: " + method.getName());
                        });
    }

    @Test
    void fatalWorkerFailureCompletesFutureAndAllowsWorkerReplacement()
            throws InterruptedException, ExecutionException, TimeoutException {
        AssertionError failure = new AssertionError("injected fatal failure");
        CountDownLatch uncaught = new CountDownLatch(1);
        try (var work = new AsyncWork(1, 2, 2)) {
            var result =
                    work.submit(
                            () -> {
                                Thread.currentThread()
                                        .setUncaughtExceptionHandler(
                                                (thread, error) -> uncaught.countDown());
                                throw failure;
                            });
            assertSame(
                    failure,
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS))
                            .getCause());
            assertTrue(uncaught.await(2, TimeUnit.SECONDS));
            assertEquals("replacement", work.submit(() -> "replacement").get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void queueSaturationRejectsWithoutRunningWorkOnCallerThread()
            throws InterruptedException, ExecutionException, TimeoutException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var work = new AsyncWork(1, 1, 2)) {
            var first =
                    work.submit(
                            () -> {
                                entered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return 1;
                            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var queued = work.submit(() -> 2);
                AtomicBoolean ran = new AtomicBoolean();
                var rejected =
                        work.submit(
                                () -> {
                                    ran.set(true);
                                    return 3;
                                });
                assertInstanceOf(
                        RejectedExecutionException.class,
                        assertThrows(
                                        ExecutionException.class,
                                        () -> rejected.get(2, TimeUnit.SECONDS))
                                .getCause());
                assertFalse(ran.get());
                release.countDown();
                assertEquals(1, first.get(2, TimeUnit.SECONDS));
                assertEquals(2, queued.get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void discoveryAndCommandSettingsDoNotRetainMutableInputs() {
        var includes = new ArrayList<>(List.of("vip*"));
        var excludes = new HashSet<>(Set.of("staff"));
        var discovery = new Settings.Discovery(true, includes, excludes, "", 60, "<group>", 0);
        includes.clear();
        excludes.clear();
        assertEquals(List.of("vip*"), discovery.include());
        assertEquals(Set.of("staff"), discovery.exclude());
        assertThrows(UnsupportedOperationException.class, () -> discovery.include().add("admin"));
        var aliases = new ArrayList<>(List.of("vt"));
        var command = new Settings.Command("viptime", aliases, "vipmanager.time", "VIP time");
        aliases.clear();
        assertEquals(List.of("vt"), command.aliases());
    }

    @Test
    void webhookEmbedFieldsAreImmutableAcrossReloads() {
        var fields =
                new ArrayList<>(List.of(new Settings.DiscordField("Player", "<player>", true)));
        var embed = new Settings.DiscordEmbed("Title", "Description", 0, fields);
        fields.clear();
        assertEquals(1, embed.fields().size());
        assertThrows(UnsupportedOperationException.class, () -> embed.fields().clear());
    }
}
