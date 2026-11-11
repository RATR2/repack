package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class ExprRipples extends SimpleExpression<Long> {

    static final String[] PATTERNS = {
            "[the] number of active ripples",
            "[the] max[imum] number of ripples",
    };

    private int pattern;

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        return true;
    }

    @Override
    protected Long @Nullable [] get(Event event) {
        return new Long[]{(long) (pattern == 0 ? RepackAPI.activeRipples() : RepackAPI.maxRipples())};
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends Long> getReturnType() {
        return Long.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return pattern == 0 ? "the number of active ripples" : "the maximum number of ripples";
    }
}
