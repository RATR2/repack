package io.github.ratr2.repack.skript;

import ch.njol.skript.registrations.EventValues;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import io.github.ratr2.repack.pack.RepackJoinEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** The values Repack's events hold for Skript: event-block, event-player, event-location and event-string. */
final class SkriptEventValues {

    private SkriptEventValues() {
    }

    static void register() {
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Block.class, CustomBlockInteractEvent::getBlock, 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Player.class, CustomBlockInteractEvent::getPlayer, 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, Location.class, e -> e.getBlock().getLocation(), 0);
        EventValues.registerEventValue(CustomBlockInteractEvent.class, String.class, CustomBlockInteractEvent::getId, 0);

        EventValues.registerEventValue(RepackJoinEvent.class, Player.class, RepackJoinEvent::getPlayer, 0);

        EventValues.registerEventValue(CustomBlockEvent.class, Block.class, CustomBlockEvent::getBlock, 0);
        EventValues.registerEventValue(CustomBlockEvent.class, Player.class, CustomBlockEvent::getPlayer, 0);
        EventValues.registerEventValue(CustomBlockEvent.class, Location.class, e -> e.getBlock().getLocation(), 0);
        EventValues.registerEventValue(CustomBlockEvent.class, String.class, CustomBlockEvent::getId, 0);
    }
}
