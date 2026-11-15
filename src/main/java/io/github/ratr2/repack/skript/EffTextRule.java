package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.pack.TextRules;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Registers a text rule: text drawn in the rule's marker color is moved to an anchor of the screen (like the middle bottom), with an offset, scale and color. Or unregisters rules. Needs a pack build and push to take effect.
 */
@Name("Text Rule")
@Description({"Registers a text rule: text drawn in the rule's marker color is moved to an anchor of the screen (like the middle bottom), with an offset, scale and color. Or unregisters rules. Needs a pack build and push to take effect."})
@Examples({"register a text rule \"myscript:mana\" at \"bottom center\" offset -91, -40 from \"top center\" colored \"55AAFF\"", "unregister the text rule \"myscript:mana\""})
@Since("1.0.1")
public class EffTextRule extends Effect {

    static final String[] PATTERNS = {
            "register [a] text rule %string% (at|anchored to) [the] %string% [offset %-number%, %-number%] [from [the] %-string%] [scaled %-number%] [colo[u]red %-string%]",
            "unregister [the] text rule[s] %strings%",
    };

    private int pattern;
    private Expression<String> first;
    private @Nullable Expression<String> anchor;
    private @Nullable Expression<Number> offsetX;
    private @Nullable Expression<Number> offsetY;
    private @Nullable Expression<String> source;
    private @Nullable Expression<Number> scale;
    private @Nullable Expression<String> color;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        first = (Expression<String>) exprs[0];
        if (matchedPattern == 0) {
            anchor = (Expression<String>) exprs[1];
            offsetX = (Expression<Number>) exprs[2];
            offsetY = (Expression<Number>) exprs[3];
            source = (Expression<String>) exprs[4];
            scale = (Expression<Number>) exprs[5];
            color = (Expression<String>) exprs[6];
        }
        return true;
    }

    /** "bottom center", "bottom-center" and BOTTOM_CENTER are the same anchor. */
    static TextRules.@Nullable Anchor anchorOf(@Nullable String name) {
        if (name == null) return null;
        try {
            return TextRules.Anchor.valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int rgb(@Nullable String text) {
        if (text == null) return 0xFFFFFF;
        String hex = text.trim().replace("#", "");
        try {
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private static double number(@Nullable Expression<Number> expression, Event event, double fallback) {
        Number value = expression == null ? null : expression.getSingle(event);
        return value == null ? fallback : value.doubleValue();
    }

    @Override
    protected void execute(Event event) {
        if (pattern == 1) {
            for (String id : first.getArray(event)) RepackAPI.unregisterTextRule(id);
            return;
        }
        String id = first.getSingle(event);
        TextRules.Anchor to = anchorOf(anchor == null ? null : anchor.getSingle(event));
        TextRules.Anchor from = source == null ? TextRules.Anchor.TOP_CENTER : anchorOf(source.getSingle(event));
        if (id == null || to == null || from == null) {
            Bukkit.getLogger().warning("[Repack] text rule '" + id + "': the anchor is one of top, center or bottom with left, center or right (like bottom center)");
            return;
        }
        if (RepackAPI.registerTextRule(id, to, number(offsetX, event, 0), number(offsetY, event, 0), from, 0.0, 0.0,
                number(scale, event, 1.0), rgb(color == null ? null : color.getSingle(event))) == null) {
            Bukkit.getLogger().warning("[Repack] text rule '" + id + "' was refused: the id uses a-z, 0-9 and _.:/-, and there are at most " + TextRules.MAX_RULES + " rules");
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return (pattern == 0 ? "register text rule " : "unregister text rules ") + first.toString(event, debug);
    }
}
