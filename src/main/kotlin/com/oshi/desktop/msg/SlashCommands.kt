package com.oshi.desktop.msg

import com.oshi.desktop.i18n.t
import com.oshi.messenger.util.CashuPayCommand

/**
 * __DESKTOP_SLASH_MENU_2026_10_29__ The `/` menu above the composer — iOS's `SlashCommands`.
 *
 * The last of the three gaps PARITY row 0.0's sweep left open, and the one whose point is
 * discoverability rather than capability. **`/pay` already works on this client**:
 * `ChatShellModel` has parsed it through the shared `CashuPayCommand` since the Cashu work
 * landed. What was missing is any way to find out that it exists.
 *
 * iOS wrote its version for exactly that reason and says so: *"`/pay` existait et personne ne
 * pouvait le trouver. Une commande dont il faut vous avoir parlé est une commande que presque
 * personne n'utilise, et pour une fonctionnalité de paiement c'est la différence entre livré
 * et utilisable."* Wiring a feature is not arming it.
 *
 * ## The rules are iOS's, including the two that look like details
 *
 *  - **A space or a newline closes the menu.** `/pay 5` is a command being argued, not a
 *    command being chosen, and a list hovering over the composer while somebody types the
 *    amount is in the way.
 *  - **A bare `/` opens the FULL list.** That is the discovery gesture — the one that answers
 *    "what commands are there?" — so it must not require guessing a first letter.
 *  - **The completion ends in a space.** Most commands take an argument, and the space both
 *    saves a keystroke and closes the menu by the rule above: choosing a command must not
 *    leave the list sitting open.
 *
 * ## Deliberately short
 *
 * iOS: *"Un menu qui liste tout ce que l'app sait faire est un second écran de réglages."*
 * This offers what OSHI answers itself, plus — __DESKTOP_BOT_COMMANDS_2026_11_24__ — the
 * `/` commands of the bots that serve the conversation ([forBots]): the account's own bots
 * (`OwnedBotStore`, like iOS `BotManager.shared.bots`) and, once the server can say so,
 * other people's bots in the group (`GroupBotCommandDirectory`, endpoint missing).
 */
object SlashCommands {

    /**
     * One offered command.
     *
     * [source] is who answers it, shown beside the name so a bot command and an OSHI command
     * are never mistaken for each other once bots join the list.
     */
    data class Command(
        val command: String,
        val description: String,
        val source: String,
        /** The bot's emoji for a bot command; null for OSHI's own. */
        val emoji: String? = null,
        /** __BOTS_PHASE1_2026_11_24__ needs arguments even when its description does not say `<arg>`. */
        val needsArguments: Boolean = false,
    )

    /** __BOTS_PHASE1_2026_11_24__ What choosing a row does (contract §4.4, the same on every client). */
    enum class Pick { SEND, INSERT }

    private val ARG = Regex("<[^<>\\s][^<>]*>")

    /**
     * A click / Enter on a row SENDS the command; Tab or the row's arrow INSERTS `/name ` to add
     * arguments; a command that needs arguments (`<arg>` in its description) always inserts.
     */
    fun pickAction(c: Command, insertGesture: Boolean): Pick =
        if (insertGesture || c.needsArguments || ARG.containsMatchIn(c.description)) Pick.INSERT else Pick.SEND

    /**
     * What the user has typed after the slash, or null when the menu should be closed.
     *
     * Null for anything that is not a bare token: no leading `/`, or a space or newline
     * already typed. An empty string means a lone `/` — the discovery gesture — and is NOT
     * the same as null.
     */
    fun query(text: String): String? {
        if (!text.startsWith("/")) return null
        val body = text.drop(1)
        if (body.contains(' ') || body.contains('\n') || body.contains('\r')) return null
        return body.lowercase()
    }

    /** What OSHI answers itself. */
    fun builtIns(): List<Command> = listOf(
        Command(
            command = CashuPayCommand.PREFIX,
            // The shared catalogs carry iOS's own sentence for this. The BARE `t`, imported
            // — `CatalogAuditTest`'s suspect scan looks for a literal beside a registered
            // accessor and a fully-qualified call is not one. Third time today; the rule is
            // simply "import t, call it bare".
            description = t("cashu_pay_command_description"),
            source = "OSHI",
            // `/pay` alone is not a payment: it always takes the token after it.
            needsArguments = true,
        ),
    )

    /**
     * Commands matching what has been typed; the full list for a lone `/`. [extra] (bot
     * commands) follow the built-ins; a bot command spelled like a built-in is dropped — the
     * built-in is what the composer executes, so offering both would lie about one of them.
     * Prefix matches first, then commands that merely contain the query (Telegram's order).
     */
    fun matches(text: String, extra: List<Command> = emptyList()): List<Command> {
        val q = query(text) ?: return emptyList()
        val builtIn = builtIns().sortedBy { it.command }
        val taken = builtIn.map { it.command.lowercase() }.toMutableSet()
        val bots = extra.filter { taken.add(it.command.lowercase()) }
        val all = builtIn + bots
        if (q.isEmpty()) return all
        fun name(c: Command) = c.command.removePrefix("/").lowercase()
        val prefix = all.filter { name(it).startsWith(q) }
        return prefix + all.filter { it !in prefix && name(it).contains(q) }
    }

    /** Bot commands as menu rows, the bot's name as their source. Order: by bot, then as authored. */
    fun forBots(
        owned: List<Pair<com.oshi.desktop.bot.OwnedBot, com.oshi.desktop.bot.BotCommand>>,
        remote: List<Pair<String, com.oshi.desktop.bot.BotCommand>> = emptyList(),
    ): List<Command> =
        owned.map { (b, c) -> Command(c.slash, c.description, b.name, b.emoji) } +
            remote.map { (name, c) -> Command(c.slash, c.description, name, null) }

    /** The composer's text once a command is chosen. The trailing space is deliberate. */
    fun completion(command: Command): String = command.command + " "
}
