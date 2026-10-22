package io.github.ratr2.repack.menu

import org.bukkit.event.inventory.ClickType

/** The kinds of screen a custom GUI can be. Only a chest has a row count of its own; the others have a fixed shape. */
enum class GuiKind(val key: String, val width: Int) {
    CHEST("chest", 9),
    HOPPER("hopper", 5),
    DISPENSER("dispenser", 3),
    DROPPER("dropper", 3),
    ANVIL("anvil", 0),
    DIALOG("dialog", 0),
    ;

    val hasSlots: Boolean get() = width > 0

    fun size(rows: Int): Int = when (this) {
        CHEST -> rows * 9
        HOPPER -> 5
        DISPENSER, DROPPER -> 9
        else -> 0
    }

    fun layoutRows(rows: Int): Int = when (this) {
        CHEST -> rows
        HOPPER -> 1
        DISPENSER, DROPPER -> 3
        else -> 0
    }

    companion object {
        fun of(key: String): GuiKind? = entries.firstOrNull { it.key == key.lowercase().trim() }
    }
}

/** A slot is named by a character of the layout, or by its index in the inventory. An index beats a character. */
sealed interface SlotKey {
    data class Char(val value: kotlin.Char) : SlotKey {
        override fun toString() = "\"$value\""
    }

    data class Index(val value: Int) : SlotKey {
        override fun toString() = value.toString()
    }
}

enum class ClickKind(val key: String) {
    LEFT("left"), RIGHT("right"), SHIFT("shift"), MIDDLE("middle"), DROP("drop"),
    ;

    companion object {
        fun of(type: ClickType): ClickKind? = when (type) {
            ClickType.LEFT, ClickType.DOUBLE_CLICK -> LEFT
            ClickType.RIGHT -> RIGHT
            ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT -> SHIFT
            ClickType.MIDDLE -> MIDDLE
            ClickType.DROP, ClickType.CONTROL_DROP -> DROP
            else -> null
        }
    }
}

/** What a click, an opening or a closing does. They run one after the other, with {player}, {uuid}, {gui}, {slot} and {input.<key>} filled in. */
sealed interface GuiAction {
    data class Command(val text: String, val console: Boolean) : GuiAction
    data class Message(val text: String) : GuiAction
    data class Open(val id: String) : GuiAction
    data class Sound(val key: String, val volume: Float, val pitch: Float) : GuiAction
    data class Event(val name: String) : GuiAction
    data object Close : GuiAction
    data object Back : GuiAction
    data object Refresh : GuiAction
}

class SlotClicks(val any: List<GuiAction>, val specific: Map<ClickKind, List<GuiAction>>) {

    val isEmpty: Boolean get() = any.isEmpty() && specific.values.all { it.isEmpty() }

    fun actionsFor(click: ClickType): List<GuiAction> = ClickKind.of(click)?.let { specific[it] } ?: any

    companion object {
        val NONE = SlotClicks(emptyList(), emptyMap())
    }
}

data class ItemSpec(
    val item: String,
    val name: String? = null,
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val glow: Boolean = false,
    val model: String? = null,
)

class SlotDef(
    val item: ItemSpec?,
    val frames: List<ItemSpec> = emptyList(),
    val frameTicks: Int = 10,
    val removable: Boolean = false,
    val clicks: SlotClicks = SlotClicks.NONE,
    val tags: Map<String, String> = emptyMap(),
    /** An exact item, for a slot made in code. It is shown as it is, with no placeholders. */
    val stack: org.bukkit.inventory.ItemStack? = null,
    val handler: ((GuiClickEvent) -> Unit)? = null,
) {
    val animated: Boolean get() = frames.size > 1
}

enum class HoleMode(val key: String) {
    OFF("off"), CONTAINER("container"), ALL("all"),
    ;

    companion object {
        fun of(text: String?): HoleMode? = when (text?.lowercase()?.trim()) {
            null, "", "off", "false", "no" -> OFF
            "container", "gui" -> CONTAINER
            "all", "true", "yes", "everything" -> ALL
            else -> null
        }
    }
}

/** A picture that becomes the GUI's screen. Holes cut a transparent square over every item slot, for a picture that is painted over them. */
data class Background(val image: String, val holes: HoleMode = HoleMode.OFF)

sealed interface DialogInput {
    val key: String
    val label: String

    data class Text(override val key: String, override val label: String, val initial: String, val maxLength: Int, val width: Int, val lines: Int) : DialogInput
    data class Number(override val key: String, override val label: String, val min: Float, val max: Float, val initial: Float?, val step: Float?, val width: Int) : DialogInput
    data class Toggle(override val key: String, override val label: String, val initial: Boolean) : DialogInput
    data class Options(override val key: String, override val label: String, val options: List<Pair<String, String>>, val initial: String?, val width: Int) : DialogInput
}

class DialogButton(val label: String, val tooltip: String?, val width: Int, val actions: List<GuiAction>)

class DialogSpec(
    val body: List<String>,
    val inputs: List<DialogInput>,
    val buttons: List<DialogButton>,
    val exit: DialogButton?,
    val columns: Int,
    val escape: Boolean,
)

class AnvilSpec(val initial: String, val submit: List<GuiAction>, val cancel: List<GuiAction>)

/**
 * What a GUI file says. A template is only ever copied from (`from:`), never opened.
 * layout is one string per row, a character per slot: every character slots define sticks, whether or not the layout uses it yet.
 */
class GuiDef(
    val id: String,
    val template: Boolean,
    val kind: GuiKind,
    val title: String,
    val rows: Int,
    val layout: List<String>,
    val slots: Map<SlotKey, SlotDef>,
    val background: Background?,
    val removable: Boolean,
    val shared: Boolean,
    val update: Int,
    val permission: String?,
    val onOpen: List<GuiAction>,
    val onClose: List<GuiAction>,
    val dialog: DialogSpec?,
    val anvil: AnvilSpec?,
    val fallback: String?,
)
