package io.github.ratr2.repack.edit

/**
 * The same edits as the entity builder screens, for other plugins and for Skript: change a model entity type with chained calls, then [save].
 * Nothing is written until [save], which checks it and loads it at once. If it could not be opened or created, every call does nothing and
 * [save] says why.
 *
 * ```java
 * String error = RepackAPI.createEntity("guard", "knight").animation("idle").scale(1.5).save();
 * ```
 */
class EntityBuilder internal constructor(private val draft: EntityDraft?, private val failure: String?) {

    private inline fun edit(change: (EntityDraft) -> Unit): EntityBuilder {
        if (draft != null) change(draft)
        return this
    }

    fun set(path: String, value: Any?) = edit { it.set(path, value) }

    fun model(model: String) = edit { it.set("model", model) }

    /** The animation that plays when one appears, or null for the still model. */
    fun animation(name: String?) = edit { it.set("animation", name) }

    fun scale(all: Double) = edit { it.set("scale", all) }

    fun scale(x: Double, y: Double, z: Double) = edit { it.set("scale", listOf(x, y, z)) }

    fun offset(x: Double, y: Double, z: Double) = edit { it.set("offset", listOf(x, y, z)) }

    fun viewRange(multiplier: Double) = edit { it.set("view-range", multiplier) }

    /** 0 to 15, or null to light it like the world around it. */
    fun brightness(level: Int?) = edit { it.set("brightness", level) }

    /** `none`, `gui`, `ground`, `fixed`, `head` and so on. */
    fun context(context: String) = edit { it.set("context", context.lowercase()) }

    fun problem(): String? = failure ?: draft?.problem()

    /** Checks it, writes `entities/<id>.yml` and loads it. Returns what is wrong, or null when it worked. */
    fun save(): String? = failure ?: draft?.save()
}
