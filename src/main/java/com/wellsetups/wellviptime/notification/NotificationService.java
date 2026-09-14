package com.wellsetups.wellviptime.notification;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.storage.*;
import com.wellsetups.wellviptime.vip.VipBalance;

import java.sql.SQLException;
import java.util.*;

public final class NotificationService {

    public record Notice(VipBalance vip, Set<Settings.Channel> channels, boolean expired) {}

    public record Refresh(
            UUID player, List<VipBalance> balances, List<Notice> notices, boolean loginMenu) {}

    private final Database database;

    private final VipRepository repository;

    public NotificationService(Database database, VipRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    public Refresh refresh(UUID player, boolean login, Settings settings) throws SQLException {
        return refresh(player, login, null, settings);
    }

    public Refresh refresh(UUID player, boolean login, Boolean hidden, Settings settings)
            throws SQLException {
        return database.transaction(
                c -> {
                    database.lockPlayers(c, List.of(player));
                    if (hidden != null) {
                        Sql.update(
                                c,
                                "UPDATE players SET hidden=? WHERE uuid=?",
                                hidden ? 1 : 0,
                                player.toString());
                    }
                    long now = database.now(c);
                    List<VipBalance> balances = repository.all(c, player);
                    List<Notice> notices = new ArrayList<>();
                    boolean menu = false;
                    for (VipBalance balance : balances) {
                        Set<String> sent =
                                new HashSet<>(
                                        Sql.query(
                                                c,
                                                "SELECT threshold_id FROM notification_receipts"
                                                    + " WHERE uuid=? AND vip_type=? AND revision=?",
                                                r -> r.getString(1),
                                                player.toString(),
                                                balance.type(),
                                                balance.revision()));
                        collectNotices(c, balance, sent, now, login, settings, notices);
                        menu |= claimLoginMenu(c, balance, sent, now, login, settings);
                    }
                    return new Refresh(player, balances, List.copyOf(notices), menu);
                });
    }

    private void collectNotices(
            java.sql.Connection c,
            VipBalance balance,
            Set<String> sent,
            long now,
            boolean login,
            Settings settings,
            List<Notice> notices)
            throws SQLException {
        if (settings.notifications().enabled() && balance.active(now)) {
            var crossed =
                    ThresholdRules.crossed(
                            settings.notifications().thresholds(),
                            balance.remaining(now),
                            balance.originalMs(),
                            sent);
            for (var threshold : crossed) {
                claim(c, balance, threshold.id(), now);
            }
            for (var threshold :
                    ThresholdRules.display(crossed, login, settings.notifications().login())) {
                notices.add(new Notice(balance, threshold.channels(), false));
            }
        }
        if (balance.status() == VipBalance.Status.EXPIRED && !sent.contains("expired")) {
            claim(c, balance, "expired", now);
            if (settings.notifications().expirationMessage()
                    && !(login && settings.notifications().login() == Settings.LoginMode.IGNORE)) {
                notices.add(new Notice(balance, Set.of(Settings.Channel.CHAT), true));
            }
        }
    }

    private boolean claimLoginMenu(
            java.sql.Connection c,
            VipBalance balance,
            Set<String> sent,
            long now,
            boolean login,
            Settings settings)
            throws SQLException {
        if (!login || !settings.gui().enabled() || !settings.gui().login()) {
            return false;
        }
        boolean nearExpiry =
                balance.status() == VipBalance.Status.EXPIRED
                        || balance.active(now)
                                && settings.notifications().thresholds().stream()
                                        .anyMatch(
                                                t -> balance.remaining(now) <= t.seconds() * 1000);
        if (nearExpiry && (settings.gui().repeatLogin() || !sent.contains("login-menu"))) {
            if (!sent.contains("login-menu")) {
                claim(c, balance, "login-menu", now);
            }
            return true;
        }
        return false;
    }

    private void claim(java.sql.Connection c, VipBalance balance, String threshold, long now)
            throws SQLException {
        Sql.update(
                c,
                "INSERT INTO notification_receipts(uuid,vip_type,revision,threshold_id,created_at)"
                        + " VALUES(?,?,?,?,?)",
                balance.player().toString(),
                balance.type(),
                balance.revision(),
                threshold,
                now);
    }
}
