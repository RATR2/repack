package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.menu.GuiActionEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class EvtGuiAction extends SkriptEvent {

    static final String[] PATTERNS = {"custom gui action [of %-strings%]"};

    private @Nullable Literal<String> names;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Literal<?>[] args, int matchedPattern, ParseResult parseResult) {
        names = (Literal<String>) args[0];
        return true;
    }

    @Override
    public boolean check(Event event) {
        return event instanceof GuiActionEvent action && (names == null || Arrays.asList(names.getAll()).contains(action.getName()));
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom gui action" + (names != null ? " of " + names.toString(event, debug) : "");
    }
}
