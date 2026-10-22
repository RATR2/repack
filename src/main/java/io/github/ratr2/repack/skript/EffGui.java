package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.menu.GuiCloseEvent;
import io.github.ratr2.repack.menu.GuiManager;
import io.github.ratr2.repack.menu.LiveGui;
import io.github.ratr2.repack.menu.SlotKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffGui extends Effect {

    static final String[] PATTERNS = {
            "(open|show|display) [[custom] gui] %customgui% (to|for) %players%",
            "close [custom] gui (of|for) %players%",
            "(pause|:resume) [custom] gui [events] (of|for) %players%",
            "refresh [[custom] gui] %customgui%",
            "(unmake|unformat|remove) [the] [custom] gui slot[s] %integers/strings% [in %-customgui%]",
            "(unmake|unformat|remove) all [[of] the] [custom] gui slots [in %-customgui%]",
            "(cancel|:uncancel) [the] [custom] gui clos(e|ing)",
    };

    private int pattern;
    private Expression<?> first;
    private @Nullable Expression<?> second;
    private boolean resume;
    private boolean uncancel;

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        first = exprs.length > 0 ? exprs[0] : null;
        second = exprs.length > 1 ? exprs[1] : null;
        resume = parseResult.hasTag("resume");
        uncancel = parseResult.hasTag("uncancel");
        if (pattern == 6 && !getParser().isCurrentEvent(GuiCloseEvent.class)) {
            ch.njol.skript.Skript.error("`cancel gui closing` only works in `run on gui close`.");
            return false;
        }
        return true;
    }

    @Override
    protected void execute(Event event) {
        GuiManager manager = GuiScript.manager();
        if (manager == null) return;
        switch (pattern) {
            case 0 -> {
                LiveGui gui = (LiveGui) first.getSingle(event);
                if (gui != null) for (Player player : (Player[]) second.getArray(event)) manager.open(player, gui, true);
            }
            case 1 -> {
                for (Player player : (Player[]) first.getArray(event)) manager.close(player);
            }
            case 2 -> {
                for (Player player : (Player[]) first.getArray(event)) {
                    if (resume) manager.resume(player); else manager.pause(player);
                }
            }
            case 3 -> {
                LiveGui gui = (LiveGui) first.getSingle(event);
                if (gui != null) gui.refresh();
            }
            case 4, 5 -> {
                Expression<?> guiExpr = pattern == 4 ? second : first;
                LiveGui gui = guiExpr != null ? (LiveGui) guiExpr.getSingle(event) : GuiScript.current(event);
                if (gui == null) return;
                if (pattern == 5) {
                    gui.unmakeAll();
                } else {
                    for (SlotKey key : GuiScript.keys(first.getArray(event))) gui.unmakeSlot(key);
                }
            }
            default -> {
                if (event instanceof GuiCloseEvent close) close.setCancelled(!uncancel);
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom gui effect " + pattern;
    }
}
