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
import io.github.ratr2.repack.menu.GuiCloseEvent;
import io.github.ratr2.repack.menu.GuiManager;
import io.github.ratr2.repack.menu.LiveGui;
import io.github.ratr2.repack.menu.SlotKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Opens, closes and refreshes custom GUIs, removes their slots, pauses and resumes a player's GUI code, and cancels a close.
 */
@Name("Custom GUI Effects")
@Description({"Opens, closes and refreshes custom GUIs, removes their slots, pauses and resumes a player's GUI code, and cancels a close.", "Pausing holds the open and close code and slot actions of a player's GUIs while you switch screens, so the old one's close code does not run. Always resume."})
@Examples({"open custom gui \"shop\" to player", "open the last created custom gui to player", "close custom gui for player", "refresh custom gui {_gui}", "unmake custom gui slot \"a\"", "pause custom gui events of player", "resume custom gui events of player", "create a custom gui with a chest with 3 rows:", "    run on custom gui close:", "        cancel custom gui closing"})
@Since("1.0.0")
public class EffGui extends Effect {

    static final String[] PATTERNS = {
            "(open|show|display) [[custom] gui] %customgui% (to|for) %players%",
            "close [custom] gui (of|for) %players%",
            "(pause|:resume) [custom] gui [events] (of|for) %players%",
            "refresh [[custom] gui] %customgui%",
            "(unmake|unformat|remove) [the] [custom] gui slot[s] %integers/strings% [in %-customgui%]",
            "(unmake|unformat|remove) all [[of] the] [custom] gui slots [in %-customgui%]",
            "(cancel|:uncancel) [the] [custom] gui clos(e|ing)",
            "(open|show|display) [custom] gui %string% (to|for) %players%",
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
            ch.njol.skript.Skript.error("`cancel custom gui closing` only works in `run on custom gui close`.");
            return false;
        }
        return true;
    }

    @Override
    protected void execute(Event event) {
        GuiManager manager = GuiScript.manager();
        if (manager == null) return;
        // What the effect works on is read here, on the thread the script runs on; the inventories are touched on the main thread (an async chat event cannot open one).
        Runnable task = null;
        switch (pattern) {
            case 0 -> {
                LiveGui gui = (LiveGui) first.getSingle(event);
                Player[] players = (Player[]) second.getArray(event);
                if (gui != null) task = () -> { for (Player player : players) manager.open(player, gui, true); };
            }
            case 1 -> {
                Player[] players = (Player[]) first.getArray(event);
                task = () -> { for (Player player : players) manager.close(player); };
            }
            case 2 -> {
                Player[] players = (Player[]) first.getArray(event);
                boolean resuming = resume;
                task = () -> { for (Player player : players) { if (resuming) manager.resume(player); else manager.pause(player); } };
            }
            case 3 -> {
                LiveGui gui = (LiveGui) first.getSingle(event);
                if (gui != null) task = gui::refresh;
            }
            case 4, 5 -> {
                Expression<?> guiExpr = pattern == 4 ? second : first;
                LiveGui gui = guiExpr != null ? (LiveGui) guiExpr.getSingle(event) : GuiScript.current(event);
                if (gui == null) return;
                if (pattern == 5) {
                    task = gui::unmakeAll;
                } else {
                    java.util.List<SlotKey> keys = new java.util.ArrayList<>();
                    for (SlotKey key : GuiScript.keys(first.getArray(event))) keys.add(key);
                    task = () -> { for (SlotKey key : keys) gui.unmakeSlot(key); };
                }
            }
            case 6 -> {
                if (event instanceof GuiCloseEvent close) close.setCancelled(!uncancel);
            }
            default -> {
                String id = (String) first.getSingle(event);
                if (id == null) return;
                for (Player player : (Player[]) second.getArray(event)) {
                    String problem = manager.open(player, id, true);
                    if (problem != null) player.sendMessage(net.kyori.adventure.text.Component.text(problem, net.kyori.adventure.text.format.NamedTextColor.RED));
                }
            }
        }
        if (task != null) io.github.ratr2.repack.RepackAPI.onMainThread(task);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom gui effect " + pattern;
    }
}
