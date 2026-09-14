package com.wellsetups.wellviptime.voucher;

import com.wellsetups.wellviptime.language.Languages;

import java.util.Map;

/** Display provenance only; signed owner UUID still controls redemption. */
public record VoucherOrigin(Kind kind, String name) {

    public enum Kind {
        PLAYER,
        ADMIN,
        CONSOLE
    }

    public String label(Languages languages, String locale) {
        String key = "voucher.owner-" + kind.name().toLowerCase(java.util.Locale.ROOT);
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(languages.render(locale, key, Map.of("player", name)));
    }
}
