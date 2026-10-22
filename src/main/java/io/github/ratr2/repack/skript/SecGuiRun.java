package io.github.ratr2.repack.skript;

import ch.njol.skript.config.SectionNode;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Section;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.menu.GuiCloseEvent;
import io.github.ratr2.repack.menu.GuiOpenEvent;
import io.github.ratr2.repack.menu.LiveGui;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SecGuiRun extends Section {

    static final String[] PATTERNS = {"run (on|when) [custom] gui (:open|:close)"};

    private boolean open;
    private Trigger trigger;

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult, SectionNode sectionNode, List<TriggerItem> triggerItems) {
        open = parseResult.hasTag("open");
        trigger = open ? loadCode(sectionNode, "gui open", GuiOpenEvent.class) : loadCode(sectionNode, "gui close", GuiCloseEvent.class);
        return true;
    }

    @Override
    protected @Nullable TriggerItem walk(Event event) {
        LiveGui gui = GuiScript.current(event);
        if (gui == null) {
            Bukkit.getLogger().warning("[Repack] `run on gui open/close` belongs inside a `create custom gui` section.");
        } else if (open) {
            gui.getOpenHooks().add(GuiScript.later(trigger, event));
        } else {
            gui.getCloseHooks().add(GuiScript.later(trigger, event));
        }
        return walk(event, false);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "run on custom gui " + (open ? "open" : "close");
    }
}
