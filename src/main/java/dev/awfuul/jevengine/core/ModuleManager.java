package dev.awfuul.jevengine.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Keeps track of the modules, which are on, and what they are called.
 *
 * <p>A module that throws on enable is logged and left off rather than taking
 * the plugin down with it. One feature failing to read its config should not
 * stop the others running.
 */
public final class ModuleManager {

    private final Logger logger;
    private final Map<String, JevModule> registered = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, JevModule> running = new LinkedHashMap<>();

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    public void register(JevModule module) {
        String id = module.id().toLowerCase(Locale.ROOT);
        if (registered.containsKey(id)) {
            logger.warning("Two modules both call themselves '" + id + "'. Keeping the first.");
            return;
        }
        registered.put(id, module);
        for (String alias : module.commandAliases()) {
            String key = alias.toLowerCase(Locale.ROOT);
            if (registered.containsKey(key)) {
                continue;
            }
            String taken = aliases.putIfAbsent(key, id);
            if (taken != null) {
                logger.warning("Both '" + taken + "' and '" + id + "' want the word '" + key
                        + "'. It will reach '" + taken + "'.");
            }
        }
    }

    public void enableEnabled(CoreConfig core, ModuleContext context) {
        for (Map.Entry<String, JevModule> entry : registered.entrySet()) {
            String id = entry.getKey();
            if (!core.moduleEnabled(id)) {
                logger.info("Module '" + id + "' is off in config.yml.");
                continue;
            }
            try {
                entry.getValue().enable(context);
                running.put(id, entry.getValue());
                logger.info("Module '" + id + "' is on.");
            } catch (Throwable failure) {
                logger.severe("Module '" + id + "' failed to start and has been left off: "
                        + failure);
            }
        }
    }

    public void disableAll() {
        List<JevModule> reversed = new ArrayList<>(running.values());
        java.util.Collections.reverse(reversed);
        for (JevModule module : reversed) {
            try {
                module.disable();
            } catch (Throwable failure) {
                logger.warning("Module '" + module.id() + "' misbehaved while stopping: "
                        + failure);
            }
        }
        running.clear();
    }

    /** @return warnings from every running module, each tagged with its id */
    public List<String> reloadAll() {
        List<String> warnings = new ArrayList<>();
        for (JevModule module : running.values()) {
            warnings.addAll(reloadOne(module));
        }
        return warnings;
    }

    public List<String> reloadOne(JevModule module) {
        List<String> warnings = new ArrayList<>();
        try {
            for (String warning : module.reload()) {
                warnings.add(module.id() + ": " + warning);
            }
        } catch (Throwable failure) {
            warnings.add(module.id() + ": reload failed, keeping the old settings (" + failure
                    + ")");
        }
        return warnings;
    }

    /** Resolves a module id or one of the words a module asked to answer to. */
    public JevModule find(String word) {
        String key = word.toLowerCase(Locale.ROOT);
        JevModule direct = running.get(key);
        if (direct != null) {
            return direct;
        }
        String aliased = aliases.get(key);
        return aliased == null ? null : running.get(aliased);
    }

    public Collection<JevModule> running() {
        return running.values();
    }

    public Collection<JevModule> all() {
        return registered.values();
    }

    public boolean isRunning(String id) {
        return running.containsKey(id.toLowerCase(Locale.ROOT));
    }
}
