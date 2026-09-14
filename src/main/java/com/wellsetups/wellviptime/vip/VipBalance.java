package com.wellsetups.wellviptime.vip;

import java.util.UUID;

public record VipBalance(
        UUID player,
        String type,
        long startedAt,
        long expiresAt,
        long originalMs,
        long historicalMs,
        Status status,
        long revision,
        UUID createdBy,
        long createdAt,
        long modifiedAt) {

    public enum Status {
        ACTIVE,
        FROZEN,
        EXPIRED,
        REMOVED
    }

    public VipBalance {
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(createdBy, "createdBy");
        if (originalMs < 0
                || historicalMs < 0
                || revision < 1
                || expiresAt < startedAt
                || startedAt < 0
                || status == Status.ACTIVE && originalMs == 0) {
            throw new IllegalArgumentException("Corrupt VIP balance for " + player + "/" + type);
        }
    }

    public long remaining(long now) {
        return status == Status.ACTIVE ? Math.max(0, expiresAt - now) : 0;
    }

    public boolean active(long now) {
        return remaining(now) > 0;
    }

    public double percentage(long now) {
        return originalMs == 0 ? 0 : Math.max(0, Math.min(1, (double) remaining(now) / originalMs));
    }
}
