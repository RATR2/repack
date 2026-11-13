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

    private val REAL_TOOLS = listOf(ToolType.PICKAXE, ToolType.AXE, ToolType.SHOVEL, ToolType.HOE, ToolType.SWORD)

    fun isRealTool(material: String): Boolean = REAL_TOOLS.any { material.endsWith(it.suffix!!) }

    fun satisfies(material: String, breaking: Breaking): Boolean {
        if (!isType(material, breaking.tool)) return false
        val required = breaking.tier ?: return true
        // A tier only means something for a real tool: an iron ingot or a diamond helmet has "iron" and "diamond" in its name but breaks nothing.
        if (breaking.tool == ToolType.ANY && !isRealTool(material)) return false
        val tier = tierOf(material) ?: return false
        return tier.ordinal >= required.ordinal
    }
}
