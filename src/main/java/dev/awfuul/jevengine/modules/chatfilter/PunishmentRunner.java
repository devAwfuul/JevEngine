package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.api.Answer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs the configured command for a punishment.
 *
 * <p>Commands go through the console sender on the main thread, because that is
 * where Bukkit expects dispatch to happen and most ban plugins assume it. The
 * player name is stripped to the characters a name can legitimately contain
 * before it is substituted, so a display name can never carry extra arguments
 * into the command line.
 */
public final class PunishmentRunner {

    private static final Pattern UNSAFE_IN_NAME = Pattern.compile("[^A-Za-z0-9_.-]");

    private final Plugin plugin;
    private volatile ChatFilterConfig config;

    public PunishmentRunner(Plugin plugin, ChatFilterConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void apply(ChatFilterConfig replacement) {
        this.config = replacement;
    }

    public void run(String playerName, UUID playerId, Verdict verdict) {
        if (!verdict.punishes()) {
            return;
        }
        ChatFilterConfig current = config;
        String template = current.punishments().commandFor(verdict.category());
        if (template == null || template.isBlank()) {
            plugin.getLogger().warning("No punish command configured for '" + verdict.category()
                    + "' and no default, so nothing ran for " + playerName + ".");
            return;
        }

        String safeName = UNSAFE_IN_NAME.matcher(playerName).replaceAll("");
        if (safeName.isEmpty()) {
            safeName = playerId.toString();
        }

        String reason = current.punishments().reason()
                .replace("%category%", verdict.category());

        String command = template
                .replace("%player%", safeName)
                .replace("%uuid%", playerId.toString())
                .replace("%duration%", verdict.duration() == null ? "" : verdict.duration())
                .replace("%category%", verdict.category())
                .replace("%severity%", severityOf(current, verdict))
                .replace("%reason%", reason)
                .trim();

        plugin.getLogger().info("Punishing " + safeName + " for " + verdict.category()
                + " (" + verdict.tier() + "): /" + command);

        String punishedName = safeName;

        if (!plugin.isEnabled()) {
            plugin.getLogger().warning("JevEngine is shutting down, so that command did not run.");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Punishment command failed for " + punishedName
                        + " (" + verdict.category() + "): " + failure.getMessage());
            }
        });
    }

    private static String severityOf(ChatFilterConfig config, Verdict verdict) {
        Answer answer = verdict.answers().get(config.severity().question());
        if (answer instanceof Answer.Score score) {
            return String.format(Locale.ROOT, "%.2f", score.score());
        }
        return "0.00";
    }
}
