package com.wellsetups.wellviptime.api;

import com.wellsetups.wellviptime.vip.VipBalance;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Futures complete after the SQL commit. Bukkit events are dispatched afterward on the server
 * thread.
 */
public interface VipApi {

    record Actor(UUID uuid, String name) {

        public Actor {
            if (uuid == null || name == null || name.isBlank() || name.length() > 64) {
                throw new IllegalArgumentException("Invalid actor identity");
            }
        }
    }

    record VoucherToken(byte[] payload, byte[] signature) {

        public VoucherToken {
            payload = payload.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    CompletionStage<List<VipBalance>> getActiveVips(UUID player);

    CompletionStage<VipBalance> grant(
            UUID operation, Actor actor, UUID target, String type, Duration duration);

    CompletionStage<VipBalance> removeTime(
            UUID operation, Actor actor, UUID target, String type, Duration duration);

    CompletionStage<VoucherToken> freeze(
            UUID operation, Actor actor, UUID target, String type, boolean bypassCooldown);

    CompletionStage<VipBalance> redeem(UUID operation, Actor actor, VoucherToken voucher);
}
