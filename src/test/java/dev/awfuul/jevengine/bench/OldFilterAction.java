package dev.awfuul.jevengine.bench;

/**
 * What the legacy chat filter did with a message, read off {@code was_filtered}
 * and {@code was_blocked}.
 *
 * <p>{@code BLOCKED} and {@code FILTERED} are deliberately kept apart rather
 * than collapsed into one "flagged" bucket. A block is the same shape of action
 * as Jev's BLOCK/PUNISH: the message never reached anyone. A filter is the same
 * shape as Jev's FLAG: the message went out, only altered or noted. Comparing
 * like with like is the entire point of this benchmark.
 */
public enum OldFilterAction {
    CLEAN,
    FILTERED,
    BLOCKED;

    public static OldFilterAction from(boolean wasFiltered, boolean wasBlocked) {
        if (wasBlocked) {
            return BLOCKED;
        }
        if (wasFiltered) {
            return FILTERED;
        }
        return CLEAN;
    }
}
