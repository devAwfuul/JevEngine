package dev.awfuul.jevengine.text;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the deobfuscator made of one message.
 *
 * @param raw          the message exactly as typed
 * @param deobfuscated disguises undone, still readable as a sentence
 * @param lettersOnly  everything but a-z removed, which catches spacing and
 *                     punctuation tricks but is lossy enough that it is only
 *                     ever supporting evidence
 * @param leetExpanded the whole message read as leet, present only when it
 *                     actually looked like leet and the reading differs from
 *                     {@code deobfuscated}. The most lossy variant of the four,
 *                     because it treats punctuation as letters.
 * @param signals      counts of what was undone, which is what lets Jev judge
 *                     whether the disguise looked deliberate
 */
public record Normalized(String raw, String deobfuscated, String lettersOnly, String leetExpanded,
                         Map<String, Integer> signals) {

    public static Normalized unchanged(String raw) {
        return new Normalized(raw, raw, raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""),
                null, new LinkedHashMap<>());
    }

    /** True when the cleaned text differs from what was typed. */
    public boolean altered() {
        return !raw.equalsIgnoreCase(deobfuscated) || leetExpanded != null;
    }

    public boolean hasLeetReading() {
        return leetExpanded != null && !leetExpanded.isBlank();
    }

    /**
     * Cache key. Two messages that clean up identically get the same verdict,
     * and the leet reading is part of that because two different-looking lines
     * can share a plain form while meaning different things once read as leet.
     */
    public String cacheKey() {
        return leetExpanded == null ? deobfuscated : deobfuscated + "\u0000" + leetExpanded;
    }
}
