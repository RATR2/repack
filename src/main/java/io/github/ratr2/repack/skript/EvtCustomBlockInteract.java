package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Fires when a player clicks a custom block. Left clicks too, since digging starts with one. event-block, event-player and event-string (the block's id) are available.
 */
@Name("Custom Block Interact")
@Description({"Fires when a player clicks a custom block. Left clicks too, since digging starts with one. event-block, event-player and event-string (the block's id) are available."})
@Examples({"on custom block interact of \"table\":", "    send \"You touched the table.\" to player"})
@Since("1.0.0")
public class EvtCustomBlockInteract extends SkriptEvent {

    static final String[] PATTERNS = {"custom block (interact|click) [of %-strings%]"};

    private @Nullable Literal<String> ids;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
        ids = (Literal<String>) args[0];
        return true;
    }

    @Override
    public boolean check(Event event) {
        return event instanceof CustomBlockInteractEvent interact && matches(ids, interact.getId());
    }

    static boolean matches(@Nullable Literal<String> ids, String id) {
        // Block ids are lower case, so `of "Table"` matches the block "table".
        return ids == null || Arrays.stream(ids.getAll()).anyMatch(wanted -> wanted.equalsIgnoreCase(id));
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom block interact" + (ids != null ? " of " + ids.toString(event, debug) : "");
    }
}
