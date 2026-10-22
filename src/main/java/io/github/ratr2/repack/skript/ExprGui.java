package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.menu.GuiManager;
import io.github.ratr2.repack.menu.LiveGui;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class ExprGui extends SimpleExpression<LiveGui> {

    static final String[] PATTERNS = {
            "[the] (last[ly] [created]|current) custom gui",
            "[the] custom gui with id[entifier] %string%",
            "[the] custom gui of %player%",
    };

    private int pattern;
    private @Nullable Expression<?> argument;

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        argument = matchedPattern == 0 ? null : exprs[0];
        return true;
    }

    @Override
    protected LiveGui @Nullable [] get(Event event) {
        GuiManager manager = GuiScript.manager();
        LiveGui gui = switch (pattern) {
            case 0 -> GuiScript.current(event);
            case 1 -> {
                String id = (String) argument.getSingle(event);
                yield manager == null || id == null ? null : manager.lookup(id.toLowerCase(Locale.ROOT));
            }
            default -> {
                Player player = (Player) argument.getSingle(event);
                yield player == null ? null : player.getOpenInventory().getTopInventory().getHolder() instanceof LiveGui open ? open : null;
            }
        };
        return gui == null ? new LiveGui[0] : new LiveGui[]{gui};
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends LiveGui> getReturnType() {
        return LiveGui.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "the last created custom gui";
            case 1 -> "the custom gui with id " + argument.toString(event, debug);
            default -> "the custom gui of " + argument.toString(event, debug);
        };
    }
}
