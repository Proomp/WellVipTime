package com.wellsetups.wellviptime.audit;

import com.google.gson.Gson;
import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class DiscordAudit implements AutoCloseable {

    private record Pending(AuditEntry audit, int attempt) {}

    private final Database database;

    private final AsyncWork worker = new AsyncWork(1, 8, 5);

    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private final Gson gson = new Gson();

    private final AtomicBoolean active = new AtomicBoolean();

    private final com.wellsetups.wellviptime.integration.ServerTasks.Task task;

    private final java.util.logging.Logger logger;

    private long lastWarning;

    private final com.wellsetups.wellviptime.integration.DebugLog debug;

    public DiscordAudit(
            JavaPlugin plugin,
            Database database,
            Supplier<Snapshot> configuration,
            com.wellsetups.wellviptime.integration.ServerTasks main) {
        this.database = database;
        logger = plugin.getLogger();
        debug = new com.wellsetups.wellviptime.integration.DebugLog(configuration, logger);
        task =
                main.repeat(
                        () -> {
                            var snapshot = configuration.get();
                            if (!snapshot.settings().discord().enabled()
                                    || !active.compareAndSet(false, true)) {
                                return;
                            }
                            worker.submit(
                                            () -> {
                                                process(snapshot);
                                                return null;
                                            })
                                    .whenComplete(
                                            (ignored, error) -> {
                                                active.set(false);
                                                if (error != null) {
                                                    warn(
                                                            "Discord audit storage failed; durable"
                                                                    + " delivery will retry ("
                                                                    + error.getClass()
                                                                            .getSimpleName()
                                                                    + ")");
                                                }
                                            });
                        },
                        20,
                        20);
    }

    private record Outcome(String state, long retryMillis, long gateMillis) {}

    private void process(Snapshot snapshot) throws SQLException {
        var settings = snapshot.settings().discord();
        Pending pending = claim(settings);
        if (pending == null) {
            return;
        }
        Outcome outcome;
        try {
            outcome = send(pending, snapshot);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        String state = outcome.state();
        if (state.equals("PENDING") && pending.attempt() >= settings.maxAttempts()) {
            state = "FAILED";
        }
        acknowledge(pending, new Outcome(state, outcome.retryMillis(), outcome.gateMillis()));
    }

    private Outcome send(Pending pending, Snapshot snapshot) throws InterruptedException {
        var settings = snapshot.settings().discord();
        long retry = (1L << pending.attempt()) * 1000;
        if (!settings.events().contains(pending.audit().action())) {
            return new Outcome("DISABLED", retry, 0);
        }
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(settings.url()))
                        .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "WellVipTime/2.1")
                        .POST(HttpRequest.BodyPublishers.ofString(body(pending.audit(), snapshot)))
                        .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            debug.write("discord", "HTTP " + status + " audit=" + pending.audit().id());
            Outcome outcome = response(status, response.headers(), retry);
            if (!outcome.state().equals("SENT")) {
                warn("Discord webhook returned HTTP " + status + "; audit=" + pending.audit().id());
            }
            return outcome;
        } catch (IOException failure) {
            warn(
                    "Discord request failed ("
                            + failure.getClass().getSimpleName()
                            + "); audit="
                            + pending.audit().id());
            return new Outcome("PENDING", retry, 0);
        }
    }

    private static Outcome response(int status, HttpHeaders headers, long retry) {
        String state = "PENDING";
        long gate = 0;
        if (status >= 200 && status < 300) {
            state = "SENT";
        } else if (status == 429) {
            retry = retryMillis(headers.firstValue("Retry-After").orElse("5"));
            gate = retry;
        } else if (status < 500) {
            state = "FAILED";
        }
        if (headers.firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
            gate =
                    Math.max(
                            gate,
                            retryMillis(headers.firstValue("X-RateLimit-Reset-After").orElse("1")));
        }
        return new Outcome(state, retry, gate);
    }

    private void acknowledge(Pending pending, Outcome outcome) throws SQLException {
        database.transaction(
                c -> {
                    long now = database.now(c);
                    Sql.update(
                            c,
                            "UPDATE audit_log SET webhook_state=?,webhook_next=? WHERE id=? AND"
                                    + " webhook_attempts=?",
                            outcome.state(),
                            now + outcome.retryMillis(),
                            pending.audit().id().toString(),
                            pending.attempt());
                    Sql.update(
                            c,
                            "UPDATE webhook_gate SET until_at=? WHERE id=1",
                            now + outcome.gateMillis());
                    return null;
                });
    }

    private Pending claim(Settings.Discord settings) throws SQLException {
        return database.transaction(
                c -> {
                    long now = database.now(c);
                    Sql.update(
                            c,
                            "UPDATE audit_log SET webhook_state='FAILED' WHERE"
                                    + " webhook_state='SENDING' AND webhook_next<=? AND"
                                    + " webhook_attempts>=?",
                            now,
                            settings.maxAttempts());
                    Sql.update(
                            c,
                            (database.sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                                    + " INTO webhook_gate(id,until_at) VALUES(1,0)");
                    if (Sql.update(
                                    c,
                                    "UPDATE webhook_gate SET until_at=? WHERE id=1 AND until_at<=?",
                                    now + 60000,
                                    now)
                            == 0) {
                        return null;
                    }
                    var rows =
                            Sql.query(
                                    c,
                                    "SELECT * FROM audit_log WHERE webhook_state IN"
                                            + " ('PENDING','SENDING') AND webhook_next<=? AND"
                                            + " webhook_attempts<? ORDER BY created_at LIMIT 1",
                                    r -> {
                                        String voucher = r.getString("voucher_id");
                                        var audit =
                                                new AuditEntry(
                                                        UUID.fromString(r.getString("id")),
                                                        UUID.fromString(
                                                                r.getString("operation_id")),
                                                        r.getString("action"),
                                                        UUID.fromString(r.getString("actor_uuid")),
                                                        r.getString("actor_name"),
                                                        UUID.fromString(r.getString("target_uuid")),
                                                        r.getString("target_name"),
                                                        r.getString("vip_type"),
                                                        r.getLong("duration_ms"),
                                                        r.getLong("previous_expiry"),
                                                        r.getLong("new_expiry"),
                                                        voucher == null
                                                                ? null
                                                                : UUID.fromString(voucher),
                                                        r.getLong("created_at"));
                                        return new Pending(audit, r.getInt("webhook_attempts") + 1);
                                    },
                                    now,
                                    settings.maxAttempts());
                    if (rows.isEmpty()) {
                        Sql.update(c, "UPDATE webhook_gate SET until_at=0 WHERE id=1");
                        return null;
                    }
                    Pending row = rows.get(0);
                    Sql.update(
                            c,
                            "UPDATE audit_log SET"
                                + " webhook_state='SENDING',webhook_attempts=?,webhook_next=? WHERE"
                                + " id=?",
                            row.attempt(),
                            now + 60000,
                            row.audit().id().toString());
                    return row;
                });
    }

    private String body(AuditEntry audit, Snapshot snapshot) {
        return gson.toJson(DiscordEmbeds.body(audit, snapshot));
    }

    public static long retryMillis(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return Double.isFinite(seconds)
                    ? (long)
                            com.wellsetups.wellviptime.integration.Platform.clamp(
                                    Math.ceil(seconds * 1000), 1000, 86400000)
                    : 5000;
        } catch (NumberFormatException e) {
            return 5000;
        }
    }

    private synchronized void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < 30000) {
            return;
        }
        lastWarning = now;
        logger.warning(message);
    }

    @Override
    public void close() {
        task.cancel();
        worker.close();
    }
}
