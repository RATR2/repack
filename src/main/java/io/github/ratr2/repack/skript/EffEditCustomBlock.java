package io.github.ratr2.repack.skript;

import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.edit.BlockBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EffEditCustomBlock extends Effect {

    static final String[] PATTERNS = {
            "create [a] custom block %string% (with|using) model %string%",
            "set custom block %string%'s option %string% to %objects%",
            "set custom block %string%'s item to %itemtype%",
            "set custom block %string%'s item to %itemtype% without [the] block['s] model",
            "set custom block %string%'s recipe to %itemtypes% [with amount %-number%]",
            "clear custom block %string%'s item",
            "clear custom block %string%'s recipe"
    };

    private int pattern;
    private Expression<String> id;
    private @Nullable Expression<String> second;
    private @Nullable Expression<?> values;
    private @Nullable Expression<Number> amount;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        id = (Expression<String>) exprs[0];
        switch (matchedPattern) {
            case 0 -> second = (Expression<String>) exprs[1];
            case 1 -> {
                second = (Expression<String>) exprs[1];
                values = exprs[2];
            }
            case 2, 3 -> values = exprs[1];
            case 4 -> {
                values = exprs[1];
                amount = (Expression<Number>) exprs[2];
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    protected void execute(Event event) {
        String block = id.getSingle(event);
        if (block == null) return;
        String error;
        switch (pattern) {
            case 0 -> {
                String model = second == null ? null : second.getSingle(event);
                error = model == null ? "no model" : RepackAPI.create(block, model).save();
            }
            case 1 -> error = setOption(block, event);
            case 2, 3 -> {
                ItemStack item = firstItem(event);
                error = item == null ? "no item" : RepackAPI.edit(block).item(item, pattern == 2).save();
            }
            case 4 -> error = setRecipe(block, event);
            case 5 -> error = RepackAPI.edit(block).item(null, true).save();
            default -> error = RepackAPI.edit(block).clearRecipe().save();
        }
        if (error != null) Bukkit.getLogger().warning("[Repack] custom block '" + block + "': " + error);
    }

    private @Nullable String setOption(String block, Event event) {
        String path = second == null ? null : second.getSingle(event);
        if (path == null || values == null) return "no option";
        Object[] all = values.getArray(event);
        Object value;
        if (all.length == 1) {
            value = plain(all[0]);
        } else {
            List<Object> list = new ArrayList<>();
            for (Object entry : all) list.add(plain(entry));
            value = list;
        }
        return RepackAPI.edit(block).set(path, value).save();
    }

    /** Skript numbers are longs and doubles, texts are strings; anything else is written as its text. */
    private static Object plain(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return String.valueOf(value);
    }

    private @Nullable ItemStack firstItem(Event event) {
        if (values == null) return null;
        Object[] all = values.getArray(event);
        return all.length == 0 || !(all[0] instanceof ItemType type) ? null : type.getRandom();
    }

    private @Nullable String setRecipe(String block, Event event) {
        if (values == null) return "no items";
        Object[] all = values.getArray(event);
        if (all.length > 9) return "a recipe has at most nine items, row by row";
        ItemStack[] grid = new ItemStack[9];
        for (int i = 0; i < all.length; i++) {
            if (all[i] instanceof ItemType type) {
                ItemStack stack = type.getRandom();
                grid[i] = stack == null || stack.getType() == Material.AIR ? null : stack;
            }
        }
        Number count = amount == null ? null : amount.getSingle(event);
        return RepackAPI.edit(block).recipe(Arrays.asList(grid), false, count == null ? 1 : count.intValue()).save();
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "edit custom block " + id.toString(event, debug);
    }
}
