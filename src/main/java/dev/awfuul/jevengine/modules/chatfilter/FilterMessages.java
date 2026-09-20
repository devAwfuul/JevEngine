package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.api.Answer;
import dev.awfuul.jevengine.ui.Branding;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Map;

/**
 * What the filter puts on screen.
 *
 * <p>The prefix and colours come from the engine's shared branding, so this only
 * knows how to lay out a verdict. Player-typed text is always a literal
 * component and never goes through MiniMessage.
 */
public final class FilterMessages {

    private final Branding branding;
    private volatile ChatFilterConfig config;

    public FilterMessages(Branding branding, ChatFilterConfig config) {
        this.branding = branding;
        this.config = config;
    }

    public void apply(ChatFilterConfig replacement) {
        this.config = replacement;
    }

    public Component line(String key, String fallback, Map<String, String> placeholders) {
        return branding.line(config.line(key, fallback), placeholders);
    }

    public Component line(String key, String fallback) {
        return line(key, fallback, Map.of());
    }

    public Component say(String text) {
        return branding.say(text);
    }

    /** A line that carries its own icon and colour, with no engine prefix. */
    public Component notice(String key, String fallback) {
        return branding.notice(config.line(key, fallback), Map.of());
    }

    /**
     * The verbose readout: the message, every answer that came back, and the one
     * line of policy that settled it.
     */
    public Component report(String playerName, Verdict verdict, ChatFilterConfig settings,
                            boolean showAllQuestions) {
        TextComponent.Builder out = Component.text();
        out.append(branding.prefix())
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(dot())
                .append(Component.text(verdict.action().name(), colorFor(verdict.action()))
                        .decorate(TextDecoration.BOLD))
                .append(dot())
                .append(Component.text(verdict.cached() ? "cached"
                        : verdict.latencyMs() + "ms", Branding.MUTED));

        if (verdict.inputTokens() > 0) {
            out.append(dot())
                    .append(Component.text(verdict.inputTokens() + " tok", Branding.MUTED));
        }

        out.append(Component.newline())
                .append(branch())
                .append(Component.text("\"", Branding.MUTED))
                .append(Component.text(verdict.normalized().raw(), NamedTextColor.GRAY))
                .append(Component.text("\"", Branding.MUTED));

        if (verdict.normalized().altered()) {
            out.append(Component.newline())
                    .append(branch())
                    .append(Component.text("reads as ", Branding.MUTED))
                    .append(Component.text(verdict.normalized().deobfuscated(), Branding.NOTICE));
        }

        if (verdict.wasAssembled()) {
            out.append(Component.newline())
                    .append(branch())
                    .append(Component.text("with the messages before it ", Branding.MUTED))
                    .append(Component.text(verdict.assembled(), Branding.WARN));
        }

        if (!verdict.answers().isEmpty()) {
            int column = 0;
            TextComponent.Builder row = null;
            for (Map.Entry<String, Answer> entry : verdict.answers().entrySet()) {
                if (entry.getValue() instanceof Answer.Noul noul) {
                    if (!showAllQuestions && noul.probability() < 0.05D
                            && !entry.getKey().equals(verdict.decidingQuestion())) {
                        continue;
                    }
                    if (row == null) {
                        row = Component.text();
                    }
                    row.append(Component.text(entry.getKey() + " ", Branding.MUTED))
                            .append(Component.text(Branding.number(noul.probability()),
                                    Branding.ramp(noul.probability())))
                            .append(Component.text("  "));
                    if (++column % 3 == 0) {
                        out.append(Component.newline()).append(branch()).append(row.build());
                        row = null;
                    }
                }
            }
            if (row != null) {
                out.append(Component.newline()).append(branch()).append(row.build());
            }

            for (Map.Entry<String, Answer> entry : verdict.answers().entrySet()) {
                if (entry.getValue() instanceof Answer.Score score) {
                    out.append(Component.newline()).append(branch())
                            .append(Component.text(entry.getKey() + " ", Branding.MUTED))
                            .append(Component.text(Branding.number(score.score()),
                                    Branding.ramp(score.score() / 4.0D)))
                            .append(Component.text(" conf " + Branding.number(score.confidence()),
                                    Branding.MUTED))
                            .append(Component.text("  " + score.nearestLevel(),
                                    NamedTextColor.DARK_GRAY));
                } else if (entry.getValue() instanceof Answer.Choice choice) {
                    out.append(Component.newline()).append(branch())
                            .append(Component.text(entry.getKey() + " ", Branding.MUTED))
                            .append(Component.text(choice.choice(),
                                    "none".equals(choice.choice())
                                            ? Branding.PASS : Branding.WARN))
                            .append(Component.text(" " + Branding.number(choice.primary())
                                    + " conf " + Branding.number(choice.confidence()),
                                    Branding.MUTED));
                }
            }
        }

        if (verdict.error() != null) {
            out.append(Component.newline()).append(branch())
                    .append(Component.text("api: " + verdict.error(), Branding.ALERT));
        }

        out.append(Component.newline())
                .append(Component.text("  └ ", Branding.MUTED))
                .append(Component.text(verdict.reason(),
                        verdict.guardVetoed() ? Branding.PASS : NamedTextColor.GRAY));

        if (verdict.punishes()) {
            out.append(Component.newline())
                    .append(Component.text("  └ ", Branding.MUTED))
                    .append(Component.text("ran " + verdict.category() + " for "
                            + verdict.duration() + " (" + verdict.tier() + ")", Branding.ALERT));
        }
        return out.build();
    }

    /** The shorter version staff see when something is flagged or blocked. */
    public Component alert(String playerName, Verdict verdict) {
        TextComponent.Builder out = Component.text()
                .append(branding.prefix())
                .append(Component.text(verdict.action().name(), colorFor(verdict.action()))
                        .decorate(TextDecoration.BOLD))
                .append(dot())
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(dot())
                .append(Component.text(verdict.category(), branding.body()))
                .append(Component.newline())
                .append(branch())
                .append(Component.text("\"", Branding.MUTED))
                .append(Component.text(verdict.normalized().raw(), NamedTextColor.GRAY))
                .append(Component.text("\"", Branding.MUTED));

        // Without this a staff member sees a player blocked for typing "er".
        if (verdict.wasAssembled()) {
            out.append(Component.newline())
                    .append(branch())
                    .append(Component.text("with the messages before it ", Branding.MUTED))
                    .append(Component.text(verdict.assembled(), Branding.WARN));
        }

        return out.append(Component.newline())
                .append(Component.text("  └ ", Branding.MUTED))
                .append(Component.text(verdict.reason(), NamedTextColor.GRAY))
                .build();
    }

    private static Component branch() {
        return Component.text("  │ ", Branding.MUTED);
    }

    private static Component dot() {
        return Component.text(" · ", Branding.MUTED);
    }

    public static TextColor colorFor(Action action) {
        return switch (action) {
            case PASS -> Branding.PASS;
            case FLAG -> Branding.NOTICE;
            case BLOCK -> Branding.WARN;
            case PUNISH -> Branding.ALERT;
        };
    }
}
