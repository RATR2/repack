package io.github.ratr2.repack.skript;

import ch.njol.skript.config.SectionNode;
import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.EffectSection;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.menu.GuiDef;
import io.github.ratr2.repack.menu.GuiKind;
import io.github.ratr2.repack.menu.GuiManager;
import io.github.ratr2.repack.menu.LiveGui;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Creates a custom GUI: a chest, hopper, dispenser or dropper with a layout, or a copy of a GUI file or template. Code inside the section fills it in with {@code make custom gui slot} and {@code run on custom gui open/close}. Open it with {@code open ... to player}.
 */
@Name("Create Custom GUI")
@Description({"Creates a custom GUI: a chest, hopper, dispenser or dropper with a layout, or a copy of a GUI file or template. Code inside the section fills it in with `make custom gui slot` and `run on custom gui open/close`. Open it with `open ... to player`."})
@Examples({"create a custom gui with id \"shop\" with a chest with 3 rows named \"&6Shop\" with layout \"#########\", \"#  a b  #\" and \"####c####\":", "    make custom gui slot \"#\" with gray stained glass pane named \" \"", "    make custom gui slot \"a\" with diamond named \"&bBuy\":", "        give player a diamond", "open the last created custom gui to player"})
@Since("1.0.0")
public class SecCreateGui extends EffectSection {

    static final String[] PATTERNS = {
            "create [a] [new] custom gui [with id[entifier] %-string%] with [a] (:chest|:hopper|:dispenser|:dropper) [[inventory] with %-number% row[s]] [(named|titled) %-string%] [(and|with) (layout|shape) %-strings%] [(and|with) :removable items]",
            "create [a] [new] custom gui [with id[entifier] %-string%] from [template] %string% [(named|titled) %-string%]",
    };

    private int pattern;
    private @Nullable Expression<String> id;
    private @Nullable Expression<Number> rows;
    private @Nullable Expression<String> title;
    private @Nullable Expression<String> layout;
    private @Nullable Expression<String> template;
    private GuiKind kind = GuiKind.CHEST;
    private boolean removable;
    private boolean hasBody;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult, @Nullable SectionNode sectionNode, @Nullable List<TriggerItem> triggerItems) {
        pattern = matchedPattern;
        id = (Expression<String>) exprs[0];
        if (matchedPattern == 0) {
            rows = (Expression<Number>) exprs[1];
            title = (Expression<String>) exprs[2];
            layout = (Expression<String>) exprs[3];
            for (GuiKind candidate : new GuiKind[]{GuiKind.CHEST, GuiKind.HOPPER, GuiKind.DISPENSER, GuiKind.DROPPER}) {
                if (parseResult.hasTag(candidate.getKey())) kind = candidate;
            }
            removable = parseResult.hasTag("removable");
        } else {
            template = (Expression<String>) exprs[1];
            title = (Expression<String>) exprs[2];
        }
        if (sectionNode != null) {
            hasBody = true;
            loadCode(sectionNode);
        }
        return true;
    }

    @Override
    protected @Nullable TriggerItem walk(Event event) {
        GuiManager manager = GuiScript.manager();
        if (manager == null) return walk(event, false);
        try {
            LiveGui gui;
            String name = title != null ? title.getSingle(event) : null;
            if (pattern == 0) {
                Number rowCount = rows != null ? rows.getSingle(event) : null;
                gui = manager.create(kind, rowCount != null ? rowCount.intValue() : 3, name != null ? name : "");
                if (layout != null) gui.layout(Arrays.asList(layout.getArray(event)));
                gui.setRemovable(removable);
            } else {
                String from = template != null ? template.getSingle(event) : null;
                GuiDef def = from == null ? null : manager.getDefinitions().get(from.toLowerCase(Locale.ROOT));
                if (def == null) {
                    Bukkit.getLogger().warning("[Repack] There is no GUI or template '" + from + "' to create a custom gui from.");
                    return walk(event, false);
                }
                gui = manager.instance(def);
                if (name != null) gui.title(name);
            }
            String guiId = id != null ? id.getSingle(event) : null;
            if (guiId != null) gui.register(guiId.toLowerCase(Locale.ROOT));
            GuiScript.setLast(event, gui);
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[Repack] Could not create the custom gui: " + e.getMessage());
            return walk(event, false);
        }
        return walk(event, hasBody);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "create a custom gui" + (id != null ? " with id " + id.toString(event, debug) : "");
    }
}
