package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffBlockAnimation extends Effect {

    static final String[] PATTERNS = {
            "play [the] animation %string% [as (1¦loop|2¦once|3¦hold)] on [the] custom block[s] at %locations%",
            "stop [the] animation of [the] custom block[s] at %locations%",
    };

    private Expression<String> animation;
    private Expression<Location> locations;
    private String mode;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        if (matchedPattern == 0) {
            animation = (Expression<String>) exprs[0];
            locations = (Expression<Location>) exprs[1];
            mode = switch (parseResult.mark) { case 1 -> "loop"; case 2 -> "once"; case 3 -> "hold"; default -> null; };
        } else {
            locations = (Expression<Location>) exprs[0];
        }
        return true;
    }

    @Override
    protected void execute(Event event) {
        String name = animation == null ? null : animation.getSingle(event);
        for (Location location : locations.getArray(event)) {
            RepackAPI.playAnimation(location, name, mode);
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        if (animation == null) return "stop the animation of custom block at " + locations.toString(event, debug);
        return "play animation " + animation.toString(event, debug) + (mode == null ? "" : " as " + mode) + " on custom block at " + locations.toString(event, debug);
    }
}
