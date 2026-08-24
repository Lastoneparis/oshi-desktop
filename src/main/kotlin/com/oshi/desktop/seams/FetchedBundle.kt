package com.oshi.messenger.network.v2

/**
 * Mirror of the `FetchedBundle` declared in the Android tree at
 * `network/v2/V2KeysClient.kt`.
 *
 * We compile `V2Session.kt` verbatim out of the Android tree, and it references this
 * type. We cannot compile `V2KeysClient.kt` itself — it imports android.util.Base64,
 * android.util.Log, BuildConfig and Hilt — so the declaration is restated here in the
 * SAME package, field for field and order for order.
 *
 * This file is one of only two hand-copies in the whole skeleton, and it is a copy of a
 * data-holder with no behaviour. `BundleDeclarationParityTest` re-reads the Android
 * source and fails if the field list there ever stops matching the field list here, so
 * the copy cannot rot silently.
 *
 * In the real desktop client this type should move into a shared `:v2-core` Gradle
 * module that Android, and this project, both depend on — see PLAN.md, "Step 1".
 */
data class FetchedBundle(
    val identityKey: ByteArray,   // peer X25519 identity (== their address)
    val signingKey: ByteArray,    // peer Ed25519 signing pub
    val signedPreKeyId: String,
    val signedPreKey: ByteArray,  // peer X25519 SPK pub (signature verified)
    val oneTimePreKeyId: String?, // null when the server's OPK pool is drained
    val oneTimePreKey: ByteArray?,
)
