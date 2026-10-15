package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.util.Version;
import io.github.ratr2.repack.Repack;

/**
 * Adds Repack's syntax to Skript: the registry API became stable in 2.14, so 2.14 and newer use it and older versions use the
 * old static methods. Each registrar has its own class so the other version's is never loaded.
 */
public final class SkriptHook {

    private static final Version MODERN_API = new Version(2, 14);

    private SkriptHook() {
    }

    public static void register(Repack plugin) {
        boolean modern = Skript.getVersion().compareTo(MODERN_API) >= 0;
        if (modern) {
            ModernSyntax.register(plugin);
        } else {
            LegacySyntax.register(plugin);
        }
        SkriptEventValues.register();
        plugin.getLogger().info("Skript " + Skript.getVersion() + " found: registered custom block syntax with the "
                + (modern ? "2.14+ registration API." : "older registration API."));
    }
}
