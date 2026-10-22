package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.variables.Variables;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.menu.GuiActionEvent;
import io.github.ratr2.repack.menu.GuiClickEvent;
import io.github.ratr2.repack.menu.GuiCloseEvent;
import io.github.ratr2.repack.menu.GuiManager;
import io.github.ratr2.repack.menu.GuiOpenEvent;
import io.github.ratr2.repack.menu.LiveGui;
import io.github.ratr2.repack.menu.SlotKey;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

final class GuiScript {

    private static final Map<Event, LiveGui> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private GuiScript() {
    }

    static void setLast(Event event, LiveGui gui) {
        LAST.put(event, gui);
    }

    static @Nullable LiveGui current(Event event) {
        if (event instanceof GuiClickEvent click) return click.getGui();
        if (event instanceof GuiOpenEvent open) return open.getGui();
        if (event instanceof GuiCloseEvent close) return close.getGui();
        if (event instanceof GuiActionEvent action) return action.getGui();
        return LAST.get(event);
    }

    static @Nullable GuiManager manager() {
        return RepackAPI.guiManager();
    }

    static List<SlotKey> keys(Object[] values) {
        List<SlotKey> keys = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                keys.add(new SlotKey.Index(number.intValue()));
            } else if (value instanceof String text) {
                for (char c : text.toCharArray()) keys.add(new SlotKey.Char(c));
            }
        }
        return keys;
    }

    /** Runs a section's code later, for an event that came after the script line ran, with the local variables it had then. */
    static <E extends Event> Consumer<E> later(Trigger trigger, Event creating) {
        Object locals = Variables.copyLocalVariables(creating);
        return event -> {
            if (locals != null) Variables.setLocalVariables(event, locals);
            TriggerItem.walk(trigger, event);
            Variables.setLocalVariables(event, Variables.removeLocals(event));
        };
    }
}
