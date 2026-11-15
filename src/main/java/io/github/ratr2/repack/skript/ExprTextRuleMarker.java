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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * The marker color text must be drawn in for a text rule to move it, as a color code to put in front of the text. Empty when there is no such rule.
 */
@Name("Text Rule Marker")
@Description({"The marker color text must be drawn in for a text rule to move it, as a color code to put in front of the text. Empty when there is no such rule."})
@Examples({"set {_marker} to the text rule marker of \"myscript:mana\"", "send \"%{_marker}%Mana 20/20\" to player"})
@Since("1.0.1")
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
