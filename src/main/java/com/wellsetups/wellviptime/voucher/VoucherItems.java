package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.TimeFormat;
import com.wellsetups.wellviptime.vip.DomainFailure;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Supplier;

public final class VoucherItems {

    private final NamespacedKey payloadKey;

    private final NamespacedKey signatureKey;

    private final VoucherSigner signer;

    private final Supplier<Snapshot> configuration;

    public VoucherItems(JavaPlugin plugin, VoucherSigner signer, Supplier<Snapshot> configuration) {
        this.signer = signer;
        this.configuration = configuration;
        payloadKey = Objects.requireNonNull(NamespacedKey.fromString("vipmanager:voucher_payload"));
        signatureKey =
                Objects.requireNonNull(NamespacedKey.fromString("vipmanager:voucher_signature"));
    }

    public boolean isVoucher(ItemStack item) {
        return item != null
                && item.hasItemMeta()
                && item.getItemMeta()
                        .getPersistentDataContainer()
                        .has(payloadKey, PersistentDataType.BYTE_ARRAY);
    }

    public Voucher read(ItemStack item) {
        if (!isVoucher(item)) {
            throw new DomainFailure("error.voucher-invalid");
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        try {
            return signer.verify(
                    pdc.get(payloadKey, PersistentDataType.BYTE_ARRAY),
                    pdc.get(signatureKey, PersistentDataType.BYTE_ARRAY));
        } catch (IllegalArgumentException e) {
            throw new DomainFailure("error.voucher-invalid");
        }
    }

    public ItemStack create(Voucher voucher, Player recipient) {
        return create(
                voucher,
                recipient,
                new VoucherOrigin(
                        voucher.issuer().equals(new UUID(0, 0))
                                ? VoucherOrigin.Kind.CONSOLE
                                : voucher.historicalCredit()
                                        ? VoucherOrigin.Kind.ADMIN
                                        : VoucherOrigin.Kind.PLAYER,
                        voucher.issuer().toString()));
    }

    public ItemStack create(Voucher voucher, Player recipient, VoucherOrigin origin) {
        var snapshot = configuration.get();
        var settings = snapshot.settings();
        String locale =
                snapshot.languages()
                        .locale(com.wellsetups.wellviptime.integration.Platform.locale(recipient));
        String owner = origin.label(snapshot.languages(), locale);
        Map<String, String> values =
                Map.of(
                        "duration",
                        new TimeFormat(snapshot.languages(), settings.display())
                                .duration(locale, voucher.durationMs() / 1000),
                        "owner",
                        owner,
                        "voucher",
                        voucher.id().toString(),
                        "vip",
                        voucher.type(),
                        "redeemer",
                        voucher.owner() == null
                                ? snapshot.languages().raw(locale, "voucher.transferable")
                                : voucher.owner().equals(recipient.getUniqueId())
                                        ? recipient.getName()
                                        : voucher.owner().toString());
        String display =
                settings.types().containsKey(voucher.type())
                        ? settings.types().get(voucher.type()).displayName()
                        : voucher.type();
        ItemStack item = new ItemStack(settings.voucher().material());
        var meta = item.getItemMeta();
        com.wellsetups.wellviptime.integration.Platform.name(
                meta, snapshot.languages().render(locale, "voucher.name", values, display));
        com.wellsetups.wellviptime.integration.Platform.lore(
                meta, snapshot.languages().lines(locale, "voucher.lore", values, display));
        com.wellsetups.wellviptime.integration.Platform.glow(meta, settings.voucher().glow());
        meta.addItemFlags(settings.voucher().flags().toArray(org.bukkit.inventory.ItemFlag[]::new));
        if (!settings.voucher().modelData().isEmpty()) {
            com.wellsetups.wellviptime.integration.Platform.modelData(
                    meta, settings.voucher().modelData());
        }
        byte[] payload = signer.encode(voucher);
        meta.getPersistentDataContainer().set(payloadKey, PersistentDataType.BYTE_ARRAY, payload);
        meta.getPersistentDataContainer()
                .set(signatureKey, PersistentDataType.BYTE_ARRAY, signer.sign(payload));
        item.setItemMeta(meta);
        return item;
    }

    public void consume(Player player, UUID id) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isVoucher(item)) {
                continue;
            }
            try {
                if (read(item).id().equals(id)) {
                    item.setAmount(item.getAmount() - 1);
                    inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
                    return;
                }
            } catch (DomainFailure ignored) {
                /* Unrelated malformed items must not be consumed. */
            }
        }
    }
}
