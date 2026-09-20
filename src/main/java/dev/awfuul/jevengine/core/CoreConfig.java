package dev.awfuul.jevengine.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.yml: the things that are true of the whole engine.
 *
 * <p>Only what every module shares belongs here. The Jev credentials and request
 * budget, which modules are on, and how the plugin looks when it speaks. Anything
 * a single feature cares about lives in that feature's own file.
 */
public final class CoreConfig {

    public record Api(String key, String model, String endpoint, int timeoutMs,
                      int maxRetries, long retryBaseDelayMs, int concurrency) {

        public boolean hasKey() {
            return key != null && !key.isBlank();
        }
    }

    public record Branding(String prefix, String prefixFallback, String bodyColor) {
    }

    private final Api api;
    private final Branding branding;
    private final Set<String> enabledModules;
    private final Map<String, String> lines;
    private final List<String> warnings;

    private CoreConfig(Api api, Branding branding, Set<String> enabledModules,
                       Map<String, String> lines, List<String> warnings) {
        this.api = api;
        this.branding = branding;
        this.enabledModules = Set.copyOf(enabledModules);
        this.lines = Map.copyOf(lines);
        this.warnings = List.copyOf(warnings);
    }

    public static CoreConfig load(FileConfiguration file) {
        List<String> warnings = new ArrayList<>();

        String configured = file.getString("api.key", "");
        String key = configured == null || configured.isBlank()
                ? System.getenv("TYPESAFE_API_KEY")
                : configured;

        Api api = new Api(
                key,
                file.getString("api.model", "jev-1.13.0"),
                file.getString("api.endpoint", "https://api.typesafe.ai/v1/systemone"),
                file.getInt("api.timeout-ms", 2500),
                file.getInt("api.max-retries", 2),
                file.getLong("api.retry-base-delay-ms", 200L),
                Math.max(1, file.getInt("api.concurrency", 24)));

        Branding branding = new Branding(
                file.getString("messages.prefix", ""),
                file.getString("messages.prefix-fallback", ""),
                file.getString("messages.body-color", "#f6e0ff"));

        Set<String> enabled = new LinkedHashSet<>();
        ConfigurationSection modules = file.getConfigurationSection("modules");
        if (modules == null) {
            warnings.add("no modules section, nothing will run");
        } else {
            for (String id : modules.getKeys(false)) {
                if (modules.getBoolean(id, false)) {
                    enabled.add(id);
                }
            }
        }

        Map<String, String> lines = new LinkedHashMap<>();
        ConfigurationSection messages = file.getConfigurationSection("messages");
        if (messages != null) {
            for (String messageKey : messages.getKeys(false)) {
                if (messages.get(messageKey) instanceof String text) {
                    lines.put(messageKey, text);
                }
            }
        }

        return new CoreConfig(api, branding, enabled, lines, warnings);
    }

    public Api api() {
        return api;
    }

    public Branding branding() {
        return branding;
    }

    /**
     * Whether a module should run. A module the file says nothing about stays
     * off, so adding a jar never turns a feature on behind someone's back.
     */
    public boolean moduleEnabled(String id) {
        return enabledModules.contains(id);
    }

    public Set<String> enabledModules() {
        return enabledModules;
    }

    public Map<String, String> lines() {
        return lines;
    }

    public String line(String key, String fallback) {
        return lines.getOrDefault(key, fallback);
    }

    public List<String> warnings() {
        return warnings;
    }
}
