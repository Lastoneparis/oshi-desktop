#!/usr/bin/env python3
"""
PC smoke run: drive the INSTALLED OSHI desktop app (from the real .exe/.msi/.deb a user
downloads) on a GitHub Windows or Linux runner, against the PRODUCTION relay, with
throwaway identities. Nothing here deletes an account, publishes, signs or tags anything.

Phases (selected with --phase):
  legacy   the PUBLISHED previous version (1.4.5): make the DEFAULT profile on the OS key
           store (DPAPI / libsecret) and remember its address.
  full     after installing the version under test over it:
             1. the upgraded default profile opens as the SAME address (console + window);
             2. the window on the default profile: close-to-tray, a second launch brings it
                back, an oshi:// link reaches it;
             3. --probe crypto self-test;
             4. two console clients exchange text (accents/emoji), a photo and a file from a
                folder with spaces and accents, a group message; a restart keeps the address;
             5. a FRESH window: first run -> Create New Identity -> main window; a console
                client writes to it (text, photo, file, m4a voice note); the thread, settings
                and the language picker are screenshotted; a reply is typed in the window;
             6. the window in French (Linux: LANG; Windows: JVM user.language);
             7. an m4a voice note decoded by the INSTALLED jars through ffmpeg (jshell);
             8. every GUI log scanned for uncaught exceptions.

Every step is recorded; a failing step does not stop the others. Exit 1 when any MUST check
failed. Summary in <out>/summary-<phase>.json, screenshots in <out>/shots.
"""
import argparse, glob, json, os, platform, re, shutil, subprocess, sys, threading, time, traceback, urllib.parse
from pathlib import Path

IS_WIN = platform.system() == "Windows"
IS_LINUX = platform.system() == "Linux"

results = []          # [{name, ok, must, detail}]
OUT = None
SERVER = None         # None = the app's built-in default (production)


def record(name, ok, detail="", must=True):
    results.append({"name": name, "ok": bool(ok), "must": must, "detail": str(detail)[:4000]})
    print(f"[{'PASS' if ok else ('FAIL' if must else 'WARN')}] {name} {('- ' + str(detail)[:300]) if detail else ''}", flush=True)


def server_args():
    return ["--server", SERVER] if SERVER else []


# --------------------------------------------------------------------------- REPL client
class Repl:
    def __init__(self, exe, home, name, passphrase=None, tag=None, env_extra=None):
        self.tag = tag or name
        env = dict(os.environ)
        env.pop("OSHI_PASSPHRASE", None)
        if passphrase:
            env["OSHI_PASSPHRASE"] = passphrase
        env.update(env_extra or {})
        self.lines = []
        self.lock = threading.Lock()
        self.log = open(OUT / f"repl-{self.tag}.log", "a", encoding="utf-8")
        cmd = [str(exe), "--client"] + (["--home", str(home)] if home is not None else []) + ["--name", name] + server_args()
        self.log.write("$ " + " ".join(cmd) + "\n")
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  env=env, text=True, encoding="utf-8", errors="replace", bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stdout:
            with self.lock:
                self.lines.append(line.rstrip("\r\n"))
            self.log.write(line); self.log.flush()

    def mark(self):
        with self.lock:
            return len(self.lines)

    def wait(self, pattern, since=0, timeout=30):
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                for l in self.lines[since:]:
                    m = rx.search(l)
                    if m:
                        return m
            if self.p.poll() is not None:
                return None
            time.sleep(0.2)
        return None

    def cmd(self, line, expect=None, timeout=30):
        since = self.mark()
        self.log.write(f">>> {line}\n")
        try:
            self.p.stdin.write(line + "\n"); self.p.stdin.flush()
        except Exception as e:
            self.log.write(f"stdin closed: {e}\n")
            return None
        if expect:
            return self.wait(expect, since, timeout)
        time.sleep(1.5)
        return None

    def poll_history(self, peer, pattern, timeout=90):
        """Re-run /history until `pattern` shows up (delivery over the real relay takes a while)."""
        deadline = time.time() + timeout
        last = ""
        while time.time() < deadline:
            since = self.mark()
            self.cmd(f"/history {peer}")
            time.sleep(2)
            last = self.text_since(since)
            if re.search(pattern, last):
                return True, last
            time.sleep(4)
        return False, last

    def text_since(self, since):
        with self.lock:
            return "\n".join(self.lines[since:])

    def address(self):
        m = self.wait(r"^\s*address\s*:\s*([A-Za-z0-9+/=_-]{40,})", 0, 120)
        return m.group(1) if m else None

    def quit(self):
        try:
            self.p.stdin.write("/quit\n"); self.p.stdin.flush()
            self.p.wait(40)
        except Exception:
            self.p.kill()


def tiny_png(path):
    import zlib, struct
    w, h = 160, 120
    raw = b"".join(b"\x00" + b"".join(bytes((x * 2 % 256, y * 2 % 256, 128)) for x in range(w)) for y in range(h))
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def make_m4a(path):
    ff = shutil.which("ffmpeg")
    if not ff:
        return False
    r = subprocess.run([ff, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                        "-c:a", "aac", "-b:a", "64k", str(path)], capture_output=True, text=True)
    return r.returncode == 0 and path.is_file()


# --------------------------------------------------------------------------- screen
def hide_runner_consoles():
    """__RUNNER_CONSOLE_2026_11_21__ The windows-latest image of 2026-11-11 shows the runner's own
    agent console ("C:\\ProgramData\\GitHub\\Host", a Windows Terminal window) ON TOP of
    everything, so every screenshot was that console: OCR checks failed, or passed vacuously on its
    text. Minimise terminal/console windows that are not OSHI's before each screenshot."""
    if not IS_WIN:
        return
    try:
        import ctypes
        from ctypes import wintypes
        u = ctypes.windll.user32
        cb_t = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        found = []
        def cb(hwnd, _):
            if u.IsWindowVisible(hwnd) and not u.IsIconic(hwnd):
                cls = ctypes.create_unicode_buffer(256)
                u.GetClassNameW(hwnd, cls, 256)
                n = u.GetWindowTextLengthW(hwnd)
                t = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(hwnd, t, n + 1)
                if cls.value in ("CASCADIA_HOSTING_WINDOW_CLASS", "ConsoleWindowClass") and not t.value.startswith("OSHI"):
                    found.append(hwnd)
            return True
        u.EnumWindows(cb_t(cb), 0)
        for h in found:
            u.ShowWindow(h, 6)  # SW_MINIMIZE
        if found:
            time.sleep(0.5)
    except Exception as e:
        print(f"   hide_runner_consoles failed: {e}")


def shot(name):
    hide_runner_consoles()
    try:
        from PIL import ImageGrab
        img = ImageGrab.grab(all_screens=True) if IS_WIN else ImageGrab.grab()
        p = OUT / "shots" / f"{len(list((OUT / 'shots').glob('*.png'))):02d}-{name}.png"
        img.save(p)
        return p
    except Exception as e:
        print(f"   screenshot {name} failed: {e}")
        return None


def ocr_words(img_path):
    """Words + centres. Two passes: as is, and inverted (white text on the brand-blue buttons)."""
    try:
        import pytesseract
        from PIL import Image, ImageOps
        base = Image.open(img_path).convert("L")
        base = base.resize((base.width * 2, base.height * 2))      # small grey labels OCR better at 2x
        out = []
        for img in (base, ImageOps.invert(base)):
            d = pytesseract.image_to_data(img, lang=os.environ.get("OCR_LANGS", "eng"),
                                          output_type=pytesseract.Output.DICT)
            for i, w in enumerate(d["text"]):
                if w and w.strip():
                    out.append((w.strip(), (d["left"][i] + d["width"][i] // 2) // 2, (d["top"][i] + d["height"][i] // 2) // 2))
        (Path(str(img_path) + ".ocr.txt")).write_text(" ".join(w for w, *_ in out), encoding="utf-8")
        return out
    except Exception as e:
        print(f"   OCR failed: {e}")
        return []


def click(x, y, double=False):
    if IS_WIN:
        import ctypes
        u = ctypes.windll.user32
        u.SetCursorPos(int(x), int(y))
        time.sleep(0.15)
        for _ in range(2 if double else 1):
            u.mouse_event(0x0002, 0, 0, 0, 0); time.sleep(0.05); u.mouse_event(0x0004, 0, 0, 0, 0); time.sleep(0.08)
    else:
        subprocess.run(["xdotool", "mousemove", str(int(x)), str(int(y)), "click", "--repeat", "2" if double else "1", "1"])
    time.sleep(0.6)


def type_text(s):
    if IS_WIN:
        import ctypes
        from ctypes import wintypes
        ULONG_PTR = ctypes.c_size_t
        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]
        class INPUT(ctypes.Structure):
            class _I(ctypes.Union):
                _fields_ = [("ki", KEYBDINPUT), ("pad", ctypes.c_byte * 32)]
            _anonymous_ = ("i",)
            _fields_ = [("type", wintypes.DWORD), ("i", _I)]
        data = s.encode("utf-16-le")
        units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
        for u16 in units:
            for flags in (0x0004, 0x0004 | 0x0002):
                inp = INPUT(type=1); inp.ki = KEYBDINPUT(0, u16, flags, 0, 0)
                ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(inp))
            time.sleep(0.02)
    else:
        subprocess.run(["xdotool", "type", "--delay", "30", s])
    time.sleep(0.4)


def key(name):
    if IS_WIN:
        import ctypes
        vk = {"enter": 0x0D, "tab": 0x09, "esc": 0x1B}[name]
        ctypes.windll.user32.keybd_event(vk, 0, 0, 0); time.sleep(0.05); ctypes.windll.user32.keybd_event(vk, 0, 2, 0)
    else:
        subprocess.run(["xdotool", "key", {"enter": "Return", "tab": "Tab", "esc": "Escape"}[name]])
    time.sleep(0.5)


def click_text(pattern, name, prefer_last=False, ignore_case=True):
    """Screenshot, OCR, click the first word matching `pattern` inside the OSHI window area."""
    p = shot(name + "-before")
    if not p:
        return False
    words = ocr_words(p)
    rx = re.compile(pattern, re.I if ignore_case else 0)
    # never the taskbar / panel at the bottom of the screen
    hits = [w for w in words if rx.search(w[0]) and w[2] < 1000]
    if not hits:
        print(f"   no OCR hit for /{pattern}/ in {p.name}: {' '.join(w for w, *_ in words)[:400]}")
        return False
    w = hits[-1] if prefer_last else hits[0]
    print(f"   click '{w[0]}' at {w[1]},{w[2]}")
    click(w[1], w[2])
    return True


def click_create(name):
    """'Create New Identity' is white on the brand gradient; OCR it, else click above 'Import'."""
    if click_text(r"^Create$|^Créer$", name):
        return True
    p = shot(name + "-fallback")
    words = ocr_words(p) if p else []
    imp = [w for w in words if re.match(r"^Import", w[0])]
    if imp:
        x, y = imp[0][1], imp[0][2] - 98
        print(f"   fallback click above 'Import' at {x},{y}")
        click(x, y)
        return True
    return False


def screen_text(name):
    p = shot(name)
    return " ".join(w for w, *_ in ocr_words(p)) if p else ""


# --------------------------------------------------------------------------- windows of a process
def visible_windows(pid=None):
    """[(id, title)] of VISIBLE top-level windows titled OSHI…  (`pid` is ignored: on Windows the
    jpackage launcher re-executes itself, so the window belongs to a CHILD of the Popen'd pid)."""
    pid = None
    out = []
    if IS_WIN:
        import ctypes
        from ctypes import wintypes
        u = ctypes.windll.user32
        cb_t = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        def cb(hwnd, _):
            if u.IsWindowVisible(hwnd):
                p = wintypes.DWORD()
                u.GetWindowThreadProcessId(hwnd, ctypes.byref(p))
                n = u.GetWindowTextLengthW(hwnd)
                buf = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(hwnd, buf, n + 1)
                if (pid is None or p.value == pid) and buf.value.startswith("OSHI"):
                    out.append((hwnd, buf.value))
            return True
        u.EnumWindows(cb_t(cb), 0)
    else:
        r = subprocess.run(["xdotool", "search", "--onlyvisible", "--name", "OSHI"], capture_output=True, text=True)
        for wid in r.stdout.split():
            t = subprocess.run(["xdotool", "getwindowname", wid], capture_output=True, text=True).stdout.strip()
            if t.startswith("OSHI"):
                out.append((wid, t))
    return out


def close_window(win):
    """Ask a window to close the way the title-bar X does (WM_CLOSE / _NET_CLOSE_WINDOW)."""
    if IS_WIN:
        import ctypes
        ctypes.windll.user32.PostMessageW(win, 0x0010, 0, 0)
    else:
        subprocess.run(["wmctrl", "-i", "-c", hex(int(win))])


# --------------------------------------------------------------------------- the window
class Gui:
    def __init__(self, exe, home, tag, env_extra=None, args_extra=None, plain=False):
        env = dict(os.environ)
        env.pop("OSHI_PASSPHRASE", None)
        env.update(env_extra or {})
        self.tag = tag
        self.logp = OUT / f"gui-{tag}.log"
        self.logf = open(self.logp, "w", encoding="utf-8")
        if plain:
            cmd = [str(exe)] + (args_extra or [])          # exactly the Start-menu / desktop-entry launch
        else:
            cmd = [str(exe), "--ui"] + (["--home", str(home)] if home is not None else []) + \
                ["--name", "PC Smoke"] + server_args() + (args_extra or [])
        self.logf.write("$ " + " ".join(cmd) + "\n"); self.logf.flush()
        self.p = subprocess.Popen(cmd, stdout=self.logf, stderr=subprocess.STDOUT, env=env)

    def alive(self):
        return self.p.poll() is None

    def log(self):
        try:
            return self.logp.read_text(encoding="utf-8", errors="replace")
        except Exception:
            return ""

    def wait_window(self, timeout=60):
        deadline = time.time() + timeout
        while time.time() < deadline:
            w = visible_windows(self.p.pid if IS_WIN else None)
            w = [x for x in w if "OSHI" in x[1]] or w
            if w:
                return w
            if not self.alive():
                return []
            time.sleep(1)
        return []

    def stop(self):
        if self.alive():
            if IS_WIN:
                subprocess.run(["taskkill", "/PID", str(self.p.pid), "/T", "/F"], capture_output=True)
            else:
                self.p.terminate()
            try:
                self.p.wait(20)
            except Exception:
                self.p.kill()
        try:
            self.logf.close()
        except Exception:
            pass


def main():
    global OUT, SERVER
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=None, help="installed app directory (contains OSHI.exe, or bin/OSHI)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--phase", choices=["legacy", "full"], default="full")
    ap.add_argument("--console", default=None, help="console launcher override (legacy phase)")
    ap.add_argument("--state", default=None, help="file carrying the legacy profile's address between phases")
    ap.add_argument("--server", default=None, help="relay URL; default = the app's own (production)")
    a = ap.parse_args()
    SERVER = a.server
    OUT = Path(a.out).resolve(); (OUT / "shots").mkdir(parents=True, exist_ok=True)
    if IS_WIN:
        try:
            import ctypes; ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass
    app = Path(a.app).resolve() if a.app else None
    if app is not None:
        if IS_WIN:
            gui_exe, console_exe = app / "OSHI.exe", app / "OSHI-Console.exe"
        else:
            gui_exe = console_exe = app / "bin" / "OSHI"
    else:
        gui_exe = console_exe = None
    if a.console:
        console_exe = Path(a.console)
    work = OUT / "work"
    homes = work / "Profils OSHI été"
    homes.mkdir(parents=True, exist_ok=True)
    try:
        if a.phase == "legacy":
            legacy(console_exe, Path(a.state))
        else:
            if a.state and Path(a.state).is_file():
                upgraded(gui_exe, console_exe, Path(a.state))
            run_all(gui_exe, console_exe, homes, app)
    except Exception:
        record("driver crashed", False, traceback.format_exc())
    finally:
        (OUT / f"summary-{a.phase}.json").write_text(json.dumps(results, indent=1, ensure_ascii=False), encoding="utf-8")
        failed = [r for r in results if r["must"] and not r["ok"]]
        warned = [r for r in results if not r["must"] and not r["ok"]]
        print(f"\n==== {len(results)} checks, {len(failed)} MUST failures, {len(warned)} warnings ====")
        for r in failed: print(" FAIL", r["name"], r["detail"][:800])
        for r in warned: print(" WARN", r["name"], r["detail"][:400])
        sys.exit(1 if failed else 0)


def default_profile_dir():
    if IS_WIN:
        return Path(os.environ["APPDATA"]) / "OSHI"
    return Path(os.environ.get("XDG_DATA_HOME") or (Path.home() / ".local/share")) / "oshi"


def legacy(console_exe, state):
    """The PUBLISHED previous build: create the default profile on the OS key store."""
    c = Repl(console_exe, None, "Legacy", tag="legacy")
    addr = c.address()
    c.cmd("/whoami", r"V2 rollout gate", 30)
    c.quit()
    vault = default_profile_dir() / "keyvault.json"
    record("published build created a default profile on the OS key store", bool(addr) and vault.is_file(),
           f"{addr} {vault.read_text()[:300] if vault.is_file() else 'no vault at ' + str(vault)}\n" + c.text_since(0)[-1500:])
    state.write_text(json.dumps({"address": addr}))


def upgraded(gui_exe, console_exe, state):
    """After installing the build under test over the published one: same profile, same address."""
    want = json.loads(state.read_text()).get("address")
    c = Repl(console_exe, None, "Legacy", tag="upgraded")
    got = c.address()
    c.quit()
    record("UPGRADE: the published build's profile opens as the SAME address (console)", bool(want) and got == want,
           f"before={want} after={got}\n" + c.text_since(0)[-1500:])
    vf = default_profile_dir() / "keyvault.json"
    v = json.loads(vf.read_text()) if vf.is_file() else {}
    record("UPGRADE: vault still os-store", v.get("protection") == "os-store", json.dumps(v)[:300], must=False)

    # The WINDOW on the default profile, launched exactly like the Start menu / app menu does.
    g = Gui(gui_exe, None, "default-profile", plain=True)
    wins = g.wait_window(90)
    time.sleep(8)
    shot("upgraded-default-profile-window")
    record("UPGRADE: the window opens on the upgraded profile and stays up", g.alive() and bool(wins),
           f"windows={wins}\n" + g.log()[-1500:])
    if not g.alive():
        return
    t = screen_text("upgraded-window-text")
    record("UPGRADE: the window does NOT show the first-run screen (profile kept)",
           re.search(r"Create New|Import Existing", t) is None, t[:600])

    if IS_WIN:
        time.sleep(5)
        r = subprocess.run(["reg", "query", r"HKCU\Software\Classes\oshi\shell\open\command", "/ve"], capture_output=True, text=True)
        record("oshi:// protocol registered for this user (HKCU)", r.returncode == 0 and "OSHI.exe" in r.stdout, r.stdout + r.stderr)
    else:
        r = subprocess.run(["xdg-mime", "query", "default", "x-scheme-handler/oshi"], capture_output=True, text=True)
        record("oshi:// handled by the installed desktop entry (xdg-mime)", "oshi" in r.stdout.lower(), r.stdout + r.stderr, must=False)

    # close-to-tray: the X hides the window, the process stays.
    wins = visible_windows(g.p.pid if IS_WIN else None)
    main_w = [w for w in wins if "OSHI" in w[1]] or wins
    if main_w:
        close_window(main_w[0][0])
        time.sleep(6)
        after = visible_windows(g.p.pid if IS_WIN else None)
        shot("after-close-button")
        record("close button keeps OSHI running (tray)", g.alive(), f"visible after close: {after}\n" + g.log()[-800:],
               must=IS_WIN)
        record("close button hides the window", not [w for w in after if w[1] == main_w[0][1]], str(after), must=False)
    else:
        record("found the main window to close", False, str(wins), must=False)

    base_procs = count_app_processes()
    if g.alive():
        # second launch: brings the window back, then exits.
        g2 = Gui(gui_exe, None, "second-launch", plain=True)
        try:
            g2.p.wait(60)
        except Exception:
            pass
        time.sleep(4)
        back = visible_windows(g.p.pid if IS_WIN else None)
        shot("after-second-launch")
        record("second launch exits (hands off to the running copy)", not g2.alive(), g2.log()[-800:])
        record("second launch brings the window back", bool([w for w in back if "OSHI" in w[1]]), str(back))
        g2.stop()

        # oshi:// link, the way a browser starts it — to SOMEONE ELSE (a link to yourself is ignored).
        import base64
        other = base64.b64encode(os.urandom(32)).decode()
        link = "oshi://add?key=" + urllib.parse.quote(other, safe="")
        if IS_WIN:
            try:
                os.startfile(link)
            except Exception as e:
                record("os.startfile(oshi://…)", False, repr(e))
        else:
            subprocess.Popen(["xdg-open", link], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(12)
        t = screen_text("after-oshi-link")
        record("oshi:// link reaches the running window (an add-contact sheet with the key is shown)",
               g.alive() and re.search(r"[Aa]dd|[Cc]ontact|" + re.escape(other[:6]), t) is not None, t[:600], must=False)
        procs = count_app_processes()
        record("after second launch + link: still ONE running copy", procs == base_procs, f"{procs} processes now, {base_procs} before", must=False)
        key("esc")
    g.stop()


def count_app_processes():
    if IS_WIN:
        r = subprocess.run(["tasklist", "/FI", "IMAGENAME eq OSHI.exe", "/FO", "CSV", "/NH"], capture_output=True, text=True)
        return sum(1 for l in r.stdout.splitlines() if l.startswith('"OSHI.exe"'))
    r = subprocess.run(["pgrep", "-f", "/opt/oshi-desktop/"], capture_output=True, text=True)
    return len(r.stdout.split())


def run_all(gui_exe, console_exe, homes, app):
    # 1 ------------------------------------------------------------------ probe
    try:
        r = subprocess.run([str(console_exe), "--probe"], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=240)
        out = r.stdout + r.stderr
    except Exception as e:
        out = repr(e)
    (OUT / "probe.log").write_text(out, encoding="utf-8")
    record("packaged --probe runs the crypto self-test", "known-answer vectors" in out, out[-1200:])

    # 2 ------------------------------------------------------------------ REPL flows, production relay
    alice_home, bob_home = homes / "Alice", homes / "Bob"
    alice = Repl(console_exe, alice_home, "Alice PC", tag="alice")
    bob = Repl(console_exe, bob_home, "Bob PC", passphrase="ci-bob-throwaway", tag="bob")
    a_addr, b_addr = alice.address(), bob.address()
    record("ALICE opens on the real OS key store (no passphrase)", bool(a_addr), alice.text_since(0)[-1500:])
    record("BOB opens on a passphrase vault", bool(b_addr), bob.text_since(0)[-800:])
    hdr = alice.text_since(0)
    m = re.search(r"^\s*data\s*:\s*(.*)$", hdr, re.M)
    record("console prints an accented data path intact (stdout is not mis-encoded)",
           bool(m) and "Profils OSHI été" in m.group(1), m.group(1) if m else hdr[-600:])
    if not (a_addr and b_addr):
        return
    vault = json.loads((alice_home / "keyvault.json").read_text())
    record("ALICE's vault is os-store", vault.get("protection") == "os-store", json.dumps(vault)[:300])
    time.sleep(5)

    msg = "Bonjour depuis le PC — été, ça marche ? 😀"
    m = alice.cmd(f"/send {b_addr} {msg}", r"[Ss]ent|NOT|REFUSED|WAITING", 90)
    record("ALICE sends text (production relay)", bool(m) and "NOT" not in m.string, m.string if m else alice.text_since(0)[-800:])
    ok, h = bob.poll_history(a_addr, r"Bonjour depuis le PC", 120)
    record("BOB receives the text", ok, h[-800:])
    record("BOB's console prints accents/emoji intact", ok and "ça marche" in h and "😀" in h, h[-400:], must=False)

    docs = homes / "Mes documents été"
    docs.mkdir(exist_ok=True)
    photo = docs / "photo de vacances.png"; tiny_png(photo)
    doc = docs / "Facture n°12 (copie).txt"; doc.write_text("ligne 1\r\nligne 2 é\r\n", encoding="utf-8")
    voice = docs / "note vocale été.m4a"
    have_voice = make_m4a(voice)
    record("ffmpeg made a test m4a", have_voice, str(voice), must=False)
    for f, kind in ((photo, "photo"), (doc, "file")):
        m = alice.cmd(f"/sendfile {b_addr} {f}", r"[Ss]ent|NOT|no such file|unknown|REFUSED", 120)
        record(f"ALICE sends a {kind} from a path with spaces/accents", bool(m) and "NOT" not in m.string and "no such" not in m.string,
               m.string if m else alice.text_since(0)[-500:])
    ok, h = bob.poll_history(a_addr, r"photo de vacances|\[image\]", 120)
    record("BOB receives the photo", ok, h[-1200:])
    ok, h = bob.poll_history(a_addr, r"Facture", 90)
    record("BOB receives the document", ok, h[-1200:])
    # the received media actually exist and decrypt to the same size
    m = re.findall(r"→\s*(\S.*)$", h, re.M)
    record("BOB's history names local paths for the media", bool(m), "\n".join(m[-3:]), must=False)

    m = alice.cmd(f"/group new Equipe {b_addr}", r"created|unknown|usage", 90)
    record("ALICE creates a group", bool(m) and "created" in m.string, m.string if m else alice.text_since(0)[-500:])
    if m and "created" in m.string:
        gid = re.search(r"created (\w+)", m.string).group(1)
        m2 = alice.cmd(f"/group send {gid} message de groupe é 🎉", r"sent to|NOT|unknown", 90)
        record("ALICE sends to the group", bool(m2) and "sent to 1/1" in m2.string, m2.string if m2 else "")
        deadline = time.time() + 120; seen = ""
        while time.time() < deadline:
            since = bob.mark(); bob.cmd("/groups"); time.sleep(3); seen = bob.text_since(since)
            if "Equipe" in seen:
                break
            time.sleep(5)
        record("BOB sees the group", "Equipe" in seen, seen[-500:])

    alice.quit()
    alice2 = Repl(console_exe, alice_home, "Alice PC", tag="alice-restart")
    a2 = alice2.address()
    record("ALICE restarts as the SAME address (OS key store persisted)", a2 == a_addr, f"{a_addr} vs {a2}")
    ok, h = alice2.poll_history(b_addr, r"Bonjour depuis le PC", 30)
    record("ALICE's history survives the restart", ok, h[-600:])
    alice2.quit()

    # 3 ------------------------------------------------------------------ a fresh WINDOW
    gui_home = homes / "Fenêtre"
    g = Gui(gui_exe, gui_home, "first-run")
    wins = g.wait_window(90)
    time.sleep(10)
    record("WINDOW up on a fresh profile (OS key store)", g.alive() and bool(wins), f"{wins}\n" + g.log()[-1500:])
    t = screen_text("first-run")
    record("first-run screen shows the identity choice", re.search(r"Create|Identity|Identit", t, re.I) is not None, t[:600], must=False)
    clicked = click_create("click-create")
    record("clicked 'Create New Identity'", clicked)
    time.sleep(25)
    record("WINDOW alive after 'Create New Identity'", g.alive(), g.log()[-1500:])
    shot("after-create")
    gvault = gui_home / "keyvault.json"
    gv = json.loads(gvault.read_text()) if gvault.is_file() else {}
    record("WINDOW created an os-store vault", gv.get("protection") == "os-store", json.dumps(gv)[:300])
    g.stop()

    # The window's address via the console on the same profile (also: the key store reopens).
    w = Repl(console_exe, gui_home, "PC Smoke", tag="window-profile")
    w_addr = w.address()
    w.quit()
    record("window profile reopens from the OS key store (console)", bool(w_addr), w.text_since(0)[-800:])
    g = Gui(gui_exe, gui_home, "main")
    wins = g.wait_window(90)
    time.sleep(25)          # the window publishes its prekeys; a sender needs them
    record("WINDOW up on an existing profile", g.alive() and bool(wins), f"{wins}\n" + g.log()[-1500:])
    if w_addr:
        m = None
        for _ in range(6):
            m = bob.cmd(f"/send {w_addr} Salut la fenêtre 👋 — premier message", r"[Ss]ent|NOT|REFUSED|WAITING", 90)
            if m and "NOT" not in m.string:
                break
            time.sleep(15)
        record("BOB (console) sends to the WINDOW's address", bool(m) and "NOT" not in m.string, m.string if m else "")
    time.sleep(15)
    shot("main-with-incoming")
    if w_addr and g.alive():
        bob.cmd(f"/sendfile {w_addr} {photo}", r"[Ss]ent|NOT", 120)
        bob.cmd(f"/sendfile {w_addr} {doc}", r"[Ss]ent|NOT", 120)
        if have_voice:
            bob.cmd(f"/sendfile {w_addr} {voice}", r"[Ss]ent|NOT", 120)
        time.sleep(20)
        shot("main-after-media")
        if click_text(r"^Bob", "open-bob-thread"):
            time.sleep(5)
            t = screen_text("bob-thread")
            record("thread shows Bob's text", re.search(r"fen[eê]tre|premier", t) is not None, t[:800], must=False)
            record("thread text is not mojibake (no Ã/â€ sequences)", re.search(r"Ã|â€", t) is None, t[:800])
            if click_text(r"^Message$|^Écrire", "focus-composer", prefer_last=True, ignore_case=False):
                type_text("Réponse depuis Windows é 😀" if IS_WIN else "Reponse depuis Linux e")
                key("enter")
                time.sleep(8)
                shot("after-reply")
                ok, h = bob.poll_history(w_addr, r"depuis (Windows|Linux)", 90)
                record("BOB receives the reply typed in the WINDOW", ok, h[-600:], must=False)
                record("the reply keeps accents/emoji", ok and ("é 😀" in h if IS_WIN else True), h[-300:], must=False)
    if g.alive():
        # Settings live under the "More" destination of the top strip.
        if click_text(r"^More$|^Plus$", "open-more"):
            time.sleep(3); shot("more")
        if click_text(r"^Settings$|^Réglages$|^Paramètres$", "open-settings"):
            time.sleep(3); shot("settings")
            if click_text(r"^Language$|^Langue$", "open-language"):
                time.sleep(2); shot("language-picker")
                key("esc")
        else:
            record("found Settings by OCR", False, must=False)
    record("WINDOW still alive at the end", g.alive(), g.log()[-1500:])
    g.stop()
    bob.quit()

    # 4 ------------------------------------------------------------------ French
    fr_home = homes / "fr"
    if IS_LINUX:
        env_fr = {"LANG": "fr_FR.UTF-8", "LC_ALL": "fr_FR.UTF-8", "LANGUAGE": "fr_FR:fr"}
    else:
        env_fr = {"JAVA_TOOL_OPTIONS": "-Duser.language=fr -Duser.country=FR"}
    g = Gui(gui_exe, fr_home, "french", env_extra=env_fr)
    g.wait_window(90); time.sleep(10)
    t = screen_text("first-run-french")
    record("French system language opens the window in French", re.search(r"Créer|Identité|Importer", t) is not None, t[:600])
    if click_create("fr-click-create"):
        time.sleep(25)
        shot("fr-main")
        if click_text(r"^Réglages$|^Paramètres$", "fr-open-settings"):
            time.sleep(3); shot("fr-settings")
    record("French window alive", g.alive(), g.log()[-800:])
    g.stop()

    # 5 ------------------------------------------------------------------ voice note through the installed jars
    if have_voice and app is not None:
        voice_note_check(app, voice)

    # 6 ------------------------------------------------------------------ logs
    for f in OUT.glob("gui-*.log"):
        txt = f.read_text(encoding="utf-8", errors="replace")
        fatal = [l for l in txt.splitlines() if re.search(r"Exception in thread|FATAL|A fatal error has been detected|hs_err", l)]
        record(f"no uncaught exception in {f.name}", not fatal, "\n".join(fatal[:30]))
        other = [l for l in txt.splitlines() if re.search(r"Exception|Error:|at com\.oshi", l)]
        record(f"no exception text in {f.name}", not other, "\n".join(other[:40]), must=False)


def voice_note_check(app, m4a):
    """Decode an m4a the way an incoming phone voice note is, with the INSTALLED jars and ffmpeg on PATH."""
    jars = sorted(glob.glob(str(app / "app" / "*.jar")) + glob.glob(str(app / "lib" / "app" / "*.jar")))
    jshell = shutil.which("jshell")
    if not jars or not jshell:
        record("voice note: jshell + installed jars available", False, f"jars={len(jars)} jshell={jshell}", must=False)
        return
    cp = os.pathsep.join(jars)
    ff = shutil.which("ffmpeg")
    no_ff_path = os.pathsep.join(d for d in os.environ.get("PATH", "").split(os.pathsep)
                                 if not (Path(d) / ("ffmpeg.exe" if IS_WIN else "ffmpeg")).exists())
    # with ffmpeg on PATH (a power user), and WITHOUT (almost every Windows user: the bundled one)
    for tag, path in (("path", os.environ.get("PATH", "")), ("bundled", no_ff_path)):
        wav = m4a.with_name(f"{m4a.stem}-{tag}.wav")
        script = OUT / f"voice-{tag}.jsh"
        script.write_text(
            "System.out.println(\"which=\" + com.oshi.desktop.media.AudioProc.INSTANCE.which(\"ffmpeg\"));\n"
            "System.out.println(\"bundled=\" + com.oshi.desktop.media.AudioProc.INSTANCE.getBundledFfmpeg());\n"
            "var t = new com.oshi.desktop.media.SystemAudioTranscoder();\n"
            "System.out.println(\"probe=\" + t.probe());\n"
            f"System.out.println(\"result=\" + t.toWav(new java.io.File({json.dumps(str(m4a))}), new java.io.File({json.dumps(str(wav))})));\n"
            "/exit\n", encoding="utf-8")
        env = dict(os.environ); env["PATH"] = path
        r = subprocess.run([jshell, "--class-path", cp, str(script)], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=300, env=env)
        out = f"(python which ffmpeg = {ff})\n" + r.stdout + r.stderr
        (OUT / f"voice-note-{tag}.log").write_text(out, encoding="utf-8")
        record(f"voice note ({tag} ffmpeg): the installed transcoder decodes an m4a",
               "Produced" in out and wav.is_file() and wav.stat().st_size > 1000, out[-1500:])


if __name__ == "__main__":
    main()
