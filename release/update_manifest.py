#!/usr/bin/env python3
"""
__DESKTOP_AUTO_UPDATE_2026_11_21__ The signed update manifest the desktop app reads.

    update_manifest.py keygen  [--key ~/.oshi-release/desktop-update-ed25519.key]
    update_manifest.py pubkey  [--key …]                  # base64 public key + fingerprint
    update_manifest.py build   --version 1.6.1 --dir <folder with the 4 installers>
                               [--sums SHA256SUMS-windows.txt --sums SHA256SUMS-linux.txt]
                               (or, without --dir: --sums … --sizes-file sizes.json)
                               [--notes-file notes.json] --out desktop-latest.json
    update_manifest.py sign    --in desktop-latest.json [--key …]   # writes desktop-latest.json.sig
    update_manifest.py verify  --in desktop-latest.json [--pubkey <base64>]

The app (com.oshi.desktop.update) fetches `downloads/desktop-latest.json` and
`downloads/desktop-latest.json.sig`, verifies the Ed25519 signature over the EXACT bytes of the
JSON with the public key compiled into it, and only then parses it. The .sig file is the
64-byte signature, base64, on one line.

The PRIVATE key never enters a repository: it lives at ~/.oshi-release/desktop-update-ed25519.key
(PKCS#8 PEM, chmod 600). Losing it means the next release cannot be offered in-app (the app
only trusts the compiled-in key); leaking it means anyone who can also serve files as
oshi-messenger.com can push an installer to every desktop user. Back it up offline.

Needs Python 3 with the `cryptography` package (pip install cryptography).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import datetime

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
    from cryptography.exceptions import InvalidSignature
except ImportError:  # pragma: no cover
    sys.exit("update_manifest.py needs the 'cryptography' package: python3 -m pip install cryptography")

DEFAULT_KEY = os.path.expanduser("~/.oshi-release/desktop-update-ed25519.key")
BASE_URL = "https://oshi-messenger.com/downloads/"
PAGE_URL = "https://oshi-messenger.com/desktop"
# Stable names in the GitHub release / on the site → manifest kind. The manifest points at the
# VERSIONED copies publish_website.sh also writes, so a download that straddles the next
# release's swap still matches its own hash.
STABLE = {
    "exe": "OSHI-Desktop-Setup.exe",
    "msi": "OSHI-Desktop.msi",
    "deb": "oshi-desktop-amd64.deb",
    "rpm": "oshi-desktop.x86_64.rpm",
}


def versioned_name(kind, ver):
    return {
        "exe": f"OSHI-{ver}.exe",
        "msi": f"OSHI-{ver}.msi",
        "deb": f"oshi-desktop_{ver}-1_amd64.deb",
        "rpm": f"oshi-desktop-{ver}-1.x86_64.rpm",
    }[kind]


def load_private(path):
    with open(path, "rb") as f:
        k = serialization.load_pem_private_key(f.read(), password=None)
    if not isinstance(k, Ed25519PrivateKey):
        sys.exit(f"{path} is not an Ed25519 private key")
    return k


def raw_public(pub):
    return pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def fingerprint(raw):
    """SHA-256 of the 32 raw public-key bytes, hex — what UpdateKeys.kt prints too."""
    return hashlib.sha256(raw).hexdigest()


def cmd_keygen(a):
    if os.path.exists(a.key):
        sys.exit(f"refusing to overwrite {a.key} — a new key orphans every installed app")
    os.makedirs(os.path.dirname(a.key), mode=0o700, exist_ok=True)
    k = Ed25519PrivateKey.generate()
    pem = k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    fd = os.open(a.key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    raw = raw_public(k.public_key())
    print("private key :", a.key, "(chmod 600)")
    print("public key  :", base64.b64encode(raw).decode())
    print("fingerprint :", fingerprint(raw))


def cmd_pubkey(a):
    raw = raw_public(load_private(a.key).public_key())
    print(base64.b64encode(raw).decode())
    print("sha256:" + fingerprint(raw), file=sys.stderr)


def read_sums(paths):
    out = {}
    for p in paths or []:
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = re.match(r"^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$", line.strip())
                if m:
                    out[os.path.basename(m.group(2))] = m.group(1).lower()
    return out


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_build(a):
    if not re.fullmatch(r"\d+\.\d+\.\d+", a.version):
        sys.exit("--version must look like 1.2.3")
    sums = read_sums(a.sums)
    sizes = {}
    if a.sizes_file:
        with open(a.sizes_file, encoding="utf-8") as f:
            sizes = {k: int(v) for k, v in json.load(f).items()}
    if not a.dir and not sizes:
        sys.exit("give --dir (the installers) or --sums + --sizes-file (hashes and sizes only)")
    files = {}
    for kind, stable in STABLE.items():
        vname = versioned_name(kind, a.version)
        listed = sums.get(stable) or sums.get(vname)
        path = None
        for cand in ([os.path.join(a.dir, stable), os.path.join(a.dir, vname)] if a.dir else []):
            if os.path.isfile(cand):
                path = cand
                break
        if path:
            digest, size = sha256_file(path), os.path.getsize(path)
            if sums and listed is None:
                print(f"warning: {stable} is not listed in the SHA256SUMS given", file=sys.stderr)
            if listed is not None and listed != digest:
                sys.exit(f"ABORT: {stable} hashes to {digest} but the release's SHA256SUMS says {listed}")
        else:
            # Hashes-only mode (sign_update_manifest.sh): the release's own SHA256SUMS + the
            # asset sizes GitHub reports. The upload step re-hashes the SERVED files before
            # the manifest goes live, so a mismatch with what users download cannot ship.
            size = sizes.get(stable) or sizes.get(vname)
            if listed is None or size is None:
                sys.exit(f"missing {stable}: not in --dir, and no SHA256SUMS line + size for it")
            if sizes.get(stable) and sizes.get(vname) and sizes[stable] != sizes[vname]:
                sys.exit(f"ABORT: {stable} and {vname} differ in size")
            digest = listed
        files[kind] = {"url": BASE_URL + vname, "sha256": digest, "size": size}
    manifest = {
        "schema": 1,
        "product": "oshi-desktop",
        "version": a.version,
        "released": a.released or datetime.date.today().isoformat(),
        "page": PAGE_URL,
        "files": files,
    }
    if a.notes_file:
        with open(a.notes_file, encoding="utf-8") as f:
            notes = json.load(f)
        if isinstance(notes, str):
            notes = {"en": notes}
        if not isinstance(notes, dict) or not all(isinstance(v, str) for v in notes.values()):
            sys.exit("--notes-file must hold a string or an object {language tag: text}")
        manifest["notes"] = notes
    body = json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=False) + "\n"
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(body)
    print(f"wrote {a.out} ({len(body.encode())} bytes) for {a.version}")


def cmd_sign(a):
    k = load_private(a.key)
    with open(a.inp, "rb") as f:
        data = f.read()
    manifest = json.loads(data)  # never sign something the app could not parse
    if manifest.get("product") != "oshi-desktop":
        sys.exit("refusing to sign: product is not oshi-desktop")
    sig = k.sign(data)
    k.public_key().verify(sig, data)
    out = a.inp + ".sig"
    with open(out, "w", encoding="ascii") as f:
        f.write(base64.b64encode(sig).decode() + "\n")
    print(f"wrote {out}  (key sha256:{fingerprint(raw_public(k.public_key()))})")


def cmd_verify(a):
    with open(a.inp, "rb") as f:
        data = f.read()
    with open(a.inp + ".sig", "rb") as f:
        sig = base64.b64decode(f.read().strip(), validate=True)
    if a.pubkey:
        raw = base64.b64decode(a.pubkey)
    else:
        raw = raw_public(load_private(a.key).public_key())
    try:
        Ed25519PublicKey.from_public_bytes(raw).verify(sig, data)
    except InvalidSignature:
        sys.exit("BAD SIGNATURE")
    m = json.loads(data)
    print(f"OK: {m['product']} {m['version']} signed by sha256:{fingerprint(raw)}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(dest="cmd", required=True)
    g = s.add_parser("keygen"); g.add_argument("--key", default=DEFAULT_KEY); g.set_defaults(f=cmd_keygen)
    g = s.add_parser("pubkey"); g.add_argument("--key", default=DEFAULT_KEY); g.set_defaults(f=cmd_pubkey)
    g = s.add_parser("build")
    g.add_argument("--version", required=True)
    g.add_argument("--dir")
    g.add_argument("--sums", action="append")
    g.add_argument("--sizes-file", help="JSON {asset name: size} (hashes-only mode)")
    g.add_argument("--notes-file")
    g.add_argument("--released")
    g.add_argument("--out", required=True)
    g.set_defaults(f=cmd_build)
    g = s.add_parser("sign"); g.add_argument("--in", dest="inp", required=True); g.add_argument("--key", default=DEFAULT_KEY); g.set_defaults(f=cmd_sign)
    g = s.add_parser("verify"); g.add_argument("--in", dest="inp", required=True); g.add_argument("--key", default=DEFAULT_KEY)
    g.add_argument("--pubkey"); g.set_defaults(f=cmd_verify)
    a = p.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
