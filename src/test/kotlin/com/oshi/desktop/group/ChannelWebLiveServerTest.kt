package com.oshi.desktop.group

import com.oshi.desktop.app.OshiClient
import com.oshi.desktop.store.InMemorySecretStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * __CHANNEL_WEB_2026_11_26__ The whole desktop path against a REAL local server of
 * feat/group-handle-registry-server (055e72f1a): a real OshiClient binds its identity (prekey
 * publish), registers a public handle, turns the web preview on, publishes a text post and a photo
 * (with EXIF orientation + GPS-ish tag), edits (same n), resolves n as a reader would (public feed),
 * deletes, and turns the preview off. Opt-in, never production:
 *
 *     OSHI_CHANNEL_WEB_SERVER=~/oshi-wt/group-server/ServerVPS/v2/v2_server.js \
 *       ./gradlew test --tests '*ChannelWebLiveServerTest*'
 */
class ChannelWebLiveServerTest {
    private var node: Process? = null
    private val dirs = ArrayList<File>()
    private val clients = ArrayList<OshiClient>()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @After fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        node?.destroy()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dir(p: String) = Files.createTempDirectory(p).toFile().also { dirs += it }

    private fun startServer(): String {
        val src = System.getenv("OSHI_CHANNEL_WEB_SERVER")?.let { File(it.replaceFirst("~", System.getProperty("user.home"))) }
        val nodeBin = listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { File(it).canExecute() }
        Assume.assumeTrue("set OSHI_CHANNEL_WEB_SERVER to the group-server v2_server.js (and have node)", src?.isFile == true && nodeBin != null)
        val port = ServerSocket(0).use { it.localPort }
        val webDir = dir("chanweb-store")
        val pb = ProcessBuilder(nodeBin, src!!.absolutePath).redirectErrorStream(true)
        pb.environment()["OSHI_V2_PORT"] = port.toString()
        pb.environment()["OSHI_CHANNEL_WEB_DIR"] = webDir.absolutePath
        pb.environment()["OSHI_AUTH_BINDINGS_FILE"] = File(webDir, "bindings.json").absolutePath
        val p = pb.start(); node = p
        val out = BufferedReader(InputStreamReader(p.inputStream))
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = out.readLine() ?: break
            if (System.getenv("CHANWEB_VERBOSE") != null) println("[server] $line")
            if (line.contains("OSHI v2 server on")) {
                Thread({ runCatching { while (true) { val l = out.readLine() ?: break; if (System.getenv("CHANWEB_VERBOSE") != null) println("[server] $l") } } }, "node-out")
                    .apply { isDaemon = true; start() }
                return "http://127.0.0.1:$port"
            }
        }
        throw AssertionError("server did not start")
    }

    private fun get(url: String): Pair<Int, ByteArray> {
        val r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        return r.statusCode() to r.body()
    }

    private fun photoWithExif(): File {
        val img = BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color.BLUE; g.fillRect(0, 0, 32, 32); g.dispose()
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        // APP1 Exif: Make = "GPS", Orientation = 6 (big-endian TIFF).
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v shr 8 and 0xFF); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write('M'.code); tiff.write('M'.code); u16(42); u32(8); u16(2)
        u16(0x010F); u16(2); u32(4); tiff.write("GPS\u0000".toByteArray())
        u16(0x0112); u16(3); u32(1); u16(6); u16(0); u32(0)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.toByteArray()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((payload.size + 2) shr 8).toByte(), ((payload.size + 2) and 0xFF).toByte()) + payload
        return File(dir("chanweb-photo"), "photo.jpg").apply { writeBytes(jpeg.copyOfRange(0, 2) + seg + jpeg.copyOfRange(2, jpeg.size)) }
    }

    @Test fun `publish, photo, edit, reader lookup, delete and off against a local server`() {
        val url = startServer()
        val home = dir("chanweb-admin")
        val c = OshiClient(home = home, secretStore = InMemorySecretStore(), serverUrl = url, displayName = "Admin").also { clients += it }
        c.config.refresh(c.address)
        assertTrue("prekey publish binds the identity", c.router.publishBundleIfNeeded())

        val g = c.createGroup("IT Channel", emptyList(), GroupType.ADMIN_ONLY)
        c.setGroupDescription(g.groupId, "*IT* run")
        c.setGroupPostPolicy(g.groupId, GroupSettings.PostPolicy.ADMINS)
        val handle = "it_chan_" + (System.nanoTime() % 100000)
        assertEquals(OshiClient.PublicLinkOutcome.OK, c.setGroupPublic(g.groupId, handle, showPreview = true))

        assertEquals(ChannelWebPublisher.EnableResult.OK, c.enableChannelWeb(g.groupId))
        assertTrue(c.groupSettings(g.groupId)!!.webPreviewActive)
        c.channelWeb.drain()   // profile + admin sync
        val (fs, feed) = get("$url/v2/c/$handle")
        assertEquals(200, fs)
        val ch = JSONObject(String(feed)).getJSONObject("channel")
        assertEquals("IT Channel", ch.getString("title"))
        assertEquals("**IT** run", ch.getString("description"))

        // A text post: app markup → web markup, n assigned.
        c.sendGroupText(g.groupId, "*Weekly* position — see https://example.org/x")
        val textMsg = c.messages.messages(GroupIdentity.canonicalGroupId(g.groupId)).last { it.fromMe && it.mediaType == null }
        c.channelWeb.drain()
        val n = c.channelWeb.n(textMsg.id, handle)
        assertNotNull("published: ${c.channelWeb.jobs()}", n)
        val (ps, pj) = get("$url/v2/c/$handle/$n")
        assertEquals(200, ps)
        val post = JSONObject(String(pj)).getJSONObject("post")
        assertEquals("<strong>Weekly</strong> position — see <a href=\"https://example.org/x\" target=\"_blank\" rel=\"noopener noreferrer nofollow ugc\">https://example.org/x</a>",
            post.getString("html"))
        assertEquals(textMsg.sentAtMs, post.getLong("createdAt"))
        val (pageStatus, page) = get("$url/c/$handle/$n")
        assertEquals(200, pageStatus)
        val html = String(page)
        assertTrue(html.contains("oshi://channel?h=$handle&amp;n=$n") || html.contains("oshi://channel?h=$handle&n=$n"))

        // A photo with EXIF orientation 6 and a GPS-ish tag: uploaded upright, without metadata.
        assertNotNull(c.sendGroupFile(g.groupId, photoWithExif(), caption = "a _photo_"))
        val photoMsg = c.messages.messages(GroupIdentity.canonicalGroupId(g.groupId)).last { it.fromMe && it.mediaType != null }
        c.channelWeb.drain()
        val pn = c.channelWeb.n(photoMsg.id, handle)
        assertNotNull("photo published: ${c.channelWeb.jobs()}", pn)
        val photoPost = JSONObject(String(get("$url/v2/c/$handle/$pn").second)).getJSONObject("post")
        assertEquals("a _photo_", photoPost.getString("text"))
        val media = photoPost.getJSONArray("media").getJSONObject(0)
        assertEquals(32, media.getInt("width")); assertEquals(64, media.getInt("height"))
        val mediaPath = URI(media.getString("url")).path
        val (ms, bytes) = get("$url$mediaPath")
        assertEquals(200, ms)
        assertFalse("no EXIF/GPS on the web copy", String(bytes, Charsets.ISO_8859_1).contains("GPS"))

        // Edit → same n, new text.
        c.editGroupMessage(g.groupId, textMsg.id, "*Edited* position")
        c.channelWeb.drain()
        assertEquals(n, c.channelWeb.n(textMsg.id, handle))
        val edited = JSONObject(String(get("$url/v2/c/$handle/$n").second)).getJSONObject("post")
        assertEquals("**Edited** position", edited.getString("text"))
        assertTrue(!edited.isNull("editedAt"))

        // A reader (no local map, unsigned public feed) finds the same n by send time.
        val reader = ChannelWebPublisher(
            { m, p, q, b, _, signed ->
                assertFalse("a reader never signs", signed)
                val r = get("$url$p" + (q?.let { "?$it" } ?: "")); r.first to String(r.second)
            },
            object : ChannelWebPublisher.Source by NoSource {}, ChannelWebPublisher.Persistence.memory(),
        )
        assertEquals(n, reader.resolveN(g.groupId, textMsg.id, handle, isAdmin = false, createdAtMs = textMsg.sentAtMs, text = null))
        // The deep link's n maps back to our message.
        assertEquals(textMsg.id, c.channelWebMessageId(g.groupId, handle, n!!))

        // Delete for everyone → gone from the web.
        c.deleteGroupMessage(g.groupId, textMsg.id)
        c.channelWeb.drain()
        assertEquals(404, get("$url/v2/c/$handle/$n").first)

        // Off → the whole channel is purged.
        c.disableChannelWeb(g.groupId)
        assertFalse(c.groupSettings(g.groupId)!!.webPreview)
        c.channelWeb.drain()
        assertTrue(c.channelWeb.jobs().isEmpty())
        assertEquals(404, get("$url/v2/c/$handle").first)
        assertEquals(404, get("$url/v2/c/$handle/$pn").first)
    }

    private object NoSource : ChannelWebPublisher.Source {
        override fun post(groupId: String, postId: String): ChannelWebPostSource? = null
        override fun imageBytes(groupId: String, postId: String): ByteArray? = null
        override fun profile(groupId: String): ChannelWebProfile? = null
        override fun avatarBytes(groupId: String): ByteArray? = null
        override fun channels(): List<ChannelWebPublisher.Channel> = emptyList()
        override fun sentTimes(groupId: String): List<Pair<String, Long>> = emptyList()
        override fun reRegisterHandle(groupId: String): Boolean = false
    }
}
