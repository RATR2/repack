package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffShake extends Effect {

    static final String[] PATTERNS = {
            "shake %players%'[s] screen with strength %number% for %number% tick[s]",
            "stop shaking %players%'[s] screen",
    };

    private Expression<Player> players;
    private Expression<Number> strength;
    private Expression<Number> ticks;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        if (matchedPattern == 0) {
            strength = (Expression<Number>) exprs[1];
            ticks = (Expression<Number>) exprs[2];
        }
        return true;
    }

    @Override
    protected void execute(Event event) {
        Number s = strength == null ? null : strength.getSingle(event);
        Number t = ticks == null ? null : ticks.getSingle(event);
        for (Player player : players.getArray(event)) {
            if (strength == null) {
                RepackAPI.stopShake(player);
            } else if (s != null && t != null && t.intValue() > 0) {
                RepackAPI.shake(player, s.doubleValue(), t.intValue());
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        if (strength == null) return "stop shaking " + players.toString(event, debug) + "'s screen";
        return "shake " + players.toString(event, debug) + "'s screen with strength " + strength.toString(event, debug) + " for " + ticks.toString(event, debug) + " ticks";
    }
}
