package dev.awfuul.jevengine.text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls anything address-shaped out of a message.
 *
 * <p>This finds candidates and stops there. Whether "play.example.net" is an
 * advert, a video link, a mod page or someone asking a question about another
 * server is a judgment, and that judgment belongs to Jev. Whether a run of
 * characters is shaped like an address is an exact test, and that belongs to a
 * regular expression.
 *
 * <p>Both the original text and the deobfuscated text are scanned. The
 * deobfuscated form is what catches lookalike characters in a domain, but it has
 * also had leetspeak undone, which quietly rewrites things like a Discord invite
 * code. Reading both and keeping the union costs nothing and avoids handing Jev
 * an address that was never typed.
 */
public final class LinkScanner {

    private static final int MAX_CANDIDATES = 6;

    /** "example dot com", "example(.)com", "example [dot] com", "example . com". */
    private static final Pattern MASKED_DOT = Pattern.compile(
            "(?i)(?<=[a-z0-9])\\s*(?:[\\(\\[\\{<*_-]\\s*)?(?:dot|d0t|punkt|punto|\\.)"
                    + "(?:\\s*[\\)\\]\\}>*_-])?\\s*(?=[a-z0-9])");

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");

    /** Host, optional port, optional path. The trailing groups are what keep an
     *  unusual top level domain in play when the rest of it looks deliberate. */
    private static final Pattern DOMAIN = Pattern.compile(
            "\\b[a-z0-9][a-z0-9-]{0,61}(?:\\.[a-z0-9-]{1,61})*\\.([a-z]{2,12})"
                    + "(:\\d{1,5})?(/[^\\s]*)?");

    private static final Pattern INVITE = Pattern.compile(
            "\\b(?:discord\\.gg|dsc\\.gg|discord\\.com/invite|invite\\.gg)/[a-z0-9_-]{2,}");

    /**
     * Top level domains common enough that seeing one is reason enough to ask
     * about it. Anything outside this list still counts when it comes with a
     * port or a path, which is what separates "play.example.zone:25565" from
     * someone forgetting the space in "that was close.Nice one". Add your own
     * with detection.advertising.extra-tlds.
     */
    private static final Set<String> COMMON_TLDS = Set.of(
            "com", "net", "org", "gg", "io", "me", "xyz", "club", "fun", "online", "store",
            "shop", "co", "tv", "pro", "site", "live", "top", "mc", "info", "biz", "dev",
            "app", "link", "space", "website", "cloud", "host", "world", "network", "games",
            "play", "server", "zone", "land", "life", "today", "vip", "wiki", "pw", "cc",
            "ws", "to", "ly", "sh", "id", "ru", "de", "uk", "nl", "ca", "au", "us", "eu",
            "fr", "es", "it", "pl", "br", "in", "jp", "kr", "cn", "za", "se", "no", "fi",
            "dk", "cz", "gr", "pt", "tr", "ua");

    private LinkScanner() {
    }

    public static List<String> scan(String raw, String deobfuscated, Set<String> extraTlds) {
        Set<String> found = new LinkedHashSet<>();
        for (String source : new String[]{raw, deobfuscated}) {
            if (source == null || source.isBlank()) {
                continue;
            }
            String lower = source.toLowerCase(Locale.ROOT);
            collect(INVITE, lower, found, extraTlds);
            collect(IPV4, lower, found, extraTlds);
            collect(DOMAIN, lower, found, extraTlds);

            String unmasked = MASKED_DOT.matcher(lower).replaceAll(".");
            if (!unmasked.equals(lower)) {
                collect(IPV4, unmasked, found, extraTlds);
                collect(DOMAIN, unmasked, found, extraTlds);
            }
        }
        return trim(found);
    }

    private static void collect(Pattern pattern, String text, Set<String> into,
                                Set<String> extraTlds) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && into.size() < MAX_CANDIDATES * 2) {
            String match = matcher.group();
            if (pattern == DOMAIN && !worthKeeping(matcher, extraTlds)) {
                continue;
            }
            into.add(match);
        }
    }

    private static boolean worthKeeping(Matcher matcher, Set<String> extraTlds) {
        String tld = matcher.group(1);
        boolean hasPortOrPath = matcher.group(2) != null || matcher.group(3) != null;
        return hasPortOrPath
                || COMMON_TLDS.contains(tld)
                || (extraTlds != null && extraTlds.contains(tld));
    }

    /** Drops a candidate that is just the front of a longer one already found. */
    private static List<String> trim(Set<String> found) {
        List<String> out = new ArrayList<>(found.size());
        for (String candidate : found) {
            boolean covered = false;
            for (String other : found) {
                if (!other.equals(candidate) && other.startsWith(candidate)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                out.add(candidate);
            }
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return List.copyOf(out);
    }
}
