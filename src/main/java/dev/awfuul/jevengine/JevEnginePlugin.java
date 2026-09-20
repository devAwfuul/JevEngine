package dev.awfuul.jevengine;

import dev.awfuul.jevengine.api.JevClient;
import dev.awfuul.jevengine.command.JevEngineCommand;
import dev.awfuul.jevengine.core.CoreConfig;
import dev.awfuul.jevengine.core.ModuleContext;
import dev.awfuul.jevengine.core.ModuleManager;
import dev.awfuul.jevengine.modules.chatfilter.ChatFilterModule;
import dev.awfuul.jevengine.ui.Branding;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine.
 *
 * <p>It owns three things and nothing else: the config that every module shares,
 * the Jev client they all ask through, and the branding they all speak in.
 * Features live in modules, each with its own file, and the plugin's job is to
 * hand them what they need and get out of the way.
 *
 * <p>To add a module, write a {@link dev.awfuul.jevengine.core.JevModule},
 * register it in {@link #registerModules()}, ship its default yml in resources,
 * and add a line for it under {@code modules} in config.yml.
 */
public final class JevEnginePlugin extends JavaPlugin {

    private CoreConfig core;
    private JevClient jev;
    private Branding branding;
    private ModuleManager modules;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.core = CoreConfig.load(getConfig());
        reportCore();

        this.jev = new JevClient(core.api());
        this.branding = new Branding(core.branding(), getLogger());
        this.modules = new ModuleManager(getLogger());

        registerModules();
        modules.enableEnabled(core, context());

        PluginCommand command = getCommand("jevengine");
        if (command != null) {
            JevEngineCommand handler = new JevEngineCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getLogger().info("JevEngine is up with " + modules.running().size() + " of "
                + modules.all().size() + " modules running.");
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (jev != null) {
            jev.close();
        }
    }

    /** Every module the jar knows about. Being here does not turn one on. */
    private void registerModules() {
        modules.register(new ChatFilterModule());
    }

    private ModuleContext context() {
        return new ModuleContext(this, core, jev, branding);
    }

    /**
     * Re-reads config.yml and then every running module's file.
     *
     * <p>Modules are not restarted. The shared client is reconfigured in place
     * rather than replaced, so a module holding a reference to it keeps working,
     * and a module that has just been switched on in config.yml needs a server
     * restart rather than a reload, because starting one mid-flight would mean
     * registering listeners against messages already being judged.
     *
     * @return warnings worth showing whoever ran the reload
     */
    public List<String> reloadEverything() {
        reloadConfig();
        this.core = CoreConfig.load(getConfig());
        reportCore();

        jev.reconfigure(core.api());

        List<String> warnings = new ArrayList<>(core.warnings());
        warnings.addAll(modules.reloadAll());

        for (String id : core.enabledModules()) {
            if (!modules.isRunning(id)) {
                warnings.add("module '" + id
                        + "' is on in config.yml but was not running, so it needs a restart");
            }
        }
        return warnings;
    }

    private void reportCore() {
        for (String warning : core.warnings()) {
            getLogger().warning(warning);
        }
        if (!core.api().hasKey()) {
            getLogger().warning("No API key. Set api.key in config.yml or the TYPESAFE_API_KEY "
                    + "environment variable. Until then modules that ask Jev anything will fall "
                    + "back to whatever they do when the service is unreachable.");
        }
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

    public ModuleManager modules() {
        return modules;
    }
}
