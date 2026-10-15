package io.github.ratr2.repack.block

import org.bukkit.inventory.ItemStack
import java.util.Random

class Loot(private val items: BlockItems) {

    private val random = Random()

    fun roll(definition: BlockDefinition): List<ItemStack> {
        val result = ArrayList<ItemStack>()
        for (drop in definition.drops) {
            if (drop.chance < 1.0 && random.nextDouble() >= drop.chance) continue
            val amount = drop.amount.roll(random)
            if (amount <= 0) continue
            when (drop) {
                is ItemDrop -> result += ItemStack(drop.material, amount)
                is SelfDrop -> result += items.create(definition, amount)
            }
        }
        return result
    }
}
