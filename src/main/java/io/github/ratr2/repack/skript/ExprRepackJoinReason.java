package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.pack.RepackJoinEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class ExprRepackJoinReason extends SimpleExpression<String> {

    static final String[] PATTERNS = {"[the] repack join reason"};

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        if (!getParser().isCurrentEvent(RepackJoinEvent.class)) {
            Skript.error("The repack join reason only exists in an 'on repack join' event.");
            return false;
        }
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        return event instanceof RepackJoinEvent join ? new String[]{join.getReason().getText()} : new String[0];
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "the repack join reason";
    }
}
