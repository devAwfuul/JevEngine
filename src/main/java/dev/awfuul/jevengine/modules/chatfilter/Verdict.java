package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.api.Answer;
import dev.awfuul.jevengine.text.Normalized;

import java.util.Map;

/**
 * The full record of one decision: what Jev said, what the policy made of it,
 * and what it cost. Verbose mode renders this directly, which is deliberate. If
 * something is worth acting on it should be worth showing, and a verdict that
 * cannot explain itself is one nobody can tune.
 *
 * @param decidingQuestion the question that settled the outcome
 * @param decidingValue    that question's number
 * @param guardVetoed      true when the casual-speech guard held an action back
 * @param reason           short plain-language account of why this action
 * @param assembled        this message run together with the short ones before
 *                         it, when there were any; null otherwise. Staff need to
 *                         see this, because the message on its own explains
 *                         nothing about why it was stopped.
 */
public record Verdict(Action action,
                      String category,
                      String tier,
                      String duration,
                      String reason,
                      Normalized normalized,
                      String assembled,
                      Map<String, Answer> answers,
                      String decidingQuestion,
                      double decidingValue,
                      boolean guardVetoed,
                      long latencyMs,
                      boolean cached,
                      boolean failedOpen,
                      String error,
                      int inputTokens) {

    public boolean delivers() {
        return action.delivers();
    }

    public boolean punishes() {
        return action == Action.PUNISH;
    }

    public Verdict asViolationPunishment(String tier, String duration, String reason) {
        return new Verdict(Action.PUNISH, category, tier, duration, reason, normalized,
                assembled, answers, decidingQuestion, decidingValue, guardVetoed, latencyMs,
                cached, failedOpen, error, inputTokens);
    }

    public boolean wasAssembled() {
        return assembled != null && !assembled.isBlank();
    }

    /** A message too short to be worth a request. */
    public static Verdict skipped(Normalized normalized, String why) {
        return new Verdict(Action.PASS, "none", null, null, why, normalized, null, Map.of(),
                "", 0.0D, false, 0L, false, false, null, 0);
    }

    /** The service could not be reached and fail-open let the message through. */
    public static Verdict failedOpen(Normalized normalized, String error, long latencyMs) {
        return new Verdict(Action.PASS, "none", null, null, "moderation unavailable", normalized,
                null, Map.of(), "", 0.0D, false, latencyMs, false, true, error, 0);
    }

    /** The service could not be reached and fail-open was off. */
    public static Verdict failedClosed(Normalized normalized, String error, long latencyMs) {
        return new Verdict(Action.BLOCK, "none", null, null, "moderation unavailable", normalized,
                null, Map.of(), "", 0.0D, false, latencyMs, false, true, error, 0);
    }

    public Verdict asCached(long latencyMs) {
        return new Verdict(action, category, tier, duration, reason, normalized, assembled,
                answers, decidingQuestion, decidingValue, guardVetoed, latencyMs, true, failedOpen,
                error, 0);
    }

    public Verdict withNormalized(Normalized replacement) {
        return new Verdict(action, category, tier, duration, reason, replacement, assembled,
                answers, decidingQuestion, decidingValue, guardVetoed, latencyMs, cached,
                failedOpen, error, inputTokens);
    }
}
