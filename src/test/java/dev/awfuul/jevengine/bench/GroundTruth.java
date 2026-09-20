package dev.awfuul.jevengine.bench;

import java.util.Locale;

/**
 * What we can independently know about a message, as opposed to what the old
 * filter merely decided about it.
 *
 * <p>The old filter's own verdict is not evidence of what was actually right;
 * grading it against itself would be circular. Two outcomes in this dataset do
 * carry outside evidence:
 *
 * <ul>
 *   <li>{@code outcome=sent} with neither flag set: the message went out with
 *       no incident recorded against it. Not a proof of innocence, but the best
 *       approximation of a clean message this data offers.</li>
 *   <li>{@code outcome=review-approved}: a human moderator looked at a message
 *       the old filter had stopped and put it through. That is a direct,
 *       human-sourced statement that the old filter was wrong about this one.</li>
 * </ul>
 *
 * <p>Everything else is marked {@code UNKNOWN} and left out of any accuracy
 * figure. It still gets counted in the descriptive breakdown, just not in a
 * number that claims to be graded against the truth.
 */
public enum GroundTruth {

    /** Delivered with nothing recorded against it. */
    CONFIRMED_CLEAN,

    /** Blocked by the old filter, then a human overturned that call. */
    OLD_FILTER_OVERTURNED,

    /** The old filter's word is the only word we have; not used for scoring. */
    UNKNOWN;

    public boolean impliesShouldHaveBeenAllowed() {
        return this == CONFIRMED_CLEAN || this == OLD_FILTER_OVERTURNED;
    }

    public static GroundTruth classify(String outcome, boolean wasFiltered, boolean wasBlocked) {
        String normalized = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("review-approved")) {
            return OLD_FILTER_OVERTURNED;
        }
        if (normalized.equals("sent") && !wasFiltered && !wasBlocked) {
            return CONFIRMED_CLEAN;
        }
        return UNKNOWN;
    }
}
