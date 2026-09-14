package com.wellsetups.wellviptime.command;

import com.wellsetups.wellviptime.configuration.Settings;
import com.wellsetups.wellviptime.language.Messages;
import com.wellsetups.wellviptime.vip.DomainFailure;

import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.*;

/**
 * Portable Bukkit registration and parsing; public labels/permissions still come from commands.yml.
 */
public final class CommandTree implements AutoCloseable {

    private final Map<String, Settings.Command> definitions;

    private final CommandArguments arguments;

    private final CommandCompletion completion;

    private final Consumer<CommandRequest> dispatch;

    private final List<Command> registered = new ArrayList<>();

    private CommandMap commandMap;

    public CommandTree(
            Map<String, Settings.Command> definitions,
            Supplier<Settings> settings,
            Supplier<List<String>> names,
            Consumer<CommandRequest> dispatch) {
        this.definitions = Map.copyOf(definitions);
        this.arguments = new CommandArguments(definitions);
        this.completion = new CommandCompletion(definitions, settings, names);
        this.dispatch = dispatch;
    }

    public void register(JavaPlugin plugin, Messages messages) {
        try {
            commandMap =
                    (CommandMap)
                            plugin.getServer()
                                    .getClass()
                                    .getMethod("getCommandMap")
                                    .invoke(plugin.getServer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Bukkit command map unavailable", e);
        }
        for (String id :
                definitions.keySet().stream().filter(key -> !key.contains(".")).sorted().toList()) {
            var definition = definitions.get(id);
            Command command =
                    new Command(
                            definition.name(),
                            definition.description(),
                            "/" + definition.name(),
                            definition.aliases()) {

                        @Override
                        public boolean execute(CommandSender sender, String label, String[] args) {
                            try {
                                var request = parse(sender, id, args);
                                if (!sender.hasPermission(
                                        definitions.get(request.id()).permission())) {
                                    messages.send(sender, "error.permission");
                                } else {
                                    dispatch.accept(request);
                                }
                            } catch (DomainFailure e) {
                                messages.send(sender, e.key(), e.values());
                            }
                            return true;
                        }

                        @Override
                        public List<String> tabComplete(
                                CommandSender sender, String alias, String[] args) {
                            return suggest(sender, id, args);
                        }
                    };
            if (!commandMap.register("wellviptime", command)) {
                plugin.getLogger()
                        .warning(
                                "Command collision: /"
                                        + definition.name()
                                        + "; use /wellviptime:"
                                        + definition.name());
            }
            registered.add(command);
        }
    }

    public CommandRequest parse(CommandSender sender, String root, String[] args) {
        return arguments.parse(sender, root, args);
    }

    public List<String> suggest(CommandSender sender, String root, String[] args) {
        return completion.suggest(sender, root, args);
    }

    @Override
    public void close() {
        if (commandMap != null) {
            registered.forEach(command -> command.unregister(commandMap));
        }
    }
}
