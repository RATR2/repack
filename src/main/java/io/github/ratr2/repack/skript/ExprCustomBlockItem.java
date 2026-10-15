package io.github.ratr2.repack.skript;

import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** give player custom block item "table" x 2: the item that places a custom block. */
public class ExprCustomBlockItem extends SimpleExpression<ItemType> {

    static final String[] PATTERNS = {"[the] custom block item %string% [(x|times) %-number%]"};

    private Expression<String> id;
    private @Nullable Expression<Number> amount;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        id = (Expression<String>) exprs[0];
        amount = (Expression<Number>) exprs[1];
        return true;
    }

    @Override
    protected ItemType @Nullable [] get(Event event) {
        String wanted = id.getSingle(event);
        if (wanted == null) return new ItemType[0];
        Number count = amount == null ? null : amount.getSingle(event);
        ItemStack stack = RepackAPI.item(wanted, count == null ? 1 : count.intValue());
        return stack == null ? new ItemType[0] : new ItemType[]{new ItemType(stack)};
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends ItemType> getReturnType() {
        return ItemType.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom block item " + id.toString(event, debug) + (amount != null ? " x " + amount.toString(event, debug) : "");
    }
}
