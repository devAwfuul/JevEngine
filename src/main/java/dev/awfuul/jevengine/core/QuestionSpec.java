package dev.awfuul.jevengine.core;

/**
 * One question as written in config.yml.
 *
 * <p>Instructions and criteria stay as loose objects rather than strings because
 * Jev accepts nested JSON in both, so an admin can expand a flat description into
 * something like {@code {what: ..., not_for: ..., examples: [...]}} without the
 * plugin needing to know about the shape.
 *
 * @param id          the key in config.yml, which the policy section refers to
 * @param type        noul, choice or score
 * @param instructions the judgment being asked for
 * @param criteria    a map for noul and choice, a list for score
 */
public record QuestionSpec(String id, String type, Object instructions, Object criteria) {

    public boolean isNoul() {
        return "noul".equals(type);
    }

    public boolean isChoice() {
        return "choice".equals(type);
    }

    public boolean isScore() {
        return "score".equals(type);
    }

    public boolean typeIsKnown() {
        return isNoul() || isChoice() || isScore();
    }
}
