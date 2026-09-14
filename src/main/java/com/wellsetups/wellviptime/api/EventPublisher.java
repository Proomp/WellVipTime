package com.wellsetups.wellviptime.api;

import com.wellsetups.wellviptime.vip.VipService;

import org.bukkit.plugin.PluginManager;

public final class EventPublisher {

    private final PluginManager plugins;

    public EventPublisher(PluginManager plugins) {
        this.plugins = plugins;
    }

    public void publish(VipService.Change change) {
        var b = change.balance();
        var a = change.audit();
        VipEvent event =
                switch (a.action()) {
                    case "VIP_GRANTED" ->
                            new VipGrantEvent(b, a.operation(), a.actor(), a.voucher());
                    case "VIP_EXTENDED" ->
                            new VipExtendEvent(b, a.operation(), a.actor(), a.voucher());
                    case "VIP_EXPIRED" ->
                            new VipExpireEvent(b, a.operation(), a.actor(), a.voucher());
                    case "VIP_FROZEN" ->
                            new VipFreezeEvent(b, a.operation(), a.actor(), a.voucher());
                    case "VIP_VOUCHER_REDEEMED" ->
                            new VipVoucherRedeemEvent(b, a.operation(), a.actor(), a.voucher());
                    case "VIP_TIME_REMOVED" ->
                            new VipTimeRemoveEvent(b, a.operation(), a.actor(), a.voucher());
                    default -> null;
                };
        if (event != null) {
            plugins.callEvent(event);
        }
    }
}
