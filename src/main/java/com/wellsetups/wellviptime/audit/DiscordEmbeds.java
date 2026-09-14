package com.wellsetups.wellviptime.audit;

import com.wellsetups.wellviptime.configuration.ConfigLoader.Snapshot;
import com.wellsetups.wellviptime.language.TimeFormat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class DiscordEmbeds {

    private DiscordEmbeds() {}

    private static final Pattern PARAMETERS = Pattern.compile("<([a-z_]+)>");

    public static Map<String, Object> body(AuditEntry audit, Snapshot snapshot) {
        var settings = snapshot.settings().discord();
        String locale = snapshot.settings().display().defaultLanguage();
        Map<String, String> values = new HashMap<>();
        values.put("action", audit.action());
        values.put("actor", audit.actorName());
        values.put("actor_uuid", audit.actor().toString());
        values.put("player", audit.targetName());
        values.put("uuid", audit.target().toString());
        values.put("vip", audit.type());
        values.put("duration_seconds", Long.toString(audit.duration() / 1000));
        values.put(
                "duration",
                new TimeFormat(snapshot.languages(), snapshot.settings().display())
                        .duration(locale, audit.duration() / 1000));
        values.put("previous", date(audit.previousExpiry(), snapshot));
        values.put("expiry", date(audit.newExpiry(), snapshot));
        values.put("voucher", audit.voucher() == null ? "—" : audit.voucher().toString());
        values.put("reference", audit.id().toString());
        values.put("server", snapshot.settings().core().serverId());
        var template = settings.templates().get(audit.action());
        var plain = PlainTextComponentSerializer.plainText();
        String title =
                limit(
                        template == null
                                ? plain.serialize(
                                        snapshot.languages()
                                                .render(locale, "discord.title", values))
                                : render(template.title(), values),
                        256);
        String description =
                limit(
                        template == null
                                ? plain.serialize(
                                                snapshot.languages()
                                                        .render(
                                                                locale,
                                                                "discord.description",
                                                                values))
                                        .replace("\\n", "\n")
                                : render(template.description(), values),
                        1800);
        String footer = limit(render(settings.footer(), values), 512);
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", template == null ? settings.color() : template.color());
        embed.put("footer", Map.of("text", footer));
        if (template != null) {
            var fields =
                    fields(
                            template,
                            values,
                            6000 - title.length() - description.length() - footer.length());
            if (!fields.isEmpty()) {
                embed.put("fields", fields);
            }
        }
        if (settings.timestamp()) {
            embed.put("timestamp", Instant.ofEpochMilli(audit.timestamp()).toString());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", limit(settings.username(), 80));
        if (!settings.avatar().isEmpty()) {
            body.put("avatar_url", settings.avatar());
        }
        body.put("allowed_mentions", Map.of("parse", List.of()));
        body.put("embeds", List.of(embed));
        return body;
    }

    private static List<Map<String, Object>> fields(
            com.wellsetups.wellviptime.configuration.Settings.DiscordEmbed template,
            Map<String, String> values,
            int remaining) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (var field : template.fields()) {
            String name = limit(render(field.name(), values), 128);
            if (name.isBlank()) {
                name = "—";
            }
            if (remaining <= name.length()) {
                break;
            }
            String value =
                    limit(render(field.value(), values), Math.min(1024, remaining - name.length()));
            if (value.isBlank()) {
                value = "—";
            }
            fields.add(Map.of("name", name, "value", value, "inline", field.inline()));
            remaining -= name.length() + value.length();
        }
        return fields;
    }

    private static String date(long epoch, Snapshot snapshot) {
        return epoch <= 0
                ? "—"
                : snapshot.settings().display().dates().format(Instant.ofEpochMilli(epoch));
    }

    private static String render(String template, Map<String, String> values) {
        return PARAMETERS
                .matcher(template)
                .replaceAll(
                        match ->
                                java.util.regex.Matcher.quoteReplacement(
                                        escape(
                                                values.getOrDefault(
                                                        match.group(1), match.group()))));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("~", "\\~")
                .replace("|", "\\|");
    }

    private static String limit(String text, int length) {
        if (text.length() <= length) {
            return text;
        }
        int end = length;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
