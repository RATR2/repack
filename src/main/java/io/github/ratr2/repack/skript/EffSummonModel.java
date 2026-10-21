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

/** summon model entity "guard" at player's location facing 90 playing "idle" */
public class EffSummonModel extends Effect {

    static final String[] PATTERNS = {"summon [a] model entity %string% at %locations% [facing %-number%] [playing %-string%]"};

    private Expression<String> id;
    private Expression<Location> locations;
    private @Nullable Expression<Number> yaw;
    private @Nullable Expression<String> clip;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        id = (Expression<String>) exprs[0];
        locations = (Expression<Location>) exprs[1];
        yaw = (Expression<Number>) exprs[2];
        clip = (Expression<String>) exprs[3];
        return true;
    }

    @Override
    protected void execute(Event event) {
        String wanted = id.getSingle(event);
        if (wanted == null) return;
        Number facing = yaw == null ? null : yaw.getSingle(event);
        String animation = clip == null ? null : clip.getSingle(event);
        for (Location location : locations.getArray(event)) {
            Location at = location.clone();
            if (facing != null) at.setYaw(facing.floatValue());
            ModelEntity entity = RepackAPI.spawnEntity(wanted, at);
            if (entity != null && animation != null) entity.play(animation.toLowerCase());
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "summon model entity " + id.toString(event, debug) + " at " + locations.toString(event, debug)
                + (yaw != null ? " facing " + yaw.toString(event, debug) : "") + (clip != null ? " playing " + clip.toString(event, debug) : "");
    }
}
