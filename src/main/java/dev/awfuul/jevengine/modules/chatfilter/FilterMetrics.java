package dev.awfuul.jevengine.modules.chatfilter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the filter has decided so far.
 *
 * <p>Only outcomes. Latency, tokens and spend belong to the shared client, since
 * those are the engine's budget rather than this module's.
 */
public final class FilterMetrics {

    private final AtomicLong evaluated = new AtomicLong();
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong flagged = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong punished = new AtomicLong();

    public void countEvaluated() {
        evaluated.incrementAndGet();
    }

    public void countAction(Action action) {
        switch (action) {
            case PASS -> passed.incrementAndGet();
            case FLAG -> flagged.incrementAndGet();
            case BLOCK -> blocked.incrementAndGet();
            case PUNISH -> punished.incrementAndGet();
        }
    }

    public long evaluated() {
        return evaluated.get();
    }

    public long passed() {
        return passed.get();
    }

    public long flagged() {
        return flagged.get();
    }

    public long blocked() {
        return blocked.get();
    }

    public long punished() {
        return punished.get();
    }
}
