package dev.awfuul.jevengine.modules.triggerbot;

import dev.awfuul.jevengine.core.QuestionSpec;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Settings for the short, on-demand triggerbot sample. */
public final class TriggerbotConfig {

    public record Sampling(int durationSeconds, int intervalTicks, double opponentRadius,
                            int maxOpponents, int maxFrames, int maxAttacks,
                            int minimumFrames, int maxConcurrentChecks) {
    }

    private static final List<Map<String, String>> DEFAULT_CRITERIA = List.of(
            Map.of("range", "0-24", "meaning", "The sample is consistent with ordinary play."),
            Map.of("range", "25-49", "meaning", "There are weak or mixed automation signals."),
            Map.of("range", "50-74", "meaning", "The sample contains several suspicious patterns."),
            Map.of("range", "75-100", "meaning", "The sample strongly supports automated aiming or clicking."));

    private final Sampling sampling;
    private final String questionId;
    private final QuestionSpec question;
    private final double reportThreshold;
    private final List<String> warnings;

    private TriggerbotConfig(Sampling sampling, String questionId, QuestionSpec question,
                             double reportThreshold, List<String> warnings) {
        this.sampling = sampling;
        this.questionId = questionId;
        this.question = question;
        this.reportThreshold = reportThreshold;
        this.warnings = List.copyOf(warnings);
    }

    public static TriggerbotConfig load(FileConfiguration file) {
        List<String> warnings = new ArrayList<>();

        int duration = boundedInt(file, "sampling.duration-seconds", 5, 1, 15, warnings);
        int interval = boundedInt(file, "sampling.interval-ticks", 2, 1, 20, warnings);
        double radius = boundedDouble(file, "sampling.opponent-radius", 12.0D,
                2.0D, 32.0D, warnings);
        int maxOpponents = boundedInt(file, "sampling.max-opponents", 4, 0, 16, warnings);
        int maxFrames = boundedInt(file, "sampling.max-frames", 80, 10, 400, warnings);
        int maxAttacks = boundedInt(file, "sampling.max-attacks", 128, 1, 512, warnings);
        int minimumFrames = boundedInt(file, "sampling.minimum-frames", 10, 1, maxFrames,
                warnings);
        int maxConcurrent = boundedInt(file, "sampling.max-concurrent-checks", 4, 1, 32,
                warnings);

        String questionId = file.getString("question.id", "triggerbot_score");
        if (questionId == null || questionId.isBlank()) {
            questionId = "triggerbot_score";
            warnings.add("question.id is blank, using triggerbot_score");
        }
        String instructions = file.getString("question.instructions", "");
        if (instructions == null || instructions.isBlank()) {
            instructions = "Rate how strongly this five second Minecraft combat sample "
                    + "supports a triggerbot or other automated attack pattern. "
                    + "Account for the player's ping, movement, target movement, aim "
                    + "changes and normal human variation. A skilled player is not "
                    + "evidence of automation, and a high score is an investigation "
                    + "lead rather than proof.";
            warnings.add("question.instructions is blank, using the built-in instructions");
        }
        Object criteria = file.get("question.criteria");
        if (criteria == null) {
            criteria = DEFAULT_CRITERIA;
        }
        String type = file.getString("question.type", "score");
        if (!"score".equalsIgnoreCase(type)) {
            warnings.add("question.type must be score, using score");
            type = "score";
        }
        QuestionSpec question = new QuestionSpec(questionId, type.toLowerCase(), instructions,
                criteria);

        double threshold = boundedDouble(file, "report-threshold", 70.0D,
                0.0D, 100.0D, warnings);
        return new TriggerbotConfig(
                new Sampling(duration, interval, radius, maxOpponents, maxFrames, maxAttacks,
                        minimumFrames, maxConcurrent),
                questionId, question, threshold, warnings);
    }

    private static int boundedInt(FileConfiguration file, String path, int fallback,
                                  int minimum, int maximum, List<String> warnings) {
        int value = file.getInt(path, fallback);
        if (value < minimum || value > maximum) {
            warnings.add(path + " must be between " + minimum + " and " + maximum
                    + ", using " + fallback);
            return fallback;
        }
        return value;
    }

    private static double boundedDouble(FileConfiguration file, String path, double fallback,
                                        double minimum, double maximum,
                                        List<String> warnings) {
        double value = file.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            warnings.add(path + " must be between " + minimum + " and " + maximum
                    + ", using " + fallback);
            return fallback;
        }
        return value;
    }

    public Sampling sampling() {
        return sampling;
    }

    public String questionId() {
        return questionId;
    }

    public QuestionSpec question() {
        return question;
    }

    public double reportThreshold() {
        return reportThreshold;
    }

    public List<String> warnings() {
        return warnings;
    }
}
