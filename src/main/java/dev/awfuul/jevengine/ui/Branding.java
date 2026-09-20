package dev.awfuul.jevengine.ui;

import dev.awfuul.jevengine.core.CoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * How the engine looks when it speaks.
 *
 * <p>Shared by every module so the prefix is resolved once and a second feature
 * cannot drift into its own colours.
 *
 * <p>Config strings go through MiniMessage. Player-typed text never does. A
 * message being moderated is quoted back to staff as a literal component, so
 * nobody can get colours, click events or a fake plugin prefix into a staff
 * alert by typing them into chat.
 */
public final class Branding {

    public static final TextColor PASS = TextColor.fromCSSHexString("#6ee7a8");
    public static final TextColor NOTICE = TextColor.fromCSSHexString("#fbbf24");
    public static final TextColor WARN = TextColor.fromCSSHexString("#fb923c");
    public static final TextColor ALERT = TextColor.fromCSSHexString("#f87171");
    public static final TextColor MUTED = TextColor.fromCSSHexString("#6b6472");

    private static final Pattern SPRITE_TAG = Pattern.compile("<sprite:[^>]*>\\s*");

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final Component prefix;
    private final TextColor body;

    public Branding(CoreConfig.Branding settings, Logger logger) {
        this.body = colour(settings.bodyColor(), "#f6e0ff");
        this.prefix = resolvePrefix(settings, logger);
    }

    /**
     * The sprite tag needs a recent Adventure build. Rather than assume, the
     * prefix is parsed once at load and checked for a tag that came back as
     * literal text, which is what MiniMessage does with a tag it does not know.
     */
    private static Component resolvePrefix(CoreConfig.Branding settings, Logger logger) {
        String configured = settings.prefix();
        if (configured != null && !configured.isBlank()) {
            try {
                Component parsed = MINI.deserialize(configured);
                if (!PLAIN.serialize(parsed).contains("<sprite")) {
                    return parsed;
                }
                logger.info("This server's Adventure build does not know the sprite tag, "
                        + "so the engine is using messages.prefix-fallback.");
            } catch (RuntimeException failure) {
                logger.warning("messages.prefix did not parse (" + failure.getMessage()
                        + "), using messages.prefix-fallback.");
            }
        }
        try {
            return MINI.deserialize(String.valueOf(settings.prefixFallback()));
        } catch (RuntimeException failure) {
            return Component.text("JevEngine | ", NamedTextColor.LIGHT_PURPLE);
        }
    }

    private static TextColor colour(String hex, String fallback) {
        TextColor parsed = hex == null ? null : TextColor.fromCSSHexString(hex);
        return parsed != null ? parsed : TextColor.fromCSSHexString(fallback);
    }

    public Component prefix() {
        return prefix;
    }

    public TextColor body() {
        return body;
    }

    /** Prefixed plain text, for things not driven by config. */
    public Component say(String text) {
        return Component.empty().append(prefix).append(Component.text(text, body));
    }

    /** A configured line, prefixed and coloured, with %placeholders% filled in. */
    public Component line(String raw, Map<String, String> placeholders) {
        String text = raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        Component rendered;
        try {
            rendered = MINI.deserialize("<color:" + hex(body) + ">" + text + "</color>");
        } catch (RuntimeException failure) {
            rendered = Component.text(text, body);
        }
        return Component.empty().append(prefix).append(rendered);
    }

    /**
     * A complete line, styled entirely by the config string.
     *
     * <p>No prefix and no body colour, for the few messages that carry their own
     * icon and their own voice. If the sprite tag is not understood, it is
     * dropped and the rest of the line is kept, which is better than showing the
     * tag as text.
     */
    public Component notice(String mini, Map<String, String> placeholders) {
        String text = mini;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        try {
            Component parsed = MINI.deserialize(text);
            if (!PLAIN.serialize(parsed).contains("<sprite")) {
                return parsed;
            }
            return MINI.deserialize(SPRITE_TAG.matcher(text).replaceAll(""));
        } catch (RuntimeException failure) {
            return Component.text(SPRITE_TAG.matcher(text).replaceAll(""), body);
        }
    }

    /** Green through red, so a row of numbers reads at a glance. */
    public static TextColor ramp(double value) {
        if (value < 0.15D) {
            return PASS;
        }
        if (value < 0.40D) {
            return TextColor.fromCSSHexString("#a3e635");
        }
        if (value < 0.60D) {
            return NOTICE;
        }
        if (value < 0.80D) {
            return WARN;
        }
        return ALERT;
    }

    public static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String hex(TextColor colour) {
        return String.format(Locale.ROOT, "#%06x", colour.value());
    }
}
