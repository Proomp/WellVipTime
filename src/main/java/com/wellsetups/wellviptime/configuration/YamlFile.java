package com.wellsetups.wellviptime.configuration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class YamlFile {

    final String file;

    final YamlConfiguration yaml = new YamlConfiguration();

    YamlFile(Path path) throws IOException {
        file = path.getFileName().toString();
        try {
            yaml.load(path.toFile());
        } catch (InvalidConfigurationException e) {
            throw new ConfigError(
                    file,
                    "<document>",
                    "invalid YAML",
                    "valid YAML syntax (parser contents withheld to protect secrets)");
        }
        try (var input = YamlFile.class.getClassLoader().getResourceAsStream(file)) {
            if (input != null) {
                var defaults = new YamlConfiguration();
                try {
                    defaults.load(
                            new java.io.InputStreamReader(
                                    input, java.nio.charset.StandardCharsets.UTF_8));
                } catch (InvalidConfigurationException e) {
                    throw new IOException("Invalid bundled defaults for " + file, e);
                }
                var configuredKeys = Set.copyOf(yaml.getKeys(true));
                for (String key : defaults.getKeys(true)) {
                    boolean dynamic =
                            file.equals("vip-types.yml") && !key.startsWith("sync.")
                                    || file.equals("notifications.yml")
                                            && key.startsWith("thresholds.")
                                    || file.equals("gui.yml") && key.startsWith("items.")
                                    || file.equals("discord.yml")
                                            && key.contains(".fields.")
                                            && configuredKeys.contains(
                                                    key.substring(0, key.indexOf(".fields.") + 7));
                    if (!dynamic && !defaults.isConfigurationSection(key) && !yaml.contains(key)) {
                        yaml.set(key, defaults.get(key));
                    }
                }
            }
        }
    }

    String text(String path) {
        Object value = yaml.get(path);
        if (!(value instanceof String s) || s.isBlank()) {
            throw error(path, "non-empty string");
        }
        return s;
    }

    String optional(String path) {
        return yaml.contains(path) ? yaml.getString(path, "") : "";
    }

    boolean bool(String path) {
        if (!(yaml.get(path) instanceof Boolean value)) {
            throw error(path, "true or false");
        }
        return value;
    }

    int number(String path, int min, int max) {
        Object raw = yaml.get(path);
        if (!(raw instanceof Number n)
                || n.doubleValue() != n.longValue()
                || n.longValue() < min
                || n.longValue() > max) {
            throw error(path, "integer in [" + min + ", " + max + "]");
        }
        return n.intValue();
    }

    List<String> strings(String path) {
        Object raw = yaml.get(path);
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String))) {
            throw error(path, "list of strings");
        }
        return List.copyOf(yaml.getStringList(path));
    }

    Set<String> keys(String path) {
        var section = yaml.getConfigurationSection(path);
        if (section == null) {
            throw error(path, "mapping");
        }
        return section.getKeys(false);
    }

    <E extends Enum<E>> E choice(String path, Class<E> type) {
        try {
            return Enum.valueOf(type, text(path));
        } catch (IllegalArgumentException e) {
            throw error(path, java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    ConfigError error(String path, String expected) {
        return new ConfigError(
                file,
                path,
                path.contains("password") || path.contains("url") ? "<redacted>" : yaml.get(path),
                expected);
    }
}
