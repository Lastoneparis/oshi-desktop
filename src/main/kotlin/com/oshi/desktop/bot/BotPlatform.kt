package com.oshi.desktop.bot

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONObject

/**
 * __BOTS_PHASE1_2026_11_24__ The bot-platform routes of phase 1, as the desktop needs them,
 * behind one interface so the UI and the tests never depend on a deployed server
 * (nothing is deployed yet — the server patch is `ServerPatches/bots_phase1_2026_11_24`).
 *
 * The real implementation is [HttpBotPlatform]; tests use a fake. Every method blocks and
 * never throws: call it off the UI thread and read the outcome.
 *
 * Routes and their status:
 *  - `GET /api/bot/public`, `GET /api/bot/search`, `GET /api/bot/commands`, `POST
 *    /api/bot/setMyProfile`: contract §3 + amendment A7 (server patch written, not deployed).
 *  - `POST /api/bot/dispatch` `kind:"command"|"callback_query"`: deployed today (bot_e2e),
 *    A8 keeps only the first line of a command and refuses `/cmd@otherbot`.
 *  - `POST /api/bot/addToGroup`, `/removeFromGroup`, `/revokeToken`, `GET /usernameAvailable`:
 *    server amendment "2026-11-24 (late)" S1, S3, S4 (patch written and tested, not deployed).
 */
interface BotPlatform {

    sealed interface Lookup {
        data class Found(val bot: PublicBotProfile) : Lookup
        /** Unknown OR not public — the server makes them indistinguishable on purpose (A7). */
        data object NotFound : Lookup
        data class Failed(val reason: String) : Lookup
    }

    sealed interface ProfileSave {
        data class Saved(val bot: PublicBotProfile?) : ProfileSave
        data object UsernameTaken : ProfileSave
        data object UsernameInvalid : ProfileSave
        data class Failed(val reason: String) : ProfileSave
    }

    sealed interface Dispatch {
        data class Sent(val callbackQueryId: String? = null) : Dispatch
        data object NotAMember : Dispatch
        data object UnknownBot : Dispatch
        /** A8: `/cmd@otherbot` sent to a bot whose username is not `otherbot`. */
        data object WrongBot : Dispatch
        data class Failed(val reason: String) : Dispatch
    }

    /** Fields of `setMyProfile`; null = leave as is. An empty [username] clears it (A7). */
    data class ProfileEdit(
        val username: String? = null,
        val name: String? = null,
        val about: String? = null,
        val description: String? = null,
        val avatarEmoji: String? = null,
        val public: Boolean? = null,
    )

    fun byUsername(username: String): Lookup
    fun byId(botId: String): Lookup
    fun search(query: String): Result<List<PublicBotProfile>>
    fun commands(botId: String, userPublicKey: String, groupId: String?): Result<List<BotCommand>>
    fun setMyProfile(token: String, edit: ProfileEdit): ProfileSave
    fun dispatchCommand(botId: String, groupId: String, fromPublicKey: String, text: String, messageId: String): Dispatch
    fun dispatchCallback(botId: String, groupId: String, fromPublicKey: String, callbackData: String, messageId: String): Dispatch

    /** S4: add a public bot this account does not own to [groupId] ([kind] "group" | "channel"), signed as the adder. */
    fun addToGroup(botId: String, groupId: String, kind: String, memberKeys: List<String>, permissions: BotPermissions, adderPublicKey: String): Result<Unit>

    /** S4: take a bot this account added out of [groupId]. */
    fun removeFromGroup(botId: String, groupId: String, adderPublicKey: String): Result<Unit>

    /** S3: a new token; the old one is dead at once. The botId does not change. */
    data class Revoked(val token: String, val botId: String)
    fun revokeToken(token: String): Result<Revoked>

    /** S1: `GET /api/bot/usernameAvailable` — with the bot's own [token], its current name reads available. */
    sealed interface UsernameCheck {
        data object Available : UsernameCheck
        data object Taken : UsernameCheck
        data object Invalid : UsernameCheck
        data class Failed(val reason: String) : UsernameCheck
    }
    fun usernameAvailable(username: String, token: String? = null): UsernameCheck

    companion object {
        /** `/start <payload>`-free search query: 1–32 chars, a leading `@` ignored (A7). Null = do not search. */
        fun searchQuery(raw: String): String? = raw.trim().removePrefix("@").trim().take(32).takeIf { it.isNotEmpty() }
    }
}

/**
 * [BotPlatform] over HTTP, on the same legacy server as [BotApi]. [sign] adds the Ed25519
 * request headers of `docs/CALL_PUSH_AUTH_CONTRACT.md` §1 (amendment A1: verified when present on
 * dispatch/commands, so the desktop sends them; required on the routes that act as the user).
 */
class HttpBotPlatform(
    private val baseUrl: String,
    private val sign: ((method: String, path: String, body: ByteArray) -> Map<String, String>)? = null,
    private val timeout: Duration = Duration.ofSeconds(20),
) : BotPlatform {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private class Answer(val status: Int, val json: JSONObject)

    override fun byUsername(username: String): BotPlatform.Lookup =
        lookup("/api/bot/public?username=" + enc(BotUsername.normalize(username)))

    override fun byId(botId: String): BotPlatform.Lookup = lookup("/api/bot/public?botId=" + enc(botId))

    private fun lookup(pathAndQuery: String): BotPlatform.Lookup {
        val a = get(pathAndQuery) ?: return BotPlatform.Lookup.Failed("unreachable")
        return when {
            a.status == 404 -> BotPlatform.Lookup.NotFound
            a.status in 200..299 -> PublicBotProfile.fromJson(a.json.optJSONObject("bot"))
                ?.let { BotPlatform.Lookup.Found(it) } ?: BotPlatform.Lookup.NotFound
            else -> BotPlatform.Lookup.Failed("HTTP ${a.status}")
        }
    }

    override fun search(query: String): Result<List<PublicBotProfile>> {
        val q = BotPlatform.searchQuery(query) ?: return Result.success(emptyList())
        val a = get("/api/bot/search?q=" + enc(q)) ?: return Result.failure(IllegalStateException("unreachable"))
        if (a.status !in 200..299) return Result.failure(IllegalStateException("HTTP ${a.status}"))
        return Result.success(PublicBotProfile.listFromJson(a.json.optJSONArray("bots")).filter { it.public }.take(20))
    }

    override fun commands(botId: String, userPublicKey: String, groupId: String?): Result<List<BotCommand>> {
        val q = "/api/bot/commands?botId=" + enc(botId) + "&userPublicKey=" + enc(userPublicKey) +
            (groupId?.let { "&groupId=" + enc(it) } ?: "")
        val a = get(q, signPath = "/api/bot/commands") ?: return Result.failure(IllegalStateException("unreachable"))
        if (a.status !in 200..299) return Result.failure(IllegalStateException("HTTP ${a.status}"))
        return Result.success(BotCommands.fromJson(a.json.optJSONArray("commands")))
    }

    override fun setMyProfile(token: String, edit: BotPlatform.ProfileEdit): BotPlatform.ProfileSave {
        val body = JSONObject().put("token", token)
        edit.username?.let { body.put("username", BotUsername.normalize(it)) }
        edit.name?.let { body.put("name", it) }
        edit.about?.let { body.put("about", it) }
        edit.description?.let { body.put("description", it) }
        edit.avatarEmoji?.let { body.put("avatarEmoji", it) }
        edit.public?.let { body.put("public", it) }
        val a = post("/api/bot/setMyProfile", body, signed = false) ?: return BotPlatform.ProfileSave.Failed("unreachable")
        return when {
            a.status in 200..299 -> BotPlatform.ProfileSave.Saved(PublicBotProfile.fromJson(a.json.optJSONObject("bot")))
            a.status == 409 -> BotPlatform.ProfileSave.UsernameTaken
            a.status == 400 && a.json.optString("error") == "username_invalid" -> BotPlatform.ProfileSave.UsernameInvalid
            else -> BotPlatform.ProfileSave.Failed(a.json.optString("error", "").ifEmpty { "HTTP ${a.status}" })
        }
    }

    override fun dispatchCommand(botId: String, groupId: String, fromPublicKey: String, text: String, messageId: String) =
        dispatch(JSONObject().put("botId", botId).put("kind", "command").put("groupId", groupId)
            .put("fromPublicKey", fromPublicKey).put("text", text).put("messageId", messageId))

    override fun dispatchCallback(botId: String, groupId: String, fromPublicKey: String, callbackData: String, messageId: String) =
        dispatch(JSONObject().put("botId", botId).put("kind", "callback_query").put("groupId", groupId)
            .put("fromPublicKey", fromPublicKey).put("callbackData", callbackData).put("messageId", messageId))

    private fun dispatch(body: JSONObject): BotPlatform.Dispatch {
        val a = post("/api/bot/dispatch", body, signed = true) ?: return BotPlatform.Dispatch.Failed("unreachable")
        return when {
            a.status in 200..299 && a.json.optBoolean("success", false) ->
                BotPlatform.Dispatch.Sent(a.json.optString("callback_query_id", "").ifEmpty { null })
            // Night amendment: 403 `forbidden` = the signature did not prove the identity (clock/key) — not membership.
            a.status == 403 && a.json.optString("error") == "forbidden" -> BotPlatform.Dispatch.Failed("forbidden")
            a.status == 403 -> BotPlatform.Dispatch.NotAMember
            a.status == 404 -> BotPlatform.Dispatch.UnknownBot
            a.status == 400 && a.json.optString("error") == "wrong_bot" -> BotPlatform.Dispatch.WrongBot
            else -> BotPlatform.Dispatch.Failed(a.json.optString("error", "").ifEmpty { "HTTP ${a.status}" })
        }
    }

    // S4: signed (Ed25519 headers, PATH /api/bot/addToGroup); `name` omitted on purpose — the
    // server does not need the group's name (S4b), and an E2E group's name stays on the devices.
    override fun addToGroup(botId: String, groupId: String, kind: String, memberKeys: List<String>, permissions: BotPermissions, adderPublicKey: String): Result<Unit> {
        val body = JSONObject().put("botId", botId).put("groupId", groupId).put("kind", kind)
            .put("members", org.json.JSONArray(memberKeys)).put("permissions", permissions.toJson())
            .put("adderPublicKey", adderPublicKey)
        return unit(post("/api/bot/addToGroup", body, signed = true))
    }

    override fun removeFromGroup(botId: String, groupId: String, adderPublicKey: String): Result<Unit> =
        unit(post("/api/bot/removeFromGroup", JSONObject().put("botId", botId).put("groupId", groupId).put("adderPublicKey", adderPublicKey), signed = true))

    private fun unit(a: Answer?): Result<Unit> = when {
        a == null -> Result.failure(IllegalStateException("unreachable"))
        a.status in 200..299 -> Result.success(Unit)
        else -> Result.failure(IllegalStateException(a.json.optString("error", "").ifEmpty { "HTTP ${a.status}" }))
    }

    // S3: `{token}` → `{success, token:<new>, botId}`.
    override fun revokeToken(token: String): Result<BotPlatform.Revoked> {
        val a = post("/api/bot/revokeToken", JSONObject().put("token", token), signed = false)
            ?: return Result.failure(IllegalStateException("unreachable"))
        val fresh = a.json.optString("token", "")
        return if (a.status in 200..299 && fresh.isNotEmpty()) Result.success(BotPlatform.Revoked(fresh, a.json.optString("botId", "")))
        else Result.failure(IllegalStateException(a.json.optString("error", "").ifEmpty { "HTTP ${a.status}" }))
    }

    override fun usernameAvailable(username: String, token: String?): BotPlatform.UsernameCheck {
        val q = "/api/bot/usernameAvailable?username=" + enc(BotUsername.normalize(username)) + (token?.let { "&token=" + enc(it) } ?: "")
        val a = get(q) ?: return BotPlatform.UsernameCheck.Failed("unreachable")
        if (a.status !in 200..299) return BotPlatform.UsernameCheck.Failed("HTTP ${a.status}")
        return when {
            a.json.optBoolean("available", false) -> BotPlatform.UsernameCheck.Available
            a.json.optString("reason") == "invalid" -> BotPlatform.UsernameCheck.Invalid
            else -> BotPlatform.UsernameCheck.Taken
        }
    }

    private fun get(pathAndQuery: String, signPath: String? = null): Answer? = signedOnce(signPath != null) {
        val b = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(timeout).GET()
        if (signPath != null) sign?.invoke("GET", signPath, ByteArray(0))?.forEach { (k, v) -> b.header(k, v) }
        b.build()
    }

    private fun post(path: String, body: JSONObject, signed: Boolean): Answer? {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        return signedOnce(signed) {
            val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            // Signed over the EXACT bytes sent (§1: serialize once, sign those bytes).
            if (signed) sign?.invoke("POST", path, bytes)?.forEach { (k, v) -> b.header(k, v) }
            b.build()
        }
    }

    /**
     * Night amendment (`__BOT_USER_AUTH_2026_11_24__`): a 401 `signature_required` on a signed
     * route is answered by signing afresh (new timestamp) and retrying ONCE; a 403 is never retried.
     */
    private fun signedOnce(signed: Boolean, build: () -> HttpRequest): Answer? {
        val first = exchange(build())
        if (!signed || sign == null || first == null || first.status != 401 || first.json.optString("error") != "signature_required") return first
        return exchange(build())
    }

    private fun exchange(req: HttpRequest): Answer? = try {
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        Answer(resp.statusCode(), runCatching { JSONObject(resp.body() ?: "") }.getOrElse { JSONObject() })
    } catch (e: Exception) {
        null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
