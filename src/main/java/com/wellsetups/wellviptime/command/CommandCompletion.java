package com.wellsetups.wellviptime.command;

import com.wellsetups.wellviptime.configuration.Settings;

import org.bukkit.command.CommandSender;

import java.util.*;
import java.util.function.Supplier;

final class CommandCompletion {

    private final Map<String, Settings.Command> definitions;

    private final Supplier<Settings> settings;

    private final Supplier<List<String>> names;

    CommandCompletion(
            Map<String, Settings.Command> definitions,
            Supplier<Settings> settings,
            Supplier<List<String>> names) {
        this.definitions = Map.copyOf(definitions);
        this.settings = settings;
        this.names = names;
    }

    public List<String> suggest(CommandSender sender, String root, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        List<String> choices =
                args.length == 1 ? rootChoices(sender, root) : argumentChoices(sender, root, args);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .sorted()
                .limit(100)
                .toList();
    }

    private boolean permitted(CommandSender sender, String id) {
        return sender.hasPermission(definitions.get(id).permission());
    }

    private void children(CommandSender sender, String root, List<String> choices) {
        String prefix = root + ".";
        for (var entry : definitions.entrySet()) {
            String id = entry.getKey();
            if (!id.startsWith(prefix) || id.substring(prefix.length()).contains(".")) {
                continue;
            }
            boolean accessible =
                    permitted(sender, id)
                            || definitions.keySet().stream()
                                    .anyMatch(
                                            nested ->
                                                    nested.startsWith(id + ".")
                                                            && permitted(sender, nested));
            if (accessible) {
                choices.add(entry.getValue().name());
                choices.addAll(entry.getValue().aliases());
            }
        }
    }

    private List<String> rootChoices(CommandSender sender, String root) {
        List<String> choices = new ArrayList<>();
        children(sender, root, choices);
        if (root.equals("viptime") && permitted(sender, "viptime.others")) {
            choices.addAll(names.get());
        }
        if (root.equals("vipfreeze") && permitted(sender, root)) {
            choices.addAll(settings.get().types().keySet());
        }
        if ((root.equals("viplist") || root.equals("vipleaderboard")) && permitted(sender, root)) {
            choices.addAll(List.of("1", "2"));
        }
        return choices;
    }

    private List<String> argumentChoices(CommandSender sender, String root, String[] args) {
        if (root.equals("viplist") && args.length == 2 && permitted(sender, root)) {
            return List.copyOf(settings.get().types().keySet());
        }
        String id = CommandArguments.child(definitions, root, args[0]);
        if (id == null) {
            return List.of();
        }
        if (id.equals("viptime.item")) {
            return itemChoices(sender, args);
        }
        if (!permitted(sender, id)) {
            return List.of();
        }
        return switch (id) {
            case "viptime.give" -> choicesAt(args.length, 2, 3, 4);
            case "viptime.remove" -> choicesAt(args.length, 3, 4, 2);
            case "vipfreeze.others" -> choicesAt(args.length, 3, -1, 2);
            case "viptime.others" -> choicesAt(args.length, -1, -1, 2);
            default -> List.of();
        };
    }

    private List<String> itemChoices(CommandSender sender, String[] args) {
        String nested = CommandArguments.child(definitions, "viptime.item", args[1]);
        if (args.length > 2 && nested != null) {
            return giftChoices(sender, nested, args.length);
        }
        List<String> choices = new ArrayList<>();
        if (args.length == 2) {
            children(sender, "viptime.item", choices);
        }
        if (permitted(sender, "viptime.item")) {
            choices.addAll(choicesAt(args.length, 2, 3, -1));
        }
        return choices;
    }

    private List<String> giftChoices(CommandSender sender, String nested, int position) {
        if (!permitted(sender, nested)) {
            return List.of();
        }
        if (position == 3) {
            return List.copyOf(settings.get().types().keySet());
        }
        if (position == 4) {
            return List.of("30d", "7d", "12h");
        }
        boolean give = nested.equals("viptime.item.give");
        if (position == 5 && give) {
            return names.get();
        }
        if (position == (give ? 6 : 5)) {
            return List.of("1", "5");
        }
        return List.of();
    }

    private List<String> choicesAt(int position, int type, int duration, int player) {
        if (position == type) {
            return List.copyOf(settings.get().types().keySet());
        }
        if (position == duration) {
            return List.of("30d", "12h", "30m", "45s", "7d12h30m");
        }
        if (position == player) {
            return names.get();
        }
        return List.of();
    }
}
