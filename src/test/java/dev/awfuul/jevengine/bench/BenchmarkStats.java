package dev.awfuul.jevengine.bench;

import dev.awfuul.jevengine.modules.chatfilter.Action;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters for one benchmark run, plus every disagreement between
 * Jev and the old filter, kept for manual review.
 *
 * <p>Every increment method is safe to call from any of the virtual threads a
 * row's Jev request completes on. Nothing here blocks, so recording a result
 * never becomes the bottleneck next to a network call.
 *
 * <p>Nothing here caps how many disagreements are kept. A run bounded by
 * {@code --limit} already bounds this by construction, since there cannot be
 * more disagreements than rows processed, so holding all of them in memory
 * costs at most a few hundred bytes per row processed.
 */
public final class BenchmarkStats {

    public record Disagreement(long rowNumber, String id, String playerName, String original,
                               String filteredByOldSystem, String outcome, String reviewStatus,
                               OldFilterAction oldAction, Action jevAction, String jevCategory,
                               String jevReason) {
    }

    private final AtomicLong totalRows = new AtomicLong();
    private final AtomicLong skippedBlank = new AtomicLong();
    private final AtomicLong apiErrors = new AtomicLong();

    private final Map<Action, LongAdder> jevActionCounts = new ConcurrentHashMap<>();
    private final Map<OldFilterAction, LongAdder> oldActionCounts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> outcomeCounts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> jevCategoryCounts = new ConcurrentHashMap<>();

    // Raw, whole-dataset agreement: "blocked-ish" means BLOCK or PUNISH for Jev,
    // BLOCKED for the old filter. This is a diagnostic, not an accuracy figure,
    // because the old filter's own decision is not proof of what was right.
    private final AtomicLong bothBlockedIsh = new AtomicLong();
    private final AtomicLong bothCleanIsh = new AtomicLong();
    private final AtomicLong jevBlockedOldClean = new AtomicLong();
    private final AtomicLong oldBlockedJevClean = new AtomicLong();

    // The ground-truth subset: rows independently known to deserve delivery.
    private final AtomicLong groundTruthTotal = new AtomicLong();
    private final AtomicLong confirmedCleanCount = new AtomicLong();
    private final AtomicLong overturnedCount = new AtomicLong();

    private final Map<OldFilterAction, LongAdder> oldOnGroundTruth = new ConcurrentHashMap<>();
    private final Map<Action, LongAdder> jevOnGroundTruth = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<Disagreement> disagreements = new ConcurrentLinkedQueue<>();
    private final AtomicLong disagreementCount = new AtomicLong();

    /**
     * Per-player violation counts, keyed by whatever is in {@code player_name}.
     * A violation here means Jev's action on that row was BLOCK or PUNISH,
     * i.e. something that would not have reached chat. FLAG is not counted:
     * it means the message went out and staff were merely told to look, which
     * is a different, lighter thing than a top-offenders list is asking about.
     */
    private final Map<String, LongAdder> violationsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> messagesByPlayer = new ConcurrentHashMap<>();

    public void recordSkippedBlank() {
        skippedBlank.incrementAndGet();
    }

    public void recordApiError() {
        apiErrors.incrementAndGet();
        totalRows.incrementAndGet();
    }

    public void recordRow(CsvRecord row, String original, OldFilterAction oldAction,
                          GroundTruth groundTruth, Action jevAction, String jevCategory,
                          String jevReason) {
        totalRows.incrementAndGet();
        count(jevActionCounts, jevAction);
        count(oldActionCounts, oldAction);
        count(jevCategoryCounts, jevCategory);
        outcomeCounts.computeIfAbsent(Outcomes.bucket(row.get("outcome")), k -> new LongAdder())
                .increment();

        String player = row.get("player_name");
        if (player != null && !player.isBlank()) {
            messagesByPlayer.computeIfAbsent(player, k -> new LongAdder()).increment();
            if (jevAction == Action.BLOCK || jevAction == Action.PUNISH) {
                violationsByPlayer.computeIfAbsent(player, k -> new LongAdder()).increment();
            }
        }

        boolean jevBlockedIsh = jevAction == Action.BLOCK || jevAction == Action.PUNISH;
        boolean oldBlockedIsh = oldAction == OldFilterAction.BLOCKED;
        if (jevBlockedIsh && oldBlockedIsh) {
            bothBlockedIsh.incrementAndGet();
        } else if (!jevBlockedIsh && !oldBlockedIsh) {
            bothCleanIsh.incrementAndGet();
        } else if (jevBlockedIsh) {
            jevBlockedOldClean.incrementAndGet();
        } else {
            oldBlockedJevClean.incrementAndGet();
        }

        if (groundTruth.impliesShouldHaveBeenAllowed()) {
            groundTruthTotal.incrementAndGet();
            if (groundTruth == GroundTruth.CONFIRMED_CLEAN) {
                confirmedCleanCount.incrementAndGet();
            } else {
                overturnedCount.incrementAndGet();
            }
            count(oldOnGroundTruth, oldAction);
            count(jevOnGroundTruth, jevAction);
        }

        boolean disagree = jevBlockedIsh != oldBlockedIsh
                || (jevAction == Action.FLAG) != (oldAction == OldFilterAction.FILTERED);
        if (disagree) {
            disagreementCount.incrementAndGet();
            disagreements.add(new Disagreement(
                    row.rowNumber(), row.getOrEmpty("id"), row.getOrEmpty("player_name"),
                    original, row.getOrEmpty("filtered_message"), row.getOrEmpty("outcome"),
                    row.getOrEmpty("review_status"), oldAction, jevAction, jevCategory,
                    jevReason));
        }
    }

    private static <K> void count(Map<K, LongAdder> map, K key) {
        map.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public long totalRows() {
        return totalRows.get();
    }

    public long skippedBlank() {
        return skippedBlank.get();
    }

    public long apiErrors() {
        return apiErrors.get();
    }

    public long disagreementCount() {
        return disagreementCount.get();
    }

    public List<Disagreement> disagreementSample() {
        return List.copyOf(disagreements);
    }

    /** The full report, ready to print or write to a file. */
    public String render(long elapsedMillis) {
        StringBuilder out = new StringBuilder(4096);

        out.append("JevEngine chat filter benchmark\n");
        out.append("================================\n\n");
        out.append(String.format(Locale.ROOT, "Rows processed : %,d%n", totalRows.get()));
        out.append(String.format(Locale.ROOT, "Skipped (blank): %,d%n", skippedBlank.get()));
        out.append(String.format(Locale.ROOT, "API errors     : %,d%n", apiErrors.get()));
        out.append(String.format(Locale.ROOT, "Elapsed        : %,.1fs%n", elapsedMillis / 1000.0));
        out.append('\n');

        out.append("Jev's own action, across every row processed\n");
        out.append("----------------------------------------------\n");
        for (Action action : Action.values()) {
            out.append(String.format(Locale.ROOT, "  %-8s %,10d%n", action,
                    jevActionCounts.getOrDefault(action, new LongAdder()).sum()));
        }
        out.append('\n');

        out.append("Jev's category, where it settled on one\n");
        out.append("----------------------------------------\n");
        jevCategoryCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> out.append(String.format(Locale.ROOT, "  %-24s %,10d%n",
                        e.getKey(), e.getValue().sum())));
        out.append('\n');

        out.append("The old filter's action, read off was_filtered / was_blocked\n");
        out.append("---------------------------------------------------------------\n");
        for (OldFilterAction action : OldFilterAction.values()) {
            out.append(String.format(Locale.ROOT, "  %-8s %,10d%n", action,
                    oldActionCounts.getOrDefault(action, new LongAdder()).sum()));
        }
        out.append('\n');

        out.append("Raw agreement across the whole dataset (diagnostic only)\n");
        out.append("-----------------------------------------------------------\n");
        out.append("The old filter's own call is not proof of what was right, so this is\n");
        out.append("not an accuracy figure. It is how often the two systems land on the\n");
        out.append("same shape of decision.\n\n");
        long total = Math.max(1, totalRows.get());
        out.append(String.format(Locale.ROOT, "  Both blocked-ish   : %,10d (%5.1f%%)%n",
                bothBlockedIsh.get(), 100.0 * bothBlockedIsh.get() / total));
        out.append(String.format(Locale.ROOT, "  Both clean-ish     : %,10d (%5.1f%%)%n",
                bothCleanIsh.get(), 100.0 * bothCleanIsh.get() / total));
        out.append(String.format(Locale.ROOT, "  Jev blocked, old not: %,9d (%5.1f%%)%n",
                jevBlockedOldClean.get(), 100.0 * jevBlockedOldClean.get() / total));
        out.append(String.format(Locale.ROOT, "  Old blocked, Jev not: %,9d (%5.1f%%)%n",
                oldBlockedJevClean.get(), 100.0 * oldBlockedJevClean.get() / total));
        out.append('\n');

        out.append("Outcome breakdown (msg-detected-to:* and reply-detected-to:* collapsed)\n");
        out.append("---------------------------------------------------------------------------\n");
        outcomeCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> out.append(String.format(Locale.ROOT, "  %-24s %,10d%n",
                        e.getKey(), e.getValue().sum())));
        out.append('\n');

        out.append("Accuracy, on the subset with independent ground truth\n");
        out.append("=========================================================\n");
        out.append(String.format(Locale.ROOT,
                "%,d of %,d rows (%.1f%%) carry outside evidence of what was right:%n",
                groundTruthTotal.get(), totalRows.get(),
                100.0 * groundTruthTotal.get() / total));
        out.append(String.format(Locale.ROOT,
                "  %,d delivered clean (outcome=sent, nothing flagged)%n", confirmedCleanCount.get()));
        out.append(String.format(Locale.ROOT,
                "  %,d blocked by the old filter, then overturned by a human reviewer%n",
                overturnedCount.get()));
        out.append("Every row in this subset should have been allowed through unmodified.\n");
        out.append("What each system actually did with them:\n\n");

        long gt = Math.max(1, groundTruthTotal.get());
        out.append("  Old filter:\n");
        for (OldFilterAction action : OldFilterAction.values()) {
            long n = oldOnGroundTruth.getOrDefault(action, new LongAdder()).sum();
            out.append(String.format(Locale.ROOT, "    %-9s %,8d  (%5.1f%%)%n", action, n,
                    100.0 * n / gt));
        }
        out.append('\n');
        out.append("  Jev:\n");
        for (Action action : Action.values()) {
            long n = jevOnGroundTruth.getOrDefault(action, new LongAdder()).sum();
            out.append(String.format(Locale.ROOT, "    %-9s %,8d  (%5.1f%%)%n", action, n,
                    100.0 * n / gt));
        }
        out.append('\n');

        long oldClean = oldOnGroundTruth.getOrDefault(OldFilterAction.CLEAN, new LongAdder()).sum();
        long jevClean = jevOnGroundTruth.getOrDefault(Action.PASS, new LongAdder()).sum();
        out.append(String.format(Locale.ROOT,
                "  Old filter's accuracy on this subset (delivered unmodified): %.1f%%%n",
                100.0 * oldClean / gt));
        out.append(String.format(Locale.ROOT,
                "  Jev's accuracy on this subset (delivered unmodified):        %.1f%%%n",
                100.0 * jevClean / gt));
        out.append('\n');
        out.append("PASS and CLEAN both mean 'delivered exactly as sent'. FLAG and FILTERED\n");
        out.append("both mean 'delivered, but altered or noted'. BLOCK/PUNISH and BLOCKED both\n");
        out.append("mean the message never went out. Read the two tables above against each\n");
        out.append("other row by row, not just the bottom line, since a filtered message and a\n");
        out.append("blocked one are both mistakes but not the same size of mistake.\n\n");

        out.append(String.format(Locale.ROOT,
                "%,d disagreements recorded, all written to disagreements.txt.%n",
                disagreementCount.get()));
        out.append('\n');

        out.append("Top 10 players by Jev violation count\n");
        out.append("========================================\n");
        out.append("A violation here is Jev's action landing on BLOCK or PUNISH: something\n");
        out.append("that would not have reached chat. FLAG is not counted, since that message\n");
        out.append("still went out. Ranked by Jev's own read of the text alone, not by what the\n");
        out.append("old filter recorded, so this can and will surface names the old system\n");
        out.append("never flagged.\n\n");
        if (messagesByPlayer.isEmpty()) {
            out.append("  (no player_name column found, or every row's was blank)\n");
        } else {
            List<Map.Entry<String, LongAdder>> ranked = violationsByPlayer.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                    .limit(10)
                    .toList();
            if (ranked.isEmpty()) {
                out.append("  (nobody triggered a BLOCK or PUNISH in this run)\n");
            }
            int rank = 1;
            for (Map.Entry<String, LongAdder> entry : ranked) {
                long violations = entry.getValue().sum();
                long playerMessages =
                        messagesByPlayer.getOrDefault(entry.getKey(), new LongAdder()).sum();
                out.append(String.format(Locale.ROOT,
                        "  %2d. %-24s %,6d violations   out of %,6d messages  (%.1f%%)%n",
                        rank++, entry.getKey(), violations, playerMessages,
                        100.0 * violations / Math.max(1, playerMessages)));
            }
        }

        return out.toString();
    }

    /**
     * Every disagreement, one readable block per row, in the order they were
     * recorded. Meant to be read top to bottom or grepped, not parsed as data;
     * {@link #summaryCounters()} is the machine-readable side of this class.
     */
    public String disagreementsText() {
        List<Disagreement> all = disagreementSample();
        StringBuilder out = new StringBuilder(Math.max(1 << 16, all.size() * 200));
        out.append(String.format(Locale.ROOT, "%,d disagreements%n", all.size()));
        out.append("=".repeat(72)).append('\n');

        int number = 1;
        for (Disagreement d : all) {
            out.append(String.format(Locale.ROOT, "%n#%d  row %d", number++, d.rowNumber()));
            if (!d.id().isBlank()) {
                out.append("  id=").append(d.id());
            }
            if (!d.playerName().isBlank()) {
                out.append("  player=").append(d.playerName());
            }
            out.append('\n');
            out.append("outcome=").append(blankAs(d.outcome(), "(blank)"));
            if (!d.reviewStatus().isBlank()) {
                out.append("  review_status=").append(d.reviewStatus());
            }
            out.append('\n');
            out.append("old filter: ").append(d.oldAction())
                    .append("      jev: ").append(d.jevAction())
                    .append("  (").append(d.jevCategory()).append(")\n");
            out.append("jev's reason: ").append(d.jevReason()).append('\n');
            out.append("message: \"").append(d.original()).append("\"\n");
            if (!d.filteredByOldSystem().isBlank()
                    && !d.filteredByOldSystem().equals(d.original())) {
                out.append("old filter's output: \"").append(d.filteredByOldSystem()).append("\"\n");
            }
            out.append("-".repeat(72)).append('\n');
        }
        return out.toString();
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Machine-readable summary, one line per counter, for scripting against. */
    public Map<String, Long> summaryCounters() {
        return Map.of(
                "total_rows", totalRows.get(),
                "skipped_blank", skippedBlank.get(),
                "api_errors", apiErrors.get(),
                "ground_truth_total", groundTruthTotal.get(),
                "disagreements", disagreementCount.get());
    }
}
