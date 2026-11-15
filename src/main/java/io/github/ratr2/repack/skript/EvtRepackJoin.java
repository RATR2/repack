package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.pack.RepackJoinEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Fires once a joining player's client has the Repack pack (or it failed, timed out, or none is sent). Skript's {@code on join} fires before the client has the pack, so use this for anything that needs it, like effects. {@code the repack join reason} says why.
 */
@Name("Repack Join")
@Description({"Fires once a joining player's client has the Repack pack (or it failed, timed out, or none is sent). Skript's `on join` fires before the client has the pack, so use this for anything that needs it, like effects. `the repack join reason` says why."})
@Examples({"on repack join:", "    if the repack join reason is \"loaded\":", "        send \"Your pack is loaded.\" to player"})
@Since("1.0.1")
public class EvtRepackJoin extends SkriptEvent {

    static final String[] PATTERNS = {"repack join"};

    @Override
    public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
        return true;
    }

    @Override
    public boolean check(Event event) {
        return event instanceof RepackJoinEvent;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "repack join";
    }
}
