package io.github.ratr2.repack.block

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

enum class BlockAction {
    /** A player starts mining it. Cancelling stops the mining. */
    BREAK_START,
    BREAK,
    EXPLODE,
    PISTON,
    BURN,
    ENTITY_CHANGE,
    ENTITY_INTERACT,
    FADE,
    /** A liquid would flow into it. Cancelled by default. */
    FLUID,
    /** A bucket would be emptied into it or filled from it. Cancelled by default. */
    BUCKET,
    /** A growing structure or bone meal would replace it. Cancelled by default. */
    GROW,
    PHYSICS,
    /** Another block broke it, such as flowing water. Cannot be cancelled. */
    BROKEN_BY_BLOCK,
}

/**
 * Fired for everything that happens to a custom block's proxy block, after the block's defaults are applied. It starts out
 * cancelled when the block resists that action (`piston: block`, `explosion.resistant`); a listener can cancel or un-cancel it.
 * cause is the Bukkit event behind it, when there is one.
 */
class CustomBlockEvent(
    val block: Block,
    val id: String,
    val definition: BlockDefinition,
    val action: BlockAction,
    val player: Player?,
    val cause: Event?,
    cancelledByDefault: Boolean,
) : Event(), Cancellable {

    private var cancelled = cancelledByDefault

    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers() = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
