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
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * The Repack id of a custom block, or nothing when the block is not one.
 */
@Name("Custom Block Id")
@Description({"The Repack id of a custom block, or nothing when the block is not one."})
@Examples({"send \"Custom block id: %custom block id of event-block%\" to player"})
@Since("1.0.0")
public class ExprCustomBlockId extends SimpleExpression<String> {

    static final String[] PATTERNS = {"[the] custom block id of %block%", "%block%'s custom block id"};

    private Expression<Block> block;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        block = (Expression<Block>) exprs[0];
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        Block found = block.getSingle(event);
        String id = found == null ? null : RepackAPI.idAt(found);
        return id == null ? new String[0] : new String[]{id};
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
        return "custom block id of " + block.toString(event, debug);
    }
}
