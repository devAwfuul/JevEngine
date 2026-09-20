package dev.awfuul.jevengine.command;

import dev.awfuul.jevengine.JevEnginePlugin;
import dev.awfuul.jevengine.api.ApiMetrics;
import dev.awfuul.jevengine.core.CoreConfig;
import dev.awfuul.jevengine.core.JevModule;
import dev.awfuul.jevengine.ui.Branding;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /jevengine}.
 *
 * <p>Engine-wide words are handled here. Anything else is a module's name, or a
 * word a module asked to answer to, and the rest of the line is passed straight
 * through to it. That way a new module brings its own subcommand without this
 * class needing to know it exists.
 */
public final class JevEngineCommand implements CommandExecutor, TabCompleter {

    private static final List<String> CORE_WORDS = List.of("reload", "modules", "status", "module");

    private final JevEnginePlugin plugin;

    public JevEngineCommand(JevEnginePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Branding branding = plugin.branding();

        if (!sender.hasPermission("jevengine.admin")
                && !sender.hasPermission("jevengine.chatfilter.verbose")) {
            sender.sendMessage(branding.line(
                    plugin.core().line("no-permission", "You do not have permission to do that."),
                    Map.of()));
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "reload" -> reload(sender, args);
            case "modules" -> listModules(sender);
            case "status" -> status(sender);
            case "module" -> module(sender, label, args);
            default -> {
                JevModule module = plugin.modules().find(first);
                if (module == null) {
                    sender.sendMessage(branding.say("No module or command called '" + first
                            + "'. Try /" + label + " modules."));
                    return true;
                }
                // A module alias such as "verbose" is itself the subcommand, so
                // it stays in the arguments. Naming the module drops it.
                String[] rest = module.id().equalsIgnoreCase(first)
                        ? Arrays.copyOfRange(args, 1, args.length)
                        : args;
                if (!module.handleCommand(sender, label + " " + module.id(), rest)) {
                    sender.sendMessage(branding.say("That is not something "
                            + module.displayName() + " does."));
                }
            }
        }
        return true;
    }

    private void usage(CommandSender sender, String label) {
        Branding branding = plugin.branding();
        sender.sendMessage(branding.say("Commands"));
        sender.sendMessage(hint("/" + label + " status", "model, latency, spend, modules"));
        sender.sendMessage(hint("/" + label + " modules", "what is registered and running"));
        sender.sendMessage(hint("/" + label + " reload [module]", "re-read config files"));
        sender.sendMessage(hint("/" + label + " module <module> ...", "run a module command"));
        for (JevModule module : plugin.modules().running()) {
            sender.sendMessage(hint("/" + label + " " + module.id() + " ...",
                    module.description()));
        }
    }

    private void module(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            usage(sender, label);
            return;
        }
        String moduleName = args[1].toLowerCase(Locale.ROOT);
        JevModule module = plugin.modules().find(moduleName);
        if (module == null) {
            sender.sendMessage(plugin.branding().say("No running module called '" + moduleName
                    + "'."));
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 2, args.length);
        if (!module.handleCommand(sender, label + " module " + module.id(), rest)) {
            sender.sendMessage(plugin.branding().say("That is not something "
                    + module.displayName() + " does."));
        }
    }

    private static Component hint(String command, String description) {
        return Component.text()
                .append(Component.text("  " + command + " ", NamedTextColor.WHITE))
                .append(Component.text(description, Branding.MUTED))
                .build();
    }

    private void reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("jevengine.admin")) {
            sender.sendMessage(plugin.branding().say("You do not have permission to do that."));
            return;
        }
        Branding branding = plugin.branding();
        List<String> warnings;

        if (args.length > 1) {
            JevModule module = plugin.modules().find(args[1]);
            if (module == null) {
                sender.sendMessage(branding.say("No module called '" + args[1] + "'."));
                return;
            }
            warnings = plugin.modules().reloadOne(module);
            sender.sendMessage(branding.say("Reloaded " + module.displayName() + "."));
        } else {
            warnings = plugin.reloadEverything();
            sender.sendMessage(branding.say("Reloaded config.yml and "
                    + plugin.modules().running().size() + " modules."));
        }

        for (String warning : warnings) {
            sender.sendMessage(Component.text("  " + warning, NamedTextColor.YELLOW));
        }
        if (!plugin.core().api().hasKey()) {
            sender.sendMessage(Component.text(
                    "  No API key. Set api.key or the TYPESAFE_API_KEY environment variable.",
                    NamedTextColor.RED));
        }
    }

    private void listModules(CommandSender sender) {
        sender.sendMessage(plugin.branding().say("Modules"));
        for (JevModule module : plugin.modules().all()) {
            boolean running = plugin.modules().isRunning(module.id());
            sender.sendMessage(Component.text()
                    .append(Component.text("  " + module.id() + " ", NamedTextColor.WHITE))
                    .append(Component.text(running ? "on" : "off",
                            running ? Branding.PASS : Branding.MUTED))
                    .append(Component.text("  " + module.description(), Branding.MUTED))
                    .build());
        }
    }

    private void status(CommandSender sender) {
        CoreConfig core = plugin.core();
        ApiMetrics api = plugin.jev().metrics();
        Branding branding = plugin.branding();

        sender.sendMessage(branding.say("Status"));
        sender.sendMessage(row("model", core.api().model()
                + (core.api().hasKey() ? ", key set" : ", NO KEY")));
        sender.sendMessage(row("requests", api.requests() + " total, " + api.errors() + " failed"));
        sender.sendMessage(row("latency", api.percentile(0.50D) + "ms median, "
                + api.percentile(0.95D) + "ms at p95"));
        sender.sendMessage(row("spend", api.inputTokens() + " input tokens, about "
                + String.format(Locale.ROOT, "$%.4f", api.estimatedCostUsd())));

        for (JevModule module : plugin.modules().running()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("  " + module.id(), branding.body())
                            .decorate(TextDecoration.BOLD))
                    .build());
            module.appendStatus(sender::sendMessage);
        }
    }

    private static Component row(String label, String value) {
        return Component.text()
                .append(Component.text("  " + label + " ", Branding.MUTED))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                      String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String word : CORE_WORDS) {
                if (word.startsWith(prefix)) {
                    out.add(word);
                }
            }
            for (JevModule module : plugin.modules().running()) {
                if (module.id().startsWith(prefix)) {
                    out.add(module.id());
                }
                for (String moduleAlias : module.commandAliases()) {
                    if (moduleAlias.startsWith(prefix)) {
                        out.add(moduleAlias);
                    }
                }
            }
            return out;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("reload")) {
            List<String> out = new ArrayList<>();
            for (JevModule module : plugin.modules().running()) {
                out.add(module.id());
            }
            return out;
        }

        if (first.equals("module")) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return plugin.modules().running().stream()
                        .map(JevModule::id)
                        .filter(id -> id.startsWith(prefix))
                        .toList();
            }
            if (args.length > 2) {
                JevModule module = plugin.modules().find(args[1]);
                if (module == null) {
                    return List.of();
                }
                return module.tabComplete(sender, Arrays.copyOfRange(args, 2, args.length));
            }
        }

        JevModule module = plugin.modules().find(first);
        if (module == null) {
            return List.of();
        }
        String[] rest = module.id().equalsIgnoreCase(first)
                ? Arrays.copyOfRange(args, 1, args.length)
                : args;
        return module.tabComplete(sender, rest);
    }
}
