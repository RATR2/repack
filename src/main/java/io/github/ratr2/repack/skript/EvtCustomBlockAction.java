package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.block.BlockAction;
import io.github.ratr2.repack.block.CustomBlockEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Fires for everything that happens to a custom block's proxy block: break start, break, explode, piston, burn, entity change, entity interact, fade, fluid, bucket, grow, physics and broken by block. The event starts cancelled when the block's file says it resists that action, so you can uncancel it.
 */
@Name("Custom Block Action")
@Description({"Fires for everything that happens to a custom block's proxy block: break start, break, explode, piston, burn, entity change, entity interact, fade, fluid, bucket, grow, physics and broken by block. The event starts cancelled when the block's file says it resists that action, so you can uncancel it."})
@Examples({"on custom block explode of \"table\":", "    uncancel event", "on custom block break start of \"table\":", "    if player does not have permission \"staff\":", "        cancel event"})
@Since("1.0.0")
public class EvtCustomBlockAction extends SkriptEvent {

    static final String[] PATTERNS;

    static {
        BlockAction[] actions = BlockAction.values();
        PATTERNS = new String[actions.length];
        for (int i = 0; i < actions.length; i++) {
            PATTERNS[i] = "custom block " + actions[i].name().toLowerCase(Locale.ROOT).replace('_', ' ') + " [of %-strings%]";
        }
    }

    private BlockAction action;
    private @Nullable Literal<String> ids;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
        action = BlockAction.values()[matchedPattern];
        ids = (Literal<String>) args[0];
        return true;
    }

    @Override
    public boolean check(Event event) {
        return event instanceof CustomBlockEvent custom && custom.getAction() == action && EvtCustomBlockInteract.matches(ids, custom.getId());
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom block " + action.name().toLowerCase(Locale.ROOT).replace('_', ' ') + (ids != null ? " of " + ids.toString(event, debug) : "");
    }
}
