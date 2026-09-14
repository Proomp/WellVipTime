package com.wellsetups.wellviptime.integration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class Platform {

    private Platform() {}

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('§')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    public static String legacy(Component value) {
        return LEGACY.serialize(value);
    }

    public static Locale locale(org.bukkit.entity.Player player) {
        return Locale.forLanguageTag(player.getLocale().replace('_', '-'));
    }

    public static Inventory top(Object view) {
        // InventoryView changed from a class to an interface in 1.21.
        try {
            return (Inventory) InventoryView.class.getMethod("getTopInventory").invoke(view);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read inventory view", e);
        }
    }

    public static void name(ItemMeta meta, Component value) {
        meta.setDisplayName(legacy(value));
    }

    public static void lore(ItemMeta meta, List<Component> value) {
        meta.setLore(value.stream().map(Platform::legacy).toList());
    }

    public static void glow(ItemMeta meta, boolean enabled) {
        try {
            ItemMeta.class
                    .getMethod("setEnchantmentGlintOverride", Boolean.class)
                    .invoke(meta, enabled);
        } catch (NoSuchMethodException e) {
            legacyGlow(meta, enabled);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set item glint", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void legacyGlow(ItemMeta meta, boolean enabled) {
        if (!enabled) {
            return;
        }
        var enchantment =
                org.bukkit.enchantments.Enchantment.getByKey(
                        org.bukkit.NamespacedKey.minecraft("unbreaking"));
        if (enchantment != null) {
            meta.addEnchant(enchantment, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    @SuppressWarnings("deprecation")
    public static void modelData(ItemMeta meta, List<Float> values) {
        if (values.isEmpty()) {
            return;
        }
        try {
            Object data = ItemMeta.class.getMethod("getCustomModelDataComponent").invoke(meta);
            Class<?> type =
                    Class.forName("org.bukkit.inventory.meta.components.CustomModelDataComponent");
            type.getMethod("setFloats", List.class).invoke(data, values);
            ItemMeta.class.getMethod("setCustomModelDataComponent", type).invoke(meta, data);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            meta.setCustomModelData(values.get(0).intValue());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set custom model data", e);
        }
    }
}
