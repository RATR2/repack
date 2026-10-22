package io.github.ratr2.repack.menu

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.text.Colors
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.InvalidKeyException
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Method
import java.util.UUID

class GuiManager(val plugin: Repack) : Listener {

    @Volatile
    var definitions: Map<String, GuiDef> = emptyMap()
        private set

    /** GUIs built in code (RepackAPI, Skript) and registered under an id. They are shared: every viewer sees the same inventory. */
    internal val registered = LinkedHashMap<String, LiveGui>()

    @Volatile
    private var built: Set<GuiBackgrounds.ShapeKey>? = null

    @Volatile
    private var glyphs: Map<GuiBackgrounds.ShapeKey, Char> = emptyMap()

    private val shared = HashMap<String, LiveGui>()

    internal val navigating = HashSet<UUID>()

    private val paused = HashSet<UUID>()

    private val history = HashMap<UUID, ArrayDeque<String>>()

    internal val dialogs = GuiDialogs(this)

    private var now = 0L

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        dialogs.register()
    }

    fun stop() {
        dialogs.unregister()
    }

    fun load(loaded: LoadedGuis, log: Boolean = true) {
        definitions = loaded.definitions
        glyphs = GuiBackgrounds.glyphs(loaded.definitions.values)
        shared.clear()
        if (log) loaded.errors.forEach { plugin.logger.warning(it) }
    }

    /** Called after a build: only pictures the build put in the pack get their screen; the others show the title as text until the next build. */
    fun built(keys: Collection<GuiBackgrounds.ShapeKey>) {
        built = keys.toSet()
    }

    fun glyphFor(key: GuiBackgrounds.ShapeKey): Char? {
        val char = glyphs[key] ?: return null
        val have = built
        return if (have == null || key in have) char else null
    }

    fun lookup(id: String): LiveGui? {
        val key = id.lowercase()
        registered[key]?.let { return it }
        return definitions[key]?.takeIf { !it.template }?.let(::instance)
    }

    val ids: List<String> get() = (definitions.filterValues { !it.template }.keys + registered.keys).sorted()

    fun open(player: Player, id: String, checkPermission: Boolean = true): String? {
        val key = id.lowercase()
        registered[key]?.let { gui ->
            if (checkPermission && gui.permission != null && !player.hasPermission(gui.permission!!)) return "You cannot open that."
            open(player, gui)
            return null
        }
        val def = definitions[key] ?: return "There is no GUI '$id'."
        if (def.template) return "'$id' is a template: it is only copied from, never opened."
        if (checkPermission && def.permission != null && !player.hasPermission(def.permission)) return "You cannot open that."
        open(player, instance(def))
        return null
    }

    fun instance(def: GuiDef): LiveGui = if (def.shared) shared.getOrPut(def.id) { create(def) } else create(def)

    private fun create(def: GuiDef): LiveGui = LiveGui(this, def.id, def.kind, def.rows, def.title, def).also { gui ->
        gui.background = def.background
        gui.removable = def.removable
        gui.update = def.update
        gui.onOpen = def.onOpen
        gui.onClose = def.onClose
        gui.permission = def.permission
        if (def.kind.hasSlots) {
            gui.layout(def.layout)
            def.slots.forEach { (key, slot) -> gui.makeSlot(key, slot) }
        }
    }

    fun create(kind: GuiKind, rows: Int, title: String): LiveGui {
        require(kind.hasSlots) { "Dialogs and anvils come from a file: ${kind.key}." }
        require(kind != GuiKind.CHEST || rows in 1..6) { "A chest has 1 to 6 rows." }
        return LiveGui(this, null, kind, rows, title)
    }

    fun open(player: Player, gui: LiveGui, fireOpen: Boolean = true) {
        when (gui.kind) {
            GuiKind.ANVIL -> dialogs.openAnvil(player, gui)
            GuiKind.DIALOG -> dialogs.openDialog(player, gui)
            else -> {
                navigating += player.uniqueId
                try {
                    gui.prepare(player)
                    player.openInventory(gui.inventory)
                } finally {
                    navigating -= player.uniqueId
                }
                if (fireOpen && player.uniqueId !in paused) {
                    val opened = GuiOpenEvent(player, gui)
                    Bukkit.getPluginManager().callEvent(opened)
                    gui.openHooks.forEach { it.accept(opened) }
                    run(player, gui, gui.onOpen)
                }
            }
        }
    }

    fun close(player: Player) {
        player.closeInventory()
    }

    /** Hold the open and close handlers and every slot's actions for a player, for switching screens without them firing. Always call resume. */
    fun pause(player: Player) {
        paused += player.uniqueId
    }

    fun resume(player: Player) {
        paused -= player.uniqueId
    }

    fun isPaused(player: Player) = player.uniqueId in paused

    fun run(player: Player, gui: LiveGui?, actions: List<GuiAction>, inputs: Map<String, String> = emptyMap()) {
        if (actions.isEmpty() || player.uniqueId in paused || !player.isOnline) return
        val context = inputs + mapOf("gui" to (gui?.id ?: ""))
        for (action in actions) {
            when (action) {
                is GuiAction.Command -> {
                    val line = text(player, action.text, context)
                    if (action.console) plugin.server.dispatchCommand(plugin.server.consoleSender, line) else player.performCommand(line)
                }
                is GuiAction.Message -> player.sendMessage(Colors.parse(text(player, action.text, context)))
                is GuiAction.Open -> {
                    val target = text(player, action.id, context)
                    val from = gui?.id
                    open(player, target)?.let { player.sendMessage(net.kyori.adventure.text.Component.text(it, NamedTextColor.RED)) } ?: from?.let { history.getOrPut(player.uniqueId) { ArrayDeque() }.addLast(it) }
                }
                GuiAction.Close -> player.closeInventory()
                GuiAction.Back -> back(player)
                GuiAction.Refresh -> gui?.refresh()
                is GuiAction.Sound -> try {
                    player.playSound(net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key(action.key), net.kyori.adventure.sound.Sound.Source.MASTER, action.volume, action.pitch))
                } catch (e: InvalidKeyException) {
                    plugin.logger.warning("GUI sound '${action.key}' is not a sound name.")
                }
                is GuiAction.Event -> Bukkit.getPluginManager().callEvent(GuiActionEvent(player, gui, action.name, context.filterKeys { it == "input" || it.startsWith("input.") }))
            }
        }
    }

    private fun back(player: Player) {
        val stack = history[player.uniqueId]
        val id = stack?.removeLastOrNull()
        if (id == null) {
            player.closeInventory()
            return
        }
        open(player, id, checkPermission = false)?.let { player.closeInventory() }
    }

    private val placeholders: Method? by lazy {
        if (!plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) null
        else runCatching { Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", OfflinePlayer::class.java, String::class.java) }.getOrNull()
    }

    /** {player}, {uuid}, whatever the action collected, and PlaceholderAPI's %placeholders% when it is installed. */
    fun text(player: Player?, text: String, context: Map<String, String> = emptyMap()): String {
        var out = text
        if ('{' in out) {
            if (player != null) out = out.replace("{player}", player.name).replace("{uuid}", player.uniqueId.toString())
            for ((key, value) in context) out = out.replace("{$key}", value)
        }
        val api = placeholders
        if (player != null && api != null && '%' in out) out = runCatching { api.invoke(null, player, out) as String }.getOrDefault(out)
        return out
    }

    fun build(spec: ItemSpec, viewer: Player?): ItemStack {
        val id = spec.item.trim()
        val stack = materialOf(id)?.let { ItemStack(it, spec.amount) } ?: custom(id, spec.amount) ?: ItemStack(Material.BARRIER, spec.amount).also {
            if (warned.add(id)) plugin.logger.warning("A GUI shows the item '$id', which is neither a vanilla item nor a Repack item or block.")
        }
        stack.editMeta { meta ->
            spec.name?.let { meta.displayName(Colors.plain(text(viewer, it))) }
            if (spec.lore.isNotEmpty()) meta.lore(spec.lore.map { Colors.plain(text(viewer, it)) })
            if (spec.glow) meta.setEnchantmentGlintOverride(true)
            meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        }
        spec.model?.let { model ->
            try {
                stack.setData(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(model))
            } catch (e: InvalidKeyException) {
                if (warned.add("model:$model")) plugin.logger.warning("A GUI item model '$model' is not a valid name (namespace:path).")
            }
        }
        return stack
    }

    private val warned = HashSet<String>()

    private fun materialOf(id: String): Material? = Material.matchMaterial(id.removePrefix("minecraft:"))?.takeIf { it.isItem }

    private fun custom(id: String, amount: Int): ItemStack? {
        val name = id.removePrefix("repack:").lowercase()
        plugin.customItems.definitionOf(name)?.let { return plugin.customItems.create(it, amount) }
        plugin.blocks.definitions[name]?.let { return plugin.items.create(it, amount) }
        return null
    }

    private fun tick() {
        now++
        val seen = HashSet<LiveGui>()
        for (player in plugin.server.onlinePlayers) {
            val gui = player.openInventory.topInventory.holder as? LiveGui ?: continue
            if (seen.add(gui)) gui.tick(now)
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val gui = event.view.topInventory.holder as? LiveGui ?: return
        val player = event.whoClicked as? Player ?: return
        gui.handleClick(event, player)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        (event.view.topInventory.holder as? LiveGui)?.handleDrag(event)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val gui = event.inventory.holder as? LiveGui ?: return
        val player = event.player as? Player ?: return
        if (player.uniqueId in navigating) return
        if (player.uniqueId !in paused) {
            val closing = GuiCloseEvent(player, gui)
            Bukkit.getPluginManager().callEvent(closing)
            gui.closeHooks.forEach { it.accept(closing) }
            if (closing.isCancelled) {
                plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) open(player, gui, fireOpen = false) })
                return
            }
        }
        history.remove(player.uniqueId)
        run(player, gui, gui.onClose)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        paused -= event.player.uniqueId
        history.remove(event.player.uniqueId)
        dialogs.forget(event.player)
    }
}
