#!/usr/bin/env python3
"""
Linux-only scenarios for the INSTALLED OSHI desktop package (desktop 1.6.1 readiness pass).
Reuses pc_smoke.py's screenshot/OCR/xdotool helpers. Throwaway profiles only; nothing is
published. Every check is recorded; exit 1 when a MUST check failed.

  --scenario nokeyring   stock session with NO Secret Service and NO tray (openbox):
                         the passphrase window (create, too short, wrong, unlock), first run,
                         WM_CLASS, the close button quitting cleanly when there is no tray.
  --scenario gnome       a real GNOME Shell (headless Wayland, the app on Xwayland): first run,
                         main window, notification, close with no tray.
"""
import argparse, json, os, re, subprocess, sys, time, traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pc_smoke as S  # noqa: E402

PASS = "correct horse battery"


def gnome_shot(name):
    """Under GNOME Wayland the X root window is black: ask the Shell (allowed in --unsafe-mode)."""
    p = S.OUT / "shots" / f"{len(list((S.OUT / 'shots').glob('*.png'))):02d}-{name}.png"
    r = subprocess.run(["gdbus", "call", "--session", "--dest", "org.gnome.Shell.Screenshot",
                        "--object-path", "/org/gnome/Shell/Screenshot", "--method",
                        "org.gnome.Shell.Screenshot.Screenshot", "false", "false", str(p)],
                       capture_output=True, text=True, timeout=30)
    if not p.exists():
        print(f"   gnome screenshot failed: {r.stdout} {r.stderr}")
        return None
    return p


if os.environ.get("LINUXQA_SHOT") == "gnome":
    S.shot = gnome_shot


def window_ids():
    r = subprocess.run(["xdotool", "search", "--onlyvisible", "--class", "OSHI"], capture_output=True, text=True)
    return r.stdout.split()


def wm_class(wid):
    return subprocess.run(["xprop", "-id", wid, "WM_CLASS"], capture_output=True, text=True).stdout.strip()


def ocr(name):
    """Screenshot + OCR of the non-black area only, read twice (block and sparse layout): small red
    error lines next to a black Xvfb background were dropped by a whole-screen pass."""
    p = S.shot(name)
    if not p:
        return ""
    t = ""
    try:
        import pytesseract
        from PIL import Image, ImageOps
        img = Image.open(p).convert("RGB")
        box = ImageOps.grayscale(img).point(lambda v: 255 if v > 24 else 0).getbbox()
        if box:
            img = img.crop(box)
        img = img.resize((img.width * 2, img.height * 2))
        langs = os.environ.get("OCR_LANGS", "eng")
        t = " ".join(pytesseract.image_to_string(img, lang=langs, config=f"--psm {m}") for m in (3, 11))
        t = re.sub(r"\s+", " ", t)
        Path(str(p) + ".ocr.txt").write_text(t, encoding="utf-8")
    except Exception as e:
        print(f"   OCR failed: {e}")
        t = S.screen_text(name + "-fallback")
    print(f"   [{name}] {t[:300]}")
    return t


def wait_text(pattern, name, timeout=60):
    deadline = time.time() + timeout
    t = ""
    while time.time() < deadline:
        t = ocr(name)
        if re.search(pattern, t, re.I):
            return t
        time.sleep(3)
    return None


def select_all_and_type(s):
    subprocess.run(["xdotool", "key", "ctrl+a"]); time.sleep(0.2)
    S.type_text(s)


def gui(exe, home, tag, env_extra=None):
    env = {"OSHI_UPDATE_FIRST_CHECK_SECONDS": "3600"}
    env.update(env_extra or {})
    return S.Gui(exe, home, tag, env_extra=env)


def nokeyring(exe, work):
    home = work / "Profil sans trousseau é"
    # 1. no keyring + no OSHI_PASSPHRASE: the passphrase window, not a failure screen
    g = gui(exe, home, "nokeyring-1")
    w = g.wait_window(90)
    S.record("no keyring: a window opens", bool(w), g.log()[-1500:])
    t = wait_text(r"passphrase", "nokeyring-create", 40)
    S.record("no keyring: the passphrase window (not 'set OSHI_PASSPHRASE')", t is not None and "OSHI_PASSPHRASE" not in (t or ""), t or g.log()[-1500:])
    for wid in window_ids():
        c = wm_class(wid)
        S.record("WM_CLASS is OSHI (dock grouping / StartupWMClass)", '"OSHI"' in c, c)
        break
    S.type_text("short"); S.key("enter")
    t = wait_text(r"at least 8|8 characters", "nokeyring-too-short", 15)
    S.record("a short passphrase is refused with a reason", t is not None, t or "")
    select_all_and_type(PASS); S.key("tab"); S.type_text(PASS + "x"); S.key("enter")
    t = wait_text(r"not the same", "nokeyring-mismatch", 15)
    S.record("two different passphrases are refused", t is not None, t or "")
    select_all_and_type(PASS); S.key("enter")      # focus is still in the second field
    t = wait_text(r"Create|Import", "nokeyring-first-run", 60)
    S.record("the passphrase leads to the first-run screen", t is not None, t or g.log()[-1500:])
    S.click_create("nokeyring-create-identity")
    t = wait_text(r"Select a chat|Chats|Filter chats", "nokeyring-main", 90)
    S.record("main window after creating the identity on a passphrase vault", t is not None, g.log()[-2000:])
    vault = home / "keyvault.json"
    prot = ""
    try:
        prot = json.loads(vault.read_text()).get("protection", "")
    except Exception as e:
        prot = f"unreadable: {e}"
    S.record("the vault is passphrase-protected", prot == "passphrase", prot)
    mode = oct(os.stat(home).st_mode & 0o777) if home.exists() else "missing"
    S.record("profile directory is 0700", mode == "0o700", mode)
    # 2. close with no tray: the process must END (nothing left running unreachable)
    for wid in window_ids():
        subprocess.run(["wmctrl", "-i", "-c", hex(int(wid))])
    deadline = time.time() + 30
    while time.time() < deadline and g.alive():
        time.sleep(1)
    S.record("no tray: closing the window quits OSHI (no invisible process left)", not g.alive(), g.log()[-1500:])
    g.stop()
    # 3. relaunch: unlock window, wrong passphrase, right passphrase
    g = gui(exe, home, "nokeyring-2")
    g.wait_window(90)
    t = wait_text(r"Unlock|passphrase", "nokeyring-unlock", 40)
    S.record("second launch asks to UNLOCK", t is not None and re.search(r"Unlock", t or "", re.I) is not None, t or g.log()[-1500:])
    S.type_text("wrong passphrase"); S.key("enter")
    t = wait_text(r"does not open", "nokeyring-wrong", 30)
    S.record("a wrong passphrase is refused and asked again", t is not None, t or g.log()[-1500:])
    select_all_and_type(PASS); S.key("enter")
    t = wait_text(r"Select a chat|Chats|Filter chats", "nokeyring-main-2", 90)
    S.record("the right passphrase opens the same account", t is not None and "Create" not in (t or ""), g.log()[-2000:])
    g.stop()
    bad = [l for l in (S.OUT / "gui-nokeyring-1.log").read_text(errors="replace").splitlines() + g.log().splitlines()
           if re.search(r"Exception|Error:", l) and "warning" not in l.lower()]
    S.record("no exception in the logs", not bad, "\n".join(bad[:20]), must=False)


def window_width():
    for wid in window_ids():
        g = subprocess.run(["xdotool", "getwindowgeometry", wid], capture_output=True, text=True).stdout
        m = re.search(r"Geometry:\s*(\d+)x(\d+)", g)
        if m:
            return int(m.group(1)), g
    return None, ""


def hidpi(exe, work):
    """Xft.dpi 192 on a 3840x2160 X11 screen: the window must open at 2x (1180dp -> ~2360px)."""
    subprocess.run(["xrdb", "-merge"], input="Xft.dpi: 192\n", text=True)
    g = gui(exe, work / "hidpi", "hidpi", env_extra={"OSHI_PASSPHRASE": PASS})
    g.wait_window(90); time.sleep(8)
    w, geo = window_width()
    ocr("hidpi-first-run")
    S.record("HiDPI (Xft.dpi 192, X11): the window opens at scale 2", w is not None and w >= 1700, f"width={w} {geo} log={g.log()[-600:]}")
    g.stop()
    subprocess.run(["xrdb", "-merge"], input="Xft.dpi: 96\n", text=True)
    g = gui(exe, work / "hidpi", "lodpi", env_extra={"OSHI_PASSPHRASE": PASS})
    g.wait_window(90); time.sleep(8)
    w1, geo1 = window_width()
    S.record("Xft.dpi 96: the window opens at scale 1", w1 is not None and w1 < 1700, f"width={w1} {geo1}")
    g.stop()


def shell_eval(js):
    r = subprocess.run(["gdbus", "call", "--session", "--dest", "org.gnome.Shell", "--object-path", "/org/gnome/Shell",
                        "--method", "org.gnome.Shell.Eval", js], capture_output=True, text=True, timeout=20)
    return (r.stdout + r.stderr).strip()


def gnome(exe, work):
    home = work / "gnome"
    print("   overview hide:", shell_eval("Main.overview.hide(); 'ok'"))
    time.sleep(2)
    g = gui(exe, home, "gnome-1", env_extra={"OSHI_PASSPHRASE": PASS})
    w = g.wait_window(120)
    S.record("GNOME: a window opens on Xwayland", bool(w), g.log()[-1500:])
    time.sleep(5)
    t = wait_text(r"Create|Import", "gnome-first-run", 60)
    S.record("GNOME: first-run screen is drawn", t is not None, t or "")
    print("   overview hide:", shell_eval("Main.overview.hide(); 'ok'"))
    time.sleep(2)
    S.click_create("gnome-create")
    t = wait_text(r"Select a chat|Chats|Filter chats", "gnome-main", 90)
    S.record("GNOME: main window", t is not None, g.log()[-1500:])
    apps = shell_eval("global.get_window_actors().map(a => { const w = a.meta_window; "
                      "const app = Shell.WindowTracker.get_default().get_window_app(w); "
                      "return w.get_wm_class() + ' -> ' + (app ? app.get_id() : 'none'); }).join('; ')")
    S.record("GNOME: the OSHI window belongs to the OSHI app (dock icon, pinning)", "OSHI.desktop" in apps or "oshi" in apps.lower().split("->")[-1], apps, must=False)
    subprocess.run(["notify-send", "--app-name=OSHI", "OSHI", "notification test"])
    time.sleep(2)
    ocr("gnome-notification")
    # The title-bar close, as GNOME sends it (XTEST keys on Xwayland never reach mutter's alt+F4).
    print("   close:", shell_eval("global.get_window_actors().map(a => a.meta_window)"
                                 ".filter(w => w.get_wm_class() === 'OSHI')"
                                 ".map(w => { w.delete(global.get_current_time()); return w.get_title(); }).join(',')"))
    deadline = time.time() + 30
    while time.time() < deadline and g.alive():
        time.sleep(1)
    S.record("GNOME (no tray): closing the window quits OSHI", not g.alive(), g.log()[-1500:])
    g.stop()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--scenario", choices=["nokeyring", "gnome", "hidpi"], required=True)
    a = ap.parse_args()
    S.OUT = Path(a.out).resolve(); (S.OUT / "shots").mkdir(parents=True, exist_ok=True)
    exe = Path(a.app) / "bin" / "OSHI"
    work = S.OUT / "work"; work.mkdir(parents=True, exist_ok=True)
    try:
        {"nokeyring": nokeyring, "gnome": gnome, "hidpi": hidpi}[a.scenario](exe, work)
    except Exception:
        S.record("driver crashed", False, traceback.format_exc())
    finally:
        (S.OUT / f"summary-{a.scenario}.json").write_text(json.dumps(S.results, indent=1, ensure_ascii=False), encoding="utf-8")
        failed = [r for r in S.results if r["must"] and not r["ok"]]
        print(f"\n==== {len(S.results)} checks, {len(failed)} MUST failures ====")
        for r in failed:
            print(" FAIL", r["name"], r["detail"][:800])
        sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
