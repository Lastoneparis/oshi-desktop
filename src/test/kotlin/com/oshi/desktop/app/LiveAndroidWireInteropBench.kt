package com.oshi.desktop.app

import com.oshi.desktop.store.InMemorySecretStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * __GROUP_E2E_V2_2026_09_23__ Android WIRE ↔ Desktop over the PRODUCTION relay
 * (docs/GROUP_E2E_V2_SPEC.md §9), throwaway identities.
 *
 * Identity "and" plays an Android 1.x phone: its bytes are built EXACTLY like
 * `OSHI-Android/.../service/GroupManager.kt` builds them (`buildIosMessageGroupJson`,
 * `wireGroupMessageJson`, `buildGroupEditSystemMessageJson`, …: lowercase message ids,
 * Apple-epoch timestamps, ISO dates, Android compat keys) and they travel through the v2 router
 * only: state = `📢GROUP_UPDATE📢` on a 1to1 envelope, content = base64(GroupMessage) on a
 * `type:"group"` envelope. Desktop clients B and C must understand every one of them.
 *
 * Gate: `OSHI_LIVE_GROUP=1`. `OSHI_LIVE_GROUP_OUT` receives the identities + canary for the
 * read-only server check.
 */
class LiveAndroidWireInteropBench {

    private fun live() = Assume.assumeTrue("set OSHI_LIVE_GROUP=1", System.getenv("OSHI_LIVE_GROUP") == "1")
    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun client(name: String): OshiClient {
        val dir = Files.createTempDirectory("oshi-live-andwire-$name").toFile()
        val c = OshiClient(home = dir, secretStore = InMemorySecretStore(), displayName = name, log = { logs += "[$name] $it" })
        c.router.refreshConfig()
        check(c.config.isEnabledCached()) { "$name: v2 gate closed" }
        check(c.router.publishBundleIfNeeded()) { "$name: bundle publish" }
        runCatching { c.deviceMailbox.tick() }
        return c
    }

    private fun pump(vararg cs: OshiClient) {
        for (c in cs) {
            runCatching { c.deviceMailbox.tick() }
            runCatching { c.router.poll() }.onFailure { logs += "poll: ${it.message}" }
        }
        Thread.sleep(700)
    }

    private fun until(what: String, vararg cs: OshiClient, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) { println("OK   $what"); return true }
            pump(*cs)
        }
        println("FAIL $what"); synchronized(logs) { logs.takeLast(30).forEach(::println) }
        return false
    }

    // ---------------------------------------------------------------- Android's builders
    private val prefix = "📢GROUP_UPDATE📢"
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun apple(ms: Long) = ms / 1000.0 - 978307200.0
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun definition(gid: String, name: String, admin: String, members: List<String>, createdMs: Long, version: Int) =
        JSONObject().apply {
            put("id", gid.uppercase()); put("name", name); put("type", "collaborative"); put("adminPublicKey", admin)
            put("members", JSONArray().apply {
                members.forEach { k -> put(JSONObject().put("publicKey", k).put("joinedAt", iso(createdMs)).put("isAdmin", k == admin)) }
            })
            put("createdAt", iso(createdMs)); put("lastActivity", iso(System.currentTimeMillis())); put("isMuted", false)
            put("description", "android wire bench"); put("stateVersion", version)
        }.toString()

    private fun groupMessage(id: String, gid: String, sender: String, body: String, ms: Long) = JSONObject().apply {
        put("id", id); put("groupId", gid.uppercase()); put("senderPublicKey", sender)
        put("encryptedContent", b64(body)); put("timestamp", apple(ms)); put("isRead", false); put("messageChainIndex", 0)
        put("messageId", id); put("content", body); put("senderName", "")
    }.toString()

    private fun system(gid: String, actor: String, type: String, data: JSONObject, ms: Long) = JSONObject().apply {
        put("id", UUID.randomUUID().toString().uppercase()); put("groupId", gid); put("senderPublicKey", "SYSTEM")
        put("encryptedContent", ""); put("timestamp", apple(ms)); put("isRead", true); put("messageChainIndex", 0)
        put("systemMessageType", type); put("systemMessageData", data.put("actorPublicKey", actor))
    }.toString()

    private fun OshiClient.state(to: List<String>, content: String) =
        to.all { router.sendText(it, content.toByteArray(Charsets.UTF_8), UUID.randomUUID().toString()) }

    private fun OshiClient.content(to: List<String>, gid: String, json: String, msgId: String) =
        to.all { router.sendText(it, b64(json).toByteArray(Charsets.UTF_8), msgId, groupId = gid.uppercase()) }

    @Test
    fun androidWireRoundOnProduction() {
        live()
        val tag = UUID.randomUUID().toString().take(8)
        val canary = "CANARY-ANDWIRE-$tag"
        val andr = client("and"); val b = client("b"); val c = client("c")
        val all = arrayOf(andr, b, c)
        System.getenv("OSHI_LIVE_GROUP_OUT")?.let { out ->
            File(out).writeText("startMs=${System.currentTimeMillis()}\ncanary=$canary\n" + all.joinToString("\n") { "key=${it.address}" } + "\n")
        }
        val results = LinkedHashMap<String, Boolean>()
        val gid = UUID.randomUUID().toString()           // Android mints lowercase
        val GID = gid.uppercase()
        val created = System.currentTimeMillis()
        val members = listOf(andr.address, b.address, c.address)

        // create (definition v1 + Android's `created` companion)
        val def1 = definition(gid, "$canary-name", andr.address, members, created, 1)
        check(andr.state(listOf(b.address, c.address), prefix + def1))
        // The "Android" identity holds its own copy of the group (as the phone would after createGroup),
        // so what the Desktop members send back can be checked on arrival.
        andr.groups.put((com.oshi.desktop.group.GroupUpdateWire.decodeDefinition(def1)
            as com.oshi.desktop.group.GroupUpdateWire.GroupUpdateDecode.Ok).definition)
        results["create"] = until("B and C learn the Android group", *all) { b.groups.get(GID) != null && c.groups.get(GID) != null }

        // text
        val m1 = UUID.randomUUID().toString()
        check(andr.content(listOf(b.address, c.address), gid, groupMessage(m1, gid, andr.address, "$canary-text", System.currentTimeMillis()), m1))
        results["text"] = until("Android text reaches B and C", *all) {
            b.messages.messages(GID).any { it.content == "$canary-text" } && c.messages.messages(GID).any { it.content == "$canary-text" }
        }

        // reply
        val m2 = UUID.randomUUID().toString()
        val reply = "💬REPLY💬" + JSONObject().put("content", "$canary-reply").put("replyTo", JSONObject()
            .put("originalMessageId", m1).put("originalSenderKey", andr.address).put("originalText", "$canary-text")
            .put("originalTimestamp", apple(created)).put("originalMediaType", JSONObject.NULL)).toString()
        andr.content(listOf(b.address), gid, groupMessage(m2, gid, andr.address, reply, System.currentTimeMillis()), m2)
        results["reply"] = until("Android reply reaches B", *all) { b.messages.messages(GID).any { (it.content ?: "").contains("$canary-reply") } }

        // reaction on B's own message: B writes, Android reacts
        b.sendGroupText(GID, "$canary-from-b")
        results["desktop text reaches android"] = until("B's text decrypted and stored by the Android identity", *all) {
            andr.messages.messages(GID).any { it.content == "$canary-from-b" }
        }
        val bMsg = b.messages.messages(GID).first { it.content == "$canary-from-b" }
        val rx = "🔥REACTION🔥" + JSONObject().put("messageId", bMsg.id).put("emoji", "✅")
            .put("senderPublicKey", andr.address).put("senderName", "and").put("timestamp", apple(System.currentTimeMillis())).put("action", "add")
        val m3 = UUID.randomUUID().toString()
        andr.content(listOf(b.address), gid, groupMessage(m3, gid, andr.address, rx.toString(), System.currentTimeMillis()), m3)
        results["reaction"] = until("Android reaction reaches B", *all) { b.messages.message(GID, bMsg.id)?.reactions?.isNotEmpty() == true }

        // edit + delete (Android system messages)
        val sysEdit = system(GID, andr.address, "message_edited", JSONObject().put("editedMessageId", m1.uppercase()).put("editedContent", "$canary-edited"), System.currentTimeMillis())
        andr.content(listOf(b.address, c.address), gid, sysEdit, UUID.randomUUID().toString())
        results["edit"] = until("Android edit reaches C", *all) { c.messages.messages(GID).any { it.content == "$canary-edited" } }
        val sysDel = system(GID, andr.address, "message_deleted", JSONObject().put("deletedMessageId", m1.uppercase()), System.currentTimeMillis())
        andr.content(listOf(b.address, c.address), gid, sysDel, UUID.randomUUID().toString())
        results["delete"] = b.messages.messages(GID).any { it.id.equals(m1, true) } &&
            until("Android delete reaches B (target present, then deleted)", *all) {
                b.messages.messages(GID).firstOrNull { it.id.equals(m1, true) }?.isDeletedForEveryone == true
            }

        // media, exactly as Android's V2MessageRouter.sendGroupFile: sealed once, one blob per member,
        // `v2file` key JSON carrying the media-stripped GroupMessage (caption in plaintextContent).
        val media = "$canary-media-bytes ".toByteArray() + ByteArray(5000) { 0x41 }
        val m5 = UUID.randomUUID().toString()
        val stripped = JSONObject(groupMessage(m5, gid, andr.address, "", System.currentTimeMillis()))
            .put("encryptedContent", "").put("content", "").put("mediaType", "photo")
            .put("mediaFileName", "photo.jpg").put("plaintextContent", "$canary-caption").toString()
        val enc = com.oshi.messenger.network.v2.OSHICryptoV2.encryptFile(media, "photo.jpg", "image/jpeg")
        val mediaSent = listOf(b.address, c.address).all { member ->
            val blobId = andr.blobs.uploadBlob(member, enc.chunks) ?: return@all false
            val key = com.oshi.messenger.network.v2.V2FileKeyMessage(
                blobId = blobId, fileKey = enc.fileKey, fileNonce = enc.fileNonce, chunkCount = enc.chunks.size,
                manifest = enc.manifest, filename = "photo.jpg", mime = "image/jpeg", mediaType = "photo",
                groupMessage = b64(stripped),
            ).toBytes()
            andr.router.sendText(member, key, m5, groupId = GID)
        }
        results["media"] = mediaSent && until("Android photo reaches B with its bytes", *all) {
            b.messages.messages(GID).any { it.content == "$canary-caption" && it.mediaRef != null }
        } && b.messages.messages(GID).first { it.content == "$canary-caption" }.let { m ->
            b.mediaVault.readBytes(File(m.mediaRef!!), 1L shl 20).contentEquals(media)
        }

        // forged author: B's ratchet carrying a GroupMessage that claims to be Android ⇒ dropped
        val m4 = UUID.randomUUID().toString()
        b.router.sendText(c.address, b64(groupMessage(m4, gid, andr.address, "$canary-forged", System.currentTimeMillis())).toByteArray(), m4, groupId = GID)
        pump(*all); pump(*all)
        results["forged author dropped"] = c.messages.messages(GID).none { it.content == "$canary-forged" }
        println((if (results["forged author dropped"]!!) "OK  " else "FAIL") + " forged author dropped")

        // rename (definition v2 + `group_renamed`)
        andr.state(listOf(b.address, c.address), prefix + definition(gid, "$canary-renamed", andr.address, members, created, 2))
        results["rename"] = until("rename reaches B and C", *all) { b.groups.get(GID)?.name == "$canary-renamed" && c.groups.get(GID)?.name == "$canary-renamed" }

        // stale replay (v1 again) must not undo the rename
        andr.state(listOf(b.address), prefix + definition(gid, "$canary-name", andr.address, members, created, 1))
        pump(*all); pump(*all)
        results["stale v1 ignored"] = b.groups.get(GID)?.name == "$canary-renamed"
        println((if (results["stale v1 ignored"]!!) "OK  " else "FAIL") + " stale v1 ignored")

        // remove C (definition v3 without C + member_removed to C)
        val v3 = JSONObject(definition(gid, "$canary-renamed", andr.address, listOf(andr.address, b.address), created, 3))
            .put("evictedMemberKeys", JSONArray().put(c.address)).toString()
        andr.state(listOf(b.address, c.address), prefix + v3)
        andr.state(listOf(c.address), prefix + JSONObject().put("type", "member_removed").put("groupId", GID).put("memberPublicKey", c.address))
        results["remove"] = until("B sees C gone", *all) { b.groups.get(GID)?.isMember(c.address) == false }

        // B leaves (Desktop) — Android identity is admin; just check B's local state
        results["leave"] = b.leaveGroup(GID) && until("B out", *all) { b.groups.get(GID) == null }

        println("ANDROID-WIRE RESULTS gid=$GID canary=$canary")
        results.forEach { (k, v) -> println("  ${if (v) "PASS" else "FAIL"}  $k") }
        all.forEach { runCatching { it.close() } }
        org.junit.Assert.assertTrue("failed steps: ${results.filterValues { !it }.keys}", results.values.all { it })
    }
}
