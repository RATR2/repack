package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.menu.GuiActionEvent;
import io.github.ratr2.repack.menu.GuiClickEvent;
import io.github.ratr2.repack.menu.SlotKey;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class ExprGuiEvent extends SimpleExpression<Object> {

    static final String[] PATTERNS = {
            "[the] gui slot (index|number)",
            "[the] gui slot id",
            "[the] gui input [%-string%]",
            "[the] gui slot tag %string%",
    };

    private int pattern;
    private @Nullable Expression<String> argument;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        argument = exprs.length > 0 ? (Expression<String>) exprs[0] : null;
        return true;
    }

    @Override
    protected Object @Nullable [] get(Event event) {
        switch (pattern) {
            case 0 -> {
                if (event instanceof GuiClickEvent click) return new Object[]{click.getSlot()};
            }
            case 1 -> {
                if (event instanceof GuiClickEvent click && click.getKey() instanceof SlotKey.Char c) return new Object[]{String.valueOf(c.getValue())};
            }
            case 2 -> {
                if (event instanceof GuiActionEvent action) {
                    String name = argument != null ? argument.getSingle(event) : null;
                    String value = action.getInputs().get(name == null ? "input" : "input." + name);
                    if (value != null) return new Object[]{value};
                }
            }
            default -> {
                if (event instanceof GuiClickEvent click && argument != null) {
                    String value = click.getTags().get(argument.getSingle(event));
                    if (value != null) return new Object[]{value};
                }
            }
        }
        return new Object[0];
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<?> getReturnType() {
        return pattern == 0 ? Number.class : String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "the gui slot index";
            case 1 -> "the gui slot id";
            case 2 -> "the gui input" + (argument != null ? " " + argument.toString(event, debug) : "");
            default -> "the gui slot tag " + argument.toString(event, debug);
        };
    }
}
