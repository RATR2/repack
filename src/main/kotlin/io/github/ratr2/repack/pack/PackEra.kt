package io.github.ratr2.repack.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * One client era: the clients that share one pack. The pack is the family build of the blocks plus the shaders of the era
 * (`packs/<id>/assets` in the jar, for Distortion's ripples, which differ with every game version's shader code). The protocol
 * numbers are the ones ViaVersion lists for each release; format is what pack.mcmeta says for it.
 */
enum class PackEra(
    val id: String,
    val label: String,
    val minProtocol: Int,
    val maxProtocol: Int,
    val family: PackVariant,
    val format: Format,
    /**
     * True when the client takes the server driven post effect API. Older clients have no such packet, so their pack overrides the
     * Fabulous graphics transparency effect instead, which only runs with Fabulous graphics on.
     */
    val usesPostEffects: Boolean = false,
) {
    V1_21_4("1.21.4", "1.21.4", 769, 769, PackVariant.LEGACY, Format.Old(46, 46)),
    V1_21_5("1.21.5", "1.21.5", 770, 770, PackVariant.LEGACY, Format.Old(55, 55)),
    V1_21_8("1.21.8", "1.21.6 to 1.21.8", 771, 772, PackVariant.LEGACY, Format.Old(63, 64)),
    V1_21_10("1.21.10", "1.21.9 to 1.21.10", 773, 773, PackVariant.MODERN, Format.New(69, 0, 69, 0)),
    V1_21_11("1.21.11", "1.21.11", 774, 774, PackVariant.MODERN, Format.New(75, 0, 75, 0)),
    V26_1("26.1", "26.1", 775, 775, PackVariant.MODERN, Format.New(84, 0, 84, 0)),
    V26_2("26.2", "26.2", 776, 776, PackVariant.MODERN, Format.New(88, 0, 88, 0)),
    V26_3("26.3", "26.3 and newer", 777, Int.MAX_VALUE, PackVariant.MODERN, Format.New(97, 1, 97, 1), usesPostEffects = true);

    sealed interface Format {
        /** first is also the `pack_format`; last is the last format in `supported_formats`. */
        class Old(val first: Int, val last: Int) : Format

        class New(val minMajor: Int, val minMinor: Int, val maxMajor: Int, val maxMinor: Int) : Format
    }

    /** Whether this era's pack can shake the screen (1.21.6 and newer: older text shaders have no game time and no room for the marker). */
    val shakes: Boolean get() = ordinal >= V1_21_8.ordinal

    fun putFormat(pack: JsonObject) {
        when (val f = format) {
            is Format.Old -> {
                pack.addProperty("pack_format", f.first)
                pack.add("supported_formats", JsonObject().apply {
                    addProperty("min_inclusive", f.first)
                    addProperty("max_inclusive", f.last)
                })
            }
            is Format.New -> {
                pack.add("min_format", JsonArray().apply { add(f.minMajor); add(f.minMinor) })
                pack.add("max_format", JsonArray().apply { add(f.maxMajor); add(f.maxMinor) })
            }
        }
    }

    companion object {
        val oldestProtocol = entries.minOf { it.minProtocol }

        val newest = entries.last()

        fun forProtocol(protocol: Int): PackEra? = entries.firstOrNull { protocol in it.minProtocol..it.maxProtocol }
    }
}
