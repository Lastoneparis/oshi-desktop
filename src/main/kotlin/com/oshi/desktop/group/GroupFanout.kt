package com.oshi.desktop.group

/**
 * How a group message is actually encrypted — and the answer is: **it is not**. Not as a
 * group. PARITY.md row 0.17's central question, answered from the shipped source.
 *
 * ============================================================ THERE IS NO GROUP KEY
 *
 * The question row 0.17 has to settle before a line of crypto is written is whether OSHI
 * groups use a per-group key, a sender-key ratchet, or fan-out over pairwise sessions. Every
 * plausible answer is present somewhere in the two trees, which is why it has to be read
 * rather than assumed. All three, and what each one turns out to be:
 *
 * **1. Sender keys — DECLARED, NEVER USED.** `OSHI/GroupMessaging.swift:414-420` defines
 * `struct SenderKey { chainKey, messageKeys, chainIndex }` under a `MARK: - Sender Key (for
 * group encryption)` heading. A `senderKeys` table is held (`:463`), persisted (`:3768`),
 * loaded (`:3779`), migrated (`:1168`) and cleared (`:3599`). It is never subscripted, never
 * consulted, and no byte anywhere is encrypted or decrypted with it: `chainKey` and
 * `messageKeys` appear exactly twice in the file — at their own declaration. It is dead
 * storage wearing the name of a protocol. **Anyone porting from this file by reading its
 * type names would implement a group ratchet that talks to nobody.**
 *
 * **2. A per-group key — REAL, AND IT HAS NO SECRET IN IT.** The legacy IPFS path derives
 * one (`deriveGroupKey`, `OSHI/GroupMessaging.swift:487-495`, matched byte for byte by
 * Android's `GroupManager.kt:2423-2431`):
 *
 *     groupKey = SHA256( utf8(uppercase(groupId)) || utf8("oshi-group-envelope-key") )
 *
 * Every input is public. The group id travels in cleartext in the mesh public-group
 * advertisement, in `{"type":"created"}`, in every minimal update, and in the v2 envelope
 * header the relay reads. So this key is derivable by anyone who has ever seen the group id,
 * including the relay and any nearby mesh peer — it is obfuscation, not encryption, and
 * calling it a group key in a design document would be repeating the mistake. It is used for
 * exactly one thing: AES-256-GCM over the envelope uploaded to Pinata/IPFS.
 *
 * **3. Fan-out over pairwise v2 sessions — WHAT ACTUALLY CARRIES A GROUP MESSAGE.** iOS's
 * `V2GroupSession` states the model in its own header (`OSHI/V2GroupSession.swift:9-21`):
 * *"The relay has ZERO knowledge of group membership. A group is pure E2E client state …
 * Sending a group message FANS OUT: the client produces one Double-Ratchet-encrypted copy
 * PER member using that member's existing 1:1 session … each wrapped in a `V2.Envelope` with
 * `type == .group` and the shared `groupId`."* The live code path does the same without
 * going through that class at all — `V2MessageRouter.sendGroupText`
 * (`OSHI/V2MessageRouter.swift:710-739`) loops the members, calls `encrypt1to1` on each, and
 * batches the envelopes.
 *
 * So: **N members, N ciphertexts, N ratchets, zero group cryptography.** Forward secrecy and
 * deniability are exactly the pairwise ones. Removing a member is not a rekey — it is simply
 * never including them in a future fan-out (`V2GroupSession.swift:18-21`), so a removed
 * member who kept their session can still read anything sent to a member who forwards it,
 * and nothing about the group's past is protected by their removal. That is a real, shipped
 * security property and this file is not the place to soften it.
 *
 * ============================================================ WHAT THIS OBJECT DOES
 *
 * Almost nothing, and that is the finding. The whole "group crypto layer" a desktop client
 * needs is: pick the recipients, and stamp `groupId` on each envelope. The encryption is the
 * 1:1 path row 0.12 already drives. [plan] is that.
 *
 * ============================================================ V2GroupSession IS NOT SHIPPED PROTOCOL
 *
 * `OSHI/V2GroupSession.swift` also defines a signed membership-control message: Ed25519 over
 *
 *     "v2ctrl\n{add|remove}\n{groupId}\n{target}\n{sender}\n{epoch}"
 *
 * (`swift:127-134`), applied only if the signature verifies against the sender's userKey AND
 * the sender is a current admin (`:264-284`). It is the only cryptographically sound
 * membership mechanism anywhere in either tree — and **this desktop client does not
 * implement it, on purpose.**
 *
 * The string `v2ctrl` occurs exactly once in the entire iOS tree: at the line that builds it.
 * Nothing calls `makeAddMember`, `makeRemoveMember` or `applyControlMessage`; the class is
 * reachable from `V2MessageRouter` (`:217, :234, :253`) but only its `MemberCiphertext`
 * struct is used, by the `V2GroupContext` conformance. It appears **nowhere at all in the
 * Android tree** — Android has no `V2GroupSession`, no `v2ctrl`, no group epoch, and no
 * signed control message of any kind. Emitting one would produce a payload that an iPhone
 * ignores and an Android phone cannot parse.
 *
 * The membership mechanism that IS live on both platforms is the unsigned
 * `📢GROUP_UPDATE📢` definition, gated on receipt by [GroupUpdateAuthorizer]. That is what
 * this package implements. It is weaker, and saying so is the point.
 *
 * ============================================================ BLOCKED SUB-ROWS
 *
 * Three parts of row 0.17 cannot honestly be implemented on this desktop client today. Each
 * is blocked for a different reason and none of them is "not written yet".
 *
 * **0.17-a — Group over the MESH: blocked, and blocked on the phones too.** PLAN_MESH.md §7
 * defect 1 is real and this package's reading confirms both halves of it. iOS's
 * `CrossPlatformMesh.handleReceivedData` routes seven content types and `GROUP_UPDATE` is
 * not among them (`OSHI/CrossPlatformMesh.swift:685-687`); Android sends `GROUP_UPDATE`,
 * `GROUP_MESSAGE` and `PUBLIC_GROUP_AD` over exactly that transport (25 call sites in
 * `GroupManager.kt` alone). On iOS they reach `default:` and are logged and dropped. The
 * send side is symmetrical and worse: iOS's `broadcastFullGroupUpdate` posts its bytes to
 * the `BroadcastGroupUpdate` notification, whose only observer is `MeshNetworkManager`
 * (`OSHI/MeshNetworkManager.swift:1362-1366`) — the MultipeerConnectivity iOS↔iOS mesh.
 * `CrossPlatformMesh` never subscribes. **So an iPhone neither sends nor receives a group
 * update over the cross-platform mesh, in either direction.** A desktop implementation of
 * mesh group sync would work against Android and be invisible to every iPhone, which is not
 * parity — it is a third dialect. Blocked until the phones agree.
 *
 * Group MESSAGES over the mesh are blocked twice over: iOS deletes the branch outright and
 * ships it behind a default-OFF kill switch (`GroupMeshPlaintextBroadcast`,
 * `OSHI/GroupMessaging.swift:16-32`), because what it used to do was broadcast group bodies
 * in CLEARTEXT to every nearby device, member or not. The removal note (`swift:2632-2650`)
 * argues the repair is impossible on that branch — establishing a ratchet needs a server
 * round-trip, so encryption "would work only when it is not needed and fail exactly when it
 * is." Implementing mesh group messaging on desktop means re-opening that hole.
 *
 * **0.17-b — the legacy IPFS group envelope: blocked behind row 0.23.** The Pinata path
 * (`OSHI/GroupMessaging.swift:2831-2880`) is what carries a group message to a member with
 * no v2 bundle. It needs `EnvelopeCrypto`, `MessagePayload`, `PinataService` and the
 * keyless `deriveGroupKey` above — all of which are row 0.23 (`⬜`), and the last of which
 * should be looked at before it is ported rather than after.
 *
 * **0.17-c — group MEDIA: blocked behind rows 0.15 + 0.16.** Group media on v2 goes through
 * `v2TrySendGroupFile` → the blob store, with only the key envelope on the relay
 * (`OSHI/GroupMessaging.swift:2775-2811`). Row 0.15 has the blob client; the group file-key
 * message shape is not yet extracted, so [GroupMessageWire.encodeMediaBlob] emits the INLINE
 * form only — correct for the legacy and small-media paths, and honestly incomplete.
 */
object GroupFanout {

    /**
     * The recipients of one group message: every member except us, in roster order.
     *
     * That is the entire fan-out rule, and both shipped implementations are one loop each —
     * iOS `V2MessageRouter.sendGroupText` (`swift:714`: `for member in members where member
     * != me`) and `V2GroupSession.encryptGroupMessage` (`swift:321`, identically).
     *
     * **Self-exclusion is by canonical identity, not by string equality, and that is a
     * deliberate divergence from both phones.** Both use a raw `!=`. PLAN_MESH.md §7 defect
     * 2 records the cost of raw equality on keys elsewhere: iOS's `sendOrRelay` looks the
     * recipient up with strict equality while Android normalises base64url/padding skew
     * first, and "the skew is real enough that Android carries dedicated code for it". A
     * roster that spells our own key with one character of skew would, under raw equality,
     * make this client encrypt a copy of every group message TO ITSELF — which on the relay
     * means a self-addressed envelope and a message that arrives twice. Folding here costs
     * nothing and cannot produce a wrong recipient: [GroupIdentity.canonicalIdentity] returns
     * its input unchanged when the folded form is not valid base64.
     */
    fun plan(memberKeys: List<String>, selfUserKey: String): List<String> {
        val me = GroupIdentity.canonicalIdentity(selfUserKey)
        return memberKeys.filter { GroupIdentity.canonicalIdentity(it) != me }
    }

    /** [plan] straight off a definition. */
    fun plan(group: GroupDefinition, selfUserKey: String): List<String> =
        plan(group.memberKeys, selfUserKey)

    /**
     * The v2 envelope `type` for group traffic. `"1to1" | "group"`, and the relay validates
     * it — `OSHI/V2ClientModels.swift:34-37` ("Values match `relay_v2.js`
     * `validateEnvelope` exactly"), Android's default at `V2MessagesClient.kt:23`.
     */
    const val ENVELOPE_TYPE_GROUP: String = "group"

    /**
     * **The guard.** A group envelope MUST carry a non-blank `groupId`.
     *
     * Not a tidiness check. Android's router doc (`V2MessageRouter.kt:17-21`) states the
     * failure mode in full: the group plaintext is bare base64 of a JSON `GroupMessage` with
     * no sentinel prefix, so `groupId` is the only thing distinguishing it from a 1:1
     * message — *"a dropped `groupId` lands them as base64 garbage in the 1:1 thread."* The
     * message is not lost, it is delivered WRONG, into the wrong conversation, unreadable,
     * and it looks like a corruption bug rather than a routing one.
     *
     * Called from [envelopeFields], which is the only supported way to build the group
     * envelope's routing fields — the guard is on the path, not beside it.
     */
    fun requireGroupId(groupId: String?) {
        require(!groupId.isNullOrBlank()) {
            "a v2 envelope with type=\"$ENVELOPE_TYPE_GROUP\" must carry a groupId. The group " +
                "plaintext is bare base64(JSON GroupMessage) with no sentinel prefix " +
                "(OSHI-Android/.../network/v2/V2MessageRouter.kt:17-21), so groupId is the only " +
                "thing that routes it; dropping it delivers base64 garbage into the 1:1 thread."
        }
    }

    /**
     * The two routing fields a group envelope adds to the 1:1 shape, guarded.
     *
     * Returns `type` and `groupId` for the caller to merge into the envelope it is building
     * (row 0.2 owns the envelope itself). Deliberately not an envelope builder: the desktop
     * has exactly one of those and this row is not going to grow a second.
     */
    fun envelopeFields(groupId: String): Map<String, String> {
        requireGroupId(groupId)
        return mapOf(
            "type" to ENVELOPE_TYPE_GROUP,
            "groupId" to GroupIdentity.canonicalGroupId(groupId),
        )
    }
}
