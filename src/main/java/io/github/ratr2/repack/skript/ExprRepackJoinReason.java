package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.pack.RepackJoinEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Why an {@code on repack join} event fired: "loaded", or the reason the pack did not load (failed, timed out, none is sent).
 */
@Name("Repack Join Reason")
@Description({"Why an `on repack join` event fired: \"loaded\", or the reason the pack did not load (failed, timed out, none is sent)."})
@Examples({"on repack join:", "    send \"Pack: %the repack join reason%\" to player"})
@Since("1.0.1")
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
