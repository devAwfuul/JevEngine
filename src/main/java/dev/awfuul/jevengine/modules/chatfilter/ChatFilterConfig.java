package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.core.QuestionSpec;
import dev.awfuul.jevengine.text.Deobfuscator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventPriority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * chatfilter.yml, parsed once and then read from every chat message.
 *
 * <p>Everything is immutable after {@link #load}. A reload builds a new instance
 * and the module swaps the reference, so a message already in flight keeps the
 * settings it started with instead of seeing half of an edit.
 *
 * <p>The API key and the branding are not here. They belong to the engine,
 * because a second module would want the same ones.
 */
public final class ChatFilterConfig {

    /**
     * @param lowLatency show the sender their own message straight away and
     *                   hold it only from everyone else. Chat feels instant
     *                   to the person typing, and a message that turns out to
     *                   break a rule still never reaches the room.
     */
    public record Chat(boolean enabled, EventPriority priority, boolean hold,
                       boolean lowLatency, boolean failOpen, int minLength,
                       boolean cacheEnabled, int cacheSize, long cacheTtlMillis,
                       int contextLines) {
    }

    public record State(String serverContext, boolean includeSenderName,
                        boolean includePlayerTenure) {
    }

    /**
     * @param splitQuestion  asked only when consecutive short messages actually
     *                       produced something to join, so an ordinary sentence
     *                       never pays for it
     * @param scanAddresses  whether to pull address-shaped text out of a message
     *                       and hand the candidates to the advertising question
     */
    public record Detection(ChatHistory.Settings history, String splitQuestion,
                            boolean scanAddresses, Set<String> extraTlds) {
    }

    /**
     * @param applyCasualGuard  whether the "this is only swearing" veto can hold
     *                          this hazard back. False for rules that are broken
     *                          whether or not the message is polite.
     * @param applySeverityFloor whether a punishment needs the harm score above
     *                          the line. False for the same reason, and for a
     *                          split bypass, where the fragment on its own
     *                          carries no harm at all.
     */
    public record Hazard(String id, double review, double action, Action onAction,
                         boolean applyCasualGuard, boolean applySeverityFloor) {
    }

    public record Guard(String question, double threshold, double overrideAt) {
    }

    public record SeverityGate(String question, double minForPunish, double minConfidence) {
    }

    public record EvasionGate(String question, double threshold, double lowersActionThresholdBy) {
    }

    public record DurationPolicy(boolean enabled, String question, double minConfidence,
                                 String defaultTier, List<String> order,
                                 Map<String, String> values, Map<String, String> maxTier) {

        public String valueFor(String tier) {
            return values.getOrDefault(tier, tier);
        }

        public boolean knows(String tier) {
            return order.contains(tier);
        }

        /**
         * Holds a tier to the ceiling configured for its category. This is the
         * last word on how long a punishment can be, so however sure Jev is that
         * something deserves a permanent ban, a category capped at a day gets a
         * day.
         */
        public String clamp(String tier, String category) {
            int chosen = order.indexOf(tier);
            if (chosen < 0) {
                chosen = Math.max(0, order.indexOf(defaultTier));
            }
            String ceiling = maxTier.getOrDefault(category, maxTier.get("default"));
            int capped = ceiling == null ? -1 : order.indexOf(ceiling);
            if (capped < 0) {
                return order.get(chosen);
            }
            return order.get(Math.min(chosen, capped));
        }
    }

    public record Punishments(boolean enabled, Map<String, String> commands, String reason,
                              DurationPolicy duration) {

        public String commandFor(String category) {
            String command = commands.get(category);
            return command != null ? command : commands.get("default");
        }
    }

    /** Repeated review-worthy messages can become a punishment of their own. */
    public record Violations(boolean enabled, int threshold, long windowMillis,
                             Map<String, Integer> thresholds) {

        public int thresholdFor(String category) {
            return thresholds.getOrDefault(category, threshold);
        }
    }

    public record Verbose(boolean console, boolean showClean, boolean showAllQuestions) {
    }

    private final Chat chat;
    private final State state;
    private final Detection detection;
    private final Deobfuscator.Settings deobfuscation;
    private final Map<String, QuestionSpec> questions;
    private final List<Hazard> hazards;
    private final Guard guard;
    private final SeverityGate severity;
    private final EvasionGate evasion;
    private final double minConfidenceForPunish;
    private final Punishments punishments;
    private final Violations violations;
    private final Verbose verbose;
    private final Map<String, String> lines;
    private final List<String> warnings;

    private ChatFilterConfig(Chat chat, State state, Detection detection,
                             Deobfuscator.Settings deobfuscation,
                             Map<String, QuestionSpec> questions, List<Hazard> hazards,
                             Guard guard, SeverityGate severity, EvasionGate evasion,
                             double minConfidenceForPunish, Punishments punishments,
                             Violations violations, Verbose verbose, Map<String, String> lines,
                             List<String> warnings) {
        this.chat = chat;
        this.state = state;
        this.detection = detection;
        this.deobfuscation = deobfuscation;
        this.questions = Collections.unmodifiableMap(questions);
        this.hazards = List.copyOf(hazards);
        this.guard = guard;
        this.severity = severity;
        this.evasion = evasion;
        this.minConfidenceForPunish = minConfidenceForPunish;
        this.punishments = punishments;
        this.violations = violations;
        this.verbose = verbose;
        this.lines = Map.copyOf(lines);
        this.warnings = List.copyOf(warnings);
    }

    public static ChatFilterConfig load(FileConfiguration file) {
        List<String> warnings = new ArrayList<>();

        EventPriority priority;
        try {
            priority = EventPriority.valueOf(
                    file.getString("chat.event-priority", "HIGHEST").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            priority = EventPriority.HIGHEST;
            warnings.add("chat.event-priority is not a valid priority, using HIGHEST");
        }

        boolean hold = file.getBoolean("chat.hold-messages", true);
        boolean lowLatency = file.getBoolean("chat.low-latency", true);
        if (lowLatency && !hold) {
            warnings.add("chat.low-latency does nothing while chat.hold-messages is "
                    + "false, because nothing is being held back from anyone");
        }

        Chat chat = new Chat(
                file.getBoolean("chat.enabled", true),
                priority,
                hold,
                lowLatency,
                file.getBoolean("chat.fail-open", true),
                file.getInt("chat.min-length", 2),
                file.getBoolean("chat.cache.enabled", true),
                file.getInt("chat.cache.size", 4096),
                file.getLong("chat.cache.ttl-seconds", 600L) * 1000L,
                Math.max(0, file.getInt("chat.context-lines", 4)));

        State state = new State(
                file.getString("state.server-context", ""),
                file.getBoolean("state.include-sender-name", true),
                file.getBoolean("state.include-player-tenure", true));

        ChatHistory.Settings history = new ChatHistory.Settings(
                file.getBoolean("detection.split.enabled", true),
                file.getLong("detection.split.window-seconds", 20L) * 1000L,
                Math.max(2, file.getInt("detection.split.max-fragments", 6)),
                Math.max(1, file.getInt("detection.split.max-fragment-length", 8)),
                Math.max(2, file.getInt("detection.split.min-joined-length", 5)),
                file.getLong("detection.spam.rate-window-seconds", 15L) * 1000L,
                Math.max(4, file.getInt("detection.memory", 12)));

        Set<String> extraTlds = new LinkedHashSet<>();
        for (String tld : file.getStringList("detection.advertising.extra-tlds")) {
            extraTlds.add(tld.trim().toLowerCase(Locale.ROOT).replace(".", ""));
        }

        Detection detection = new Detection(
                history,
                file.getString("detection.split.question", "split_bypass"),
                file.getBoolean("detection.advertising.scan-addresses", true),
                Set.copyOf(extraTlds));

        Deobfuscator.Settings deobfuscation = new Deobfuscator.Settings(
                file.getBoolean("deobfuscation.enabled", true),
                file.getBoolean("deobfuscation.strip-invisible", true),
                file.getBoolean("deobfuscation.strip-marks", true),
                file.getBoolean("deobfuscation.fold-confusables", true),
                file.getBoolean("deobfuscation.expand-leet", true),
                file.getBoolean("deobfuscation.join-spaced-letters", true),
                file.getBoolean("deobfuscation.collapse-repeats", true),
                file.getBoolean("deobfuscation.aggressive-leet.enabled", true),
                file.getBoolean("deobfuscation.aggressive-leet.multi-character", true),
                file.getDouble("deobfuscation.aggressive-leet.min-density", 0.34D),
                Math.max(32, file.getInt("deobfuscation.max-length", 512)));

        Map<String, QuestionSpec> questions = new LinkedHashMap<>();
        ConfigurationSection questionSection = file.getConfigurationSection("questions");
        if (questionSection == null) {
            warnings.add("no questions section, the filter has nothing to ask");
        } else {
            for (String id : questionSection.getKeys(false)) {
                ConfigurationSection one = questionSection.getConfigurationSection(id);
                if (one == null) {
                    warnings.add("question " + id + " is not a block of settings");
                    continue;
                }
                String type = String.valueOf(one.getString("type", "")).toLowerCase(Locale.ROOT);
                QuestionSpec spec = new QuestionSpec(id, type,
                        plain(one.get("instructions")), plain(one.get("criteria")));
                if (!spec.typeIsKnown()) {
                    warnings.add("question " + id + " has type '" + type
                            + "', expected noul, choice or score");
                    continue;
                }
                if (spec.instructions() == null) {
                    warnings.add("question " + id + " has no instructions");
                    continue;
                }
                questions.put(id, spec);
            }
        }

        List<Hazard> hazards = new ArrayList<>();
        for (Map<?, ?> entry : file.getMapList("policy.hazards")) {
            Object id = entry.get("id");
            if (id == null) {
                warnings.add("a hazard entry has no id");
                continue;
            }
            String hazardId = String.valueOf(id);
            if (!questions.containsKey(hazardId)) {
                warnings.add("hazard '" + hazardId + "' has no matching question and is ignored");
                continue;
            }
            hazards.add(new Hazard(hazardId,
                    number(entry.get("review"), 0.55D),
                    number(entry.get("action"), 0.85D),
                    Action.parse(entry.get("on-action") == null ? null
                            : String.valueOf(entry.get("on-action")), Action.BLOCK),
                    flag(entry.get("apply-casual-guard"), true),
                    flag(entry.get("apply-severity-floor"), true)));
        }
        if (hazards.isEmpty()) {
            warnings.add("no usable hazards, every message will pass");
        }

        Guard guard = new Guard(
                file.getString("policy.guard.question", "casual_only"),
                file.getDouble("policy.guard.threshold", 0.60D),
                file.getDouble("policy.guard.override-at", 0.95D));
        if (!questions.containsKey(guard.question())) {
            warnings.add("the guard question '" + guard.question()
                    + "' is missing, so nothing is protecting ordinary swearing");
        }

        SeverityGate severity = new SeverityGate(
                file.getString("policy.severity.question", "severity"),
                file.getDouble("policy.severity.min-for-punish", 2.0D),
                file.getDouble("policy.severity.min-confidence", 0.55D));

        EvasionGate evasion = new EvasionGate(
                file.getString("policy.evasion.question", "evasion"),
                file.getDouble("policy.evasion.threshold", 0.80D),
                file.getDouble("policy.evasion.lowers-action-threshold-by", 0.05D));

        double minConfidence = file.getDouble("policy.confidence.min-for-punish", 0.70D);

        List<String> tierOrder = new ArrayList<>();
        Map<String, String> tierValues = new LinkedHashMap<>();
        ConfigurationSection tiers = file.getConfigurationSection("punishments.duration.tiers");
        if (tiers != null) {
            for (String tier : tiers.getKeys(false)) {
                tierOrder.add(tier);
                tierValues.put(tier, tiers.getString(tier, tier));
            }
        }
        if (tierOrder.isEmpty()) {
            tierOrder = List.of("warn", "short", "medium", "long", "permanent");
            tierValues = Map.of("warn", "10m", "short", "1h", "medium", "1d",
                    "long", "7d", "permanent", "permanent");
            warnings.add("no duration tiers configured, falling back to the built-in ladder");
        }

        Map<String, String> maxTier = new LinkedHashMap<>();
        ConfigurationSection caps = file.getConfigurationSection("punishments.duration.max-tier");
        if (caps != null) {
            for (String category : caps.getKeys(false)) {
                maxTier.put(category, caps.getString(category));
            }
        }

        String defaultTier = file.getString("punishments.duration.default-tier", "short");
        if (!tierOrder.contains(defaultTier)) {
            warnings.add("default-tier '" + defaultTier + "' is not one of the tiers, using "
                    + tierOrder.get(0));
            defaultTier = tierOrder.get(0);
        }

        DurationPolicy duration = new DurationPolicy(
                file.getBoolean("punishments.duration.enabled", false),
                file.getString("punishments.duration.question", "duration_tier"),
                file.getDouble("punishments.duration.min-confidence", 0.75D),
                defaultTier,
                List.copyOf(tierOrder),
                Map.copyOf(tierValues),
                Map.copyOf(maxTier));

        if (duration.enabled() && !questions.containsKey(duration.question())) {
            warnings.add("duration is on but the question '" + duration.question()
                    + "' is missing, so every punishment will use the default tier");
        }

        Map<String, String> commands = new LinkedHashMap<>();
        ConfigurationSection commandSection = file.getConfigurationSection("punishments.commands");
        if (commandSection != null) {
            for (String category : commandSection.getKeys(false)) {
                commands.put(category, commandSection.getString(category));
            }
        }
        Punishments punishments = new Punishments(
                file.getBoolean("punishments.enabled", false),
                Map.copyOf(commands),
                file.getString("punishments.reason", "JevEngine: %category%"),
                duration);
        if (punishments.enabled() && commands.isEmpty()) {
            warnings.add("punishments are on but no commands are configured");
        }

        int violationThreshold = Math.max(1, file.getInt("violations.default-threshold", 3));
        long violationWindow = Math.max(1L,
                file.getLong("violations.window-seconds", 300L)) * 1000L;
        Map<String, Integer> violationThresholds = new LinkedHashMap<>();
        ConfigurationSection violationSection = file.getConfigurationSection("violations.thresholds");
        if (violationSection != null) {
            for (String category : violationSection.getKeys(false)) {
                int categoryThreshold = violationSection.getInt(category, violationThreshold);
                violationThresholds.put(category, Math.max(1, categoryThreshold));
            }
        }
        Violations violations = new Violations(
                file.getBoolean("violations.enabled", true), violationThreshold,
                violationWindow, Map.copyOf(violationThresholds));

        Verbose verbose = new Verbose(
                file.getBoolean("verbose.console", false),
                file.getBoolean("verbose.show-clean", true),
                file.getBoolean("verbose.show-all-questions", true));

        Map<String, String> lines = new LinkedHashMap<>();
        ConfigurationSection messageSection = file.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String messageKey : messageSection.getKeys(false)) {
                if (messageSection.get(messageKey) instanceof String text) {
                    lines.put(messageKey, text);
                }
            }
        }

        if (history.splitEnabled() && !questions.containsKey(detection.splitQuestion())) {
            warnings.add("split protection is on but the question '" + detection.splitQuestion()
                    + "' is missing, so nothing will be asked about joined fragments");
        }

        return new ChatFilterConfig(chat, state, detection, deobfuscation, questions, hazards,
                guard, severity, evasion, minConfidence, punishments, violations,
                verbose, lines, warnings);
    }

    /**
     * Bukkit hands back its own section types for nested blocks. Converting them
     * to plain maps and lists here means the request builder can serialise any
     * shape an admin writes without knowing anything about Bukkit.
     */
    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                out.put(key, plain(section.get(key)));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(plain(element));
            }
            return out;
        }
        return value;
    }

    private static boolean flag(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public Chat chat() {
        return chat;
    }

    public State state() {
        return state;
    }

    public Detection detection() {
        return detection;
    }

    public Deobfuscator.Settings deobfuscation() {
        return deobfuscation;
    }

    public Map<String, QuestionSpec> questions() {
        return questions;
    }

    public List<Hazard> hazards() {
        return hazards;
    }

    public Guard guard() {
        return guard;
    }

    public SeverityGate severity() {
        return severity;
    }

    public EvasionGate evasion() {
        return evasion;
    }

    public double minConfidenceForPunish() {
        return minConfidenceForPunish;
    }

    public Punishments punishments() {
        return punishments;
    }

    public Violations violations() {
        return violations;
    }

    public Verbose verbose() {
        return verbose;
    }

    public String line(String key, String fallback) {
        return lines.getOrDefault(key, fallback);
    }

    public List<String> warnings() {
        return warnings;
    }
}
