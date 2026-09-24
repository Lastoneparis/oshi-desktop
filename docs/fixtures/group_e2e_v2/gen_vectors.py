#!/usr/bin/env python3
"""Golden vectors for docs/GROUP_E2E_V2_SPEC.md (independent reference, stdlib only).

Run:  python3 gen_vectors.py > vectors.json

Every vector gives the INNER plaintext of a v2 ratchet envelope (what is encrypted), never
ciphertext: the ratchet is already covered by the v2 vectors. Comparison rule (spec §9):
parse both sides as JSON and compare values; numbers compare as doubles (|a-b| < 1e-6);
key order and number spelling (811692800.123 vs 8.11692800123E8) are NOT significant.
For the `content` channel the envelope plaintext is base64(JSON); decode it first.
"""
import base64, json

APPLE = 978307200.0
A = base64.b64encode(bytes([0x11]) * 32).decode()   # sender / creator / admin
B = base64.b64encode(bytes([0x22]) * 32).decode()
C = base64.b64encode(bytes([0x33]) * 32).decode()
D = base64.b64encode(bytes([0x44]) * 32).decode()
GID = "5E2A9C1B-7D3F-4B8A-9E6C-0F1D2A3B4C5D"
MID = "0F8E1C2A-3B4D-4E5F-8A9B-0C1D2E3F4A5B"       # the target user message
SID = "7A6B5C4D-3E2F-4A1B-8C9D-0E1F2A3B4C5D"       # a second id (system / reaction / reply)
TS_MS = 1790000000123                             # Unix millis of the message's own date
TS_APPLE = TS_MS / 1000.0 - APPLE                 # 811692800.123
ISO = "2026-09-21T14:13:20Z"                      # WireClock.toIso8601(1790000000000), second precision
PREFIX_UPDATE = "\U0001F4E2GROUP_UPDATE\U0001F4E2"


def b64(s: str) -> str:
    return base64.b64encode(s.encode("utf-8")).decode()


def gm(body, *, id=MID, sender=A, ts=TS_APPLE, media_type=None, file_name=None, caption=None,
       system=None, system_data=None, sender_name=""):
    """GroupMessage JSON in the emitter key order used by Android/Desktop."""
    o = {"id": id, "groupId": GID, "senderPublicKey": sender,
         "encryptedContent": b64(body) if body else "",
         "timestamp": ts, "isRead": system is not None, "messageChainIndex": 0}
    if media_type: o["mediaType"] = media_type
    if file_name: o["mediaFileName"] = file_name
    if media_type and caption: o["plaintextContent"] = caption
    if system:
        o["systemMessageType"] = system
        o["systemMessageData"] = system_data
    else:
        o["messageId"] = id
        o["content"] = body
        o["senderName"] = sender_name
    return o


def compact(o) -> str:
    return json.dumps(o, ensure_ascii=False, separators=(",", ":"))


def content(name, note, g, body=None):
    js = compact(g)
    v = {"name": name, "note": note, "channel": "content",
         "envelope": {"type": "group", "groupId": GID, "from": g.get("systemMessageData", {}).get("actorPublicKey", A) if g["senderPublicKey"] == "SYSTEM" else g["senderPublicKey"]},
         "groupMessage": g, "plaintext": b64(js)}
    if body is not None:
        v["body"] = body
    return v


def state(name, note, o, frm=A):
    return {"name": name, "note": note, "channel": "state",
            "envelope": {"type": "1to1", "groupId": None, "from": frm},
            "json": o, "plaintext": PREFIX_UPDATE + compact(o)}


def member(k, admin, alias=None):
    m = {"publicKey": k}
    if alias: m["alias"] = alias
    m["joinedAt"] = ISO
    m["isAdmin"] = admin
    return m


def definition(name, members, version, **extra):
    o = {"id": GID, "name": name, "type": "collaborative", "adminPublicKey": A,
         "members": members, "createdAt": ISO, "lastActivity": ISO, "isMuted": False}
    o.update(extra)
    o["stateVersion"] = version
    return o


V = {"version": 1, "spec": "docs/GROUP_E2E_V2_SPEC.md",
     "constants": {"A": A, "B": B, "C": C, "D": D, "groupId": GID, "messageId": MID, "otherId": SID,
                   "tsUnixMs": TS_MS, "tsAppleEpoch": TS_APPLE, "iso": ISO,
                   "groupUpdatePrefix": PREFIX_UPDATE},
     "content": [], "media": [], "state": [], "sentCopy": [], "receiver": []}

text = "hello group ✓"
V["content"].append(content("text", "plain text", gm(text), text))

reply_body = "\U0001F4ACREPLY\U0001F4AC" + compact({"content": "yes", "replyTo": {
    "originalMessageId": MID, "originalSenderKey": B, "originalText": "are you in?",
    "originalTimestamp": TS_APPLE, "originalMediaType": None}})
V["content"].append(content("reply", "reply envelope inside the body; iOS MessageWithReply", gm(reply_body, id=SID), reply_body))

react = "\U0001F525REACTION\U0001F525" + compact({"messageId": MID, "emoji": "\U0001F44D", "senderPublicKey": A,
                                                  "senderName": "Alice", "timestamp": TS_APPLE, "action": "add"})
V["content"].append(content("reaction_add", "iOS ReactionPayload; never stored as a bubble", gm(react, id=SID), react))
unreact = react.replace('"action":"add"', '"action":"remove"')
V["content"].append(content("reaction_remove", "same, action=remove", gm(unreact, id=SID), unreact))

typing = "⌨️TYPING⌨️" + compact({"senderPublicKey": A, "isTyping": True, "timestamp": TS_APPLE,
                                                  "groupId": GID, "senderName": "Alice"})
V["content"].append(content("typing", "ephemeral: not stored, no push, no self copy", gm(typing, id=SID), typing))

V["content"].append(content("edit", "message_edited system message; actor MUST equal envelope.from and be the author",
    gm("", id=SID, sender="SYSTEM", system="message_edited",
       system_data={"actorPublicKey": A, "editedMessageId": MID, "editedContent": "hello group (edited)"})))
V["content"].append(content("delete", "message_deleted system message; actor MUST equal envelope.from and be the author or an admin",
    gm("", id=SID, sender="SYSTEM", system="message_deleted",
       system_data={"actorPublicKey": A, "deletedMessageId": MID})))

stripped = gm("", media_type="photo", file_name="photo.jpg", caption="sunset")
stripped["content"] = ""
V["media"].append({"name": "photo", "note": "v2file key message (ratchet plaintext of a type=group envelope). blobId/fileKey/fileNonce/manifest are placeholders: the receiver takes groupMessage from here and the bytes from the blob.",
    "channel": "content", "envelope": {"type": "group", "groupId": GID, "from": A},
    "groupMessage": stripped,
    "keyMessage": {"kind": "v2file", "blobId": "blob-placeholder", "fileKey": "AAAA", "fileNonce": "AAAA",
                   "chunkCount": 1, "manifest": "AAAA", "filename": "photo.jpg", "mime": "image/jpeg",
                   "mediaType": "photo", "groupMessage": b64(compact(stripped))}})

members3 = [member(A, True), member(B, False), member(C, False)]
V["state"].append(state("create", "creator sends the definition to every member (stateVersion 1)",
                        definition("Team", members3, 1, description="weekly sync")))
V["state"].append(state("created_minimal", "Android<=1.6 compatibility; OPTIONAL, sent AFTER the definition",
    {"type": "created", "groupId": GID, "name": "Team", "description": "weekly sync", "creatorPublicKey": A,
     "members": [A, B, C], "admins": [A], "isPublic": False}))
V["state"].append(state("rename", "definition v2 (authoritative)", definition("Team 2", members3, 2, description="weekly sync")))
V["state"].append(state("rename_minimal", "OPTIONAL companion", {"type": "group_renamed", "groupId": GID, "name": "Team 2"}))
members4 = members3 + [member(D, False)]
V["state"].append(state("add_member", "definition v3 to the whole NEW roster, D included",
                        definition("Team 2", members4, 3, description="weekly sync")))
V["state"].append(state("add_member_minimal", "OPTIONAL companion", {"type": "member_added", "groupId": GID, "memberPublicKey": D}))
members_wo_c = [member(A, True), member(B, False), member(D, False)]
V["state"].append(state("remove_member", "definition v4 to the remaining members AND to C; evictedMemberKeys carries C",
                        definition("Team 2", members_wo_c, 4, description="weekly sync", evictedMemberKeys=[C])))
V["state"].append(state("remove_member_minimal", "REQUIRED companion to the removed member (so it learns), OPTIONAL to others",
                        {"type": "member_removed", "groupId": GID, "memberPublicKey": C}))
V["state"].append(state("leave", "B leaves: member_removed naming B, sent BY B (self-departure, accepted from any member in any group type)",
                        {"type": "member_removed", "groupId": GID, "memberPublicKey": B}, frm=B))
V["state"].append(state("promote_admin", "definition v5, D promoted (sender must already be admin)",
                        definition("Team 2", [member(A, True), member(B, False), member(D, True)], 5, description="weekly sync", evictedMemberKeys=[C])))
V["state"].append(state("pin", "definition carrying the pin", definition("Team 2", members_wo_c, 6, description="weekly sync",
                        evictedMemberKeys=[C], pinnedMessageId=MID, pinnedBy=A)))
V["state"].append(state("avatar_emoji", "emoji avatar rides every definition", definition("Team 2", members_wo_c, 7, description="weekly sync",
                        evictedMemberKeys=[C], avatar="\U0001F680")))
V["state"].append(state("picture", "picture inline (JPEG base64, framed plaintext <= 180000 bytes); placeholder bytes",
                        definition("Team 2", members_wo_c, 8, groupPictureData="/9j/AA==", groupPictureUpdatedBy=A, groupPictureUpdatedAt=ISO)))
V["state"].append(state("read_receipt_batch", "OPTIONAL; timestamp is Unix millis here",
                        {"type": "read_receipt_batch", "groupId": GID, "messageIds": [MID], "senderPublicKey": B, "timestamp": TS_MS}, frm=B))
V["state"].append(state("member_sync_request", "member asks for the definition", {"type": "member_sync_request", "groupId": GID, "requesterPublicKey": B}, frm=B))
V["state"].append(state("sync_request", "ask for every group we share", {"type": "sync_request", "requesterKey": B}, frm=B))

V["sentCopy"].append({"name": "group_text_self_copy", "note": "one per group message per OTHER own device (to=me,toDevice=other); message = the exact content plaintext",
    "plaintext": {"kind": "sent-copy", "v": 1, "conversation": {"type": "group", "groupId": GID},
                  "message": V["content"][0]["plaintext"], "msgId": MID, "sentAt": ISO}})

V["receiver"] = [
    {"name": "sender_mismatch", "rule": "content: GroupMessage.senderPublicKey != envelope.from (canonical) and not SYSTEM => drop",
     "envelopeFrom": B, "groupMessage": gm("spoof")},
    {"name": "system_actor_mismatch", "rule": "SYSTEM message whose systemMessageData.actorPublicKey != envelope.from => drop",
     "envelopeFrom": B, "groupMessage": V["content"][5]["groupMessage"]},
    {"name": "group_update_on_group_channel", "rule": "a type=group envelope whose plaintext is not base64(JSON GroupMessage) or v2file => drop (never shown)",
     "envelopeFrom": A, "plaintext": PREFIX_UPDATE + "{}"},
    {"name": "stale_definition", "rule": "incoming stateVersion < local stateVersion => ignore",
     "local": 5, "incoming": 4, "apply": False},
    {"name": "equal_definition", "rule": "incoming stateVersion == local => apply (authorizer still gates), local = max",
     "local": 5, "incoming": 5, "apply": True},
    {"name": "unversioned_definition", "rule": "either side without stateVersion => no version check",
     "local": None, "incoming": 4, "apply": True},
]

print(json.dumps(V, ensure_ascii=False, indent=2))
