package io.github.ratr2.repack.block

object Tools {

    fun tierOf(material: String): ToolTier? = when {
        material.startsWith("GOLDEN_") -> ToolTier.WOODEN
        else -> ToolTier.entries.firstOrNull { material.startsWith(it.prefix) }
    }

    fun isType(material: String, tool: ToolType): Boolean = when (tool) {
        ToolType.ANY -> true
        ToolType.SHEARS -> material == "SHEARS"
        else -> material.endsWith(tool.suffix!!)
    }

    fun satisfies(material: String, breaking: Breaking): Boolean {
        if (!isType(material, breaking.tool)) return false
        val required = breaking.tier ?: return true
        val tier = tierOf(material) ?: return false
        return tier.ordinal >= required.ordinal
    }
}
