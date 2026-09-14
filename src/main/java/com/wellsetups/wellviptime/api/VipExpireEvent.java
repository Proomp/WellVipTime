package com.wellsetups.wellviptime.api;

import com.wellsetups.wellviptime.vip.VipBalance;

import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class VipExpireEvent extends VipEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public VipExpireEvent(VipBalance balance, UUID operation, UUID actor, UUID voucher) {
        super(balance, operation, actor, voucher);
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
