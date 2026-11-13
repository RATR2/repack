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
            "shake %players%'[s] screen with strength %number% for %number% tick[s] [at rate %-number%] [with balance %-number%] [with roll %-number%]",
            "stop shaking %players%'[s] screen",
    };

    private Expression<Player> players;
    private Expression<Number> strength;
    private Expression<Number> ticks;
    private @Nullable Expression<Number> rate;
    private @Nullable Expression<Number> balance;
    private @Nullable Expression<Number> roll;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        if (matchedPattern == 0) {
            strength = (Expression<Number>) exprs[1];
            ticks = (Expression<Number>) exprs[2];
            rate = (Expression<Number>) exprs[3];
            balance = (Expression<Number>) exprs[4];
            roll = (Expression<Number>) exprs[5];
        }
        return true;
    }

    private static double number(@Nullable Expression<Number> expression, Event event, double fallback) {
        Number value = expression == null ? null : expression.getSingle(event);
        return value == null ? fallback : value.doubleValue();
    }

    @Override
    protected void execute(Event event) {
        Number s = strength == null ? null : strength.getSingle(event);
        Number t = ticks == null ? null : ticks.getSingle(event);
        double r = number(rate, event, 0.5);
        double b = number(balance, event, 0.5);
        double l = number(roll, event, 0.3);
        for (Player player : players.getArray(event)) {
            if (strength == null) {
                RepackAPI.stopShake(player);
            } else if (s != null && t != null && t.intValue() > 0 && Double.isFinite(s.doubleValue()) && Double.isFinite(r) && Double.isFinite(b) && Double.isFinite(l)) {
                RepackAPI.shake(player, s.doubleValue(), t.intValue(), r, b, l);
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        if (strength == null) return "stop shaking " + players.toString(event, debug) + "'s screen";
        return "shake " + players.toString(event, debug) + "'s screen with strength " + strength.toString(event, debug) + " for " + ticks.toString(event, debug) + " ticks";
    }
}
