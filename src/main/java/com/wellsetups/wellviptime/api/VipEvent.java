package com.wellsetups.wellviptime.api;

import com.wellsetups.wellviptime.vip.VipBalance;

import org.bukkit.event.Event;

import java.util.UUID;

/** Post-commit fact; intentionally not cancellable. Use the API future to observe failures. */
public abstract class VipEvent extends Event {

    private final VipBalance balance;

    private final UUID operation;

    private final UUID actor;

    private final UUID voucher;

    protected VipEvent(VipBalance balance, UUID operation, UUID actor, UUID voucher) {
        this.balance = balance;
        this.operation = operation;
        this.actor = actor;
        this.voucher = voucher;
    }

    public VipBalance balance() {
        return balance;
    }

    public UUID operation() {
        return operation;
    }

    public UUID actor() {
        return actor;
    }

    public java.util.Optional<UUID> voucher() {
        return java.util.Optional.ofNullable(voucher);
    }
}
