package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.entity.ModelEntity;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * remove model entities within 3 blocks of player's location
 * make model entities within 3 blocks of player's location play "walk"   (play "none" for the still model)
 */
public class EffModelEntities extends Effect {

    static final String[] PATTERNS = {
            "remove model entities (within|around) %number% block[s] (of|around) %locations%",
            "make model entities (within|around) %number% block[s] (of|around) %locations% play [animation] %string%",
    };

    private int pattern;
    private Expression<Number> radius;
    private Expression<Location> locations;
    private @Nullable Expression<String> clip;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        radius = (Expression<Number>) exprs[0];
        locations = (Expression<Location>) exprs[1];
        clip = pattern == 1 ? (Expression<String>) exprs[2] : null;
        return true;
    }

    @Override
    protected void execute(Event event) {
        Number blocks = radius.getSingle(event);
        if (blocks == null) return;
        String animation = clip == null ? null : clip.getSingle(event);
        if (pattern == 1 && animation == null) return;
        for (Location location : locations.getArray(event)) {
            for (ModelEntity entity : RepackAPI.entitiesNear(location, blocks.doubleValue())) {
                if (pattern == 0) entity.remove();
                else entity.play(animation.equalsIgnoreCase("none") ? null : animation.toLowerCase());
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return pattern == 0
                ? "remove model entities within " + radius.toString(event, debug) + " blocks of " + locations.toString(event, debug)
                : "make model entities within " + radius.toString(event, debug) + " blocks of " + locations.toString(event, debug) + " play " + clip.toString(event, debug);
    }
}
