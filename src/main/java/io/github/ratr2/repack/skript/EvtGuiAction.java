package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import io.github.ratr2.repack.menu.GuiActionEvent;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Fires when an {@code event: name} action of a GUI file runs (a slot, on-open, on-close, a dialog button). event-player, event-string (the name) and {@code the gui input} (what a dialog or anvil collected) are available.
 */
@Name("Custom GUI Action")
@Description({"Fires when an `event: name` action of a GUI file runs (a slot, on-open, on-close, a dialog button). event-player, event-string (the name) and `the gui input` (what a dialog or anvil collected) are available."})
@Examples({"on custom gui action of \"buy\":", "    send \"Amount: %the gui input \"amount\"%\" to event-player"})
@Since("1.0.0")
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
        return event instanceof GuiActionEvent action && (names == null || Arrays.stream(names.getAll()).anyMatch(wanted -> wanted.equalsIgnoreCase(action.getName())));
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "custom gui action" + (names != null ? " of " + names.toString(event, debug) : "");
    }
}
