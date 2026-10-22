package io.github.ratr2.repack.menu

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

class LoadedGuis(val definitions: Map<String, GuiDef>, val errors: List<String>)

object GuiLoader {

    private val ID = Regex("[a-z0-9_-]+")
    private const val MAX_DEPTH = 16

    class GuiException(message: String) : Exception(message)

    fun load(folder: File): LoadedGuis {
        val errors = ArrayList<String>()
        val raw = LinkedHashMap<String, YamlConfiguration>()
        val files = folder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val id = file.nameWithoutExtension.lowercase()
            if (!ID.matches(id)) {
                errors += "${file.name}: GUI file names may only use a-z, 0-9, _ and -."
                continue
            }
            try {
                raw[id] = YamlConfiguration().apply { load(file) }
            } catch (e: InvalidConfigurationException) {
                errors += "${file.name}: ${e.message?.lineSequence()?.firstOrNull()}"
            } catch (e: IOException) {
                errors += "${file.name}: ${e.message}"
            }
        }
        val done = LinkedHashMap<String, GuiDef>()
        val failed = HashSet<String>()
        fun resolve(id: String, stack: List<String>): GuiDef? {
            done[id]?.let { return it }
            if (id in failed) return null
            val yaml = raw[id] ?: return null
            try {
                if (id in stack) throw GuiException("templates copy each other in a circle (${(stack + id).joinToString(" > ")}).")
                if (stack.size >= MAX_DEPTH) throw GuiException("`from` goes more than $MAX_DEPTH levels deep.")
                val from = yaml.getString("from")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val parent = from?.let {
                    if (it !in raw) throw GuiException("`from: $it` names a GUI that does not exist.")
                    resolve(it, stack + id) ?: throw GuiException("`from: $it` has an error of its own.")
                }
                val def = parse(id, yaml, parent, folder)
                done[id] = def
                return def
            } catch (e: GuiException) {
                errors += "$id: ${e.message}"
            }
            failed += id
            return null
        }
        for (id in raw.keys) resolve(id, emptyList())
        return LoadedGuis(done.toSortedMap(), errors)
    }

    fun parse(id: String, yaml: ConfigurationSection, parent: GuiDef?, folder: File?): GuiDef {
        val kind = if (yaml.contains("type")) {
            GuiKind.of(yaml.getString("type").orEmpty()) ?: throw GuiException("type must be one of ${GuiKind.entries.joinToString { it.key }}.")
        } else parent?.kind ?: GuiKind.CHEST
        val rows = yaml.getInt("rows", parent?.rows ?: 3)
        if (kind == GuiKind.CHEST && rows !in 1..6) throw GuiException("rows must be 1 to 6.")
        val title = yaml.getString("title") ?: parent?.title ?: id
        val template = yaml.getBoolean("template", false)

        val layout = if (kind.hasSlots && yaml.contains("layout")) parseLayout(yaml, kind, rows) else parent?.layout?.takeIf { kind == parent.kind && rows == parent.rows }
            ?: List(kind.layoutRows(rows)) { " ".repeat(kind.width) }

        val slots = LinkedHashMap<SlotKey, SlotDef>()
        if (parent != null) slots.putAll(parent.slots)
        yaml.getConfigurationSection("slots")?.let { section ->
            for (key in section.getKeys(false)) {
                if (key.length != 1) throw GuiException("slots.$key: a slot is named by one character of the layout. For a slot number use `indexes`.")
                slots[SlotKey.Char(key[0])] = parseSlot("slots.$key", section, key)
            }
        }
        yaml.getConfigurationSection("indexes")?.let { section ->
            for (key in section.getKeys(false)) {
                val index = key.toIntOrNull()?.takeIf { it >= 0 } ?: throw GuiException("indexes.$key: a slot number is 0 or more.")
                if (kind.hasSlots && index >= kind.size(rows)) throw GuiException("indexes.$key: this GUI only has ${kind.size(rows)} slots (0 to ${kind.size(rows) - 1}).")
                slots[SlotKey.Index(index)] = parseSlot("indexes.$key", section, key)
            }
        }

        val background = if (yaml.contains("background")) parseBackground(yaml, folder) else parent?.background
        if (background != null && !kind.hasSlots) throw GuiException("a $kind GUI has no screen to put a background on.")

        val update = yaml.getInt("update", parent?.update ?: 0)
        if (update < 0) throw GuiException("update is a number of ticks, 0 or more (0 is off).")
        return GuiDef(
            id = id,
            template = template,
            kind = kind,
            title = title,
            rows = rows,
            layout = layout,
            slots = slots,
            background = background,
            removable = yaml.getBoolean("removable", parent?.removable ?: false),
            shared = yaml.getBoolean("shared", parent?.shared ?: false),
            update = update,
            permission = (if (yaml.contains("permission")) yaml.getString("permission") else parent?.permission)?.trim()?.takeIf { it.isNotEmpty() },
            onOpen = if (yaml.contains("on-open")) parseActions(yaml, "on-open") else parent?.onOpen.orEmpty(),
            onClose = if (yaml.contains("on-close")) parseActions(yaml, "on-close") else parent?.onClose.orEmpty(),
            dialog = if (kind == GuiKind.DIALOG) parseDialog(yaml, parent?.dialog) else null,
            anvil = if (kind == GuiKind.ANVIL) parseAnvil(yaml, parent?.anvil) else null,
            fallback = (if (yaml.contains("fallback")) yaml.getString("fallback") else parent?.fallback)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseLayout(yaml: ConfigurationSection, kind: GuiKind, rows: Int): List<String> {
        val given = yaml.getStringList("layout")
        val expected = kind.layoutRows(rows)
        if (given.size > expected) throw GuiException("layout has ${given.size} rows but a ${kind.key} has $expected.")
        val padded = given.map {
            if (it.length > kind.width) throw GuiException("layout row \"$it\" is longer than the ${kind.width} slots of a ${kind.key} row.")
            it.padEnd(kind.width)
        }
        return padded + List(expected - padded.size) { " ".repeat(kind.width) }
    }

    private fun parseBackground(yaml: ConfigurationSection, folder: File?): Background? {
        val value = yaml.get("background")
        if (value == null || value == false) return null
        val image: String
        val holes: HoleMode
        if (value is ConfigurationSection) {
            image = value.getString("image")?.trim().orEmpty()
            holes = HoleMode.of(value.get("holes")?.toString()) ?: throw GuiException("background.holes is off, container or all.")
        } else {
            image = value.toString().trim()
            holes = HoleMode.of(yaml.get("holes")?.toString()) ?: throw GuiException("holes is off, container or all.")
        }
        if (image.isEmpty()) throw GuiException("background needs the name of a picture.")
        if (folder != null) {
            val file = File(folder, image)
            if (file.canonicalFile.parentFile != folder.canonicalFile) throw GuiException("background must be a picture in the guis folder.")
            if (!file.isFile) throw GuiException("background '$image' is not a file in the guis folder.")
            val size = try {
                ImageIO.read(file)?.let { it.width to it.height }
            } catch (e: IOException) {
                null
            } ?: throw GuiException("background '$image' is not a picture Repack can read (use a PNG).")
            if (size.first < 16 || size.second < 16) throw GuiException("background '$image' is too small.")
        }
        return Background(image, holes)
    }

    private fun parseSlot(path: String, parent: ConfigurationSection, key: String): SlotDef {
        val section = parent.getConfigurationSection(key)
        if (section == null) {
            val text = parent.getString(key)?.trim().orEmpty()
            return SlotDef(if (text.isEmpty()) null else ItemSpec(text))
        }
        val frames = section.getList("frames").orEmpty().mapIndexed { i, entry -> parseItem("$path.frames[$i]", entry, section) }
        val item = if (section.contains("item") || section.contains("name")) parseItem(path, section, section) else frames.firstOrNull()
        val specific = LinkedHashMap<ClickKind, List<GuiAction>>()
        for (click in ClickKind.entries) {
            val name = "on-${click.key}${if (click == ClickKind.DROP) "" else "-click"}"
            if (section.contains(name)) specific[click] = parseActions(section, name)
        }
        val tags = section.getConfigurationSection("tags")?.let { tags -> tags.getKeys(false).associateWith { tags.get(it).toString() } }.orEmpty()
        val frameTicks = section.getInt("frame-ticks", 10)
        if (frameTicks < 1) throw GuiException("$path.frame-ticks must be 1 or more.")
        return SlotDef(
            item = item,
            frames = frames,
            frameTicks = frameTicks,
            removable = section.getBoolean("removable", false),
            clicks = SlotClicks(if (section.contains("on-click")) parseActions(section, "on-click") else emptyList(), specific),
            tags = tags,
        )
    }

    /** An item is a section (item, name, lore, amount, glow, model) or just the item's name. frames can use either form. */
    private fun parseItem(path: String, value: Any?, fallback: ConfigurationSection): ItemSpec {
        if (value is String) return ItemSpec(value.trim())
        val section = when (value) {
            is ConfigurationSection -> value
            is Map<*, *> -> YamlConfiguration().also { y -> value.forEach { (k, v) -> y.set(k.toString(), v) } }
            else -> throw GuiException("$path is not an item.")
        }
        val item = section.getString("item")?.trim().orEmpty()
        val amount = section.getInt("amount", 1)
        if (amount !in 1..99) throw GuiException("$path.amount must be 1 to 99.")
        if (item.isEmpty() && section.getString("model").isNullOrBlank() && section.getString("name").isNullOrBlank() && section !== fallback) {
            throw GuiException("$path needs an `item`.")
        }
        return ItemSpec(
            item = item.ifEmpty { "paper" },
            name = section.getString("name"),
            lore = strings(section, "lore"),
            amount = amount,
            glow = section.getBoolean("glow", false),
            model = section.getString("model")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun strings(section: ConfigurationSection, key: String): List<String> {
        val value = section.get(key) ?: return emptyList()
        return if (value is List<*>) value.map { it.toString() } else listOf(value.toString())
    }

    fun parseActions(section: ConfigurationSection, key: String): List<GuiAction> {
        val value = section.get(key) ?: return emptyList()
        val entries = if (value is List<*>) value else listOf(value)
        return entries.map { parseAction("$key", it) }
    }

    private fun parseAction(path: String, entry: Any?): GuiAction {
        val (name, argument) = when (entry) {
            is Map<*, *> -> {
                if (entry.size != 1) throw GuiException("$path: an action is one `name: value` pair.")
                val e = entry.entries.first()
                e.key.toString().lowercase() to e.value?.toString().orEmpty()
            }
            else -> {
                val text = entry.toString().trim()
                if (':' in text) text.substringBefore(':').trim().lowercase() to text.substringAfter(':').trim() else text.lowercase() to ""
            }
        }
        return when (name) {
            "command", "player" -> GuiAction.Command(argument.removePrefix("/"), console = false).also { if (argument.isBlank()) throw GuiException("$path: `$name` needs a command.") }
            "console" -> GuiAction.Command(argument.removePrefix("/"), console = true).also { if (argument.isBlank()) throw GuiException("$path: `console` needs a command.") }
            "message", "send" -> GuiAction.Message(argument)
            "open" -> GuiAction.Open(argument.lowercase().also { if (it.isBlank()) throw GuiException("$path: `open` needs a GUI id.") })
            "event" -> GuiAction.Event(argument.also { if (it.isBlank()) throw GuiException("$path: `event` needs a name.") })
            "close" -> GuiAction.Close
            "back" -> GuiAction.Back
            "refresh" -> GuiAction.Refresh
            "sound" -> {
                val parts = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) throw GuiException("$path: `sound` needs a sound name.")
                GuiAction.Sound(parts[0], parts.getOrNull(1)?.toFloatOrNull() ?: 1f, parts.getOrNull(2)?.toFloatOrNull() ?: 1f)
            }
            else -> throw GuiException("$path: unknown action '$name'. They are command, console, message, open, close, back, refresh, sound and event.")
        }
    }

    private fun parseAnvil(yaml: ConfigurationSection, parent: AnvilSpec?): AnvilSpec = AnvilSpec(
        initial = if (yaml.contains("initial")) yaml.getString("initial").orEmpty() else parent?.initial.orEmpty(),
        submit = if (yaml.contains("on-submit")) parseActions(yaml, "on-submit") else parent?.submit.orEmpty(),
        cancel = if (yaml.contains("on-cancel")) parseActions(yaml, "on-cancel") else parent?.cancel.orEmpty(),
    )

    private fun parseDialog(yaml: ConfigurationSection, parent: DialogSpec?): DialogSpec {
        val body = if (yaml.contains("body")) strings(yaml, "body") else parent?.body.orEmpty()
        val inputs = if (yaml.contains("inputs")) {
            val section = yaml.getConfigurationSection("inputs") ?: throw GuiException("inputs is a section of named inputs.")
            section.getKeys(false).map { key -> parseInput(key, section.getConfigurationSection(key) ?: throw GuiException("inputs.$key is not a section.")) }
        } else parent?.inputs.orEmpty()
        val buttons = if (yaml.contains("buttons")) {
            yaml.getMapList("buttons").mapIndexed { i, map -> parseButton("buttons[$i]", map) }
        } else parent?.buttons.orEmpty()
        val exit = if (yaml.contains("exit")) yaml.getConfigurationSection("exit")?.let { parseButton("exit", it.getValues(false)) } else parent?.exit
        if (buttons.isEmpty()) throw GuiException("a dialog needs at least one button (buttons: - label: OK).")
        val columns = yaml.getInt("columns", parent?.columns ?: 1)
        if (columns < 1) throw GuiException("columns must be 1 or more.")
        val keys = inputs.map { it.key }
        if (keys.size != keys.toSet().size) throw GuiException("two inputs have the same name.")
        return DialogSpec(body, inputs, buttons, exit, columns, yaml.getBoolean("escape", parent?.escape ?: true))
    }

    private fun parseButton(path: String, map: Map<*, *>): DialogButton {
        val yaml = YamlConfiguration().also { y -> map.forEach { (k, v) -> y.set(k.toString(), v) } }
        val label = yaml.getString("label")?.takeIf { it.isNotBlank() } ?: throw GuiException("$path needs a label.")
        val width = yaml.getInt("width", 150)
        if (width !in 1..1024) throw GuiException("$path.width must be 1 to 1024.")
        return DialogButton(label, yaml.getString("tooltip"), width, parseActions(yaml, "on-click"))
    }

    private fun parseInput(key: String, section: ConfigurationSection): DialogInput {
        if (!ID.matches(key.lowercase()) || key != key.lowercase()) throw GuiException("inputs.$key: names use only a-z, 0-9, _ and -.")
        val label = section.getString("label") ?: key
        val width = section.getInt("width", 200)
        return when (val type = section.getString("type", "text")!!.lowercase()) {
            "text" -> DialogInput.Text(key, label, section.getString("initial").orEmpty(), section.getInt("max-length", 64), width, section.getInt("lines", 1))
            "number" -> {
                val min = section.getDouble("min", 0.0).toFloat()
                val max = section.getDouble("max", 100.0).toFloat()
                if (min >= max) throw GuiException("inputs.$key: min must be less than max.")
                DialogInput.Number(key, label, min, max, if (section.contains("initial")) section.getDouble("initial").toFloat() else null, if (section.contains("step")) section.getDouble("step").toFloat() else null, width)
            }
            "boolean", "toggle", "checkbox" -> DialogInput.Toggle(key, label, section.getBoolean("initial", false))
            "options", "choice", "select" -> {
                val raw = section.get("options")
                val options = when (raw) {
                    is List<*> -> raw.map { it.toString() to it.toString() }
                    is ConfigurationSection -> raw.getKeys(false).map { it to (raw.getString(it) ?: it) }
                    else -> emptyList()
                }
                if (options.isEmpty()) throw GuiException("inputs.$key: options needs a list of choices.")
                DialogInput.Options(key, label, options, section.getString("initial"), width)
            }
            else -> throw GuiException("inputs.$key: type '$type' is not text, number, boolean or options.")
        }
    }
}
