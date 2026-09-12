package com.oshi.desktop.app

/**
 * What keeps inbound delivery alive when the poll body goes wrong.
 *
 * ============================================================ THE BUG THIS FILE IS
 *
 * `OshiClient.start` drives the receive path with `scheduleWithFixedDelay`, and that
 * method's contract has a sharp edge: **a task that throws is never run again.** Not
 * retried, not logged, not rescheduled. Nothing observes the returned `Future`, so nothing
 * notices.
 *
 * The body used to be wrapped in `catch (e: Exception)`. `OutOfMemoryError` is an `Error`,
 * not an `Exception`, so it walked straight past that handler and killed the poll
 * permanently — for the rest of the process's life, with no log line, because the line
 * that would have said so was inside the `catch` that did not fire.
 *
 * The user-visible result is the worst failure mode a messenger has. The window stays
 * open. The account pane still reports the client as running. Messages simply stop
 * arriving, which from the inside is indistinguishable from nobody writing to you.
 *
 * And it was reachable by a stranger: one attachment claiming `mediaType: contact` used to
 * be read into a single String with no size ceiling (`OshiClient.fetchMedia`, now bounded
 * by `MAX_CONTACT_CARD_BYTES`). That specific hole is closed; this guard closes the CLASS.
 * Whatever goes wrong on that thread in future — a decoder, a native library, a store —
 * delivery survives it.
 *
 * ============================================================ WHY IT CATCHES EVERYTHING
 *
 * Rethrowing on some "fatal" subset was considered and rejected. There is no `Error` here
 * worth trading inbound delivery for: a JVM that is genuinely unrecoverable will die on
 * its own, and until it does, polling is the thing the user needs most. `ThreadDeath` and
 * interruption are not special-cased either, because this body is never the target of
 * either — `stop()` shuts the executor down rather than interrupting the task.
 *
 * ============================================================ WHY IT IS A SEPARATE OBJECT
 *
 * Purely so it can be tested. Inside `start()` the failing path needs a live executor, a
 * network and a store to reach; as one tiny function it takes a lambda, and
 * `PollGuardTest` can hand it an `OutOfMemoryError` and watch delivery survive. The
 * defect was one word — `Exception` — and a guard nobody can aim a test at is how that
 * word gets changed back.
 */
internal object PollGuard {

    /**
     * Run [body], swallowing anything it throws and reporting it through [log].
     *
     * @return true when [body] completed normally. The scheduled caller ignores this; a
     *   test does not, and a future caller that wants to back off on repeated failure has
     *   the signal without having to change this contract.
     */
    fun run(log: (String) -> Unit, body: () -> Unit): Boolean =
        try {
            body()
            true
        } catch (t: Throwable) {
            // The message is best-effort on purpose: an OutOfMemoryError may not have the
            // headroom to build a fancy string, so this stays to two cheap fields.
            runCatching { log("client: poll failed: ${t.javaClass.simpleName}: ${t.message}") }
            false
        }
}
