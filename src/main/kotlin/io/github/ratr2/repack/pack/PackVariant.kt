package io.github.ratr2.repack.pack

enum class PackVariant(val id: String, val label: String, val minProtocol: Int, val maxProtocol: Int, val shelf: Boolean) {
    /** 1.21.4 to 1.21.8: no shelf display setting, which first appears in 1.21.9. */
    LEGACY("legacy", "1.21.4 to 1.21.8", 769, 772, shelf = false),
    MODERN("modern", "1.21.9 and newer", 773, Int.MAX_VALUE, shelf = true);

    companion object {
        val oldestProtocol = entries.minOf { it.minProtocol }

        fun forProtocol(protocol: Int): PackVariant? = entries.firstOrNull { protocol in it.minProtocol..it.maxProtocol }
    }
}
