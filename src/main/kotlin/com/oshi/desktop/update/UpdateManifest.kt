package com.oshi.desktop.update

import org.json.JSONObject

/**
 * __DESKTOP_AUTO_UPDATE_2026_11_21__ `downloads/desktop-latest.json`, after its signature has
 * been checked ([UpdateSignature]). Written by `release/update_manifest.py build`:
 *
 * ```
 * { "schema": 1, "product": "oshi-desktop", "version": "1.6.1", "released": "2026-11-21",
 *   "page": "https://oshi-messenger.com/desktop",
 *   "files": { "exe": {"url","sha256","size"}, "msi": {…}, "deb": {…}, "rpm": {…} },
 *   "notes": "text" | { "en": "…", "fr": "…" } }
 * ```
 *
 * Parsing is strict about what the app ACTS on (version, url, sha256, size) and lenient about
 * what it only shows (notes, released). Anything malformed is a [ParseException]: the checker
 * then behaves exactly as if there were no manifest at all.
 */
data class UpdateManifest(
    val version: Version,
    val released: String?,
    val page: String?,
    val files: Map<InstallerKind, UpdateFile>,
    val notes: Map<String, String>,
) {
    /** The notes in [tag] (exact, then its language), else English, else any. */
    fun notesFor(tag: String): String? {
        if (notes.isEmpty()) return null
        notes[tag]?.let { return it }
        val lang = tag.substringBefore('-')
        notes[lang]?.let { return it }
        notes.entries.firstOrNull { it.key.substringBefore('-') == lang }?.let { return it.value }
        return notes["en"] ?: notes.values.first()
    }

    class ParseException(msg: String) : Exception(msg)

    companion object {
        const val PRODUCT = "oshi-desktop"
        /** Far above any real manifest; refuses a server that streams something else at us. */
        const val MAX_BYTES = 64 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /**
         * [requireHost]: every download URL must be `https://<requireHost>/…` (the production
         * key pins oshi-messenger.com — defence in depth on top of the signature). Null = any
         * http(s) URL (the test key only).
         */
        fun parse(bytes: ByteArray, requireHost: String?): UpdateManifest {
            if (bytes.size > MAX_BYTES) throw ParseException("manifest too large")
            val j = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) {
                throw ParseException("not JSON: ${e.message}")
            }
            if (j.optInt("schema", -1) != 1) throw ParseException("unsupported schema ${j.opt("schema")}")
            if (j.optString("product") != PRODUCT) throw ParseException("not an OSHI desktop manifest")
            val version = Version.parse(j.optString("version")) ?: throw ParseException("bad version '${j.opt("version")}'")
            val filesJson = j.optJSONObject("files") ?: throw ParseException("no files")
            val files = LinkedHashMap<InstallerKind, UpdateFile>()
            for (kind in InstallerKind.entries) {
                val f = filesJson.optJSONObject(kind.key) ?: continue
                val url = f.optString("url")
                val uri = try { java.net.URI(url) } catch (_: Exception) { throw ParseException("${kind.key}: bad url") }
                val scheme = uri.scheme?.lowercase()
                if (requireHost != null) {
                    if (scheme != "https" || !uri.host.equals(requireHost, ignoreCase = true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) {
                        throw ParseException("${kind.key}: url not on https://$requireHost")
                    }
                } else if (scheme != "https" && scheme != "http") {
                    throw ParseException("${kind.key}: url is not http(s)")
                }
                val sha = f.optString("sha256").lowercase()
                if (!SHA256.matches(sha)) throw ParseException("${kind.key}: bad sha256")
                val size = f.optLong("size", -1)
                if (size <= 0 || size > MAX_INSTALLER_BYTES) throw ParseException("${kind.key}: bad size $size")
                files[kind] = UpdateFile(url, sha, size)
            }
            if (files.isEmpty()) throw ParseException("no installer listed")
            val notes = LinkedHashMap<String, String>()
            when (val n = j.opt("notes")) {
                is String -> if (n.isNotBlank()) notes["en"] = n
                is JSONObject -> n.keys().forEach { k -> (n.opt(k) as? String)?.takeIf { it.isNotBlank() }?.let { notes[k] = it } }
            }
            val page = j.optString("page").takeIf { p ->
                p.isNotBlank() && runCatching { java.net.URI(p).scheme.equals("https", true) }.getOrDefault(false)
            }
            return UpdateManifest(version, j.optString("released").ifBlank { null }, page, files, notes)
        }

        /** The 1.6.0 installers are ~110 MB; 1 GB is "something is wrong", not a release. */
        const val MAX_INSTALLER_BYTES = 1L shl 30
    }
}

data class UpdateFile(val url: String, val sha256: String, val size: Long)

/** The four installers a release publishes. [key] is the manifest's name for each. */
enum class InstallerKind(val key: String, val extension: String) {
    EXE("exe", "exe"), MSI("msi", "msi"), DEB("deb", "deb"), RPM("rpm", "rpm"),
}

/**
 * MAJOR.MINOR.PATCH, numbers only — the only shape jpackage accepts for every platform this
 * ships on (see `requireLegalAppVersion` in build.gradle.kts). Anything else is not a version
 * the checker will compare, so a garbled running or offered version never prompts.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val SHAPE = Regex("^(\\d{1,5})(?:\\.(\\d{1,5}))?(?:\\.(\\d{1,5}))?$")

        fun parse(s: String?): Version? {
            val m = SHAPE.matchEntire(s?.trim().orEmpty()) ?: return null
            return Version(
                m.groupValues[1].toInt(),
                m.groupValues[2].ifEmpty { "0" }.toInt(),
                m.groupValues[3].ifEmpty { "0" }.toInt(),
            )
        }
    }
}
