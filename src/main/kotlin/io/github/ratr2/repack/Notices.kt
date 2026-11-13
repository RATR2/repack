package io.github.ratr2.repack

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID

/** One kind of chat notice. The [id] names its settings (`notices.<id>` in config.yml, `notice-<id>` for a player); [label] and [note] are for the screens and the command. */
class Notice(val id: String, val label: String, val note: String, val message: String)

/** Where the switches of the notices are kept, so the rules can be checked without a server. */
interface NoticeSettings {
    /** A server setting from config.yml. */
    fun server(key: String, default: Boolean): Boolean

    /** A player's own setting; true when they never set it. */
    fun player(player: UUID, key: String): Boolean

    fun setPlayer(player: UUID, key: String, value: Boolean)
}

/**
 * The notices Repack chats at players, in one place. Add a notice with [register] (the built-in ones are in [BUILT_IN]) and send it with [send]: whether it
 * reaches a player is decided here, by four switches that all have to be on. The server's `client-notices` (all notices) and `notices.<id>` (this one), and the
 * player's own `notices` (all of theirs) and `notice-<id>` (this one). Players set theirs with `/repack notices` or in their settings screen.
 */
class Notices(private val settings: NoticeSettings) {

    private val registry = LinkedHashMap<String, Notice>()

    init {
        BUILT_IN.forEach(::register)
    }

    val all: List<Notice> get() = registry.values.toList()

    fun get(id: String?): Notice? = registry[id?.trim()?.lowercase()]

    /** Adds a kind of notice; a second one with the same id is refused. */
    fun register(notice: Notice) {
        require(ID.matches(notice.id)) { "a notice id uses a-z, 0-9 and - only" }
        require(notice.id !in registry) { "there is already a notice '${notice.id}'" }
        registry[notice.id] = notice
    }

    /** Whether the server lets this notice be sent at all. */
    fun serverAllows(notice: Notice): Boolean = settings.server(MASTER_KEY, true) && settings.server("notices.${notice.id}", true)

    /** Whether the player has this notice on (and notices in general). */
    fun playerAllows(player: UUID, notice: Notice): Boolean = settings.player(player, PlayerSettings.NOTICES) && settings.player(player, key(notice))

    fun allowed(player: UUID, notice: Notice): Boolean = serverAllows(notice) && playerAllows(player, notice)

    /** Chats the notice to the player if every switch is on. Returns whether it was sent. */
    fun send(player: Player, notice: Notice): Boolean {
        if (!allowed(player.uniqueId, notice)) return false
        player.sendMessage(Component.text(notice.message, NamedTextColor.YELLOW))
        return true
    }

    fun send(player: Player, id: String): Boolean = get(id)?.let { send(player, it) } ?: false

    /** Switches one notice on or off for a player, or all of them (a null [notice]). */
    fun set(player: UUID, notice: Notice?, on: Boolean) = settings.setPlayer(player, notice?.let(::key) ?: PlayerSettings.NOTICES, on)

    private fun key(notice: Notice) = "notice-${notice.id}"

    companion object {
        const val MASTER_KEY = "client-notices"
        private val ID = Regex("[a-z0-9-]+")

        val VERSION = Notice("version", "Game version", "Your game version is too old for Repack's blocks", "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
        val SHADERPACKS = Notice("shaderpacks", "Shaderpacks", "Shaderpacks do not work with this server", "Shaderpacks are not compatible with this server, if you have one on, please disable it.")
        val FABULOUS = Notice("fabulous", "Fabulous graphics", "Some effects need Fabulous! graphics on your version", "On your client version, distortion effects only show with Fabulous! graphics (Video Settings > Graphics).")

        val BUILT_IN = listOf(VERSION, SHADERPACKS, FABULOUS)
    }
}
