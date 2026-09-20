package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.core.JevModule;
import dev.awfuul.jevengine.core.ModuleContext;
import dev.awfuul.jevengine.ui.Branding;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Realtime chat moderation.
 *
 * <p>Owns chatfilter.yml, the chat listener and everything that reads a verdict.
 * The only things it takes from the engine are the Jev client and the branding.
 */
public final class ChatFilterModule implements JevModule {

    public static final String ID = "chatfilter";
    private static final String FILE = "chatfilter.yml";

    private ModuleContext context;
    private ModerationEngine engine;
    private Reporter reporter;
    private PunishmentRunner punishments;
    private FilterMessages messages;
    private ChatListener listener;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Chat filter";
    }

    @Override
    public String description() {
        return "Holds each message, judges it with Jev, and releases it.";
    }

    @Override
    public Set<String> commandAliases() {
        // Staff reach for these constantly, so they work without naming the
        // module. The router checks module ids first, so a future module called
        // "test" would still win its own name.
        return Set.of("verbose", "test");
    }

    @Override
    public void enable(ModuleContext moduleContext) {
        this.context = moduleContext;
        ChatFilterConfig config = ChatFilterConfig.load(moduleContext.config(FILE));
        warn(config);

        Branding branding = moduleContext.branding();
        Plugin plugin = moduleContext.plugin();

        this.engine = new ModerationEngine(config, moduleContext.jev());
        this.messages = new FilterMessages(branding, config);
        this.punishments = new PunishmentRunner(plugin, config);
        this.reporter = new Reporter(plugin, messages, config);
        this.listener = new ChatListener(plugin, engine, reporter, punishments);

        register(config);
    }

    @Override
    public void disable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        if (engine != null) {
            engine.shutdown();
        }
    }

    @Override
    public List<String> reload() {
        FileConfiguration file = context.config(FILE);
        ChatFilterConfig config = ChatFilterConfig.load(file);
        warn(config);

        engine.apply(config);
        punishments.apply(config);
        messages.apply(config);
        reporter.apply(messages, config);
        listener.apply(config);

        // The event priority is a config value and Bukkit fixes a priority at
        // registration time, so the listener has to go back on to pick up a
        // change to it.
        HandlerList.unregisterAll(listener);
        register(config);

        return config.warnings();
    }

    private void register(ChatFilterConfig config) {
        Plugin plugin = context.plugin();
        plugin.getServer().getPluginManager().registerEvent(
                AsyncChatEvent.class,
                listener,
                config.chat().priority(),
                (target, event) -> {
                    if (event instanceof AsyncChatEvent chat) {
                        ((ChatListener) target).onChat(chat);
                    }
                },
                plugin,
                true);
        // Picks up the @EventHandler methods, which the call above does not.
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private void warn(ChatFilterConfig config) {
        for (String warning : config.warnings()) {
            context.logger().warning("[" + ID + "] " + warning);
        }
        if (config.punishments().enabled()) {
            context.logger().info("[" + ID + "] Punishments are on"
                    + (config.punishments().duration().enabled()
                            ? " and Jev is picking durations." : " with fixed durations."));
        }
    }

    @Override
    public boolean handleCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "verbose" -> verbose(sender, args);
            case "test" -> test(sender, args);
            default -> usage(sender, label);
        }
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(messages.say("Chat filter"));
        sender.sendMessage(hint("/" + label + " verbose", "watch decisions as they happen"));
        sender.sendMessage(hint("/" + label + " test <message>",
                "judge a line without sending it"));
    }

    private static Component hint(String command, String description) {
        return Component.text()
                .append(Component.text("  " + command + " ", NamedTextColor.WHITE))
                .append(Component.text(description, Branding.MUTED))
                .build();
    }

    private void verbose(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.say(
                    "Verbose output goes to players. Set verbose.console in "
                            + FILE + " for this."));
            return;
        }
        if (!player.hasPermission("jevengine.chatfilter.verbose")) {
            sender.sendMessage(messages.line("no-permission",
                    "You do not have permission to do that."));
            return;
        }
        UUID id = player.getUniqueId();
        boolean watching;
        if (args.length > 1) {
            watching = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
            reporter.setWatching(id, watching);
        } else {
            watching = reporter.toggleWatching(id);
        }
        player.sendMessage(messages.say(watching
                ? "Verbose on. You will see every decision."
                : "Verbose off."));
    }

    private void test(CommandSender sender, String[] args) {
        if (!sender.hasPermission("jevengine.admin")) {
            sender.sendMessage(messages.line("no-permission",
                    "You do not have permission to do that."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.say("Give it something to judge."));
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String name = sender instanceof Player player ? player.getName() : "Console";
        UUID id = sender instanceof Player player ? player.getUniqueId() : new UUID(0L, 0L);

        sender.sendMessage(messages.say("Asking Jev..."));

        // Nothing is delivered, nothing is punished, and it does not go into the
        // player's history. This is the safe way to check a threshold change
        // against real phrasing before it touches chat.
        Plugin plugin = context.plugin();
        engine.evaluate(text, new ModerationEngine.Subject(name, id), List.of(), false)
                .thenAccept(verdict -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(messages.report(name, verdict, engine.config(),
                                    true)));
                });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : List.of("verbose", "test")) {
                if (option.startsWith(prefix)) {
                    out.add(option);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("verbose")) {
            return List.of("on", "off");
        }
        return List.of();
    }

    @Override
    public void appendStatus(Consumer<Component> out) {
        ChatFilterConfig config = engine.config();
        FilterMetrics metrics = engine.metrics();

        out.accept(row("asking", engine.activeQuestions().size() + " questions, "
                + config.hazards().size() + " hazards"));
        out.accept(row("punishments", config.punishments().enabled()
                ? (config.punishments().duration().enabled()
                        ? "on, Jev picks the duration" : "on, fixed duration")
                : "off"));
        out.accept(row("judged", metrics.evaluated() + " total, "
                + metrics.passed() + " pass, " + metrics.flagged() + " flag, "
                + metrics.blocked() + " block, " + metrics.punished() + " punish"));
        out.accept(row("cache", Math.round(engine.cache().hitRate() * 100)
                + "% hit rate, " + engine.cache().size() + " entries"));
        out.accept(row("watching", reporter.watcherCount() + " staff in verbose"));
    }

    private static Component row(String label, String value) {
        return Component.text()
                .append(Component.text("    " + label + " ", Branding.MUTED))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    /** Exposed for the engine's own status output and for tests. */
    public ModerationEngine engine() {
        return engine;
    }

    public Reporter reporter() {
        return reporter;
    }
}
