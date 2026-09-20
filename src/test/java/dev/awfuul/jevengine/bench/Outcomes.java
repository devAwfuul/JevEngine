package dev.awfuul.jevengine.bench;

import java.util.Locale;

/**
 * Collapses the dataset's {@code outcome} column into report-sized buckets.
 *
 * <p>{@code msg-detected-to:<name>} and {@code reply-detected-to:<name>} are
 * whisper-routing outcomes, one variant per recipient. Left alone they would
 * turn the outcome breakdown into a table with one row per player the sender
 * ever whispered, which buries the moderation-relevant outcomes it exists to
 * show. They are not moderation decisions at all, so folding every name into
 * one row each loses nothing worth keeping.
 */
public final class Outcomes {

    private Outcomes() {
    }

    public static String bucket(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return "(blank)";
        }
        String normalized = outcome.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("msg-detected-to:")) {
            return "msg-detected-to:*";
        }
        if (normalized.startsWith("reply-detected-to:")) {
            return "reply-detected-to:*";
        }
        return normalized;
    }

    /**
     * Outcomes that are routing decisions (a whisper or a duel invite went to
     * the right place) rather than a moderation decision. Still counted in the
     * outcome table, but left out of the old-filter action breakdown, since
     * {@code was_filtered}/{@code was_blocked} on these rows describes routing
     * success, not a profanity or safety call.
     */
    public static boolean isRoutingOutcome(String bucketedOutcome) {
        return switch (bucketedOutcome) {
            case "msg-detected-to:*", "reply-detected-to:*", "duel-sent", "dm-disabled" -> true;
            default -> false;
        };
    }
}
