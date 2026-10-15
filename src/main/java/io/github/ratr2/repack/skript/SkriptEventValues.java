package io.github.ratr2.repack.skript;

import ch.njol.skript.registrations.EventValues;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** event-block, event-player, event-location and event-string (the custom block id) in Repack's events. */
final class SkriptEventValues {

    private SkriptEventValues() {
    }

    static void register() {
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Block.class, CustomBlockInteractEvent::getBlock, 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Player.class, CustomBlockInteractEvent::getPlayer, 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Location.class, e -> e.getBlock().getLocation(), 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, String.class, CustomBlockInteractEvent::getId, 0);

        EventValues.registerEventValue(CustomBlockEvent.class, Block.class, CustomBlockEvent::getBlock, 0);
        EventValues.registerEventValue(CustomBlockEvent.class, Player.class, CustomBlockEvent::getPlayer, 0);
        EventValues.registerEventValue(CustomBlockEvent.class, Location.class, e -> e.getBlock().getLocation(), 0);
        EventValues.registerEventValue(CustomBlockEvent.class, String.class, CustomBlockEvent::getId, 0);
    }
}
