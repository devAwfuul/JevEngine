package dev.awfuul.jevengine.api;

import java.util.Map;

/**
 * One answer from a System One request. Jev returns a different shape per
 * primitive, so each is its own record and callers match on the one they asked
 * for. A Noul carries a probability and no confidence; Choice and Score carry a
 * full distribution plus the collapsed confidence derived from it.
 */
public sealed interface Answer {

    /** The number this answer is usually read by, for logging and display. */
    double primary();

    record Noul(double probability) implements Answer {
        @Override
        public double primary() {
            return probability;
        }
    }

    record Choice(String choice, Map<String, Double> probabilities, double confidence) implements Answer {
        @Override
        public double primary() {
            return probabilities.getOrDefault(choice, 0.0D);
        }
    }

    record Score(double score, Map<String, Double> probabilities, Map<String, String> legend,
                 double confidence) implements Answer {
        @Override
        public double primary() {
            return score;
        }

        /** The rubric text for the level this score sits closest to. */
        public String nearestLevel() {
            return legend.getOrDefault(String.valueOf(Math.round(score)), "");
        }
    }
}
