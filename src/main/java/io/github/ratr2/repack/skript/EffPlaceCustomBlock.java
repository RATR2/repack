package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffPlaceCustomBlock extends Effect {

    static final String[] PATTERNS = {"place [a] custom block %string% at %locations% [facing %-number%]"};

    private Expression<String> id;
    private Expression<Location> locations;
    private @Nullable Expression<Number> yaw;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        id = (Expression<String>) exprs[0];
        locations = (Expression<Location>) exprs[1];
        yaw = (Expression<Number>) exprs[2];
        return true;
    }

    @Override
    protected void execute(Event event) {
        String wanted = id.getSingle(event);
        if (wanted == null) return;
        Number facing = yaw == null ? null : yaw.getSingle(event);
        for (Location location : locations.getArray(event)) {
            RepackAPI.place(location, wanted, facing == null ? 0f : facing.floatValue());
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "place custom block " + id.toString(event, debug) + " at " + locations.toString(event, debug)
                + (yaw != null ? " facing " + yaw.toString(event, debug) : "");
    }
}
