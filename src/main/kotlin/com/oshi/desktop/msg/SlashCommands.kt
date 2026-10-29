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
 * This offers what OSHI answers itself. **Bot-authored `command` triggers are NOT here yet**,
 * and that is a scope statement rather than an oversight: this client's bots live behind
 * `BotApi` and the window holds no bot list to read triggers from, where iOS hands its menu
 * `BotManager.shared.bots`. The engine exists either way — `BotTriggers` already implements
 * the `command` type, including iOS's leading-slash normalisation — so when the model carries
 * bots, they join the list here and nothing else changes.
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
    )

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
        ),
    )

    /** Commands matching what has been typed; the full list for a lone `/`. */
    fun matches(text: String): List<Command> {
        val q = query(text) ?: return emptyList()
        val all = builtIns().sortedBy { it.command }
        if (q.isEmpty()) return all
        return all.filter { it.command.removePrefix("/").lowercase().startsWith(q) }
    }

    /** The composer's text once a command is chosen. The trailing space is deliberate. */
    fun completion(command: Command): String = command.command + " "
}
