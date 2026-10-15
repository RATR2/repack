package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.block.BlockAction;
import io.github.ratr2.repack.block.CustomBlockEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code on custom block break [of "table"]:}, {@code on custom block explode:}, {@code on custom block piston:} and one pattern
 * for every other thing that can happen to a custom block's proxy block (see BlockAction). The event starts cancelled when the
 * block resists that action; {@code uncancel event} lets it through and {@code cancel event} stops it.
 */
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
