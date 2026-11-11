package io.github.ratr2.repack

import io.github.ratr2.repack.pack.TextRules
import org.bukkit.Bukkit
import io.github.ratr2.repack.edit.BlockBuilder
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Static entry points for other plugins, callable directly or via reflection. From another thread a call waits for the main thread:
 *
 * ```java
 * Class<?> api = Bukkit.getPluginManager().getPlugin("repack").getClass().getClassLoader()
 *     .loadClass("io.github.ratr2.repack.RepackAPI");
 * boolean placed = (boolean) api.getMethod("place", Location.class, String.class).invoke(null, location, "table");
 * ```
 */
object RepackAPI {

    @Volatile
    internal var plugin: Repack? = null

    /** Runs action on the main thread, waiting for it when called from another one, because the block data is not thread safe. */
    internal fun <T> onMain(fallback: T, action: (Repack) -> T): T {
        val api = plugin ?: return fallback
        if (Bukkit.isPrimaryThread()) return action(api)
        return api.server.scheduler.callSyncMethod(api, Callable { action(api) }).get(10, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun place(location: Location, id: String): Boolean = place(location, id, 0f)

    @JvmStatic
    fun place(location: Location, id: String, yaw: Float): Boolean = onMain(false) { it.blocks.place(location, id, yaw) }

    @JvmStatic
    fun remove(location: Location): Boolean = onMain(false) { it.blocks.remove(location.block) }

    @JvmStatic
    fun idAt(block: Block): String? = onMain(null) { it.blocks.idAt(block) }

    @JvmStatic
    fun isCustomBlock(block: Block, id: String?): Boolean {
        val found = idAt(block) ?: return false
        return id == null || found == id
    }

    @JvmStatic
    fun ripple(location: Location, rgb: Int, radius: Double, ticks: Int): Boolean = io.github.ratr2.repack.distortion.DistortionAPI.spawn(location, rgb, radius, ticks)

    @JvmStatic
    fun activeRipples(): Int = io.github.ratr2.repack.distortion.DistortionAPI.activeRipples()

    @JvmStatic
    fun maxRipples(): Int = io.github.ratr2.repack.distortion.DistortionAPI.maxRipples()

    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? = onMain(null) { api -> api.stackFor(id, amount) }

    @JvmStatic
    fun register(id: String, model: String): String? = onMain("Repack is not running.") { it.registerBlock(id, model) }

    @JvmStatic
    fun unregister(id: String): Boolean = onMain(false) { it.unregisterBlock(id) }

    @JvmStatic
    fun reload(): List<String> = onMain(emptyList()) { it.reloadDefinitions() }

    @JvmStatic
    fun edit(id: String): BlockBuilder = onMain(BlockBuilder(null, "Repack is not running.")) { api ->
        val draft = api.gui.editor.open(id)
        BlockBuilder(draft, if (draft == null) "There is no block with the id '$id'." else null)
    }

    @JvmStatic
    fun create(id: String, model: String): BlockBuilder = onMain(BlockBuilder(null, "Repack is not running.")) { api ->
        api.gui.editor.create(id, model).fold(
            onSuccess = { BlockBuilder(it, null) },
            onFailure = { BlockBuilder(null, it.message ?: "Could not make the block.") },
        )
    }

    @JvmStatic
    fun ids(): Set<String> = onMain(emptySet()) { it.blocks.types.toSet() }

    @JvmStatic
    fun itemIds(): Set<String> = onMain(emptySet()) { it.customItems.ids.toSet() }

    @JvmStatic
    fun itemIdOf(stack: ItemStack?): String? = onMain(null) { it.customItems.idOf(stack) }

    @JvmStatic
    fun registerItem(id: String, base: String, model: String?): String? = onMain("Repack is not running.") { it.registerItem(id, base, model) }

    @JvmStatic
    fun unregisterItem(id: String): Boolean = onMain(false) { it.unregisterItem(id) }

    @JvmStatic
    fun editItem(id: String): io.github.ratr2.repack.edit.ItemBuilder = onMain(io.github.ratr2.repack.edit.ItemBuilder(null, "Repack is not running.")) { api ->
        val draft = api.itemEditor.open(id)
        io.github.ratr2.repack.edit.ItemBuilder(draft, if (draft == null) "There is no item '$id'." else null)
    }

    @JvmStatic
    fun createItem(id: String): io.github.ratr2.repack.edit.ItemBuilder = onMain(io.github.ratr2.repack.edit.ItemBuilder(null, "Repack is not running.")) { api ->
        api.itemEditor.create(id).fold(
            onSuccess = { io.github.ratr2.repack.edit.ItemBuilder(it, null) },
            onFailure = { io.github.ratr2.repack.edit.ItemBuilder(null, it.message ?: "Could not make the item.") },
        )
    }

    @JvmStatic
    fun animations(id: String): List<String> = onMain(emptyList()) { api ->
        val model = api.blocks.definitionOf(id)?.model ?: return@onMain emptyList()
        api.modelClips[model].orEmpty()
    }

    @JvmStatic
    fun playAnimation(location: Location, animation: String?): Boolean = playAnimation(location, animation, null)

    /**
     * As above, repeating as mode says: `loop`, `once` (back to the built pose) or `hold` (stays in the last pose). Null or
     * anything else uses the animation's Blockbench setting.
     */
    @JvmStatic
    fun playAnimation(location: Location, animation: String?, mode: String?): Boolean = onMain(false) {
        it.display.play(location, animation?.lowercase(), mode?.let { m -> io.github.ratr2.repack.model.AnimationMode.entries.firstOrNull { e -> e.name.equals(m.trim(), ignoreCase = true) } })
    }

    @JvmStatic
    fun stopAnimation(location: Location): Boolean = playAnimation(location, null)

    @JvmStatic
    fun animationAt(location: Location): String? = onMain(null) { it.display.playing(location) }

    /**
     * Shakes the player's screen at strength 0 to 1 for ticks, fading out. False if the client can't show it (needs the pack
     * and 1.21.6 or newer; Fabulous! graphics before 26.3).
     */
    @JvmStatic
    fun shake(player: org.bukkit.entity.Player, strength: Double, ticks: Int): Boolean = io.github.ratr2.repack.distortion.DistortionAPI.shake(player, strength, ticks)

    @JvmStatic
    fun shake(player: org.bukkit.entity.Player, strength: Double, ticks: Int, rate: Double, balance: Double, roll: Double): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.shake(player, strength, ticks, rate, balance, roll)

    @JvmStatic
    fun stopShake(player: org.bukkit.entity.Player) = io.github.ratr2.repack.distortion.DistortionAPI.stopShake(player)

    /**
     * Holds an impact frame on the player's screen for [ticks] (1 to 200), like the frame an anime holds on a hit: the whole picture is drawn as hard black and
     * white, smeared outward in jagged spikes from the focus point, with outlines where one shape stands in front of another, and the focus point draws a shape
     * over it. [type] is `radial` (a burst of wedges), `explosion`, `lightning`, `splotch` or, with a direction, `slash`, `lines`, `shatter` or `stretched` (see the
     * focus point class); [size] is in blocks (0.1 to 64). The focus point has to be in front
     * of the camera. False if the client can't show it (needs the pack and 1.21.6 or newer; Fabulous! graphics before 26.3) or the type is unknown. A new one
     * replaces the running one.
     */
    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, location: org.bukkit.Location, type: String, size: Double): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.impact(player, ticks, location, type, size)

    /** As above, with [rgb] (like 0xFF2030 for red) as the light tone instead of white; the dark tone is always black. */
    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, rgb: Int, location: org.bukkit.Location, type: String, size: Double): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.impact(player, ticks, rgb, location, type, size)

    /** An impact frame with several focus points (up to 15), each its own location, type and size. */
    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, rgb: Int, foci: List<io.github.ratr2.repack.distortion.DistortionAPI.ImpactFocus>): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.impact(player, ticks, rgb, foci)

    /** Adds a focus point to the impact frame the player is in; false when there is none running. */
    @JvmStatic
    fun addImpactFocus(player: org.bukkit.entity.Player, location: org.bukkit.Location, type: String, size: Double): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.addImpactFocus(player, location, type, size)

    /** As above, for a type with a direction: [screenAngle] is degrees on the screen, 0 to the right and 90 up. */
    @JvmStatic
    fun addImpactFocus(player: org.bukkit.entity.Player, location: org.bukkit.Location, type: String, size: Double, screenAngle: Double?): Boolean =
        io.github.ratr2.repack.distortion.DistortionAPI.addImpactFocus(player, location, type, size, screenAngle)

    /**
     * The impact frame scene by that name, a new empty one when there is none. Add focus points to it (`add`, `addRelative`), then show it to players in one go
     * with `play(player)`. Scenes are also read from `plugins/Repack/impacts/<name>.yml` (see the guide); a scene built here is not saved.
     */
    @JvmStatic
    fun impactScene(name: String): io.github.ratr2.repack.distortion.ImpactScene = io.github.ratr2.repack.distortion.ImpactScenes.getOrCreate(name)

    /** The names of all impact frame scenes, from files and built at run time. */
    @JvmStatic
    fun impactSceneIds(): List<String> = io.github.ratr2.repack.distortion.ImpactScenes.ids

    /** Shows a scene to a player with its own length and color; false when there is no such scene, it is empty, or the client cannot show it. */
    @JvmStatic
    fun playImpactScene(player: org.bukkit.entity.Player, name: String): Boolean = io.github.ratr2.repack.distortion.ImpactScenes.get(name)?.play(player) ?: false

    @JvmStatic
    fun playImpactScene(player: org.bukkit.entity.Player, name: String, ticks: Int, rgb: Int): Boolean = io.github.ratr2.repack.distortion.ImpactScenes.get(name)?.play(player, ticks, rgb) ?: false

    /** Empties a scene, or removes it when [remove] is true. False when there is no such scene. */
    @JvmStatic
    fun clearImpactScene(name: String, remove: Boolean): Boolean {
        val scene = io.github.ratr2.repack.distortion.ImpactScenes.get(name) ?: return false
        if (remove) io.github.ratr2.repack.distortion.ImpactScenes.remove(name) else scene.clear()
        return true
    }

    @JvmStatic
    fun stopImpact(player: org.bukkit.entity.Player) = io.github.ratr2.repack.distortion.DistortionAPI.stopImpact(player)

    /** Whether the player has loaded Repack's pack and their client can show screen shake and impact frames. */
    @JvmStatic
    fun canShowScreenEffects(player: org.bukkit.entity.Player): Boolean = io.github.ratr2.repack.distortion.DistortionAPI.canShowScreenEffects(player)

    /** Whether the player's client has loaded Repack's pack. */
    @JvmStatic
    fun hasPack(player: org.bukkit.entity.Player): Boolean = onMain(false) { it.sender.hasLoaded(player) }

    @JvmStatic
    fun skyboxIds(): List<String> = onMain(emptyList()) { api -> api.skyboxes?.definitions?.map { it.id }.orEmpty() }

    /**
     * Makes the player see skybox id whatever fits where they are, or none for "none"; null goes back to the one that fits.
     * False if there is no such skybox. Not saved.
     */
    @JvmStatic
    fun setSkybox(player: org.bukkit.entity.Player, id: String?): Boolean = onMain(false) { api -> api.skyboxes?.choose(player, id) ?: false }

    // Custom GUIs. Slot rules (cost, cooldown, permission) belong to the calling plugin: listen for GuiClickEvent and cancel it.

    @JvmStatic
    fun guiManager(): io.github.ratr2.repack.menu.GuiManager? = plugin?.takeIf { it.isEnabled }?.guis

    @JvmStatic
    fun guiIds(): List<String> = onMain(emptyList()) { it.guis.ids }

    @JvmStatic
    fun openGui(player: org.bukkit.entity.Player, id: String): String? = onMain("Repack is not running.") { it.guis.open(player, id) }

    @JvmStatic
    fun openGui(player: org.bukkit.entity.Player, gui: io.github.ratr2.repack.menu.LiveGui) = onMain(Unit) { it.guis.open(player, gui) }

    @JvmStatic
    fun closeGui(player: org.bukkit.entity.Player) = onMain(Unit) { it.guis.close(player) }

    @JvmStatic
    fun guiOf(player: org.bukkit.entity.Player): io.github.ratr2.repack.menu.LiveGui? = onMain(null) { player.openInventory.topInventory.holder as? io.github.ratr2.repack.menu.LiveGui }

    @JvmStatic
    fun createGui(rows: Int, title: String): io.github.ratr2.repack.menu.LiveGui? = onMain(null) { it.guis.create(io.github.ratr2.repack.menu.GuiKind.CHEST, rows, title) }

    /** A new GUI of another kind: "hopper", "dispenser" or "dropper". */
    @JvmStatic
    fun createGui(kind: String, title: String): io.github.ratr2.repack.menu.LiveGui? = onMain(null) { api ->
        io.github.ratr2.repack.menu.GuiKind.of(kind)?.takeIf { it.hasSlots }?.let { api.guis.create(it, 3, title) }
    }

    /** Holds a player's GUI handlers and slot actions, to switch screens without them firing. Always resume. */
    @JvmStatic
    fun pauseGui(player: org.bukkit.entity.Player) = onMain(Unit) { it.guis.pause(player) }

    @JvmStatic
    fun resumeGui(player: org.bukkit.entity.Player) = onMain(Unit) { it.guis.resume(player) }

    @JvmStatic
    fun skyboxOf(player: org.bukkit.entity.Player): String? = onMain(null) { api -> api.skyboxes?.skyboxOf(player)?.id }

    /**
     * Registers a shared text shader rule and returns its marker color, or null when the id is malformed or all markers are taken.
     * Text drawn in the marker (as a GUI text: boss bar title, action bar, scoreboard, chat) is moved by the pack's text shader to
     * [anchor] plus [offsetX]/[offsetY] GUI pixels and drawn in [color]. [source] is where vanilla draws that kind of text, which the
     * offset is measured from. Registering an id again replaces the rule and keeps its marker. Takes effect at the next build and push.
     * Ids should start with the plugin's name (`ethereal:mana`), and a plugin should call [unregisterTextRules] when it disables.
     */
    @JvmStatic
    @JvmOverloads
    fun registerTextRule(
        id: String, anchor: TextRules.Anchor, offsetX: Double, offsetY: Double,
        source: TextRules.Anchor = TextRules.Anchor.TOP_CENTER, sourceX: Double = 0.0, sourceY: Double = 0.0,
        scale: Double = 1.0, color: Int = 0xFFFFFF,
    ): net.kyori.adventure.text.format.TextColor? =
        TextRules.register(id, anchor, offsetX, offsetY, source, sourceX, sourceY, scale, color)?.let { net.kyori.adventure.text.format.TextColor.color(it.marker) }

    /** Removes a text rule. Text still drawn in its marker shows the marker color after the next build. */
    @JvmStatic
    fun unregisterTextRule(id: String): Boolean = TextRules.unregister(id)

    /** Removes every text rule whose id starts with [prefix], such as `ethereal:`. Returns how many. */
    @JvmStatic
    fun unregisterTextRules(prefix: String): Int = TextRules.unregisterAll(prefix)

    /** The marker color of a registered text rule, or null. */
    @JvmStatic
    fun textRuleMarker(id: String): net.kyori.adventure.text.format.TextColor? = TextRules.markerOf(id)?.let { net.kyori.adventure.text.format.TextColor.color(it) }

    /** The ids of all registered text rules. */
    @JvmStatic
    fun textRuleIds(): List<String> = TextRules.all().map { it.id }
}
