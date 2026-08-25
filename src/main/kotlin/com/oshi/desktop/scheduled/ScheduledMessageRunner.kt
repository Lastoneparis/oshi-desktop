package com.oshi.desktop.scheduled

import java.util.UUID

/**
 * The thing that actually sends a scheduled message — PARITY.md row 0.25.
 *
 * ============================================================ WHAT HAPPENS IF THE APP IS NOT RUNNING
 *
 * The brief's question, answered for all three platforms, because the answers differ and
 * only one of them is "nothing".
 *
 * **iOS: three mechanisms, one of which is not what it looks like.**
 *   1. A 30-second `Timer` while the app is alive (`ScheduledMessageManager.swift:239-247`).
 *   2. A `BGAppRefreshTaskRequest` with `earliestBeginDate = scheduledTime - 60s`
 *      (`swift:128-147`), identifier `com.moriceau.oshi.scheduled-message-delivery`. This is
 *      the "even if the app is killed" path (`swift:155`) and it is **best-effort by
 *      contract** — iOS decides whether and when to run it based on usage patterns, power
 *      and network, and it will not run at all if the user force-quit the app. `earliestBeginDate`
 *      is a floor, not an appointment.
 *   3. A `UNCalendarNotificationTrigger` local notification at the scheduled time
 *      (`swift:250-266`). This is the one that looks like delivery and is not: its title and
 *      body are `scheduled_msg.notification.*` shown to the **SENDER**. It sends no message.
 *      It fires reliably where mechanism 2 does not, so on a phone that missed the BGTask
 *      the user gets a notification saying the message is due, opens the app, and the
 *      30-second timer's first tick sends it — late, at open time.
 *   And on launch, `loadMessages()` + `startMonitor()` (`swift:85-88`) means the first tick
 *      after any launch sweeps everything overdue. So the true iOS answer is: **it is sent
 *      late, when the app next runs**, unless the BGTask happened to fire.
 *
 * **Android: WorkManager, which really does survive.** A `OneTimeWorkRequest` with
 * `setInitialDelay(scheduledTime - now)` (`.kt:259-282`) is persisted by WorkManager across
 * process death and reboot, and `ScheduledMessageWorker` runs without the UI. Doze can
 * delay it, but it is a genuine scheduled wake, not a hope. Android is the only one of the
 * three that can send while the app is not running.
 *
 * **This desktop client: it cannot, and does not pretend to.** PARITY.md row 2.3 already
 * states the shape of this — *"a desktop client POLLS `/v2/messages`… no wake-from-sleep
 * delivery"* — and sending is the same story from the other end. There is no cross-platform
 * JDK affordance to wake a stopped process at a wall-clock time. What exists on each OS is
 * a *different* OS-level scheduler (Task Scheduler, systemd timers or cron, launchd), each
 * requiring an installed unit and a separate headless entry point, and none of which this
 * client has. So:
 *
 *   **On this client, a scheduled message is sent by the first [runDue] after it comes
 *   due.** If the process is running, that is within one poll interval. If the process is
 *   not running, it is whenever the user next starts it — which may be days. A message
 *   scheduled for a birthday on a laptop that stays shut arrives when the laptop opens.
 *
 * That has to be said in the UI wherever a user picks a time, in the same way PARITY.md row
 * 0.16 requires "OSHI works with no internet" not to be claimed of the desktop. A time
 * picker that looks like a phone's and behaves like a reminder is a promise the client
 * cannot keep. [Outcome.LATE] exists so a caller can tell the user how late, rather than
 * quietly pretending it was on time — see [DueRun.lateByMs].
 *
 * ============================================================ THE SEND SEAM
 *
 * [Sender] is an interface with three answers, not a boolean, because both shipped
 * platforms learned that the hard way and only one of them acted on it:
 *
 *   - iOS distinguishes `.rateLimited` (leave PENDING, the monitor retries), `.tooLong`
 *     (FAILED), and sent (`swift:200-224`), and its comment says exactly why: *"Marking a
 *     refused message `.sent` and dropping it is exactly the silent loss this status field
 *     exists to prevent."*
 *   - Android collapses everything into `sendResult.isSuccess` and marks FAILED on
 *     anything else (`.kt:397-407`), then asks WorkManager to retry a row it has already
 *     marked FAILED — and `getContentToSend` (`.kt:243-245`) will happily hand the body
 *     over on that retry, because it never looks at the status.
 *
 * This client takes iOS's side: a refusal that might succeed later leaves the row PENDING
 * and increments [ScheduledMessage.attempts]; a refusal that cannot succeed is terminal.
 *
 * ============================================================ THE STATUS GUARD
 *
 * [runDue] sends only what [ScheduledMessageStore.due] returns, and `due` is PENDING-only
 * ([ScheduledMessage.isDue]). That is the guard against Android's cancel race described on
 * [ScheduledMessageStore.settle]: there is no path in this package that reads a message
 * body for sending without having gone through a PENDING check, and [ScheduledMessageStore]
 * refuses a `settle` on a row that is no longer PENDING, so a cancel that lands between the
 * `due` snapshot and the send cannot be overwritten by the send's own bookkeeping.
 *
 * ============================================================ IDEMPOTENCE WITHIN A PASS
 *
 * A message is attempted at most once per [runDue], enforced by [attemptedThisRun] rather
 * than by trusting the store snapshot. iOS needs the same thing for a different reason —
 * `sentDuringBGTask` (`swift:104-113`, "FIX R22") exists because a BGTask expiry handler can
 * re-enter the send loop — and its bug shape is the one worth avoiding: that set is cleared
 * at the start of each BGTask (`swift:110`) and never by the timer path, so it protects one
 * of the two entry points. Here there is one entry point and the set is per-call.
 */
class ScheduledMessageRunner(
    private val store: ScheduledMessageStore,
    private val sender: Sender,
    /**
     * How late a send may be before [DueRun] flags it. Not a policy on whether to send —
     * a late message is still sent, always; the shipped clients do the same and the
     * alternative (silently dropping a message the user queued) is worse than a late one.
     * It is the threshold at which a caller should TELL the user.
     */
    private val latenessWarnMs: Long = DEFAULT_LATENESS_WARN_MS,
) {

    /** What a send attempt reported. See THE SEND SEAM. */
    enum class Outcome {
        /** Delivered. */
        SENT,

        /** Delivered, but later than [latenessWarnMs] past its scheduled time. */
        LATE,

        /** Refused for a reason that may pass — rate limit, offline, no session yet. */
        DEFERRED,

        /** Refused permanently — body too long, recipient blocked, malformed address. */
        FAILED,
    }

    /**
     * The send seam. Returns [Outcome.SENT], [Outcome.DEFERRED] or [Outcome.FAILED];
     * [Outcome.LATE] is computed by the runner, not reported by the sender, because
     * lateness is a property of the clock and not of the transport.
     *
     * Implementations MUST NOT throw. A throwing sender is caught and treated as
     * [Outcome.DEFERRED] — an exception is by definition an unclassified failure, and the
     * safe reading of an unclassified failure is "might work next time", since the
     * alternative marks a message FAILED forever on one transient stack trace.
     */
    fun interface Sender {
        fun send(message: ScheduledMessage): Outcome
    }

    data class DueRun(
        val attempted: Int,
        val sent: Int,
        val deferred: Int,
        val failed: Int,
        /** Largest gap between a sent message's scheduled time and when it actually went. */
        val lateByMs: Long,
    ) {
        val wasLate: Boolean get() = lateByMs > 0
    }

    /**
     * One sweep. Idempotent, cheap when nothing is due, and safe to call from a poll loop,
     * on launch, and on window focus — the three moments iOS's monitor covers between
     * `startMonitor` and `loadMessages`.
     */
    fun runDue(nowMs: Long = System.currentTimeMillis()): DueRun {
        val attemptedThisRun = HashSet<String>()
        var sent = 0
        var deferred = 0
        var failed = 0
        var worstLate = 0L

        for (m in store.due(nowMs)) {
            if (!attemptedThisRun.add(m.id)) continue

            // Re-read under the store's lock: `due` returned a snapshot, and a cancel may
            // have landed since. This is the check Android's worker does not do.
            val current = store.get(m.id) ?: continue
            if (current.status != ScheduledMessage.Status.PENDING) continue

            val outcome = try {
                sender.send(current)
            } catch (e: Exception) {
                Outcome.DEFERRED
            }

            when (outcome) {
                Outcome.SENT, Outcome.LATE -> {
                    if (store.settle(current.id, ScheduledMessage.Status.SENT, current.attempts + 1)) {
                        sent++
                        val late = nowMs - current.scheduledAtMs
                        if (late > latenessWarnMs && late > worstLate) worstLate = late
                    }
                }
                Outcome.DEFERRED -> { store.retryLater(current.id); deferred++ }
                Outcome.FAILED -> {
                    if (store.settle(current.id, ScheduledMessage.Status.FAILED, current.attempts + 1)) failed++
                }
            }
        }
        return DueRun(attemptedThisRun.size, sent, deferred, failed, worstLate)
    }

    /**
     * Queue a message. [scheduledAtMs] is Unix millis — see [ScheduledMessage]'s THE EPOCH.
     *
     * **A time in the past is refused**, where both phones accept it and send immediately
     * (iOS's `sendOverdueMessages` picks it up on the next 30-second tick; Android logs
     * *"scheduled in the past — delivering immediately"* and clamps the delay to zero,
     * `.kt:260-263`, `:270`). Refusing is the stricter option and PARITY.md's working rule 2
     * says the stricter option wins where the platforms disagree — but the real reason is
     * that on THIS client the two cases are indistinguishable at the call site. A desktop
     * that has been closed for a week has a clock that agrees with the world and a schedule
     * full of past times; a user picking "yesterday" in a date field has made a mistake. If
     * the caller means "send now", it should send now, through the ordinary send path,
     * rather than through a queue whose whole contract is that delivery is deferred.
     */
    fun schedule(
        recipient: String,
        content: String,
        scheduledAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        isGroup: Boolean = false,
        tone: ScheduledMessage.Tone = ScheduledMessage.Tone.CASUAL,
        aiGenerated: Boolean = false,
        id: String = UUID.randomUUID().toString(),
    ): ScheduledMessage {
        require(scheduledAtMs > nowMs) {
            "scheduled time ${scheduledAtMs}ms is not in the future (now=${nowMs}ms). " +
                "A desktop client sends on its next poll, so a past time is either a clock " +
                "problem or a user error — send now through the ordinary send path instead."
        }
        require(content.isNotEmpty()) { "refusing to schedule an empty message body" }
        return store.put(
            ScheduledMessage(
                id = id,
                recipient = recipient,
                content = content,
                scheduledAtMs = scheduledAtMs,
                createdAtMs = nowMs,
                isGroup = isGroup,
                tone = tone,
                aiGenerated = aiGenerated,
            )
        )
    }

    /** Cancel — delegates, and exists so a caller never needs both objects. */
    fun cancel(id: String): Boolean = store.cancel(id)

    companion object {
        /**
         * Five minutes. Chosen against iOS's own tolerance rather than invented: its monitor
         * ticks every 30 s and its BGTask floor is 60 s before the due time (`swift:139,240`),
         * so a phone considers anything inside about a minute normal. Five minutes is
         * comfortably outside the poll interval a desktop client would use and comfortably
         * inside "the user will notice".
         */
        const val DEFAULT_LATENESS_WARN_MS: Long = 5 * 60 * 1000L
    }
}
