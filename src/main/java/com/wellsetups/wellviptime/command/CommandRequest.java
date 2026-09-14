package com.wellsetups.wellviptime.command;

import org.bukkit.command.CommandSender;

public record CommandRequest(
        CommandSender sender,
        String id,
        String player,
        String vip,
        String duration,
        int amount,
        int page) {}
