package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** if event-block is a custom block with id "table" */
public class CondIsCustomBlock extends Condition {

    static final String[] PATTERNS = {
            "%blocks% (is|are) [a] custom block[s] [with id %-string%]",
            "%blocks% (isn't|is not|aren't|are not) [a] custom block[s] [with id %-string%]"
    };

    private Expression<Block> blocks;
    private @Nullable Expression<String> id;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        blocks = (Expression<Block>) exprs[0];
        id = (Expression<String>) exprs[1];
        setNegated(matchedPattern == 1);
        return true;
    }

    @Override
    public boolean check(Event event) {
        Predicate<Block> test;
        if (id == null) {
            test = block -> RepackAPI.isCustomBlock(block, null);
        } else {
            String wanted = id.getSingle(event);
            test = block -> wanted != null && RepackAPI.isCustomBlock(block, wanted);
        }
        return blocks.check(event, test, isNegated());
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return blocks.toString(event, debug) + (isNegated() ? " is not" : " is") + " a custom block"
                + (id != null ? " with id " + id.toString(event, debug) : "");
    }
}
