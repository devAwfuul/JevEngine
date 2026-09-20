package dev.awfuul.jevengine.modules.chatfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells people what happened: verbose watchers, staff alerts, and the console.
 *
 * <p>Verdicts arrive on a virtual thread, so anything that reads the online
 * player list hops to the main thread first. Delivering a held chat message does
 * not go through here and stays off the main thread entirely, which keeps the
 * hot path clear. Alerts are rare enough that a tick of delay costs nothing.
 */
public final class Reporter {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final Set<UUID> watchers = ConcurrentHashMap.newKeySet();

    private volatile FilterMessages messages;
    private volatile ChatFilterConfig config;

    public Reporter(Plugin plugin, FilterMessages messages, ChatFilterConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    public void apply(FilterMessages replacement, ChatFilterConfig replacementConfig) {
        this.messages = replacement;
        this.config = replacementConfig;
    }

    public FilterMessages messages() {
        return messages;
    }

    public boolean toggleWatching(UUID player) {
        if (watchers.remove(player)) {
            return false;
        }
        watchers.add(player);
        return true;
    }

    public void setWatching(UUID player, boolean watching) {
        if (watching) {
            watchers.add(player);
        } else {
            watchers.remove(player);
        }
    }

    public boolean watching(UUID player) {
        return watchers.contains(player);
    }

    public void forget(UUID player) {
        watchers.remove(player);
    }

    public int watcherCount() {
        return watchers.size();
    }

    public void publish(String playerName, Verdict verdict) {
        ChatFilterConfig current = config;
        FilterMessages text = messages;
        boolean interesting = verdict.action() != Action.PASS;

        if (!interesting && !current.verbose().showClean() && !current.verbose().console()) {
            return;
        }

        Component report = text.report(playerName, verdict, current,
                current.verbose().showAllQuestions());

        if (current.verbose().console() && (interesting || current.verbose().showClean())) {
            plugin.getLogger().info(PLAIN.serialize(report).replace("\n", " | "));
        }

        if (watchers.isEmpty() && !interesting) {
            return;
        }

        Component alert = interesting ? text.alert(playerName, verdict) : null;
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                UUID id = online.getUniqueId();
                if (watchers.contains(id) && online.hasPermission("jevengine.chatfilter.verbose")) {
                    if (interesting || current.verbose().showClean()) {
                        online.sendMessage(report);
                    }
                } else if (alert != null && online.hasPermission("jevengine.chatfilter.notify")) {
                    online.sendMessage(alert);
                }
            }
        });
    }

    /** What the player who sent the message sees when it does not go through. */
    public void tellSender(Player player, Verdict verdict) {
        FilterMessages current = messages;
        if (verdict.failedOpen() && verdict.action() == Action.BLOCK) {
            player.sendMessage(current.line("failed-open",
                    "<gray>Moderation is offline, so messages are not going through.</gray>"));
            return;
        }
        // In low latency mode the sender has already watched their own message
        // appear, so the wording has to tell them it did not actually go out.
        if (config.chat().lowLatency()) {
            player.sendMessage(current.notice("blocked-low-latency",
                    "<color:#ff3030>Your message was found to be violating our rules "
                            + "and was not sent.</color>"));
            return;
        }

        if (verdict.action() == Action.BLOCK) {
            player.sendMessage(current.line("blocked", "That message was not sent."));
            String detail = config.line("blocked-detail", "");
            if (!detail.isBlank()) {
                player.sendMessage(current.line("blocked-detail", detail));
            }
        } else if (verdict.action() == Action.PUNISH) {
            player.sendMessage(current.line("punished",
                    "That message was not sent, and it has been logged."));
        }
    }
}
