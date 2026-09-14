package com.wellsetups.wellviptime.vip;

import com.wellsetups.wellviptime.configuration.Settings;

import java.util.List;
import java.util.UUID;

public final class VipRules {

    private VipRules() {}

    public static VipBalance grant(
            UUID player,
            String type,
            VipBalance previous,
            long duration,
            long now,
            UUID actor,
            Settings settings) {
        if (duration <= 0 || duration > settings.core().maxDays() * 86400000L) {
            throw new DomainFailure("error.duration");
        }
        boolean active = previous != null && previous.active(now);
        if (active && settings.core().same() == Settings.SamePolicy.REJECT) {
            throw new DomainFailure("error.policy");
        }
        boolean extend = active && settings.core().same() == Settings.SamePolicy.EXTEND;
        long expiry = Math.addExact(extend ? previous.expiresAt() : now, duration);
        if (expiry - now > settings.core().maxDays() * 86400000L) {
            throw new DomainFailure("error.policy");
        }
        return new VipBalance(
                player,
                type,
                extend ? previous.startedAt() : now,
                expiry,
                Math.addExact(extend ? previous.originalMs() : 0, duration),
                Math.addExact(previous == null ? 0 : previous.historicalMs(), duration),
                VipBalance.Status.ACTIVE,
                previous == null ? 1 : previous.revision() + 1,
                previous == null ? actor : previous.createdBy(),
                previous == null ? now : previous.createdAt(),
                now);
    }

    public static List<VipBalance> replaced(
            List<VipBalance> current, String type, long now, Settings settings) {
        var other = current.stream().filter(b -> !b.type().equals(type) && b.active(now)).toList();
        if (other.isEmpty()) {
            return List.of();
        }
        return switch (settings.core().different()) {
            case PARALLEL -> {
                if (!settings.core().multiple()) {
                    throw new DomainFailure("error.policy");
                }
                yield List.of();
            }
            case REJECT -> throw new DomainFailure("error.policy");
            case REPLACE -> other;
            case PRIORITY_BASED -> {
                int priority = settings.types().get(type).priority();
                if (other.stream()
                        .anyMatch(
                                b ->
                                        !settings.types().containsKey(b.type())
                                                || settings.types().get(b.type()).priority()
                                                        >= priority)) {
                    throw new DomainFailure("error.policy");
                }
                yield other;
            }
        };
    }

    public static VipBalance remove(
            VipBalance current, long duration, long now, Settings.RemovePolicy policy) {
        if (current == null || !current.active(now)) {
            throw new DomainFailure("error.no-vip");
        }
        if (duration <= 0) {
            throw new DomainFailure("error.duration");
        }
        if (duration > current.remaining(now) && policy == Settings.RemovePolicy.REJECT) {
            throw new DomainFailure("error.excessive-removal");
        }
        long expiry = current.expiresAt() - Math.min(duration, current.remaining(now));
        return new VipBalance(
                current.player(),
                current.type(),
                current.startedAt(),
                expiry,
                current.originalMs(),
                current.historicalMs(),
                expiry <= now ? VipBalance.Status.REMOVED : VipBalance.Status.ACTIVE,
                current.revision() + 1,
                current.createdBy(),
                current.createdAt(),
                now);
    }

    public static VipBalance deactivate(VipBalance b, VipBalance.Status status, long now) {
        return new VipBalance(
                b.player(),
                b.type(),
                b.startedAt(),
                b.expiresAt(),
                b.originalMs(),
                b.historicalMs(),
                status,
                b.revision() + 1,
                b.createdBy(),
                b.createdAt(),
                now);
    }
}
