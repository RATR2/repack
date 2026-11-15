package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Removes the custom block at locations, without dropping anything.
 */
@Name("Remove Custom Block")
@Description({"Removes the custom block at locations, without dropping anything."})
@Examples({"remove custom block at player's target block"})
@Since("1.0.0")
public class EffRemoveCustomBlock extends Effect {

    static final String[] PATTERNS = {"remove [the] custom block[s] at %locations%"};

    private Expression<Location> locations;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        locations = (Expression<Location>) exprs[0];
        return true;
    }

    @Override
    protected void execute(Event event) {
        for (Location location : locations.getArray(event)) {
            RepackAPI.remove(location);
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "remove custom block at " + locations.toString(event, debug);
    }
}
