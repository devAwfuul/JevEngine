package dev.awfuul.jevengine.api;

import java.util.Map;

/** A parsed System One response, plus what it cost to get. */
public record JevResponse(String model,
                          Map<String, Answer> answers,
                          int inputTokens,
                          int outputTokens,
                          long latencyMs) {

    public double noul(String id) {
        return answers.get(id) instanceof Answer.Noul n ? n.probability() : 0.0D;
    }

    public Answer.Choice choice(String id) {
        return answers.get(id) instanceof Answer.Choice c ? c : null;
    }

    public Answer.Score score(String id) {
        return answers.get(id) instanceof Answer.Score s ? s : null;
    }
}
