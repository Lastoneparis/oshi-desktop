# group_settings_v1 vectors (GROUP_INFO_SETTINGS_SPEC.md §6)

- `definition_with_settings.json`: every subkey. Admin = `adminPublicKey`. Expected parse: visibility public, handle `swiss_quant`, showPreview true, inviteToken `q1w2e3r4t5y6u7i8o9p0aA`, reactions some [👍 ❤️ 🔥], welcome as given, postPolicy admins, adminDMs false, dissolved false.
- `definition_without_settings.json`: what an old build re-emits. Expected: settings KEPT from the previous state, name applied (sender = admin).
- `definition_bad_settings.json`: expected sanitised settings: visibility private (invalid handle), handle none, showPreview false (not a bool), reactions all, allowedReactions truncated to 12, welcome truncated to 1000 chars, postPolicy everyone, adminDMs true (not a bool -> default), dissolved false; `futureKey` ignored; `v` 7 parsed best-effort.
- `definition_with_comments.json` + `comment_*.json`: comments (spec §2.12.9). Expected verdict per file is in the §2.12.9 table: post P stored; C1 (member, top-level) and C2 (admin, reply to C1) stored as comments of P; member plain post dropped `not-admin`; comment on a comment dropped `bad-parent`; comment with media dropped `comment-kind`.
