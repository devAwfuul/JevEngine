package dev.awfuul.jevengine.api;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the shared Jev client has cost so far.
 *
 * <p>This lives with the client rather than with a module because the request
 * budget is shared. When a second module starts asking questions, the spend and
 * the latency on this page are still the whole picture.
 *
 * <p>Latency is a fixed ring of the last few hundred samples rather than a
 * growing list, so memory is constant and a percentile is a sort of a small
 * array rather than a scan of the session's whole history.
 */
public final class ApiMetrics {

    private static final int WINDOW = 512;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();

    private final long[] latencies = new long[WINDOW];
    private int count;
    private int index;

    public void recordSuccess(long millis, int tokens) {
        requests.incrementAndGet();
        inputTokens.addAndGet(tokens);
        synchronized (latencies) {
            latencies[index] = millis;
            index = (index + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
        }
    }

    public void recordFailure() {
        requests.incrementAndGet();
        errors.incrementAndGet();
    }

    public long percentile(double fraction) {
        long[] copy;
        synchronized (latencies) {
            if (count == 0) {
                return 0L;
            }
            copy = Arrays.copyOf(latencies, count);
        }
        Arrays.sort(copy);
        int at = (int) Math.min(copy.length - 1L, Math.round(fraction * (copy.length - 1)));
        return copy[at];
    }

    public long requests() {
        return requests.get();
    }

    public long errors() {
        return errors.get();
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    /** Jev is billed at $42 per billion input tokens, and output is free. */
    public double estimatedCostUsd() {
        return inputTokens.get() * 42.0D / 1_000_000_000.0D;
    }
}
