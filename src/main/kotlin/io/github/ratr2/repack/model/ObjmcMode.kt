package io.github.ratr2.repack.model

/** When a model is drawn with objmc's technique instead of as a vanilla block model (the `objmc` setting). */
enum class ObjmcMode {
    /** A model vanilla can draw exactly stays a vanilla model; one that needs more (meshes, animations, rotated groups, free rotations, oversized cubes) uses objmc. */
    AUTO,

    /** Every model uses objmc. */
    ALWAYS,

    /** Vanilla models only: what they cannot show is left out, and meshes are skipped. */
    NEVER;

    companion object {
        fun fromConfig(text: String?): ObjmcMode = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: AUTO
    }
}
