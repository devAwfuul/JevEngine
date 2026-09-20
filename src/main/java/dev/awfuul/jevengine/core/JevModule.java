package dev.awfuul.jevengine.core;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One feature built on the engine.
 *
 * <p>A module owns its own config file, its own listeners and its own
 * subcommand. It is handed the shared pieces through {@link ModuleContext} and
 * does not reach for the plugin directly, so the things every module needs, the
 * Jev client, the branding and the API budget, stay in one place instead of
 * being rebuilt per feature.
 *
 * <p>The lifecycle is enable, then any number of reloads, then disable. A reload
 * re-reads the module's file and swaps its settings; it is not an enable, and a
 * module should not re-register listeners on one unless something it registered
 * with actually depends on a setting.
 */
public interface JevModule {

    /** Stable id. Names the config file, the subcommand and the toggle. */
    String id();

    /** How the module is written in messages. */
    default String displayName() {
        return id();
    }

    default String description() {
        return "";
    }

    /**
     * Extra words that reach this module's subcommand directly, so staff can
     * type the thing they want instead of the module it lives in. The router
     * checks module ids first and logs a warning if two modules claim the same
     * word.
     */
    default Set<String> commandAliases() {
        return Set.of();
    }

    void enable(ModuleContext context) throws Exception;

    void disable();

    /**
     * Re-reads this module's config file.
     *
     * @return warnings worth showing whoever asked for the reload
     */
    List<String> reload();

    /** Handles {@code /jevengine <id> ...}. Return false to print the usage. */
    default boolean handleCommand(CommandSender sender, String label, String[] args) {
        return false;
    }

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    /** Lines for {@code /jevengine status}. */
    default void appendStatus(Consumer<Component> out) {
    }
}
