package dev.awfuul.jevengine.modules.chatfilter;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Holds each chat message until Jev has answered, then lets it go.
 *
 * <p>The event is cancelled and the message re-sent rather than edited in place,
 * because a verdict takes a network round trip and an event handler cannot wait
 * for one. The renderer and viewer set are captured first, so the message that
 * comes back a moment later looks exactly like the one the server would have
 * sent, including whatever a chat formatting plugin did to it.
 *
 * <p>One consequence worth knowing: a re-sent message is not signed, so it
 * arrives as system chat. That is true of every plugin that rewrites chat, and
 * it is the price of not showing a slur to the server before deciding about it.
 */
public final class ChatListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final int CONTEXT_MEMORY = 24;

    private final Plugin plugin;
    private final ModerationEngine engine;
    private final Reporter reporter;
    private final PunishmentRunner punishments;
    private final ViolationTracker violations;

    /** One chain per player, so a player's own messages always arrive in order. */
    private final Map<UUID, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();

    private final Deque<String> recentChat = new ArrayDeque<>(CONTEXT_MEMORY);

    public ChatListener(Plugin plugin, ModerationEngine engine, Reporter reporter,
                        PunishmentRunner punishments) {
        this.plugin = plugin;
        this.engine = engine;
        this.reporter = reporter;
        this.punishments = punishments;
        this.violations = new ViolationTracker(engine.config().violations());
    }

    public void apply(ChatFilterConfig config) {
        violations.apply(config.violations());
    }

    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig config = engine.config();
        if (!config.chat().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("jevengine.chatfilter.bypass")) {
            return;
        }

        String text = PLAIN.serialize(event.message());
        ModerationEngine.Subject subject = new ModerationEngine.Subject(
                player.getName(), player.getUniqueId(), tenureDays(player));
        List<String> context = snapshotContext();

        if (!config.chat().hold()) {
            // Immediate delivery. The message is already gone by the time the
            // verdict lands, so a punishment is the only thing left to apply.
            remember(player.getName(), text);
            queue(player.getUniqueId(), () -> engine.evaluate(text, subject, context)
                    .thenAccept(verdict -> finish(player, verdict)));
            return;
        }

        event.setCancelled(true);

        Set<Audience> viewers = Set.copyOf(event.viewers());
        ChatRenderer renderer = event.renderer();
        Component message = event.message();
        Component displayName = player.displayName();

        // Low latency mode: the sender sees their own line immediately and the
        // room waits for the verdict. Typing stops feeling laggy, and a message
        // that breaks a rule still never reaches anyone else. The cost is that
        // the sender briefly sees a message that is not going anywhere, which is
        // what the blocked notice exists to explain.
        boolean shownToSender = config.chat().lowLatency() && viewers.contains(player);
        if (shownToSender) {
            sendTo(player, player, displayName, message, renderer);
        }

        queue(player.getUniqueId(), () -> engine.evaluate(text, subject, context)
                .thenAccept(verdict -> {
                    if (verdict.delivers()) {
                        deliver(player, displayName, message, renderer, viewers,
                                shownToSender ? player : null);
                        remember(player.getName(), text);
                    }
                    finish(player, verdict);
                }));
    }

    /**
     * @param alreadySeen a viewer who was sent this message up front in low
     *                    latency mode, and must not be sent it twice
     */
    private void deliver(Player source, Component displayName, Component message,
                         ChatRenderer renderer, Set<Audience> viewers,
                         Audience alreadySeen) {
        for (Audience viewer : viewers) {
            if (viewer.equals(alreadySeen)) {
                continue;
            }
            sendTo(viewer, source, displayName, message, renderer);
        }
    }

    private void sendTo(Audience viewer, Player source, Component displayName,
                        Component message, ChatRenderer renderer) {
        try {
            viewer.sendMessage(renderer.render(source, displayName, message, viewer));
        } catch (RuntimeException failure) {
            // A renderer from another plugin throwing should not cost every
            // other viewer their copy of the message.
            plugin.getLogger().warning("A chat renderer failed while the filter was "
                    + "releasing a message: " + failure);
        }
    }

    private void finish(Player player, Verdict verdict) {
        verdict = applyViolation(player.getUniqueId(), verdict);
        reporter.publish(player.getName(), verdict);
        if (!verdict.delivers()) {
            reporter.tellSender(player, verdict);
        }
        punishments.run(player.getName(), player.getUniqueId(), verdict);
    }

    private Verdict applyViolation(UUID player, Verdict verdict) {
        ChatFilterConfig config = engine.config();
        if (verdict.action() != Action.FLAG || !config.punishments().enabled()) {
            return verdict;
        }
        int count = violations.record(player, verdict.category());
        if (count == 0) {
            return verdict;
        }
        ChatFilterConfig.DurationPolicy duration = config.punishments().duration();
        String tier = duration.clamp(duration.defaultTier(), verdict.category());
        String value = duration.valueFor(tier);
        String reason = verdict.reason() + ", violation threshold reached ("
                + count + " in " + (config.violations().windowMillis() / 1000L) + "s)";
        return verdict.asViolationPunishment(tier, value, reason);
    }

    /**
     * Chains work behind whatever is already running for this player. Two
     * messages sent a moment apart cannot overtake each other, which matters
     * because a slower verdict on the first would otherwise reorder them.
     */
    private void queue(UUID player, java.util.function.Supplier<CompletableFuture<Void>> work) {
        queues.compute(player, (id, running) -> {
            CompletableFuture<Void> previous = running == null
                    ? CompletableFuture.completedFuture(null)
                    : running;
            return previous
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> work.get())
                    .exceptionally(error -> {
                        plugin.getLogger().warning("The chat filter failed on a message: " + error);
                        return null;
                    });
        });
    }

    /**
     * Days since this player first joined, or null if the server has no record.
     *
     * <p>Read straight off the player rather than through a statistic lookup,
     * which keeps it safe to call from the async chat thread.
     */
    private static Integer tenureDays(Player player) {
        long firstPlayed = player.getFirstPlayed();
        if (firstPlayed <= 0L) {
            return null;
        }
        long elapsed = System.currentTimeMillis() - firstPlayed;
        return (int) Math.max(0L, TimeUnit.MILLISECONDS.toDays(elapsed));
    }

    private void remember(String name, String text) {
        synchronized (recentChat) {
            if (recentChat.size() >= CONTEXT_MEMORY) {
                recentChat.removeFirst();
            }
            recentChat.addLast(name + ": " + text);
        }
    }

    private List<String> snapshotContext() {
        synchronized (recentChat) {
            return new ArrayList<>(recentChat);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        queues.remove(id);
        reporter.forget(id);
        engine.history().forget(id);
        violations.forget(id);
    }

    public void clearContext() {
        synchronized (recentChat) {
            recentChat.clear();
        }
    }
}
