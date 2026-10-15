package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/** remove custom block at event-location: removes the block and its model, with no drops. */
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
