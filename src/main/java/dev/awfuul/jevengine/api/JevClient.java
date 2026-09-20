package dev.awfuul.jevengine.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.awfuul.jevengine.core.CoreConfig;
import dev.awfuul.jevengine.core.QuestionSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the System One endpoint. One instance, shared by every module.
 *
 * <p>Requests run on virtual threads, so the blocking form of {@code send} is
 * used rather than the callback form. A parked virtual thread costs almost
 * nothing, which means a server with a busy chat can have dozens of judgments in
 * flight without holding a pool thread for each one, and the code reads as a
 * straight line instead of a chain of callbacks. No server thread is ever
 * involved: the caller gets a future and the main thread is never touched.
 *
 * <p>A semaphore caps how many requests are open at once. Past that point
 * callers wait rather than opening more connections, and because the cap is on
 * the shared client it is a budget for the whole engine rather than one per
 * feature.
 */
public final class JevClient implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final ExecutorService executor;
    private final ApiMetrics metrics = new ApiMetrics();

    private volatile CoreConfig.Api settings;
    private volatile Semaphore inFlight;

    public JevClient(CoreConfig.Api settings) {
        this.settings = settings;
        this.inFlight = new Semaphore(settings.concurrency());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofMillis(Math.max(500, settings.timeoutMs())))
                .executor(executor)
                .build();
    }

    /**
     * Applies new API settings without replacing the client, so a reload does
     * not leave modules holding a reference to a closed one. Requests already in
     * flight finish against the permits they took.
     */
    public void reconfigure(CoreConfig.Api replacement) {
        CoreConfig.Api previous = this.settings;
        this.settings = replacement;
        if (previous.concurrency() != replacement.concurrency()) {
            this.inFlight = new Semaphore(replacement.concurrency());
        }
    }

    public ApiMetrics metrics() {
        return metrics;
    }

    public CoreConfig.Api settings() {
        return settings;
    }

    /**
     * Asks every question in one request. Independent questions over the same
     * state run in parallel inside the service, so one round trip with twelve
     * questions costs a fraction of twelve round trips and, more importantly for
     * chat, finishes in the time of the slowest one rather than the sum.
     */
    public CompletableFuture<JevResponse> ask(Object state, Map<String, QuestionSpec> questions) {
        CoreConfig.Api current = this.settings;
        String body = buildBody(current, state, questions);
        return CompletableFuture.supplyAsync(() -> sendWithRetries(current, body), executor);
    }

    private JevResponse sendWithRetries(CoreConfig.Api current, String body) {
        long started = System.nanoTime();
        JevException last = null;

        for (int attempt = 0; attempt <= current.maxRetries(); attempt++) {
            try {
                JevResponse response = sendOnce(current, body);
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                metrics.recordSuccess(elapsed, response.inputTokens());
                return new JevResponse(response.model(), response.answers(),
                        response.inputTokens(), response.outputTokens(), elapsed);
            } catch (JevException failure) {
                last = failure;
                if (!failure.retryable() || attempt == current.maxRetries()) {
                    metrics.recordFailure();
                    throw failure;
                }
                long backoff = current.retryBaseDelayMs() * (1L << attempt);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    metrics.recordFailure();
                    throw failure;
                }
            }
        }
        metrics.recordFailure();
        throw last != null ? last : new JevException("no attempt was made", 0, false);
    }

    private JevResponse sendOnce(CoreConfig.Api current, String body) {
        Semaphore permits = this.inFlight;
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(current.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new JevException("too many requests already waiting", 0, false);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(current.endpoint()))
                    .header("Authorization", "Bearer " + current.key())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(current.timeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            if (status == 200) {
                return parse(response.body());
            }
            boolean retryable = status == 429 || status == 529 || status >= 500;
            throw new JevException(shorten(response.body()), status, retryable);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JevException("interrupted", 0, false, interrupted);
        } catch (JevException rethrow) {
            throw rethrow;
        } catch (Exception failure) {
            // Connection resets and read timeouts are worth one more try.
            throw new JevException(String.valueOf(failure.getMessage()), 0, true, failure);
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private static String shorten(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        String trimmed = body.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    public String buildBody(CoreConfig.Api current, Object state,
                            Map<String, QuestionSpec> questions) {
        JsonObject root = new JsonObject();
        root.addProperty("model", current.model());
        root.add("state", toJson(state));

        JsonObject questionJson = new JsonObject();
        for (QuestionSpec spec : questions.values()) {
            JsonObject one = new JsonObject();
            one.addProperty("type", spec.type());
            one.add("instructions", toJson(spec.instructions()));
            if (spec.criteria() != null) {
                one.add("criteria", toJson(spec.criteria()));
            }
            questionJson.add(spec.id(), one);
        }
        root.add("questions", questionJson);
        return GSON.toJson(root);
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object element : list) {
                array.add(toJson(element));
            }
            return array;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean flag) {
            return new JsonPrimitive(flag);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static JevResponse parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        Map<String, Answer> answers = new LinkedHashMap<>();

        JsonObject answerJson = root.getAsJsonObject("answers");
        if (answerJson != null) {
            for (Map.Entry<String, JsonElement> entry : answerJson.entrySet()) {
                Answer answer = parseAnswer(entry.getValue().getAsJsonObject());
                if (answer != null) {
                    answers.put(entry.getKey(), answer);
                }
            }
        }

        int inputTokens = 0;
        int outputTokens = 0;
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage != null) {
            inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : 0;
            outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : 0;
        }

        String model = root.has("model") ? root.get("model").getAsString() : "";
        return new JevResponse(model, answers, inputTokens, outputTokens, 0L);
    }

    private static Answer parseAnswer(JsonObject node) {
        // Prefer the declared type, but fall back to whichever value is present
        // so a field being added to the response never drops an answer.
        String type = node.has("type") ? node.get("type").getAsString()
                : node.has("noul") ? "noul"
                : node.has("choice") ? "choice"
                : node.has("score") ? "score" : "";

        switch (type) {
            case "noul" -> {
                return new Answer.Noul(node.has("noul") ? node.get("noul").getAsDouble() : 0.0D);
            }
            case "choice" -> {
                return new Answer.Choice(
                        node.has("choice") ? node.get("choice").getAsString() : "",
                        numberMap(node.getAsJsonObject("probabilities")),
                        node.has("confidence") ? node.get("confidence").getAsDouble() : 0.0D);
            }
            case "score" -> {
                return new Answer.Score(
                        node.has("score") ? node.get("score").getAsDouble() : 0.0D,
                        numberMap(node.getAsJsonObject("probabilities")),
                        stringMap(node.getAsJsonObject("legend")),
                        node.has("confidence") ? node.get("confidence").getAsDouble() : 0.0D);
            }
            default -> {
                return null;
            }
        }
    }

    private static Map<String, Double> numberMap(JsonObject node) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (node != null) {
            for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
                out.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonObject node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null) {
            for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
                out.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return Map.copyOf(out);
    }

    @Override
    public void close() {
        try {
            http.close();
        } catch (Throwable ignored) {
            // Older runtimes have no close on HttpClient; the executor below is
            // what actually needs releasing.
        }
        executor.shutdownNow();
    }
}
