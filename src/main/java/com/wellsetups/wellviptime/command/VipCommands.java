package com.wellsetups.wellviptime.command;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.ServerTasks;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;

import net.kyori.adventure.text.event.ClickEvent;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VipCommands {

    private final Supplier<Snapshot> configuration;

    private final Database database;

    private final VipRepository repository;

    private final VipService service;

    private final AsyncWork work;

    private final ServerTasks main;

    private final Messages messages;

    private final Logger logger;

    private final Consumer<VipService.Change> changed;

    private final Consumer<CommandSender> reload;

    private final Consumer<CommandRequest> vouchers;

    private final ListingService listings;

    private final Consumer<CommandRequest> menus;

    public VipCommands(
            Supplier<Snapshot> configuration,
            Database database,
            VipRepository repository,
            VipService service,
            AsyncWork work,
            ServerTasks main,
            Messages messages,
            Logger logger,
            Consumer<VipService.Change> changed,
            Consumer<CommandSender> reload,
            Consumer<CommandRequest> vouchers,
            ListingService listings,
            Consumer<CommandRequest> menus) {
        this.menus = menus;
        this.configuration = configuration;
        this.database = database;
        this.repository = repository;
        this.service = service;
        this.work = work;
        this.main = main;
        this.messages = messages;
        this.logger = logger;
        this.changed = changed;
        this.reload = reload;
        this.vouchers = vouchers;
        this.listings = listings;
    }

    public void execute(CommandRequest request) {
        Settings settings = configuration.get().settings();
        if (!request.sender().hasPermission(settings.commands().get(request.id()).permission())) {
            messages.send(request.sender(), "error.permission");
            return;
        }
        try {
            switch (request.id()) {
                case "vipmenu" -> menus.accept(request);
                case "viptime", "viptime.others" -> info(request);
                case "viptime.give", "viptime.remove" -> mutate(request, settings);
                case "viplist", "vipleaderboard" -> list(request, settings);
                case "vipadmin.reload" -> reload.accept(request.sender());
                case "vipadmin" -> {
                    String usage =
                            configuration
                                    .get()
                                    .languages()
                                    .raw(messages.locale(request.sender()), "command.admin-usage")
                                    .replace("<root>", settings.commands().get("vipadmin").name())
                                    .replace(
                                            "<reload>",
                                            settings.commands().get("vipadmin.reload").name())
                                    .replace(
                                            "<recover>",
                                            settings.commands().get("vipadmin.recover").name())
                                    .replace(
                                            "<revoke>",
                                            settings.commands().get("vipadmin.revoke").name());
                    messages.send(request.sender(), "command.usage", Map.of("usage", usage));
                }
                default -> vouchers.accept(request);
            }
        } catch (DomainFailure e) {
            failure(request.sender(), e);
        }
    }

    private void info(CommandRequest request) {
        String input = request.player();
        if (input == null) {
            if (!(request.sender() instanceof Player p)) {
                messages.send(request.sender(), "error.player-only");
                return;
            }
            input = p.getUniqueId().toString();
        }
        String target = input;
        work.submit(
                        () ->
                                database.read(
                                        c -> {
                                            var identity =
                                                    repository
                                                            .resolve(c, target)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new DomainFailure(
                                                                                    "error.player",
                                                                                    Map.of(
                                                                                            "input",
                                                                                            target)));
                                            return Map.entry(
                                                    identity, repository.all(c, identity.uuid()));
                                        }))
                .whenComplete(
                        (result, error) ->
                                main.execute(
                                        request.sender(),
                                        () -> {
                                            if (error != null) {
                                                failure(request.sender(), error);
                                                return;
                                            }
                                            var active =
                                                    result.getValue().stream()
                                                            .filter(
                                                                    b ->
                                                                            b.active(
                                                                                    System
                                                                                            .currentTimeMillis()))
                                                            .toList();
                                            if (active.isEmpty()) {
                                                messages.send(
                                                        request.sender(),
                                                        "error.no-vip",
                                                        Map.of("player", result.getKey().name()));
                                            }
                                            for (VipBalance balance : active) {
                                                messages.sendComponent(
                                                        request.sender(),
                                                        messages.vip(
                                                                        request.sender(),
                                                                        "vip.info",
                                                                        balance,
                                                                        Map.of(
                                                                                "player",
                                                                                result.getKey()
                                                                                        .name()))
                                                                .hoverEvent(
                                                                        messages.vip(
                                                                                request.sender(),
                                                                                "vip.hover",
                                                                                balance,
                                                                                Map.of()))
                                                                .clickEvent(
                                                                        messages.renewalClick()));
                                            }
                                        }));
    }

    private void mutate(CommandRequest request, Settings settings) {
        long duration = DurationParser.parseMillis(request.duration(), settings.core().maxDays());
        VipService.requireType(request.vip(), settings);
        VipService.Actor actor = actor(request.sender());
        work.submit(
                        () -> {
                            var target =
                                    database.read(
                                            c ->
                                                    repository
                                                            .resolve(c, request.player())
                                                            .orElseThrow(
                                                                    () ->
                                                                            new DomainFailure(
                                                                                    "error.player",
                                                                                    Map.of(
                                                                                            "input",
                                                                                            request
                                                                                                    .player()))));
                            return request.id().equals("viptime.give")
                                    ? service.grant(
                                            UUID.randomUUID(),
                                            actor,
                                            target.uuid(),
                                            request.vip(),
                                            duration,
                                            settings)
                                    : service.remove(
                                            UUID.randomUUID(),
                                            actor,
                                            target.uuid(),
                                            request.vip(),
                                            duration,
                                            settings);
                        })
                .whenComplete(
                        (change, error) -> {
                            if (error == null) {
                                changed.accept(change);
                            }
                            main.execute(
                                    request.sender(),
                                    () -> {
                                        if (error != null) {
                                            failure(request.sender(), error);
                                            return;
                                        }
                                        messages.sendComponent(
                                                request.sender(),
                                                messages.vip(
                                                        request.sender(),
                                                        request.id().equals("viptime.give")
                                                                ? "vip.granted"
                                                                : "vip.removed",
                                                        change.balance(),
                                                        Map.of(
                                                                "player",
                                                                change.audit().targetName(),
                                                                "duration",
                                                                messages.duration(
                                                                        request.sender(),
                                                                        change.audit()
                                                                                .duration()))));
                                    });
                        });
    }

    private void list(CommandRequest request, Settings settings) {
        boolean leaderboard = request.id().equals("vipleaderboard");
        if (request.vip() != null) {
            VipService.requireType(request.vip(), settings);
        }
        if (request.sender() instanceof Player
                && (leaderboard ? settings.menus().leaderboard() : settings.menus().list())) {
            menus.accept(request);
            return;
        }
        int pageSize = settings.core().pageSize();
        int offset = Math.multiplyExact(request.page() - 1, pageSize);
        int limit =
                leaderboard
                        ? Math.max(0, Math.min(pageSize, settings.core().maxRanking() - offset))
                        : pageSize;
        work.submit(() -> listings.page(settings, request.vip(), offset, limit, leaderboard))
                .whenComplete(
                        (rows, error) ->
                                main.execute(
                                        request.sender(),
                                        () -> showList(request, settings, rows, error)));
    }

    private void showList(
            CommandRequest request,
            Settings settings,
            List<VipRepository.Ranked> rows,
            Throwable error) {
        boolean leaderboard = request.id().equals("vipleaderboard");
        int pageSize = settings.core().pageSize();
        int offset = Math.multiplyExact(request.page() - 1, pageSize);
        if (error != null) {
            failure(request.sender(), error);
            return;
        }
        messages.send(
                request.sender(),
                leaderboard ? "list.leaderboard-header" : "list.header",
                Map.of("page", Integer.toString(request.page())));
        if (rows.isEmpty()) {
            messages.send(request.sender(), "list.empty");
        }
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String remaining =
                    messages.duration(
                            request.sender(),
                            leaderboard && settings.core().ranking() != Settings.Ranking.EXPIRATION
                                    ? row.score()
                                    : row.vip().remaining(System.currentTimeMillis()));
            messages.sendComponent(
                    request.sender(),
                    messages.vip(
                            request.sender(),
                            "list.row",
                            row.vip(),
                            Map.of(
                                    "rank",
                                    Integer.toString(offset + i + 1),
                                    "player",
                                    row.player().name(),
                                    "remaining",
                                    remaining)));
        }
        String root = settings.commands().get(request.id()).name();
        if (request.page() > 1) {
            pagination(request.sender(), "list.previous", root, request.page() - 1, request.vip());
        }
        if (rows.size() == pageSize
                && (!leaderboard || offset + pageSize < settings.core().maxRanking())) {
            pagination(request.sender(), "list.next", root, request.page() + 1, request.vip());
        }
    }

    private void pagination(CommandSender sender, String key, String root, int page, String type) {
        messages.sendComponent(
                sender,
                messages.component(sender, key, Map.of())
                        .clickEvent(
                                ClickEvent.runCommand(
                                        "/" + root + " " + page + (type == null ? "" : " " + type)))
                        .hoverEvent(
                                messages.component(
                                        sender,
                                        "list.page-hover",
                                        Map.of("page", Integer.toString(page)))));
    }

    public void failure(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof DomainFailure domain) {
            Map<String, String> values = new HashMap<>(domain.values());
            values.putIfAbsent("player", sender.getName());
            if (values.containsKey("remaining_ms")) {
                values.put(
                        "remaining",
                        messages.duration(sender, Long.parseLong(values.get("remaining_ms"))));
            }
            messages.send(sender, domain.key(), values);
            return;
        }
        if (cause instanceof RejectedExecutionException) {
            messages.send(sender, "error.busy");
            return;
        }
        String reference = UUID.randomUUID().toString();
        logger.log(Level.SEVERE, "VIP operation failed; reference=" + reference, cause);
        messages.send(sender, "error.internal", Map.of("reference", reference));
    }

    public static VipService.Actor actor(CommandSender sender) {
        return new VipService.Actor(
                sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0), sender.getName());
    }
}
