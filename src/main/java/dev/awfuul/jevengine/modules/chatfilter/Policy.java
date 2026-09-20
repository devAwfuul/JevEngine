package dev.awfuul.jevengine.modules.chatfilter;

import dev.awfuul.jevengine.api.Answer;
import dev.awfuul.jevengine.api.JevResponse;
import dev.awfuul.jevengine.text.Normalized;

/**
 * Turns Jev's numbers into an action.
 *
 * <p>Nothing here calls the network, which is the point. The judgments and the
 * policy are kept apart so thresholds can be retuned, argued over, and reloaded
 * without re-running a single request, and so the reason a player was punished
 * is a few readable comparisons rather than something buried in a model.
 *
 * <p>The order of the checks matters. A hazard has to clear its bar first, then
 * three separate things can pull the action back down: the casual-speech guard,
 * the category question disagreeing, and the severity score. Each one can only
 * lower the outcome, never raise it. That asymmetry is what keeps a single
 * confident-but-wrong number from reaching a punishment on its own.
 */
public final class Policy {

    private Policy() {
    }

    public static Verdict decide(ChatFilterConfig config, JevResponse response, Normalized normalized,
                                 String assembled) {
        ChatFilterConfig.Guard guard = config.guard();
        ChatFilterConfig.EvasionGate evasionGate = config.evasion();

        double casual = response.noul(guard.question());
        double evasion = response.noul(evasionGate.question());
        boolean disguised = evasion >= evasionGate.threshold();

        ChatFilterConfig.Hazard fired = null;
        Action action = Action.PASS;
        String reason = "nothing matched";

        // Hazards are checked in configured order, worst first, so the most
        // serious thing present is the one that decides the outcome.
        for (ChatFilterConfig.Hazard hazard : config.hazards()) {
            double probability = response.noul(hazard.id());
            double bar = disguised
                    ? hazard.action() - evasionGate.lowersActionThresholdBy()
                    : hazard.action();
            if (probability >= bar) {
                fired = hazard;
                action = hazard.onAction();
                reason = hazard.id() + " at " + format(probability)
                        + (disguised ? ", spelling was disguised" : "");
                break;
            }
        }

        if (fired == null) {
            ChatFilterConfig.Hazard highest = null;
            double highestProbability = 0.0D;
            for (ChatFilterConfig.Hazard hazard : config.hazards()) {
                double probability = response.noul(hazard.id());
                if (probability >= hazard.review() && probability > highestProbability) {
                    highest = hazard;
                    highestProbability = probability;
                }
            }
            if (highest != null) {
                fired = highest;
                action = Action.FLAG;
                reason = highest.id() + " at " + format(highestProbability) + ", worth a look";
            }
        }

        // The guard. Jev is asked separately whether the worst thing here is
        // ordinary swearing, and when it says yes that answer outranks a hazard
        // unless the hazard is near certain. This is the single biggest reason a
        // frustrated player does not get banned for being frustrated.
        //
        // It only applies to hazards that are about how a player is treating
        // someone. Advertising, spam and a slur spelled out one message at a
        // time are all perfectly polite, so asking whether the tone was civil
        // would veto every one of them.
        boolean guardVetoed = false;
        if (action.ordinal() > Action.FLAG.ordinal() && casual >= guard.threshold()
                && fired != null && fired.applyCasualGuard()) {
            double firedProbability = response.noul(fired.id());
            if (firedProbability < guard.overrideAt()) {
                action = Action.FLAG;
                guardVetoed = true;
                reason = "held back: this reads as ordinary swearing (" + format(casual) + ")";
            }
        }

        String category = fired != null ? fired.id() : "none";

        // A second opinion from a different question. If the category choice is
        // confidently 'none' while a hazard fired, the two disagree, and a
        // disagreement is not a good enough basis for a punishment.
        Answer.Choice categoryAnswer = response.choice("category");
        if (action == Action.PUNISH && categoryAnswer != null
                && "none".equals(categoryAnswer.choice())
                && categoryAnswer.confidence() >= config.minConfidenceForPunish()) {
            action = Action.BLOCK;
            reason = "held back: the category question sees no rule broken";
        }

        // The harm floor, for the hazards where harm is the test. A split bypass
        // is exempt for a concrete reason: the message being judged is the last
        // fragment, which on its own does nothing to anyone and scores zero.
        if (action == Action.PUNISH && fired != null && fired.applySeverityFloor()) {
            ChatFilterConfig.SeverityGate gate = config.severity();
            Answer.Score severity = response.score(gate.question());
            if (severity == null) {
                action = Action.BLOCK;
                reason = "held back: no severity score came back";
            } else if (severity.score() < gate.minForPunish()) {
                action = Action.BLOCK;
                reason = "held back: severity " + format(severity.score()) + " is below the line";
            } else if (severity.confidence() < gate.minConfidence()) {
                action = Action.BLOCK;
                reason = "held back: severity is unclear (confidence "
                        + format(severity.confidence()) + ")";
            }
        }

        if (action == Action.PUNISH && !config.punishments().enabled()) {
            action = Action.BLOCK;
            reason = reason + ", punishments are off";
        }

        String tier = null;
        String duration = null;
        if (action == Action.PUNISH) {
            ChatFilterConfig.DurationPolicy policy = config.punishments().duration();
            tier = policy.defaultTier();
            if (policy.enabled()) {
                Answer.Choice pick = response.choice(policy.question());
                if (pick != null && pick.confidence() >= policy.minConfidence()
                        && policy.knows(pick.choice())) {
                    tier = pick.choice();
                } else if (pick != null) {
                    reason = reason + ", duration fell back to " + tier;
                }
            }
            tier = policy.clamp(tier, category);
            duration = policy.valueFor(tier);
        }

        String decidingQuestion = fired != null ? fired.id() : guard.question();
        double decidingValue = fired != null ? response.noul(fired.id()) : casual;

        return new Verdict(action, category, tier, duration, reason, normalized, assembled,
                response.answers(), decidingQuestion, decidingValue, guardVetoed,
                response.latencyMs(), false, false, null, response.inputTokens());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
