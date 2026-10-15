package io.github.ratr2.repack.block

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** Something that happens to the proxy block of a custom block. */
enum class BlockAction {
    /** A player starts mining it. Cancelling stops the mining. */
    BREAK_START,

    /** It is about to be broken, by a player. */
    BREAK,

    /** An explosion would destroy it. */
    EXPLODE,

    /** A piston would push or pull it. */
    PISTON,

    /** Fire would burn it away. */
    BURN,

    /** An entity would change it: an enderman, a wither, a falling block, and so on. */
    ENTITY_CHANGE,
}

/**
 * Fired for everything that happens to a custom block's proxy block, after the block's own defaults have been applied: the event
 * starts out cancelled when the definition says the block resists that action (a piston on a block set to `piston: block`, an
 * explosion on a block set to `explosion.resistant`). A listener can cancel or un-cancel it, and the outcome is what happens.
 * [cause] is the Bukkit event behind it, when there is one.
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
