package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.pack.RepackJoinEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

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
