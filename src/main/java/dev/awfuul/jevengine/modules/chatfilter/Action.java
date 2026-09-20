package dev.awfuul.jevengine.modules.chatfilter;

/**
 * What the filter does about a message, weakest first. The order is load bearing:
 * the policy caps and escalates by comparing ordinals, so anything inserted here
 * has to go in the right place.
 */
public enum Action {

    /** Send it. */
    PASS,

    /** Send it, and tell staff to look. */
    FLAG,

    /** Do not send it. */
    BLOCK,

    /** Do not send it, and run the configured command. */
    PUNISH;

    public boolean delivers() {
        return this == PASS || this == FLAG;
    }

    public boolean notifiesStaff() {
        return this != PASS;
    }

    public Action cappedAt(Action ceiling) {
        return ordinal() > ceiling.ordinal() ? ceiling : this;
    }

    public Action atLeast(Action floor) {
        return ordinal() < floor.ordinal() ? floor : this;
    }

    public static Action parse(String raw, Action fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
