package com.wellsetups.wellviptime.voucher;

import java.util.UUID;

public record Voucher(
        UUID id,
        int schema,
        String type,
        long durationMs,
        long createdAt,
        UUID issuer,
        UUID owner,
        boolean historicalCredit) {

    public Voucher {
        if (id == null
                || issuer == null
                || type == null
                || !type.matches("[a-z0-9_-]{1,48}")
                || durationMs < 1
                || createdAt < 0) {
            throw new IllegalArgumentException("Invalid voucher payload");
        }
    }
}
