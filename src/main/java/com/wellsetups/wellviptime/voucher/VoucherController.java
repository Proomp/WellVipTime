package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.command.*;
import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.integration.ServerTasks;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.*;

import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;
import java.util.function.*;

public final class VoucherController implements Listener {

    private final Supplier<Snapshot> configuration;

    private final Database database;

    private final VipRepository identities;

    private final VoucherRepository repository;

    private final VoucherService service;

    private final VoucherItems items;

    private final AsyncWork work;

    private final ServerTasks main;

    private final Messages messages;

    private final BiConsumer<org.bukkit.command.CommandSender, Throwable> failure;

    private final Consumer<VipService.Change> changed;

    private final Function<UUID, Player> online;

    private final Supplier<? extends Collection<? extends Player>> onlinePlayers;

    private record Delivery(Voucher voucher, VoucherOrigin origin) {}

    private record Gift(List<UUID> recipients, int count) {}

    private final Set<Player> redeeming = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Set<Player> delivering = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Map<UUID, Long> debounce = new java.util.concurrent.ConcurrentHashMap<>();

    public VoucherController(
            Supplier<Snapshot> configuration,
            Database database,
            VipRepository identities,
            VoucherRepository repository,
            VoucherService service,
            VoucherItems items,
            AsyncWork work,
            ServerTasks main,
            Messages messages,
            BiConsumer<org.bukkit.command.CommandSender, Throwable> failure,
            Consumer<VipService.Change> changed,
            Function<UUID, Player> online,
            Supplier<? extends Collection<? extends Player>> onlinePlayers) {
        this.configuration = configuration;
        this.database = database;
        this.identities = identities;
        this.repository = repository;
        this.service = service;
        this.items = items;
        this.work = work;
        this.main = main;
        this.messages = messages;
        this.failure = failure;
        this.changed = changed;
        this.online = online;
        this.onlinePlayers = onlinePlayers;
    }

    public void execute(CommandRequest request) {
        if (request.id().equals("vipadmin.revoke")) {
            administration(request, false);
            return;
        }
        if (request.id().equals("vipadmin.recover")) {
            if (!(request.sender() instanceof Player)) {
                messages.send(request.sender(), "error.player-only");
                return;
            }
            administration(request, true);
            return;
        }
        var settings = configuration.get().settings();
        var actor = VipCommands.actor(request.sender());
        if (!settings.voucher().enabled()) {
            messages.send(request.sender(), "error.vouchers-disabled");
            return;
        }
        if (request.id().startsWith("viptime.item")) {
            gift(request, settings, actor);
            return;
        }
        freeze(request, settings, actor);
    }

    private void freeze(CommandRequest request, Settings settings, VipService.Actor actor) {
        Player player = request.sender() instanceof Player p ? p : null;
        if (!canFreeze(request, player, settings)) {
            return;
        }
        boolean bypass = request.sender().hasPermission(settings.voucher().bypass());
        var kind = origin(request, player, settings);
        String targetInput = request.player() == null ? actor.uuid().toString() : request.player();
        work.submit(
                        () -> {
                            var target =
                                    database.read(
                                            c ->
                                                    identities
                                                            .resolve(c, targetInput)
                                                            .orElseThrow(
                                                                    () ->
                                                                            new DomainFailure(
                                                                                    "error.player",
                                                                                    Map.of(
                                                                                            "input",
                                                                                            targetInput))));
                            return service.freeze(
                                    UUID.randomUUID(),
                                    actor,
                                    target.uuid(),
                                    request.vip(),
                                    bypass,
                                    settings,
                                    new VoucherOrigin(kind, actor.name()));
                        })
                .whenComplete(
                        (result, error) -> {
                            if (error == null) {
                                changed.accept(result.change());
                                wakeDelivery(
                                        player == null
                                                ? result.change().balance().player()
                                                : actor.uuid());
                            }
                            main.execute(
                                    request.sender(),
                                    () -> {
                                        if (error != null) {
                                            failure.accept(request.sender(), error);
                                            return;
                                        }
                                        messages.sendComponent(
                                                request.sender(),
                                                messages.vip(
                                                        request.sender(),
                                                        "vip.frozen",
                                                        result.change().balance(),
                                                        Map.of(
                                                                "player",
                                                                result.change()
                                                                        .audit()
                                                                        .targetName(),
                                                                "remaining",
                                                                messages.duration(
                                                                        request.sender(),
                                                                        result.voucher()
                                                                                .durationMs()),
                                                                "voucher",
                                                                result.voucher().id().toString())));
                                    });
                        });
    }

    private boolean canFreeze(CommandRequest request, Player player, Settings settings) {
        if (player == null && request.player() == null) {
            messages.send(request.sender(), "error.player-only");
            return false;
        }
        if (player != null) {
            if (settings.voucher().full() == Settings.FullInventory.REJECT
                    && player.getInventory().firstEmpty() < 0) {
                messages.send(player, "error.inventory");
                return false;
            }
            if (settings.voucher().disabledWorlds().contains(player.getWorld().getName())) {
                messages.send(player, "error.world");
                return false;
            }
        }
        return true;
    }

    private VoucherOrigin.Kind origin(CommandRequest request, Player player, Settings settings) {
        if (player == null) {
            return VoucherOrigin.Kind.CONSOLE;
        }
        if (request.id().equals("vipfreeze.others")
                || player.isOp()
                || player.hasPermission(settings.commands().get("viptime.item").permission())) {
            return VoucherOrigin.Kind.ADMIN;
        }
        return VoucherOrigin.Kind.PLAYER;
    }

    private void gift(CommandRequest request, Settings settings, VipService.Actor actor) {
        boolean self = request.id().equals("viptime.item");
        if (!canReceiveGift(request, settings, self)) {
            return;
        }
        long duration = DurationParser.parseMillis(request.duration(), settings.core().maxDays());
        List<UUID> selected =
                request.id().equals("viptime.item.giveall")
                        ? onlinePlayers.get().stream().map(Player::getUniqueId).distinct().toList()
                        : self ? List.of(actor.uuid()) : List.of();
        work.submit(
                        () -> {
                            List<UUID> recipients = selected;
                            if (request.id().equals("viptime.item.give")) {
                                recipients =
                                        List.of(
                                                database.<UUID>read(
                                                        c ->
                                                                identities
                                                                        .resolve(
                                                                                c, request.player())
                                                                        .orElseThrow(
                                                                                () ->
                                                                                        new DomainFailure(
                                                                                                "error.player",
                                                                                                Map
                                                                                                        .of(
                                                                                                                "input",
                                                                                                                request
                                                                                                                        .player())))
                                                                        .uuid()));
                            }
                            var created =
                                    service.createFor(
                                            UUID.randomUUID(),
                                            actor,
                                            recipients,
                                            request.vip(),
                                            duration,
                                            request.amount(),
                                            settings);
                            return new Gift(recipients, created.size());
                        })
                .whenComplete(
                        (gift, error) -> {
                            if (error == null) {
                                gift.recipients().forEach(this::wakeDelivery);
                            }
                            main.execute(
                                    request.sender(),
                                    () -> {
                                        if (error != null) {
                                            failure.accept(request.sender(), error);
                                            return;
                                        }
                                        messages.send(
                                                request.sender(),
                                                "voucher.gifted",
                                                Map.of(
                                                        "amount",
                                                        Integer.toString(gift.count()),
                                                        "players",
                                                        Integer.toString(
                                                                gift.recipients().size())));
                                    });
                        });
    }

    private boolean canReceiveGift(CommandRequest request, Settings settings, boolean self) {
        if (self && !(request.sender() instanceof Player)) {
            messages.send(request.sender(), "error.player-only");
            return false;
        }
        if (self && settings.voucher().full() == Settings.FullInventory.REJECT) {
            Player player = (Player) request.sender();
            long spaces =
                    Arrays.stream(player.getInventory().getStorageContents())
                            .filter(i -> i == null || i.getType().isAir())
                            .count();
            if (spaces < request.amount()) {
                messages.send(player, "error.inventory");
                return false;
            }
        }
        return true;
    }

    private void wakeDelivery(UUID uuid) {
        Player target = online.apply(uuid);
        if (target != null) {
            main.execute(
                    target,
                    () -> {
                        if (target.isOnline()) {
                            deliver(target);
                        }
                    });
        }
    }

    private void administration(CommandRequest request, boolean recover) {
        final UUID voucherId;
        try {
            voucherId = administrationVoucher(request);
        } catch (DomainFailure invalid) {
            failure.accept(request.sender(), invalid);
            return;
        }
        var actor = VipCommands.actor(request.sender());
        var settings = configuration.get().settings();
        work.submit(
                        () ->
                                service.administrate(
                                        UUID.randomUUID(), actor, voucherId, recover, settings))
                .whenComplete(
                        (voucher, error) ->
                                main.execute(
                                        request.sender(),
                                        () -> {
                                            if (error != null) {
                                                failure.accept(request.sender(), error);
                                                return;
                                            }
                                            messages.send(
                                                    request.sender(),
                                                    recover
                                                            ? "voucher.recovered"
                                                            : "voucher.revoked",
                                                    Map.of("voucher", voucherId.toString()));
                                            if (recover
                                                    && request.sender() instanceof Player player) {
                                                deliver(player);
                                            }
                                        }));
    }

    private UUID administrationVoucher(CommandRequest request) {
        if (request.vip() != null) {
            try {
                return UUID.fromString(request.vip());
            } catch (IllegalArgumentException invalid) {
                throw new DomainFailure("error.voucher-invalid");
            }
        }
        if (request.sender() instanceof Player player
                && items.isVoucher(player.getInventory().getItemInMainHand())) {
            return items.read(player.getInventory().getItemInMainHand()).id();
        }
        var commands = configuration.get().settings().commands();
        throw new DomainFailure(
                "command.usage",
                Map.of(
                        "usage",
                        "/"
                                + commands.get("vipadmin").name()
                                + " "
                                + commands.get(request.id()).name()
                                + " <voucher-uuid>"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !items.isVoucher(event.getItem())) {
            return;
        }
        var settings = configuration.get().settings();
        boolean right =
                event.getAction() == Action.RIGHT_CLICK_AIR
                        || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left =
                event.getAction() == Action.LEFT_CLICK_AIR
                        || event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!(settings.voucher().activation().equals("RIGHT_CLICK") ? right : left)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime();
        if (redeeming.contains(player)
                || now - debounce.getOrDefault(uuid, 0L)
                        < settings.voucher().debounceMillis() * 1000000L) {
            return;
        }
        debounce.put(uuid, now);
        Voucher voucher;
        try {
            voucher = items.read(event.getItem());
        } catch (DomainFailure error) {
            failure.accept(player, error);
            return;
        }
        redeeming.add(player);
        VipService.Actor actor = VipCommands.actor(player);
        work.submit(() -> service.redeem(UUID.randomUUID(), actor, voucher, settings))
                .whenComplete(
                        (change, error) -> {
                            if (error == null) {
                                changed.accept(change);
                            }
                            main.execute(
                                    player, () -> redeemed(player, uuid, voucher, change, error));
                        });
    }

    private void redeemed(
            Player player, UUID uuid, Voucher voucher, VipService.Change change, Throwable error) {
        redeeming.remove(player);
        Player current = online.apply(uuid) == player && player.isOnline() ? player : null;
        if (error != null) {
            if (current != null) {
                failure.accept(current, error);
            }
            return;
        }
        if (current != null) {
            items.consume(current, voucher.id());
            messages.sendComponent(
                    current,
                    messages.vip(
                            current,
                            "voucher.redeemed",
                            change.balance(),
                            Map.of("duration", messages.duration(current, voucher.durationMs()))));
        }
    }

    public void deliver(Player player) {
        UUID uuid = player.getUniqueId();
        if (!configuration.get().settings().voucher().enabled() || !delivering.add(player)) {
            return;
        }
        work.submit(
                        () ->
                                database.read(
                                        c -> {
                                            List<Delivery> pending = new ArrayList<>();
                                            for (Voucher voucher :
                                                    repository.pending(c, uuid, 36)) {
                                                pending.add(
                                                        new Delivery(
                                                                voucher,
                                                                repository.origin(c, voucher)));
                                            }
                                            return pending;
                                        }))
                .whenComplete(
                        (pending, error) ->
                                main.execute(
                                        player,
                                        () -> deliverPending(player, uuid, pending, error)));
    }

    private void deliverPending(Player player, UUID uuid, List<Delivery> pending, Throwable error) {
        if (error != null) {
            delivering.remove(player);
            failure.accept(player, error);
            return;
        }
        Player current = online.apply(uuid) == player && player.isOnline() ? player : null;
        if (current == null) {
            delivering.remove(player);
            return;
        }
        try {
            acknowledgeDelivery(player, uuid, placeVouchers(current, pending));
        } catch (RuntimeException deliveryFailure) {
            delivering.remove(player);
            failure.accept(current, deliveryFailure);
        }
    }

    private List<UUID> placeVouchers(Player current, List<Delivery> pending) {
        List<UUID> delivered = new ArrayList<>();
        for (Delivery delivery : pending) {
            Voucher voucher = delivery.voucher();
            var item = items.create(voucher, current, delivery.origin());
            var leftovers = current.getInventory().addItem(item);
            if (!leftovers.isEmpty()) {
                var config = configuration.get().settings().voucher();
                if (config.full() == Settings.FullInventory.DROP && config.allowDrops()) {
                    leftovers
                            .values()
                            .forEach(
                                    left ->
                                            current.getWorld()
                                                    .dropItemNaturally(
                                                            current.getLocation(), left));
                } else {
                    break;
                }
            }
            delivered.add(voucher.id());
        }
        return delivered;
    }

    private void acknowledgeDelivery(Player player, UUID uuid, List<UUID> delivered) {
        // Checkpoint the inventory before acknowledging SQL delivery. A pre-ack crash may duplicate
        // the same ID, which remains single-use. World drops cannot share this inventory
        // checkpoint.
        if (!delivered.isEmpty()) {
            player.saveData();
        }
        work.submit(
                        () ->
                                database.transaction(
                                        c -> {
                                            for (UUID id : delivered) {
                                                repository.delivered(c, id, uuid, database.now(c));
                                            }
                                            return null;
                                        }))
                .whenComplete(
                        (ignored, failed) ->
                                main.execute(
                                        player,
                                        () -> {
                                            delivering.remove(player);
                                            if (failed != null) {
                                                failure.accept(player, failed);
                                            } else if (!delivered.isEmpty()) {
                                                messages.send(player, "voucher.delivered");
                                            }
                                        }));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        debounce.remove(event.getPlayer().getUniqueId());
        redeeming.remove(event.getPlayer());
        delivering.remove(event.getPlayer());
    }
}
