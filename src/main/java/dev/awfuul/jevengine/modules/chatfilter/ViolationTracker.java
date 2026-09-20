package dev.awfuul.jevengine.modules.chatfilter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Counts FLAG decisions per player and category in a rolling time window. */
public final class ViolationTracker {

    private final Map<UUID, PlayerViolations> players = new ConcurrentHashMap<>();
    private volatile ChatFilterConfig.Violations config;

    public ViolationTracker(ChatFilterConfig.Violations config) {
        this.config = config;
    }

    public void apply(ChatFilterConfig.Violations replacement) {
        this.config = replacement;
        players.clear();
    }

    /**
     * Records a flag and returns the number that triggered a punishment, or zero
     * when the threshold has not been reached. The triggering category is reset
     * so one burst does not punish on every following flagged message.
     */
    public int record(UUID player, String category) {
        ChatFilterConfig.Violations current = config;
        if (!current.enabled() || category == null || category.isBlank()) {
            return 0;
        }
        int threshold = current.thresholdFor(category);
        if (threshold < 1) {
            return 0;
        }

        PlayerViolations state = players.computeIfAbsent(player, ignored -> new PlayerViolations());
        synchronized (state) {
            long now = System.currentTimeMillis();
            Deque<Long> timestamps = state.byCategory.computeIfAbsent(category,
                    ignored -> new ArrayDeque<>());
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= current.windowMillis()) {
                timestamps.removeFirst();
            }
            timestamps.addLast(now);
            if (timestamps.size() < threshold) {
                return 0;
            }
            int reached = timestamps.size();
            timestamps.clear();
            return reached;
        }
    }

    public void forget(UUID player) {
        players.remove(player);
    }

    private static final class PlayerViolations {
        private final Map<String, Deque<Long>> byCategory = new HashMap<>();
    }
}
