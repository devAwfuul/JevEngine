package dev.awfuul.jevengine.api;

/** A call to the System One endpoint that did not produce a usable answer. */
public class JevException extends RuntimeException {

    private final int status;
    private final boolean retryable;

    public JevException(String message, int status, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
    }

    public JevException(String message, int status, boolean retryable) {
        this(message, status, retryable, null);
    }

    /** HTTP status, or 0 when the request never reached the service. */
    public int status() {
        return status;
    }

    /**
     * True only for rate limiting and overload. Authentication and validation
     * failures are configuration problems and retrying them just burns time.
     */
    public boolean retryable() {
        return retryable;
    }

    /** Short text for the console and the verbose readout. */
    public String shortReason() {
        return switch (status) {
            case 401 -> "bad API key";
            case 422 -> "question schema rejected";
            case 429 -> "rate limited";
            case 529 -> "service overloaded";
            case 0 -> "no response";
            default -> "HTTP " + status;
        };
    }
}
