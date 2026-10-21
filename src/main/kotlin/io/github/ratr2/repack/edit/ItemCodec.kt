package io.github.ratr2.repack.edit

import org.bukkit.inventory.ItemStack
import java.util.Base64

object ItemCodec {

    fun encode(stack: ItemStack): String = Base64.getEncoder().encodeToString(stack.asOne().serializeAsBytes())

    fun decode(text: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(text.trim()))

    /** True for a plain stack of a material with nothing else on it: a name, lore, enchantments or any other component make it exact. */
    fun isPlain(stack: ItemStack): Boolean = stack.isSimilar(ItemStack(stack.type))
}
