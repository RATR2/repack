package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * The ids of all custom GUIs: the files in the guis folder (not templates) and the ones registered by id.
 */
@Name("Custom GUI IDs")
@Description({"The ids of all custom GUIs: the files in the guis folder (not templates) and the ones registered by id."})
@Examples({"send \"GUIs: %all custom gui ids%\" to player"})
@Since("1.0.0")
public class ExprGuiIds extends SimpleExpression<String> {

    static final String[] PATTERNS = {"[all [of the]] custom gui ids"};

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        return RepackAPI.guiIds().toArray(String[]::new);
    }

    @Override
    public boolean isSingle() {
        return false;
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "all custom gui ids";
    }
}
