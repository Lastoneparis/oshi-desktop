package com.oshi.desktop.bot

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bot command grammar and template renderer — PARITY.md row 0.26.
 *
 * The template half is checked against **assertions that ship in the Android tree**:
 * `OSHI-Android/app/src/test/java/com/oshi/messenger/BotTemplateParityTest.kt` pins five
 * exact strings, including the sentinel value `now = 1_700_000_000_000L`. Those are
 * reproduced here verbatim so this renderer is measured against a shipped vector rather than
 * against itself — the discipline PARITY.md working rule 2 asks for, and the one case in
 * these two rows where a shipped test exists to borrow.
 */
class BotTriggersTest {

    /** The exact sentinel from `BotTemplateParityTest.kt:15`. */
    private val now = 1_700_000_000_000L

    // ============================================================ SHIPPED VECTORS

    /** `BotTemplateParityTest.ios variables are substituted` (`:18-25`). */
    @Test
    fun `shipped vector - ios variables are substituted`() {
        assertEquals(
            "Helper says: ping @1700000000",
            BotTriggers.renderTemplate(
                "{{bot_name}} says: {{input}} @{{timestamp}}",
                botName = "Helper", input = "ping", senderKey = "SENDERKEY", nowMillis = now,
            ),
        )
    }

    /** `BotTemplateParityTest.legacy android variables still work as aliases` (`:34-41`). */
    @Test
    fun `shipped vector - android legacy aliases still render`() {
        assertEquals(
            "hi from ABCDEFGH / ABCDEFGH",
            BotTriggers.renderTemplate(
                "{{message}} from {{sender}} / {{member}}",
                botName = "B", input = "hi", senderKey = "ABCDEFGHIJKL", nowMillis = now,
            ),
        )
    }

    /** `BotTemplateParityTest.an ios authored template no longer renders literally` (`:43-49`). */
    @Test
    fun `shipped vector - an ios authored template does not render literally`() {
        val out = BotTriggers.renderTemplate(
            "Hi! I am {{bot_name}}. You said: {{input}}", "Bot", "hello", "K", now
        )
        assertFalse(out.contains("{{input}}"))
        assertFalse(out.contains("{{bot_name}}"))
        assertEquals("Hi! I am Bot. You said: hello", out)
    }

    /** `BotTemplateParityTest.unknown placeholders are left untouched` (`:51-55`). */
    @Test
    fun `shipped vector - unknown placeholders are left verbatim and not blanked`() {
        assertEquals("{{nope}}", BotTriggers.renderTemplate("{{nope}}", "B", "x", "k", now))
    }

    /**
     * `BotTemplateParityTest.date variable is replaced and leaves no placeholder` (`:27-32`).
     *
     * Pinned to UTC and `Locale.US` here — the shipped test leaves both to the machine,
     * which is a test that can pass on one runner and fail on another. Same assertion,
     * plus the literal it renders to under a fixed zone, which is the part that could not be
     * asserted while the zone was ambient.
     */
    @Test
    fun `shipped vector - the date variable leaves no placeholder behind`() {
        val out = BotTriggers.renderTemplate(
            "today is {{date}}", "B", "", "", now,
            zone = TimeZone.getTimeZone("UTC"), locale = Locale.US,
        )
        assertFalse(out.contains("{{date}}"))
        assertTrue(out.startsWith("today is "))
        assertEquals("today is 14 Nov 2023, 22:13", out)
    }

    // ============================================================ TRIGGER TYPES

    /**
     * The two member-event spellings do not match across platforms, and both are accepted.
     *
     * `memberJoin` (iOS, `BotManager.swift:75`) vs `member_join` (Android,
     * `BotManager.kt:254`). A trigger authored on one platform is an unknown type on the
     * other and silently never fires. Refusing either spelling here would reproduce the bug.
     */
    @Test
    fun `both platform spellings of the member events parse to one type`() {
        assertEquals(BotTriggers.TriggerType.MEMBER_JOIN, BotTriggers.TriggerType.parse("memberJoin"))
        assertEquals(BotTriggers.TriggerType.MEMBER_JOIN, BotTriggers.TriggerType.parse("member_join"))
        assertEquals(BotTriggers.TriggerType.MEMBER_LEAVE, BotTriggers.TriggerType.parse("memberLeave"))
        assertEquals(BotTriggers.TriggerType.MEMBER_LEAVE, BotTriggers.TriggerType.parse("member_leave"))
        // We EMIT the iOS spelling, since iOS is where the engine actually runs.
        assertEquals("memberJoin", BotTriggers.TriggerType.MEMBER_JOIN.wire)
        assertNull("no case folding — neither platform writes MemberJoin",
            BotTriggers.TriggerType.parse("MemberJoin"))
    }

    /** Keyword matching is case-insensitive on both platforms. */
    @Test
    fun `a keyword trigger is case insensitive`() {
        val t = trigger(BotTriggers.TriggerType.KEYWORD, "deploy")
        assertTrue(BotTriggers.shouldFire(t, "please DEPLOY it now"))
        assertTrue(BotTriggers.shouldFire(t, "deploy"))
        assertFalse(BotTriggers.shouldFire(t, "nothing to see"))
    }

    /**
     * A command trigger takes iOS's rules: a missing `/` is prepended, the message is
     * trimmed, and the comparison is case-INsensitive.
     *
     * Android does none of the three (`BotManager.kt:252`), so on Android `/HELP` does not
     * fire a `/help` trigger and a condition stored as `help` never fires at all.
     */
    @Test
    fun `a command trigger normalises the slash trims and folds case`() {
        val slashless = trigger(BotTriggers.TriggerType.COMMAND, "help")
        assertTrue("a stored condition without a slash still fires", BotTriggers.shouldFire(slashless, "/help me"))

        val withSlash = trigger(BotTriggers.TriggerType.COMMAND, "/help")
        assertTrue("case folded", BotTriggers.shouldFire(withSlash, "/HELP"))
        assertTrue("leading whitespace trimmed", BotTriggers.shouldFire(withSlash, "   /help"))
        assertFalse("must be a PREFIX, not a substring", BotTriggers.shouldFire(withSlash, "say /help"))
    }

    /** Regex is case-insensitive (iOS's `.caseInsensitive`); Android's is not. */
    @Test
    fun `a regex trigger is case insensitive and a bad pattern is quiet`() {
        assertTrue(BotTriggers.shouldFire(trigger(BotTriggers.TriggerType.REGEX, "^bu[gG]"), "BUG report"))
        assertFalse(
            "an uncompilable pattern must not throw — iOS's try? swallows it too",
            BotTriggers.shouldFire(trigger(BotTriggers.TriggerType.REGEX, "([unclosed"), "anything"),
        )
    }

    /**
     * Member events use Android's EXACT-equality rule, not iOS's `contains`.
     *
     * This is the one rule where the two platforms differ in SECURITY rather than
     * convenience: under iOS's `content.contains("[MEMBER_JOIN]")` any member can fire
     * another member's welcome bot by typing the sentinel inside an ordinary message. Exact
     * equality cannot be spoofed that way, so the stricter side wins.
     */
    @Test
    fun `a member-join trigger cannot be spoofed from inside an ordinary message`() {
        val t = trigger(BotTriggers.TriggerType.MEMBER_JOIN, "member_join")
        assertTrue(BotTriggers.shouldFire(t, "[MEMBER_JOIN]"))
        assertFalse(
            "iOS would fire on this — that is the spoof",
            BotTriggers.shouldFire(t, "hey everyone [MEMBER_JOIN] lol"),
        )
    }

    /** A schedule trigger never matches message text on either platform. */
    @Test
    fun `a schedule trigger never fires on content`() {
        assertFalse(BotTriggers.shouldFire(trigger(BotTriggers.TriggerType.SCHEDULE, "3600"), "3600"))
    }

    /** An inactive trigger never fires, whatever its type. */
    @Test
    fun `an inactive trigger never fires`() {
        val t = trigger(BotTriggers.TriggerType.KEYWORD, "deploy").copy(active = false)
        assertFalse(BotTriggers.shouldFire(t, "deploy"))
    }

    // ============================================================ THE SCHEDULE UNIT

    /**
     * The shipped `reminder` template's `"86400"` is a DAY here, not 86.4 seconds.
     *
     * iOS reads seconds (`BotManager.swift:778`), Android reads milliseconds
     * (`BotManager.kt:520`) — a factor of 1000 on the same stored bot.
     */
    @Test
    fun `the shipped reminder interval is a day in iOS units`() {
        assertEquals(86_400L, BotTriggers.parseScheduleSeconds("86400"))
        assertEquals("the template really does say 86400", "86400", BotTemplates.byKey("reminder")!!.condition)
    }

    /** iOS's floor of 60 s, and its `interval > 0` skip. */
    @Test
    fun `a sub-minute interval is floored and a non-positive one is skipped`() {
        assertEquals(60L, BotTriggers.parseScheduleSeconds("5"))
        assertNull("iOS skips the trigger entirely rather than scheduling it", BotTriggers.parseScheduleSeconds("0"))
        assertNull(BotTriggers.parseScheduleSeconds("-10"))
        assertNull(BotTriggers.parseScheduleSeconds("hourly"))
    }

    /** Android's named aliases still resolve, so an Android-authored condition works. */
    @Test
    fun `android named intervals resolve`() {
        assertEquals(900L, BotTriggers.parseScheduleSeconds("every_15min"))
        assertEquals(3600L, BotTriggers.parseScheduleSeconds("EVERY_HOUR"))
        assertEquals(86_400L, BotTriggers.parseScheduleSeconds(" every_day "))
    }

    // ============================================================ COMMAND SNIFFING

    /** iOS's four conditions, including the "second character must be a letter" one. */
    @Test
    fun `looksLikeACommand needs a slash length and a letter`() {
        assertTrue(BotTriggers.looksLikeACommand("/help"))
        assertTrue(BotTriggers.looksLikeACommand("  /help me  "))
        assertFalse("bare slash", BotTriggers.looksLikeACommand("/"))
        assertFalse("second char is not a letter", BotTriggers.looksLikeACommand("//x"))
        assertFalse("second char is not a letter", BotTriggers.looksLikeACommand("/1password"))
        assertFalse(BotTriggers.looksLikeACommand("help"))
    }

    // ============================================================ TEMPLATES

    /**
     * The welcome template's condition is DEAD: it says `member_join`, while the matcher
     * tests against the `[MEMBER_JOIN]` sentinel and never reads the condition at all.
     *
     * Reproduced verbatim, and asserted here so nobody "fixes" it into the sentinel and
     * changes what an exported template carries.
     */
    @Test
    fun `the welcome template's condition is the dead string and not the sentinel`() {
        val welcome = BotTemplates.byKey("welcome")!!
        assertEquals("member_join", welcome.condition)
        assertFalse(welcome.condition == BotTriggers.MEMBER_JOIN_SENTINEL)
        // And it fires on the sentinel regardless of the condition.
        assertTrue(BotTriggers.shouldFire(
            BotTriggers.Trigger(welcome.triggerType!!, welcome.condition, ""),
            BotTriggers.MEMBER_JOIN_SENTINEL,
        ))
    }

    /** `/echo hello` replies `/echo hello` — there is no argument parser. */
    @Test
    fun `the echo template returns the whole raw message including the command`() {
        val echo = BotTemplates.byKey("echo")!!
        assertEquals("{{input}}", echo.responseTemplate)
        assertEquals(
            "/echo hello world",
            BotTriggers.renderTemplate(echo.responseTemplate, "Echo", "/echo hello world", "k", now),
        )
    }

    /** Only the webhook template registers server-side; the other five are local-only. */
    @Test
    fun `only the webhook template has a server presence`() {
        assertEquals(6, BotTemplates.ALL.size)
        assertEquals(listOf("webhook"), BotTemplates.ALL.filter { it.botType == "webhook" }.map { it.key })
        assertNull("the webhook template ships with no triggers", BotTemplates.byKey("webhook")!!.triggerType)
    }

    private fun trigger(type: BotTriggers.TriggerType, condition: String) =
        BotTriggers.Trigger(type, condition, "response")
}
