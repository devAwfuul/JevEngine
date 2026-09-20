package dev.awfuul.jevengine.core;

import dev.awfuul.jevengine.api.JevClient;
import dev.awfuul.jevengine.ui.Branding;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * What the engine hands a module.
 *
 * <p>The Jev client is shared deliberately. One client means one connection
 * pool, one concurrency limit and one place the API key lives, so a second
 * module cannot quietly double the request budget or hold its own copy of the
 * key.
 */
public final class ModuleContext {

    private final Plugin plugin;
    private final CoreConfig core;
    private final JevClient jev;
    private final Branding branding;

    public ModuleContext(Plugin plugin, CoreConfig core, JevClient jev, Branding branding) {
        this.plugin = plugin;
        this.core = core;
        this.jev = jev;
        this.branding = branding;
    }

    /**
     * Loads a module's own file, writing the packaged default the first time.
     *
     * @param fileName for example {@code chatfilter.yml}
     */
    public FileConfiguration config(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException missing) {
                plugin.getLogger().warning("No packaged default for " + fileName
                        + ", starting from an empty file.");
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public Plugin plugin() {
        return plugin;
    }

    public Logger logger() {
        return plugin.getLogger();
    }

    public CoreConfig core() {
        return core;
    }

    public JevClient jev() {
        return jev;
    }

    public Branding branding() {
        return branding;
    }
}
