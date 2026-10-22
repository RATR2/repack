package io.github.ratr2.repack.skript;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.classes.Parser;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.EventValues;
import io.github.ratr2.repack.menu.GuiActionEvent;
import io.github.ratr2.repack.menu.GuiClickEvent;
import io.github.ratr2.repack.menu.GuiCloseEvent;
import io.github.ratr2.repack.menu.GuiOpenEvent;
import io.github.ratr2.repack.menu.LiveGui;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** The `customgui` type, and the values GUI events hold. The type is not called `gui` so that it never clashes with skript-gui. */
@SuppressWarnings({"deprecation", "removal"})
final class GuiSkriptTypes {

    private GuiSkriptTypes() {
    }

    static void register() {
        Classes.registerClass(new ClassInfo<>(LiveGui.class, "customgui")
                .user("custom ?guis?")
                .name("Custom GUI")
                .description("A screen made by Repack: a chest, hopper, dispenser or dropper with slots, built with `create custom gui`, or one of the files in the guis folder.")
                .examples("set {_gui} to custom gui with id \"shop\"")
                .since("1.1.0")
                .parser(new Parser<LiveGui>() {
                    @Override
                    public boolean canParse(ParseContext context) {
                        return false;
                    }

                    @Override
                    public String toString(LiveGui gui, int flags) {
                        return "custom gui" + (gui.getId() != null ? " " + gui.getId() : "");
                    }

                    @Override
                    public String toVariableNameString(LiveGui gui) {
                        return "customgui:" + System.identityHashCode(gui);
                    }
                }));

        EventValues.registerEventValue(GuiClickEvent.class, Player.class, GuiClickEvent::getPlayer, 0);
        EventValues.registerEventValue(GuiClickEvent.class, LiveGui.class, GuiClickEvent::getGui, 0);
        EventValues.registerEventValue(GuiOpenEvent.class, Player.class, GuiOpenEvent::getPlayer, 0);
        EventValues.registerEventValue(GuiOpenEvent.class, LiveGui.class, GuiOpenEvent::getGui, 0);
        EventValues.registerEventValue(GuiCloseEvent.class, Player.class, GuiCloseEvent::getPlayer, 0);
        EventValues.registerEventValue(GuiCloseEvent.class, LiveGui.class, GuiCloseEvent::getGui, 0);
        EventValues.registerEventValue(GuiActionEvent.class, Player.class, GuiActionEvent::getPlayer, 0);
        EventValues.registerEventValue(GuiActionEvent.class, LiveGui.class, e -> e.getGui(), 0);
        EventValues.registerEventValue(GuiActionEvent.class, String.class, GuiActionEvent::getName, 0);
    }
}
