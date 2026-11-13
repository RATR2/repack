package io.github.ratr2.repack.menu

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData
import com.github.retrooper.packetevents.protocol.dialog.DialogAction
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.Input
import com.github.retrooper.packetevents.protocol.dialog.input.InputControl
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl
import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import io.github.ratr2.repack.text.Colors
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The two screens that are not inventories. A dialog is the game's own (1.21.6 and newer, no pack needed): its buttons send a custom
 * click action back, carrying what the inputs held. The anvil asks for a line of text.
 */
class GuiDialogs(private val manager: GuiManager) {

    private class Session(val gui: LiveGui, val spec: DialogSpec, val token: String, val created: Long = System.currentTimeMillis())

    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val listener = object : PacketListenerAbstract() {
        override fun onPacketReceive(event: PacketReceiveEvent) {
            if (event.packetType != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return
            val uuid = event.user?.uuid ?: return
            val wrapper = WrapperPlayClientCustomClickAction(event)
            if (wrapper.id != ACTION) return
            val payload = wrapper.payload as? NBTCompound ?: return
            val session = sessions[uuid] ?: return
            // A dialog that has been open for half an hour is not answered: the token is for the dialog the player was shown, not for good.
            if (System.currentTimeMillis() - session.created > SESSION_MILLIS) {
                sessions.remove(uuid, session)
                return
            }
            if (payload.getStringTagValueOrNull("token") != session.token) return
            val button = payload.getStringTagValueOrNull("button") ?: return
            val values = readInputs(session.spec, payload)
            manager.plugin.server.scheduler.runTask(manager.plugin, Runnable {
                val player = manager.plugin.server.getPlayer(uuid) ?: return@Runnable
                if (sessions[uuid]?.token != session.token) return@Runnable
                sessions.remove(uuid)
                val actions = if (button == EXIT) session.spec.exit?.actions else session.spec.buttons.getOrNull(button.toIntOrNull() ?: -1)?.actions
                manager.run(player, session.gui, actions.orEmpty(), values)
            })
        }
    }

    fun register() {
        PacketEvents.getAPI().eventManager.registerListener(listener)
    }

    fun unregister() {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
    }

    fun forget(player: Player) {
        sessions.remove(player.uniqueId)
    }

    fun supported(player: Player): Boolean =
        PacketEvents.getAPI().serverManager.version.protocolVersion >= DIALOG_PROTOCOL && manager.plugin.clientVersions.protocolOf(player) >= DIALOG_PROTOCOL

    /**
     * Switching from a chest GUI to an anvil or a dialog closes the chest on the client, which is not the player leaving it: for the moment it takes, the chest's
     * close handlers (and its back history) are left alone, as they are when one GUI opens another.
     */
    private fun switching(player: Player, ticks: Long) {
        val id = player.uniqueId
        manager.navigating += id
        manager.plugin.server.scheduler.runTaskLater(manager.plugin, Runnable { manager.navigating -= id }, ticks)
    }

    fun openAnvil(player: Player, gui: LiveGui) {
        val spec = gui.definition?.anvil ?: return
        switching(player, ANVIL_SWITCH_TICKS)
        manager.plugin.gui.askText(
            player, manager.text(player, gui.title), manager.text(player, spec.initial),
            accept = { text -> manager.run(player, gui, spec.submit, mapOf("input" to text)) },
            cancel = { manager.run(player, gui, spec.cancel) },
        )
    }

    fun openDialog(player: Player, gui: LiveGui) {
        val spec = gui.definition?.dialog ?: return
        if (!supported(player)) {
            val fallback = gui.definition.fallback
            if (fallback != null && manager.open(player, fallback) == null) return
            player.sendMessage(net.kyori.adventure.text.Component.text("This screen needs Minecraft 1.21.6 or newer.", net.kyori.adventure.text.format.NamedTextColor.RED))
            return
        }
        switching(player, DIALOG_SWITCH_TICKS)
        val token = UUID.randomUUID().toString()
        sessions[player.uniqueId] = Session(gui, spec, token)
        fun text(s: String) = Colors.parse(manager.text(player, s))
        fun button(b: DialogButton, id: String) = ActionButton(
            CommonButtonData(text(b.label), b.tooltip?.let(::text), b.width),
            DynamicCustomAction(ACTION, NBTCompound().also {
                it.setTag("token", NBTString(token))
                it.setTag("button", NBTString(id))
            }),
        )
        val body: List<DialogBody> = spec.body.map { PlainMessageDialogBody(PlainMessage(text(it), 300)) }
        val inputs = spec.inputs.map { Input(it.key, control(it, ::text)) }
        val common = CommonDialogData(text(gui.title), null, spec.escape, false, DialogAction.CLOSE, body, inputs)
        val dialog = MultiActionDialog(common, spec.buttons.mapIndexed { i, b -> button(b, i.toString()) }, spec.exit?.let { button(it, EXIT) }, spec.columns)
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerShowDialog(dialog))
    }

    private fun control(input: DialogInput, text: (String) -> net.kyori.adventure.text.Component): InputControl = when (input) {
        is DialogInput.Text -> TextInputControl(
            input.width, text(input.label), true, input.initial, input.maxLength,
            if (input.lines > 1) TextInputControl.MultilineOptions(input.lines, null) else null,
        )
        is DialogInput.Number -> NumberRangeInputControl(input.width, text(input.label), "options.generic_value", NumberRangeInputControl.RangeInfo(input.min, input.max, input.initial, input.step))
        is DialogInput.Toggle -> BooleanInputControl(text(input.label), input.initial, "true", "false")
        is DialogInput.Options -> SingleOptionInputControl(
            input.width,
            input.options.map { (id, label) -> SingleOptionInputControl.Entry(id, text(label), id == input.initial) },
            text(input.label), true,
        )
    }

    /** What the player left in each input, as text: {input.<name>} in the button's actions. */
    private fun readInputs(spec: DialogSpec, payload: NBTCompound): Map<String, String> {
        val values = HashMap<String, String>()
        for (input in spec.inputs) {
            val tag: NBT = payload.getTagOrNull(input.key) ?: continue
            clean(input, tag)?.let { values["input.${input.key}"] = it }
        }
        return values
    }

    companion object {
        /**
         * What a client sent for an input, held to what the dialog offered: the client is trusted with nothing, so a modified one cannot send a number past the
         * range, text longer than the input takes, or a choice that was never offered. Null (the input is left out) when the value is not valid at all.
         */
        fun clean(input: DialogInput, tag: NBT): String? = when (input) {
            is DialogInput.Text -> (tag as? NBTString)?.value?.take(input.maxLength)
            is DialogInput.Toggle -> (tag as? NBTNumber)?.let { (it.asInt != 0).toString() } ?: (tag as? NBTString)?.value?.takeIf { it == "true" || it == "false" }
            is DialogInput.Options -> (tag as? NBTString)?.value?.takeIf { value -> input.options.any { it.first == value } }
            is DialogInput.Number -> (tag as? NBTNumber)?.asDouble?.takeIf { it.isFinite() }?.coerceIn(input.min.toDouble(), input.max.toDouble())
                ?.let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() }
        }

        private const val SESSION_MILLIS = 30L * 60 * 1000
        private const val ANVIL_SWITCH_TICKS = 4L
        private const val DIALOG_SWITCH_TICKS = 20L
        private val ACTION = ResourceLocation("repack", "gui")
        private const val EXIT = "exit"

        /** 1.21.6, the first game version with dialogs. */
        private const val DIALOG_PROTOCOL = 771
    }
}
