package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class ExprTextRuleMarker extends SimpleExpression<String> {

    static final String[] PATTERNS = {"[the] text rule marker [colo[u]r] of %string%"};

    private Expression<String> id;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        id = (Expression<String>) exprs[0];
        return true;
    }

    /** Text colored with this, like {@code "%marker%mana"}, is moved by the rule; empty when there is no such rule. */
    @Override
    protected String @Nullable [] get(Event event) {
        String name = id.getSingle(event);
        TextColor marker = name == null ? null : RepackAPI.textRuleMarker(name);
        return marker == null ? new String[0] : new String[]{"<" + marker.asHexString() + ">"};
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
        return "the text rule marker of " + id.toString(event, debug);
    }
}
