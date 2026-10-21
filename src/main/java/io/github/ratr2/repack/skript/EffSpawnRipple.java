package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.distortion.DistortionAPI;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffSpawnRipple extends Effect {

    static final String[] PATTERNS = {"spawn [a] ripple at %locations% with colo[u]r %string% radius %number% lasting %number% tick[s]"};

    private Expression<Location> locations;
    private Expression<String> color;
    private Expression<Number> radius;
    private Expression<Number> ticks;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        locations = (Expression<Location>) exprs[0];
        color = (Expression<String>) exprs[1];
        radius = (Expression<Number>) exprs[2];
        ticks = (Expression<Number>) exprs[3];
        return true;
    }

    @Override
    protected void execute(Event event) {
        String hex = color.getSingle(event);
        Number size = radius.getSingle(event);
        Number duration = ticks.getSingle(event);
        if (hex == null || size == null || duration == null) return;
        if (hex.startsWith("#")) hex = hex.substring(1);
        int rgb;
        try {
            if (hex.length() != 6) return;
            rgb = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return;
        }
        if (size.doubleValue() <= 0 || duration.intValue() <= 0) return;
        for (Location location : locations.getArray(event)) {
            DistortionAPI.spawn(location, rgb, size.doubleValue(), duration.intValue());
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "spawn a ripple at " + locations.toString(event, debug) + " with color " + color.toString(event, debug)
                + " radius " + radius.toString(event, debug) + " lasting " + ticks.toString(event, debug) + " ticks";
    }
}
