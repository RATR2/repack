package io.github.ratr2.repack.skript;

import ch.njol.skript.aliases.ItemType;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.EffectSection;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.menu.GuiClickEvent;
import io.github.ratr2.repack.menu.LiveGui;
import io.github.ratr2.repack.menu.SlotKey;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public class SecMakeSlot extends EffectSection {

    static final String[] PATTERNS = {
            "(make|format) [the] [custom] gui slot[s] %integers/strings% [in %-customgui%] (with|to) [(:removable|:stealable)] %itemtype%",
            "(make|format) [the] next [custom] gui slot [in %-customgui%] (with|to) [(:removable|:stealable)] %itemtype%",
    };

    private int pattern;
    private @Nullable Expression<?> slots;
    private @Nullable Expression<LiveGui> gui;
    private Expression<ItemType> item;
    private boolean removable;
    private @Nullable Trigger trigger;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult, @Nullable SectionNode sectionNode, @Nullable List<TriggerItem> triggerItems) {
        pattern = matchedPattern;
        int at = 0;
        if (matchedPattern == 0) slots = exprs[at++];
        gui = (Expression<LiveGui>) exprs[at++];
        item = (Expression<ItemType>) exprs[at];
        removable = parseResult.hasTag("removable") || parseResult.hasTag("stealable");
        if (sectionNode != null) trigger = loadCode(sectionNode, "gui click", GuiClickEvent.class);
        return true;
    }

    @Override
    protected @Nullable TriggerItem walk(Event event) {
        LiveGui target = gui != null ? gui.getSingle(event) : GuiScript.current(event);
        ItemType type = item.getSingle(event);
        if (target == null || type == null) {
            Bukkit.getLogger().warning("[Repack] There is no custom gui to make a slot in here: create one first.");
            return walk(event, false);
        }
        ItemStack stack = type.getRandom();
        Consumer<GuiClickEvent> handler = trigger != null ? GuiScript.later(trigger, event) : null;
        try {
            if (pattern == 1) {
                Integer free = target.nextFree();
                if (free == null) {
                    Bukkit.getLogger().warning("[Repack] The custom gui has no free slot left.");
                } else {
                    target.slotWith(new SlotKey.Index(free), stack.clone(), removable, handler);
                }
            } else {
                for (SlotKey key : GuiScript.keys(slots.getArray(event))) target.slotWith(key, stack.clone(), removable, handler);
            }
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[Repack] " + e.getMessage());
        }
        return walk(event, false);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "make custom gui slot " + (slots != null ? slots.toString(event, debug) : "next") + " with " + item.toString(event, debug);
    }
}
