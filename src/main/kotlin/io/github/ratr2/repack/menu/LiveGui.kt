package io.github.ratr2.repack.menu

import io.github.ratr2.repack.text.Colors
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/**
 * A custom GUI that can be shown: made from a file's definition (one for each player who opens it, unless the file says `shared`), or
 * built in code. Everything about it can change while it is open: the layout, the slots, the title.
 * Slots are kept apart from the layout, so a character defined before the layout uses it (or after it stops using it) is still there.
 */
class LiveGui internal constructor(
    val manager: GuiManager,
    var id: String?,
    val kind: GuiKind,
    rows: Int,
    title: String,
    val definition: GuiDef? = null,
) : InventoryHolder {

    var rows: Int = rows
        private set

    var title: String = title
        private set

    private val layoutRows = ArrayList<String>()
    private val slotDefs = LinkedHashMap<SlotKey, SlotDef>()

    var background: Background? = null
    var removable: Boolean = false
    var update: Int = 0
    var onOpen: List<GuiAction> = emptyList()
    var onClose: List<GuiAction> = emptyList()
    var permission: String? = null

    val openHooks = ArrayList<java.util.function.Consumer<GuiOpenEvent>>()
    val closeHooks = ArrayList<java.util.function.Consumer<GuiCloseEvent>>()

    val layout: List<String> get() = layoutRows

    fun nextFree(): Int? = (0 until size).firstOrNull { slotAt(it) == null }

    val slots: Map<SlotKey, SlotDef> get() = slotDefs

    private lateinit var inv: Inventory

    private val shown = HashMap<Int, Int>()

    override fun getInventory(): Inventory {
        if (!::inv.isInitialized) create(null)
        return inv
    }

    val viewers: List<Player> get() = if (::inv.isInitialized) inv.viewers.filterIsInstance<Player>() else emptyList()

    val size: Int get() = kind.size(rows)

    init {
        resetLayout()
    }

    private fun resetLayout() {
        layoutRows.clear()
        repeat(kind.layoutRows(rows)) { layoutRows += " ".repeat(kind.width) }
    }

    fun layout(rows: List<String>): LiveGui {
        require(kind.hasSlots) { "A ${kind.key} has no slots to lay out." }
        val expected = kind.layoutRows(this.rows)
        require(rows.size <= expected) { "A ${kind.key} of ${this.rows} rows has $expected layout rows, not ${rows.size}." }
        layoutRows.clear()
        for (row in rows) {
            require(row.length <= kind.width) { "The layout row \"$row\" is longer than ${kind.width} slots." }
            layoutRows += row.padEnd(kind.width)
        }
        while (layoutRows.size < expected) layoutRows += " ".repeat(kind.width)
        return redraw()
    }

    fun makeSlot(key: SlotKey, slot: SlotDef): LiveGui {
        if (key is SlotKey.Index) require(key.value in 0 until size) { "Slot ${key.value} is outside this GUI (0 to ${size - 1})." }
        slotDefs[key] = slot
        return redraw()
    }

    @JvmOverloads
    fun slot(char: Char, item: org.bukkit.inventory.ItemStack, handler: java.util.function.Consumer<GuiClickEvent>? = null): LiveGui =
        makeSlot(SlotKey.Char(char), SlotDef(null, stack = item, handler = handler?.let { h -> { e: GuiClickEvent -> h.accept(e) } }))

    @JvmOverloads
    fun slot(index: Int, item: org.bukkit.inventory.ItemStack, handler: java.util.function.Consumer<GuiClickEvent>? = null): LiveGui =
        makeSlot(SlotKey.Index(index), SlotDef(null, stack = item, handler = handler?.let { h -> { e: GuiClickEvent -> h.accept(e) } }))

    fun slotWith(key: SlotKey, item: org.bukkit.inventory.ItemStack, removable: Boolean, handler: java.util.function.Consumer<GuiClickEvent>?): LiveGui =
        makeSlot(key, SlotDef(null, stack = item, removable = removable, handler = handler?.let { h -> { e: GuiClickEvent -> h.accept(e) } }))

    fun unmakeSlot(key: SlotKey): LiveGui {
        slotDefs.remove(key)
        return redraw()
    }

    fun unmakeAll(): LiveGui {
        slotDefs.clear()
        return redraw()
    }

    /** The title it opens with from now on; a screen that is already open keeps its title, since the game cannot change a styled one. */
    fun title(text: String): LiveGui {
        title = text
        stale = true
        return this
    }

    private var stale = false

    fun register(id: String): LiveGui {
        this.id = id
        manager.registered[id] = this
        return this
    }

    fun slotAt(index: Int): Pair<SlotKey, SlotDef>? {
        slotDefs[SlotKey.Index(index)]?.let { return SlotKey.Index(index) to it }
        if (!kind.hasSlots) return null
        val row = layoutRows.getOrNull(index / kind.width) ?: return null
        val char = row.getOrNull(index % kind.width) ?: return null
        val key = SlotKey.Char(char)
        return slotDefs[key]?.let { key to it }
    }

    private fun titleFor(viewer: Player?): net.kyori.adventure.text.Component {
        val glyph = background?.let { bg -> manager.glyphFor(GuiBackgrounds.ShapeKey(bg.image, kind, if (kind == GuiKind.CHEST) rows else 0, bg.holes)) }
        return if (glyph != null) GuiBackgrounds.title(glyph) else Colors.parse(manager.text(viewer, title))
    }

    private fun create(viewer: Player?) {
        inv = when (kind) {
            GuiKind.CHEST -> Bukkit.createInventory(this, rows * 9, titleFor(viewer))
            GuiKind.HOPPER -> Bukkit.createInventory(this, InventoryType.HOPPER, titleFor(viewer))
            GuiKind.DISPENSER -> Bukkit.createInventory(this, InventoryType.DISPENSER, titleFor(viewer))
            GuiKind.DROPPER -> Bukkit.createInventory(this, InventoryType.DROPPER, titleFor(viewer))
            else -> throw IllegalStateException("A ${kind.key} GUI has no inventory.")
        }
        render(viewer)
    }

    internal fun prepare(viewer: Player) {
        if (!::inv.isInitialized || (stale && viewers.isEmpty())) {
            stale = false
            create(viewer)
        } else if (definition?.shared != true || viewers.isEmpty()) render(viewer)
    }

    fun redraw(): LiveGui {
        if (::inv.isInitialized) render(viewers.firstOrNull())
        return this
    }

    fun refresh() = redraw()

    private fun render(viewer: Player?) {
        inv.clear()
        shown.clear()
        for (index in 0 until size) {
            val (_, slot) = slotAt(index) ?: continue
            val item = slot.stack?.clone() ?: frameOf(slot, 0)?.let { manager.build(it, viewer) } ?: continue
            inv.setItem(index, item)
            if (slot.animated) shown[index] = 0
        }
    }

    private fun frameOf(slot: SlotDef, tick: Long): ItemSpec? =
        if (slot.animated) slot.frames[((tick / slot.frameTicks) % slot.frames.size).toInt()] else slot.item

    internal fun tick(now: Long) {
        if (!::inv.isInitialized || inv.viewers.isEmpty()) return
        val viewer = viewers.firstOrNull()
        if (update > 0 && now % update == 0L) {
            val before = HashMap(shown)
            render(viewer)
            // A redraw starts every animation over at its first frame, so put them back where they were.
            for ((index, _) in before) animate(index, now, viewer, force = true)
            return
        }
        for (index in shown.keys.toList()) animate(index, now, viewer, force = false)
    }

    private fun animate(index: Int, now: Long, viewer: Player?, force: Boolean) {
        val slot = slotAt(index)?.second ?: return
        if (!slot.animated) return
        val frame = ((now / slot.frameTicks) % slot.frames.size).toInt()
        if (!force && shown[index] == frame) return
        shown[index] = frame
        inv.setItem(index, manager.build(slot.frames[frame], viewer))
    }

    internal fun handleClick(event: InventoryClickEvent, player: Player) {
        if (event.clickedInventory != event.view.topInventory) {
            // Moving things in the player's own inventory could pull items into the GUI, so nothing moves unless the GUI lets items go.
            if (!removable) event.isCancelled = true
            return
        }
        val index = event.slot
        val found = slotAt(index)
        val free = found?.second?.removable == true || (removable && (found == null || found.second.clicks.isEmpty))
        if (free) return
        event.isCancelled = true
        val (key, slot) = found ?: return
        if (manager.isPaused(player)) return
        val call = GuiClickEvent(player, this, index, key, event.click, slot.tags)
        Bukkit.getPluginManager().callEvent(call)
        if (call.isCancelled) return
        slot.handler?.invoke(call)
        val actions = slot.clicks.actionsFor(event.click)
        if (actions.isEmpty()) return
        // Opening or closing inside the click itself can duplicate items, so what the click does happens a tick later.
        manager.plugin.server.scheduler.runTask(manager.plugin, Runnable { manager.run(player, this, actions, mapOf("slot" to index.toString())) })
    }

    internal fun handleDrag(event: InventoryDragEvent) {
        val topSize = event.view.topInventory.size
        val touched = event.rawSlots.filter { it < topSize }
        if (touched.isEmpty()) {
            if (!removable) event.isCancelled = true
            return
        }
        if (touched.any { slotAt(it)?.second?.removable != true && !(removable && slotAt(it)?.second?.clicks?.isEmpty != false) }) event.isCancelled = true
    }
}
