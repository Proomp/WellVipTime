package com.wellsetups.wellviptime.language;

import com.wellsetups.wellviptime.configuration.ConfigError;
import com.wellsetups.wellviptime.configuration.Settings.Display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class Languages {

    public static final List<String> BUNDLED_LOCALES =
            List.of(
                    "en_US", "de_DE", "fr_FR", "zh_CN", "nl_NL", "fi_FI", "pl_PL", "ja_JP", "ru_RU",
                    "pt_BR", "hi_IN", "cs_CZ", "ko_KR", "tr_TR", "vi_VN", "it_IT", "es_ES", "sv_SE",
                    "id_ID", "zh_TW", "th_TH", "uk_UA", "es_MX", "ro_RO", "ar_SA", "pt_PT");

    private final Map<String, Map<String, String>> bundles;

    private final Map<String, String> bundled;

    private final Display display;

    private final Consumer<String> warning;

    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    private final MiniMessage mini = MiniMessage.miniMessage();

    private Languages(
            Map<String, Map<String, String>> bundles,
            Map<String, String> bundled,
            Display display,
            Consumer<String> warning) {
        this.bundled = Map.copyOf(bundled);
        this.bundles = Map.copyOf(bundles);
        this.display = display;
        this.warning = warning;
    }

    public static Languages load(Path directory, Display display, Consumer<String> warning)
            throws IOException {
        Map<String, Map<String, String>> bundles = new HashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file :
                    files.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                Map<String, String> messages = loadBundle(file);
                bundles.put(
                        file.getFileName().toString().replaceFirst("\\.yml$", ""),
                        Map.copyOf(messages));
            }
        }
        for (String language : List.of(display.defaultLanguage(), display.fallbackLanguage())) {
            if (!bundles.containsKey(language)) {
                throw new ConfigError("config.yml", "language", language, "existing language file");
            }
        }
        for (String language : display.locales().values()) {
            if (!bundles.containsKey(language)) {
                throw new ConfigError(
                        "config.yml",
                        "language.locale-mapping",
                        language,
                        "existing language file");
            }
        }
        Map<String, String> bundled = new HashMap<>();
        try (var input = Languages.class.getResourceAsStream("/languages/en_US.yml")) {
            if (input == null) {
                throw new IOException("Missing bundled English language");
            }
            var yaml = new YamlConfiguration();
            try {
                yaml.load(
                        new java.io.InputStreamReader(
                                input, java.nio.charset.StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException("Invalid bundled English language", e);
            }
            bundled.putAll(messageValues(yaml));
        }
        return new Languages(bundles, bundled, display, warning);
    }

    private static Map<String, String> loadBundle(Path file) throws IOException {
        var yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (InvalidConfigurationException e) {
            throw new ConfigError(
                    file.toString(), "<document>", "invalid YAML", "valid language YAML");
        }
        if (!yaml.contains("prefix")) {
            String previous = Files.readString(file);
            Path backup = file.resolveSibling(file.getFileName() + ".pre-2.0.bak");
            if (!Files.exists(backup)) {
                Files.copy(file, backup);
            }
            String updated =
                    "# Added by WellVipTime 2.0; chat prefix uses MiniMessage.\nprefix: ''\n"
                            + previous.replace(
                                    "<gradient:#F6D365:#FDA085><bold>VIP</bold></gradient>"
                                            + " <#536171>›</#536171> ",
                                    "");
            Files.writeString(file, updated);
            try {
                yaml.loadFromString(updated);
            } catch (InvalidConfigurationException e) {
                throw new IOException("Cannot migrate language prefix", e);
            }
        }
        return messageValues(yaml);
    }

    private static Map<String, String> messageValues(YamlConfiguration yaml) {
        Map<String, String> messages = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            Object value = yaml.get(key);
            if (value instanceof String s) {
                messages.put(key, s);
            } else if (value instanceof List<?> list
                    && list.stream().allMatch(v -> v instanceof String)) {
                messages.put(key, String.join("\n", yaml.getStringList(key)));
            }
        }
        return Map.copyOf(messages);
    }

    public String locale(Locale locale) {
        if (!display.autoLocale() || locale == null) {
            return display.defaultLanguage();
        }
        String normalized = locale.toString().toLowerCase(Locale.ROOT);
        return display.locales()
                .getOrDefault(
                        normalized,
                        bundles.keySet().stream()
                                .filter(k -> k.equalsIgnoreCase(normalized))
                                .findFirst()
                                .orElse(display.defaultLanguage()));
    }

    public String raw(String language, String key) {
        String value = bundles.getOrDefault(language, Map.of()).get(key);
        if (value != null) {
            return value;
        }
        if (missing.size() < 4096 && missing.add(language + ":" + key)) {
            warning.accept("Missing translation " + language + " :: " + key);
        }
        return bundles.get(display.fallbackLanguage())
                .getOrDefault(
                        key,
                        bundles.get(display.defaultLanguage())
                                .getOrDefault(key, bundled.getOrDefault(key, key)));
    }

    public Component render(String language, String key, Map<String, String> values) {
        return mini.deserialize(
                raw(language, key),
                TagResolver.resolver(
                        values.entrySet().stream()
                                .map(e -> Placeholder.unparsed(e.getKey(), e.getValue()))
                                .toList()));
    }

    public Component render(
            String language, String key, Map<String, String> values, String vipDisplay) {
        return renderTemplate(raw(language, key), values, vipDisplay);
    }

    public List<Component> lines(
            String language, String key, Map<String, String> values, String vipDisplay) {
        return Arrays.stream(raw(language, key).split("\\R", -1))
                .map(line -> renderTemplate(line, values, vipDisplay))
                .toList();
    }

    private Component renderTemplate(
            String template, Map<String, String> values, String vipDisplay) {
        return mini.deserialize(
                template,
                TagResolver.resolver(
                        values.entrySet().stream()
                                .filter(e -> !e.getKey().equals("vip"))
                                .map(e -> Placeholder.unparsed(e.getKey(), e.getValue()))
                                .toList()),
                Placeholder.component("vip", mini.deserialize(vipDisplay)));
    }
}
