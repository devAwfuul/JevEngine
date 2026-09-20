package dev.awfuul.jevengine.text;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Undoes the spelling tricks people use to slip words past a chat filter.
 *
 * <p>This runs in code rather than in the model on purpose. Jev reads text
 * literally and is weak at character-level indirection, so asking it to work out
 * that {@code |\|1663|2} is a slur means asking it to do the one thing it is
 * worst at. Undoing the disguise here and handing over the original plus the
 * cleaned versions turns a character puzzle into an ordinary reading
 * comprehension question, which is what Jev is good at.
 *
 * <p>Leet is handled in two passes, because the two kinds of it need opposite
 * treatment. Light leet such as {@code n1gg3r} still has most of its real
 * letters, so the conservative pass can swap characters that sit between
 * letters and leave everything else alone. Heavy leet such as {@code 5|_|(|<}
 * has no real letters at all, which is exactly what the conservative pass needs
 * in order to be safe, so a second and far more aggressive pass reads the whole
 * thing as letters. That pass would wreck ordinary punctuation, so it only runs
 * when the text actually looks like leet, and its output is an extra variant
 * rather than a replacement.
 *
 * <p>Nothing here decides anything. The cleaned text is evidence, and lossy
 * evidence at that, since collapsing a sentence to bare letters will happily
 * join two innocent words into something that looks worse. That is exactly why
 * the original is always sent alongside and why no wordlist lives in this class.
 */
public final class Deobfuscator {

    /**
     * @param aggressiveLeet    read heavily disguised text as letters, as an
     *                          extra variant alongside the conservative one
     * @param multiCharacterLeet resolve sequences such as {@code |-|} and
     *                          {@code \/\/} that spell one letter with several
     *                          characters
     * @param minLeetDensity    how much of a word has to look like leet before
     *                          the aggressive pass will read it
     * @param maxLength         messages longer than this are truncated before any
     *                          work, which bounds the cost of a pasted wall of text
     */
    public record Settings(boolean enabled,
                           boolean stripInvisible,
                           boolean stripMarks,
                           boolean foldConfusables,
                           boolean expandLeet,
                           boolean joinSpacedLetters,
                           boolean collapseRepeats,
                           boolean aggressiveLeet,
                           boolean multiCharacterLeet,
                           double minLeetDensity,
                           int maxLength) {

        public static Settings defaults() {
            return new Settings(true, true, true, true, true, true, true, true, true, 0.34D, 512);
        }
    }

    private static final Map<Integer, String> CONFUSABLES = new HashMap<>(256);

    /** Swapped only between letters, so ordinary punctuation survives. */
    private static final Map<Character, Character> LEET = new HashMap<>();

    /** Everything above plus the characters that are only leet in context. */
    private static final Map<Character, Character> AGGRESSIVE_LEET = new HashMap<>();

    /**
     * Sequences that spell one letter with several characters, longest first so
     * that {@code \/\/} is read as w before its halves are read as two vs.
     */
    private static final String[][] MULTI_LEET = {
            {"\\/\\/", "w"},
            {"|\\/|", "m"},
            {"/\\/", "n"},
            {"|\\|", "n"},
            {"/-\\", "a"},
            {"|-|", "h"},
            {"|_|", "u"},
            {"(_)", "u"},
            {"\\_/", "u"},
            {"|v|", "m"},
            {"/v\\", "m"},
            {"\\^/", "w"},
            {"><", "x"},
            {")(", "x"},
            {"/\\", "a"},
            {"\\/", "v"},
            {"|)", "d"},
            {"|>", "d"},
            {"|3", "b"},
            {"|2", "r"},
            {"|<", "k"},
            {"|{", "k"},
            {"1<", "k"},
            {"|_", "l"},
            {"|=", "f"},
            {"()", "o"},
            {"[]", "o"},
            {"{}", "o"},
            {"vv", "w"},
    };

    /**
     * Characters rare enough in ordinary chat that seeing one inside a word is
     * reason enough to try reading the word as leet. Punctuation people actually
     * use, including {@code ! ? * - _ . , '} and the brackets that make up
     * emoticons, is deliberately absent.
     */
    private static final String TRIGGER_SYMBOLS = "@$#|[]{}\\=";

    /** Three or more single letters split up by punctuation, as in "f u c k". */
    private static final Pattern SPACED_LETTERS =
            Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\p{L}[^\\p{L}\\p{N}]{1,2}){2,}\\p{L}(?![\\p{L}\\p{N}])");

    private static final Pattern REPEATS = Pattern.compile("(\\p{L})\\1{2,}");

    private static final Pattern NOT_LETTERS = Pattern.compile("[^a-z]");

    static {
        fold("\u0430\u03b1\u0251\u1d00\u04d9", 'a');
        fold("\u0432\u03b2\u044c\u0299\u1d03\u0184\u0431", 'b');
        fold("\u0441\u1d04\u03f2\u217d", 'c');
        fold("\u0501\u0111\u1d05\u0257", 'd');
        fold("\u0435\u03b5\u025b\u1d07\u0454\u04bd\u0437", 'e');
        fold("\u0493\u0192\u1e9d", 'f');
        fold("\u0261\u0262\u01e5\u0581", 'g');
        fold("\u04bb\u0127\u029c\u210e\u043d", 'h');
        fold("\u0456\u0131\u026a\u0268\u03b9\u2170", 'i');
        fold("\u0458\u1d0a\u0249", 'j');
        fold("\u043a\u03ba\u1d0b\u049b", 'k');
        fold("\u04cf\u0142\u029f\u019a\u217c", 'l');
        fold("\u043c\u1d0d\u0271\u217f", 'm');
        fold("\u043f\u03b7\u0274\u019e\u03c0\u0273", 'n');
        fold("\u043e\u03bf\u00f8\u1d0f\u03c3\u0473\u03b8\u0275", 'o');
        fold("\u0440\u03c1\u1d18\u01a5", 'p');
        fold("\u051b\u01eb\u024b", 'q');
        fold("\u0433\u0280\u027e\u1d26\u044f", 'r');
        fold("\u0455\u0283\ua731\u01a8\u03c2", 's');
        fold("\u0442\u03c4\u0167\u1d1b\u01ad", 't');
        fold("\u03c5\u03bc\u1d1c\u0265", 'u');
        fold("\u03bd\u1d20\u0475\u028b", 'v');
        fold("\u051d\u1d21\u03c9\u0461\u026f", 'w');
        fold("\u0445\u03c7\u166e\u2179", 'x');
        fold("\u0443\u03b3\u028f\u04af\u01b4", 'y');
        fold("\u03b6\u1d22\u0225\u01b6", 'z');

        // Flag emoji spell words surprisingly well, so map them back to letters.
        for (int i = 0; i < 26; i++) {
            CONFUSABLES.put(0x1F1E6 + i, String.valueOf((char) ('a' + i)));
        }

        LEET.put('0', 'o');
        LEET.put('1', 'i');
        LEET.put('3', 'e');
        LEET.put('4', 'a');
        LEET.put('5', 's');
        LEET.put('6', 'g');
        LEET.put('7', 't');
        LEET.put('8', 'b');
        LEET.put('9', 'g');
        LEET.put('@', 'a');
        LEET.put('$', 's');
        LEET.put('!', 'i');
        LEET.put('+', 't');
        LEET.put('|', 'l');

        AGGRESSIVE_LEET.putAll(LEET);
        AGGRESSIVE_LEET.put('2', 'z');
        AGGRESSIVE_LEET.put('#', 'h');
        AGGRESSIVE_LEET.put('(', 'c');
        AGGRESSIVE_LEET.put('<', 'c');
        AGGRESSIVE_LEET.put('[', 'c');
        AGGRESSIVE_LEET.put('{', 'c');
        AGGRESSIVE_LEET.put('\\', 'v');
        AGGRESSIVE_LEET.put('/', 'l');
        AGGRESSIVE_LEET.put('=', 'e');
        AGGRESSIVE_LEET.put('\u20ac', 'e');
        AGGRESSIVE_LEET.put('\u00a3', 'e');
        AGGRESSIVE_LEET.put('\u00a5', 'y');
        AGGRESSIVE_LEET.put('\u00a7', 's');
        AGGRESSIVE_LEET.put('\u00a1', 'i');
    }

    private Deobfuscator() {
    }

    private static void fold(String lookalikes, char target) {
        for (int i = 0; i < lookalikes.length(); i++) {
            CONFUSABLES.put((int) lookalikes.charAt(i), String.valueOf(target));
        }
    }

    public static Normalized normalize(String raw, Settings settings) {
        if (!settings.enabled() || raw == null || raw.isEmpty()) {
            return Normalized.unchanged(raw == null ? "" : raw);
        }

        String input = raw.length() > settings.maxLength()
                ? raw.substring(0, settings.maxLength())
                : raw;

        // NFKD alone handles fullwidth, circled, squared, superscript and the
        // mathematical alphabets, so the table below only needs the leftovers.
        String working = Normalizer.normalize(input, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);

        int invisible = 0;
        int marks = 0;
        int lookalikes = 0;

        StringBuilder builder = new StringBuilder(working.length());
        for (int index = 0; index < working.length(); ) {
            int codePoint = working.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);

            if (settings.stripMarks() && isMark(type)) {
                marks++;
                continue;
            }
            if (settings.stripInvisible() && isInvisible(codePoint, type)) {
                invisible++;
                continue;
            }
            if (settings.foldConfusables()) {
                String replacement = CONFUSABLES.get(codePoint);
                if (replacement != null) {
                    lookalikes++;
                    builder.append(replacement);
                    continue;
                }
            }
            builder.appendCodePoint(codePoint);
        }

        String folded = builder.toString();

        int[] leetCount = {0};
        String conservative = settings.expandLeet() ? expandLeet(folded, leetCount) : folded;

        int[] joined = {0};
        int[] collapsed = {0};
        conservative = finish(conservative, settings, joined, collapsed);

        // The aggressive reading, only where the text asks for it.
        String leetExpanded = null;
        int[] multiCount = {0};
        if (settings.aggressiveLeet() && looksLikeLeet(folded, settings.minLeetDensity())) {
            String candidate = aggressiveLeet(folded, settings, multiCount);
            candidate = finish(candidate, settings, new int[1], new int[1]);
            if (!candidate.equals(conservative) && !candidate.isBlank()) {
                leetExpanded = candidate;
            }
        }

        Map<String, Integer> signals = new LinkedHashMap<>();
        signals.put("invisible_characters_removed", invisible);
        signals.put("combining_marks_removed", marks);
        signals.put("lookalike_letters_folded", lookalikes);
        signals.put("leet_characters_expanded", leetCount[0]);
        signals.put("multi_character_leet_expanded", multiCount[0]);
        signals.put("spaced_out_runs_joined", joined[0]);
        signals.put("stretched_letters_collapsed", collapsed[0]);

        String lettersOnly = NOT_LETTERS.matcher(conservative).replaceAll("");
        return new Normalized(input, conservative, lettersOnly, leetExpanded, signals);
    }

    /** The transforms that apply to every variant once its letters are settled. */
    private static String finish(String text, Settings settings, int[] joined, int[] collapsed) {
        String result = text;
        if (settings.joinSpacedLetters()) {
            Matcher matcher = SPACED_LETTERS.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            while (matcher.find()) {
                joined[0]++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        matcher.group().replaceAll("[^\\p{L}\\p{N}]", "")));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        if (settings.collapseRepeats()) {
            Matcher matcher = REPEATS.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            while (matcher.find()) {
                collapsed[0]++;
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        matcher.group(1) + matcher.group(1)));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return result;
    }

    /**
     * Does any word here look like it was written in leet?
     *
     * <p>Three ways to qualify. A word containing both letters and one of the
     * characters people do not otherwise type mid-word, which catches
     * {@code $hit} and {@code |\|ice}. A word with no letters at all but enough
     * leet characters to be spelling something, which catches {@code 5|_|(|<}
     * while leaving {@code :)} and {@code <3} alone. And a long enough word
     * mixing letters and digits densely enough to be deliberate.
     */
    private static boolean looksLikeLeet(String text, double minDensity) {
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int letters = 0;
            int digits = 0;
            int triggers = 0;
            int symbols = 0;
            for (int i = 0; i < token.length(); i++) {
                char current = token.charAt(i);
                if (current >= 'a' && current <= 'z') {
                    letters++;
                } else if (Character.isDigit(current)) {
                    digits++;
                }
                if (TRIGGER_SYMBOLS.indexOf(current) >= 0) {
                    triggers++;
                }
                if (!Character.isDigit(current) && AGGRESSIVE_LEET.containsKey(current)) {
                    symbols++;
                }
            }
            if (letters > 0 && triggers > 0) {
                return true;
            }
            // Doubled v for w is the one letter-only substitution worth acting
            // on. It costs an extra reading of words like "savvy" and buys
            // "vvhore" and "vvhite", which is a trade worth making.
            if (letters >= 3 && token.contains("vv")) {
                return true;
            }
            // A word with no letters left qualifies only if it is built out of
            // punctuation. Counting digits here would read "1.21.11" and "100%"
            // as leet, and a bare number never is.
            if (letters == 0 && token.length() >= 4 && symbols >= 2) {
                return true;
            }
            if (letters > 0 && digits > 0 && token.length() >= 4
                    && (double) (digits + triggers) / token.length() >= minDensity) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the text as leet with no regard for what it does to punctuation.
     * Only ever called on text that {@link #looksLikeLeet} has already vouched
     * for, and its result is an extra variant, never a replacement.
     */
    private static String aggressiveLeet(String text, Settings settings, int[] multiCount) {
        String result = text;
        if (settings.multiCharacterLeet()) {
            for (String[] pair : MULTI_LEET) {
                int from = result.indexOf(pair[0]);
                while (from >= 0) {
                    multiCount[0]++;
                    result = result.substring(0, from) + pair[1]
                            + result.substring(from + pair[0].length());
                    from = result.indexOf(pair[0], from + pair[1].length());
                }
            }
        }
        StringBuilder out = new StringBuilder(result.length());
        for (int i = 0; i < result.length(); i++) {
            char current = result.charAt(i);
            Character replacement = AGGRESSIVE_LEET.get(current);
            out.append(replacement == null ? current : replacement.charValue());
        }
        return out.toString();
    }

    /**
     * Swaps leet characters back, inside words only and mostly between letters.
     *
     * <p>The guards here exist because every one of them was earning its keep on
     * ordinary chat. Requiring two real letters in the word keeps "1v1 me" and "I
     * need 4 iron" intact. Requiring a letter on both sides keeps "COME ON!!!!"
     * from turning into "come onii", which is the kind of noise that makes a
     * disguise signal meaningless. A digit on the edge of a longer word is still
     * swapped, since that is a real bypass and punctuation is not.
     */
    private static String expandLeet(String text, int[] counter) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                out.append(current);
                index++;
                continue;
            }
            int start = index;
            while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            out.append(expandToken(text.substring(start, index), counter));
        }
        return out.toString();
    }

    private static String expandToken(String token, int[] counter) {
        int letters = 0;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                letters++;
            }
        }
        if (letters < 2) {
            return token;
        }
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            Character replacement = LEET.get(current);
            if (replacement != null && shouldSwap(token, i, letters)) {
                counter[0]++;
                out.append(replacement.charValue());
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static boolean shouldSwap(String token, int index, int letters) {
        if (hasLetterBefore(token, index) && hasLetterAfter(token, index)) {
            return true;
        }
        // On the edge of a word, only a digit is worth swapping, and only when
        // there is enough word around it to be a disguised spelling rather than
        // "gr8" or a trailing "!".
        return Character.isDigit(token.charAt(index)) && letters >= 3;
    }

    private static boolean hasLetterBefore(String token, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (Character.isLetter(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLetterAfter(String token, int index) {
        for (int i = index + 1; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMark(int type) {
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isInvisible(int codePoint, int type) {
        if (type == Character.FORMAT || type == Character.CONTROL) {
            return true;
        }
        return switch (codePoint) {
            case 0x00AD, 0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180E,
                 0x2028, 0x2029, 0x3164, 0xFFA0, 0xFFF9, 0xFFFA, 0xFFFB -> true;
            default -> false;
        };
    }
}
