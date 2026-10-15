package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.util.Version;
import io.github.ratr2.repack.Repack;

/**
 * Adds Repack's syntax to Skript. Skript changed how addons register syntax: the registry API became stable in 2.14 and the old
 * static methods were deprecated, so 2.14 and newer use the new one and older versions the old one. The syntax classes are the
 * same for both. Each registrar lives in its own class so the one for the other version is never loaded.
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
