package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

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
        return ids == null || Arrays.asList(ids.getAll()).contains(id);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom block interact" + (ids != null ? " of " + ids.toString(event, debug) : "");
    }
}
