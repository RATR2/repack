package io.github.ratr2.repack.skript;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import ch.njol.util.coll.CollectionUtils;
import io.github.ratr2.repack.menu.LiveGui;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class ExprGuiLayout extends SimpleExpression<String> {

    static final String[] PATTERNS = {
            "[custom] gui (layout|shape)[s] of %customgui%",
            "%customgui%'[s] [custom] gui (layout|shape)[s]",
            "custom gui (title|name) of %customgui%",
            "%customgui%'[s] custom gui (title|name)",
    };

    private boolean title;
    private Expression<LiveGui> gui;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        title = matchedPattern >= 2;
        gui = (Expression<LiveGui>) exprs[0];
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        LiveGui target = gui.getSingle(event);
        if (target == null) return new String[0];
        return title ? new String[]{target.getTitle()} : target.getLayout().toArray(String[]::new);
    }

    @Override
    public Class<?> @Nullable [] acceptChange(ChangeMode mode) {
        return mode == ChangeMode.SET ? CollectionUtils.array(String[].class) : null;
    }

    @Override
    public void change(Event event, Object @Nullable [] delta, ChangeMode mode) {
        LiveGui target = gui.getSingle(event);
        if (target == null || delta == null || mode != ChangeMode.SET) return;
        String[] values = Arrays.copyOf(delta, delta.length, String[].class);
        try {
            if (title) {
                target.title(values.length > 0 ? values[0] : "");
            } else {
                target.layout(Arrays.asList(values));
            }
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[Repack] " + e.getMessage());
        }
    }

    @Override
    public boolean isSingle() {
        return title;
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "the custom gui " + (title ? "title" : "layout") + " of " + gui.toString(event, debug);
    }
}
