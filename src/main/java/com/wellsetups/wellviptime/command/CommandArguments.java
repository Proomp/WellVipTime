package com.wellsetups.wellviptime.command;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.vip.DomainFailure;

import org.bukkit.command.CommandSender;

import java.util.*;

final class CommandArguments {

    private final Map<String, Settings.Command> definitions;

    CommandArguments(Map<String, Settings.Command> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    static String child(Map<String, Settings.Command> definitions, String root, String text) {
        return definitions.entrySet().stream()
                .filter(
                        e ->
                                e.getKey().startsWith(root + ".")
                                        && !e.getKey().substring(root.length() + 1).contains(".")
                                        && (e.getValue().name().equalsIgnoreCase(text)
                                                || e.getValue().aliases().stream()
                                                        .anyMatch(text::equalsIgnoreCase)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public CommandRequest parse(CommandSender sender, String root, String[] args) {
        return switch (root) {
            case "vipadmin" -> admin(sender, root, args);
            case "viptime" -> time(sender, root, args);
            case "vipfreeze" -> freeze(sender, root, args);
            case "viplist", "vipleaderboard" -> listing(sender, root, args);
            default -> {
                if (args.length > 0) {
                    throw usage(root);
                }
                yield new CommandRequest(sender, root, null, null, null, 1, 1);
            }
        };
    }

    private CommandRequest admin(CommandSender sender, String root, String[] args) {
        String id = root;
        String vip = null;
        String sub = args.length == 0 ? null : child(definitions, root, args[0]);
        if (args.length == 0) {
            return new CommandRequest(sender, root, null, null, null, 1, 1);
        }
        if (sub == null) {
            throw usage(root);
        }
        id = sub;
        if (id.equals("vipadmin.reload")) {
            if (args.length != 1) {
                throw usage(root);
            }
        } else {
            if (args.length > 2) {
                throw usage(root);
            }
            vip = args.length == 2 ? args[1] : null;
        }
        return new CommandRequest(sender, id, null, vip, null, 1, 1);
    }

    private CommandRequest time(CommandSender sender, String root, String[] args) {
        String id = args.length == 0 ? null : child(definitions, root, args[0]);
        if (id == null) {
            if (args.length > 1) {
                throw usage(root);
            }
            return new CommandRequest(
                    sender,
                    args.length == 1 ? "viptime.others" : root,
                    args.length == 1 ? args[0] : null,
                    null,
                    null,
                    1,
                    1);
        }
        if (id.equals("viptime.item")) {
            return item(sender, root, args);
        }
        if (id.equals("viptime.others")) {
            if (args.length != 2) {
                throw usage(root);
            }
            return new CommandRequest(sender, id, args[1], null, null, 1, 1);
        }
        if (args.length != 4) {
            throw usage(root);
        }
        return switch (id) {
            case "viptime.give" -> new CommandRequest(sender, id, args[3], args[1], args[2], 1, 1);
            case "viptime.remove" ->
                    new CommandRequest(sender, id, args[1], args[2], args[3], 1, 1);
            default -> throw usage(root);
        };
    }

    private CommandRequest item(CommandSender sender, String root, String[] args) {
        String id = args.length > 1 ? child(definitions, "viptime.item", args[1]) : null;
        if (id == null) {
            if (args.length < 3 || args.length > 4) {
                throw usage(root);
            }
            int amount = args.length == 4 ? integer(args[3], 1, 64, root) : 1;
            return new CommandRequest(sender, "viptime.item", null, args[1], args[2], amount, 1);
        }
        boolean all = id.equals("viptime.item.giveall");
        int required = all ? 4 : 5;
        if (args.length < required || args.length > required + 1) {
            throw usage(root);
        }
        int amount = args.length > required ? integer(args[required], 1, 64, root) : 1;
        return new CommandRequest(sender, id, all ? null : args[4], args[2], args[3], amount, 1);
    }

    private CommandRequest freeze(CommandSender sender, String root, String[] args) {
        String id = root;
        String player = null;
        String vip = null;
        String sub = args.length == 0 ? null : child(definitions, root, args[0]);
        if (sub != null) {
            if (args.length != 3) {
                throw usage(root);
            }
            id = sub;
            player = args[1];
            vip = args[2];
        } else if (args.length == 1) {
            vip = args[0];
        } else if (args.length == 2) {
            id = "vipfreeze.others";
            player = args[0];
            vip = args[1];
        } else if (args.length > 2) {
            throw usage(root);
        }
        return new CommandRequest(sender, id, player, vip, null, 1, 1);
    }

    private CommandRequest listing(CommandSender sender, String root, String[] args) {
        if (args.length > (root.equals("viplist") ? 2 : 1)) {
            throw usage(root);
        }
        int page = args.length > 0 ? integer(args[0], 1, 100000, root) : 1;
        String vip = args.length > 1 ? args[1] : null;
        return new CommandRequest(sender, root, null, vip, null, 1, page);
    }

    private int integer(String input, int min, int max, String root) {
        try {
            int value = Integer.parseInt(input);
            if (value < min || value > max) {
                throw usage(root);
            }
            return value;
        } catch (NumberFormatException e) {
            throw usage(root);
        }
    }

    private DomainFailure usage(String root) {
        String label = "/" + definitions.get(root).name();
        String hint =
                switch (root) {
                    case "vipadmin" ->
                            " "
                                    + definitions.get("vipadmin.reload").name()
                                    + " | "
                                    + definitions.get("vipadmin.recover").name()
                                    + " [voucher-uuid] | "
                                    + definitions.get("vipadmin.revoke").name()
                                    + " [voucher-uuid]";
                    case "viptime" ->
                            " [player] | "
                                    + definitions.get("viptime.give").name()
                                    + " <vip> <duration> <player> | "
                                    + definitions.get("viptime.remove").name()
                                    + " <player> <vip> <duration> | "
                                    + definitions.get("viptime.item").name()
                                    + " <vip> <duration> [amount] | "
                                    + definitions.get("viptime.item").name()
                                    + " "
                                    + definitions.get("viptime.item.give").name()
                                    + " <vip> <duration> <player> [amount] | "
                                    + definitions.get("viptime.item").name()
                                    + " "
                                    + definitions.get("viptime.item.giveall").name()
                                    + " <vip> <duration> [amount]";
                    case "vipfreeze" -> " [player] [vip]";
                    case "viplist" -> " [page] [vip]";
                    case "vipleaderboard" -> " [page]";
                    default -> "";
                };
        return new DomainFailure("command.usage", Map.of("usage", label + hint));
    }
}
