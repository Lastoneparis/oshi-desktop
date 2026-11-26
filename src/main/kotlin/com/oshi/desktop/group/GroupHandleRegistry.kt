package com.oshi.desktop.group

import org.json.JSONArray
import org.json.JSONObject

/**
 * __GROUP_INFO_SETTINGS_2026_11_20__ The public-link registry of GROUP_INFO_SETTINGS_SPEC §4
 * (`/v2/g/<handle>`), as the client sees it. An interface so tests and screen renders run
 * against a stub: the server side is not deployed, and nothing here may ever be pointed at
 * production from a test.
 *
 * What the server learns (§4.1): the handle, an opaque [GroupSettings.groupRef], the admin
 * keys that answer joins and edit the record, and — only when the admin opted in — the
 * preview. Never the members, the messages or the group id.
 */
interface GroupHandleRegistry {

    data class Preview(val name: String?, val description: String?, val memberCount: Int?)

    sealed interface Lookup {
        data class Found(val handle: String, val joinKeys: List<String>, val preview: Preview?) : Lookup
        data object NotFound : Lookup
        data class Failed(val why: String) : Lookup
    }

    sealed interface Write {
        data object Ok : Write
        /** 409 (`handle-taken` / `group-has-handle`) or 403 (reserved): "This link is already taken." */
        data class Taken(val why: String) : Write
        data class Failed(val why: String) : Write
    }

    fun lookup(handle: String): Lookup
    fun register(handle: String, groupRef: String, joinKeys: List<String>, controllers: List<String>, preview: Preview?): Write
    fun unregister(handle: String): Write

    companion object {
        /** §4.2 the lookup body → [Lookup.Found], or null when it is not one. */
        fun parseLookup(handle: String, body: String): Lookup.Found? {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val keys = (o.opt("joinKeys") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }
                .orEmpty().filter { com.oshi.desktop.pairing.ExternalLinkGate.isPublicKey(it) }.take(8)
            if (keys.isEmpty()) return null
            val p = o.opt("preview") as? JSONObject
            val preview = p?.let {
                Preview(
                    name = (it.opt("name") as? String)?.take(64)?.takeIf(String::isNotBlank),
                    description = (it.opt("description") as? String)?.take(255)?.takeIf(String::isNotBlank),
                    memberCount = (it.opt("memberCount") as? Number)?.toInt()?.takeIf { n -> n in 0..1_000_000 },
                )
            }
            return Lookup.Found(handle, keys, preview)
        }

        /** §4.2 PUT body. */
        fun registerBody(groupRef: String, joinKeys: List<String>, controllers: List<String>, preview: Preview?): String =
            JSONObject()
                .put("groupRef", groupRef)
                .put("joinKeys", JSONArray(joinKeys))
                .put("controllers", JSONArray(controllers))
                .put("preview", preview?.let {
                    JSONObject().apply {
                        it.name?.let { n -> put("name", n.take(64)) }
                        it.description?.let { d -> put("description", d.take(255)) }
                        it.memberCount?.let { c -> put("memberCount", c) }
                    }
                } ?: JSONObject.NULL)
                .toString()
    }
}

/** The registry over the v2 server ([com.oshi.desktop.net.V2Http]). Lookup unsigned, writes signed. */
class HttpGroupHandleRegistry(private val http: com.oshi.desktop.net.V2Http) : GroupHandleRegistry {

    private fun path(handle: String) = "/v2/g/$handle"

    override fun lookup(handle: String): GroupHandleRegistry.Lookup {
        val r = runCatching { http.unsignedGet(path(handle)) }.getOrElse { return GroupHandleRegistry.Lookup.Failed(it.javaClass.simpleName) }
        return when {
            r.code == 404 -> GroupHandleRegistry.Lookup.NotFound
            r.isSuccess -> GroupHandleRegistry.parseLookup(handle, r.body) ?: GroupHandleRegistry.Lookup.NotFound
            else -> GroupHandleRegistry.Lookup.Failed("http-${r.code}")
        }
    }

    override fun register(
        handle: String, groupRef: String, joinKeys: List<String>, controllers: List<String>, preview: GroupHandleRegistry.Preview?,
    ): GroupHandleRegistry.Write {
        val body = GroupHandleRegistry.registerBody(groupRef, joinKeys, controllers, preview).toByteArray(Charsets.UTF_8)
        val r = runCatching {
            http.put(path(handle), body, bodyToHash = body, contentType = "application/json; charset=utf-8", withUserHeader = true)
        }.getOrElse { return GroupHandleRegistry.Write.Failed(it.javaClass.simpleName) }
        return when {
            r.isSuccess -> GroupHandleRegistry.Write.Ok
            r.code == 409 || r.code == 403 -> GroupHandleRegistry.Write.Taken(r.body.take(80))
            else -> GroupHandleRegistry.Write.Failed("http-${r.code}")
        }
    }

    override fun unregister(handle: String): GroupHandleRegistry.Write {
        // The server hashes the raw body it received; a DELETE sends none, so zero bytes are hashed.
        val r = runCatching {
            http.request("DELETE", path(handle), null, bodyToHash = ByteArray(0), body = null, withUserHeader = true)
        }.getOrElse { return GroupHandleRegistry.Write.Failed(it.javaClass.simpleName) }
        return when {
            // 404 = no such record for us any more: nothing left to unregister.
            r.isSuccess || r.code == 404 -> GroupHandleRegistry.Write.Ok
            else -> GroupHandleRegistry.Write.Failed("http-${r.code}")
        }
    }
}
