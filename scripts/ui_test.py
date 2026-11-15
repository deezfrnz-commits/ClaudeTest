#!/usr/bin/env python3
"""Drives DocumentFacilitator on an emulator the way a person would: taps tiles, buttons and
menus, types text, opens and saves through the system file picker, then checks the saved files
with independent libraries (pypdf, python-docx, openpyxl).

Run with an emulator attached and the debug APK installed: python3 scripts/ui_test.py
Screenshots, UI dumps and logs are written to emulator-out/ui/.
"""
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "com.documentfacilitator"
FX = "core/src/test/resources/fx"
OUT = "emulator-out/ui"
DL = "/sdcard/Download"
os.makedirs(OUT, exist_ok=True)
results = []
shot_n = [0]


# ---------------------------------------------------------------- adb helpers
def adb(*args, check=False, binary=False, timeout=60):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def log(msg):
    print(msg, flush=True)


def shot(name):
    shot_n[0] += 1
    fn = f"{OUT}/{shot_n[0]:02d}_{re.sub(r'[^A-Za-z0-9]+', '_', name)}.png"
    with open(fn, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    return fn


class Node:
    def __init__(self, e):
        self.text = e.get("text") or ""
        self.desc = e.get("content-desc") or ""
        self.cls = e.get("class") or ""
        self.pkg = e.get("package") or ""
        self.rid = e.get("resource-id") or ""
        self.focused = e.get("focused") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds") or "[0,0][0,0]")
        self.x0, self.y0, self.x1, self.y1 = map(int, m.groups())

    @property
    def cx(self):
        return (self.x0 + self.x1) // 2

    @property
    def cy(self):
        return (self.y0 + self.y1) // 2

    @property
    def label(self):
        return self.text or self.desc

    def __repr__(self):
        return f"<{self.cls.split('.')[-1]} '{self.label[:40]}' {self.x0},{self.y0}-{self.x1},{self.y1}>"


def dump():
    for _ in range(6):
        out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
        if out.strip().startswith("<?xml"):
            try:
                root = ET.fromstring(out)
                return [Node(e) for e in root.iter("node")]
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def find(pred, timeout=15, what="element"):
    end = time.time() + timeout
    while time.time() < end:
        for n in dump():
            if pred(n):
                return n
        time.sleep(0.7)
    raise AssertionError(f"not found on screen: {what}")


def by_text(t, exact=True, timeout=15):
    if exact:
        return find(lambda n: n.text == t or n.desc == t, timeout, f"'{t}'")
    rx = re.compile(t, re.I)
    return find(lambda n: rx.search(n.text) or rx.search(n.desc), timeout, f"/{t}/")


def tap(x, y=None):
    if y is None:
        x, y = x.cx, x.cy
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def tap_text(t, exact=True, timeout=15):
    n = by_text(t, exact, timeout)
    tap(n)
    return n


def type_text(s):
    # adb "input text" needs spaces as %s; only plain characters are used in this test
    sh("input text " + s.replace(" ", "%s"))
    time.sleep(0.6)


def key(code):
    sh(f"input keyevent {code}")
    time.sleep(0.5)


def back():
    key(4)


def screen_text():
    return " | ".join(n.label for n in dump() if n.label)


def logcat():
    return adb("logcat", "-d", timeout=30)


def wait_log(pattern, timeout=30):
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        if rx.search(adb("logcat", "-d", "-s", "DocFacilitator", timeout=30)):
            return True
        if re.search(r"Process: " + re.escape(PKG) + r", PID", logcat()):
            break
        time.sleep(1)
    raise AssertionError(f"log line never appeared: {pattern}")


def assert_no_crash():
    lc = logcat()
    m = re.search(r"FATAL EXCEPTION.*\n.*Process: " + re.escape(PKG), lc)
    if m:
        raise AssertionError("app crashed:\n" + lc[m.start():m.start() + 3000])
    if not sh(f"pidof {PKG}").strip():
        raise AssertionError("app process is not running")
    txt = screen_text()
    if "Webpage not available" in txt or "ERR_" in txt:
        raise AssertionError("WebView error page shown: " + txt[:300])


def downloads():
    return set(x.strip() for x in sh(f"ls {DL}").splitlines() if x.strip())


def pull_new(before, expect_ext):
    end = time.time() + 20
    while time.time() < end:
        new = [f for f in downloads() - before if f.lower().endswith("." + expect_ext)]
        if new:
            time.sleep(1.5)  # let the write finish
            name = sorted(new)[-1]
            local = f"{OUT}/{name}"
            adb("pull", f"{DL}/{name}", local, check=True)
            return local
        time.sleep(1)
    raise AssertionError(f"no new .{expect_ext} file appeared in Downloads")


# ---------------------------------------------------------------- app actions
def menu(item, exact=True):
    tap_text("≡")
    tap_text(item, exact)


def mode(name):
    tap_text(name)
    time.sleep(1.2)


def dialog_input(text, ok="OK"):
    e = find(lambda n: n.cls.endswith("EditText") and n.pkg == PKG, what="text box in dialog")
    tap(e)
    key(123)  # move to end
    # clear what is there (prompts preselect their text, but be explicit)
    sh("input keyevent " + " ".join(["67"] * 40))
    type_text(text)
    tap_text(ok)


def system_save(before, ext):
    """In the system 'save' screen: make sure we are in Downloads, then press Save."""
    time.sleep(2.5)
    shot("save_dialog")
    nodes = dump()
    if not any(n.label in ("Download", "Downloads") for n in nodes):
        try:
            tap(find(lambda n: n.desc in ("Show roots", "Open navigation drawer"), 5, "roots button"))
            tap_text("Downloads", timeout=8)
        except AssertionError:
            pass
    tap(find(lambda n: n.text.upper() == "SAVE" and n.cls.endswith("Button"), 15, "SAVE button"))
    time.sleep(2)
    return pull_new(before, ext)


def system_pick(name):
    """In the system 'open' screen: search for the file by name and choose it."""
    time.sleep(2.5)
    try:
        tap(find(lambda n: n.text == name, 3, name))
        return
    except AssertionError:
        pass
    tap(find(lambda n: n.desc == "Search" or n.text == "Search", 10, "search button"))
    time.sleep(1)
    type_text(name)
    key(66)
    time.sleep(2.5)
    shot("picker_search")
    # a result row carries a "Preview the file <name>" button; the search box and the
    # suggestion list show the same text, so tap the name on the row of that button
    prev = find(lambda n: n.desc == "Preview the file " + name, 15, "search result " + name)
    # tapping the thumbnail previews (opens the file in a viewer app); tap the row's middle to pick it
    w = int(re.search(r"(\d+)x(\d+)", sh("wm size")).group(1))
    tap(int(w * 0.6), prev.cy)


def export(fmt_regex=None):
    before = downloads()
    menu("Export copy to phone…")
    time.sleep(1)
    nodes = dump()
    if any(n.text.startswith("Export as") for n in nodes):
        if fmt_regex:
            tap_text(fmt_regex, exact=False)
        else:
            choices = [n for n in nodes if n.pkg == PKG and re.search(r"\(\.\w+\)$", n.text)]
            tap(choices[0])
    return before


def ext_after_export(before, ext):
    path = system_save(before, ext)
    time.sleep(1.5)
    assert_no_crash()
    return path


def open_via_intent(fname, m=0):
    adb("logcat", "-c")
    sh(f"am start -W -S -n {PKG}/.ViewerActivity -d file:///data/data/{PKG}/files/fx/{fname} --ei mode {m}")
    wait_log(r"ready .* mode=%d" % m)
    if not fname.endswith((".pdf", ".png")):
        wait_log("dom ready")
    time.sleep(1.5)


def webview():
    return find(lambda n: n.cls == "android.webkit.WebView", 10, "document view")


def go_home():
    sh(f"am force-stop {PKG}")
    adb("logcat", "-c")
    sh(f"am start -W -n {PKG}/.HomeActivity")
    time.sleep(2)


# ---------------------------------------------------------------- scenarios
def test_new_documents():
    """Home -> NEW -> each type: type text, save a state, export, check the file."""
    kinds = [("Text note", "txt"), ("Markdown", "md"), ("Rich document", "docx"), ("Web page", "html"), ("Spreadsheet", "csv")]
    for label, ext in kinds:
        go_home()
        tap(find(lambda n: n.desc == "NEW" or n.text == "NEW", 10, "NEW tile"))
        tap_text(label, exact=False)
        if ext in ("docx", "html", "csv"):
            wait_log("dom ready")
        else:
            wait_log("text: ")
        time.sleep(1.5)
        assert_no_crash()
        word = f"Hello {ext} test"
        if ext in ("txt", "md"):
            tap(find(lambda n: n.cls.endswith("EditText"), what="editor"))
            type_text(word)
        else:
            w = webview()
            dens = int(re.search(r"(\d+)", sh("wm density")).group(1)) / 160
            if ext == "csv":  # cell A1: right of the row-number column, below the column letters
                tap(int(w.x0 + 70 * dens), int(w.y0 + 40 * dens))
            else:
                tap(int(w.x0 + 60 * dens), int(w.y0 + 40 * dens))
            type_text(word)
            if ext == "csv":
                key(66)  # Enter commits the cell
        shot(f"new_{ext}_typed")
        assert_no_crash()
        menu("Save state…")
        dialog_input(f"Draft {ext}")
        time.sleep(1)
        before = export()
        path = ext_after_export(before, ext)
        if ext == "docx":
            import docx
            body = "\n".join(p.text for p in docx.Document(path).paragraphs)
        else:
            body = open(path, encoding="utf-8", errors="replace").read()
        assert word in body, f"exported .{ext} does not contain the typed text: {body[:300]!r}"
        log(f"   new .{ext}: typed text present in exported file ({os.path.getsize(path)} bytes)")


def test_pdf_form_fill_annotate_and_states():
    open_via_intent("form.pdf", 2)
    shot("form_fill_start")
    filled = {"text": 0, "choice": 0}
    for i in range(8):
        tap_text("Next ▶")
        time.sleep(1.2)
        nodes = dump()
        if any(n.text == "No more fields" for n in nodes):
            break
        edit = [n for n in nodes if n.cls.endswith("EditText") and n.pkg == PKG]
        if edit:
            dialog_input("Ada Lovelace" if filled["text"] == 0 else "Some notes")
            filled["text"] += 1
        elif any(n.text == "Rome" for n in nodes):
            tap_text("Rome")
            filled["choice"] += 1
        shot(f"form_fill_step{i}")
    assert filled["text"] >= 2 and filled["choice"] == 1, f"fields reached: {filled}"
    assert_no_crash()
    # annotate: pen stroke and a highlight
    mode("EDIT")
    w, h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())
    tap_text("Pen")
    sh(f"input swipe {w // 4} {h // 2} {w * 3 // 4} {h // 2 + 80} 400")
    tap_text("Highlight")
    sh(f"input swipe {w // 4} {h // 3} {w * 2 // 3} {h // 3 + 30} 400")
    time.sleep(1)
    shot("form_annotated")
    assert_no_crash()
    menu("Save state…")
    dialog_input("Signed copy")
    before = export()
    path = ext_after_export(before, "pdf")
    from pypdf import PdfReader
    r = PdfReader(path)
    fields = {k: v.get("/V") for k, v in r.get_fields().items()}
    log(f"   form values in exported PDF: {fields}")
    assert fields.get("name") == "Ada Lovelace", fields
    assert fields.get("city") == "Rome", fields
    assert str(fields.get("agree")) not in ("None", "/Off"), fields
    contents = r.pages[0]["/Contents"].get_object()
    n = len(contents) if isinstance(contents, list) else 1
    assert n >= 3, f"annotations missing: page has {n} content streams"
    # states survive closing the app
    sh(f"am force-stop {PKG}")
    open_via_intent("form.pdf", 0)
    nodes = dump()
    if any("Restore them?" in n.text for n in nodes):
        tap_text("Discard")
    menu("States…")
    tap_text("^Signed copy", exact=False)
    tap_text("Load")
    time.sleep(2)
    shot("state_loaded")
    assert_no_crash()
    before = export()
    path2 = ext_after_export(before, "pdf")
    f2 = {k: v.get("/V") for k, v in PdfReader(path2).get_fields().items()}
    assert f2.get("name") == "Ada Lovelace", f"state did not restore the form values: {f2}"
    log("   saved state restored after restarting the app")


def test_flat_pdf_detected_blanks():
    open_via_intent("flat.pdf", 2)
    typed = 0
    for i in range(6):
        tap_text("Next ▶")
        time.sleep(1.2)
        if any(n.cls.endswith("EditText") and n.pkg == PKG for n in dump()):
            dialog_input(f"Answer{typed}")
            typed += 1
        if typed >= 2:
            break
    shot("flat_filled")
    assert typed >= 2, "fewer than two text blanks reached with Next"
    before = export()
    path = ext_after_export(before, "pdf")
    from pypdf import PdfReader
    text = PdfReader(path).pages[0].extract_text()
    assert "Answer0" in text and "Answer1" in text, f"typed answers missing: {text[:400]!r}"
    log("   typed answers are real text in the exported PDF")


def test_docx_edit_and_fill():
    open_via_intent("sample.docx", 1)
    shot("docx_edit")
    tail = by_text("Tail paragraph.")
    tap(tail.x1 - 2, tail.cy)
    key(123)
    type_text(" Added by test")
    time.sleep(1)
    mode("FILL")
    time.sleep(1.5)
    tap_text("Next ▶")
    time.sleep(1)
    type_text("Ada")
    shot("docx_filled")
    assert_no_crash()
    before = export("Word document")
    path = ext_after_export(before, "docx")
    import docx
    d = docx.Document(path)
    text = "\n".join(p.text for p in d.paragraphs)
    assert "Tail paragraph. Added by test" in text, text
    assert "Name: Ada" in text, text
    assert any(p.style.name.startswith("Heading") for p in d.paragraphs), "formatting lost"
    log("   DOCX edit and fill written back, heading style kept")


def test_xlsx_edit():
    open_via_intent("sample.xlsx", 1)
    tap(by_text("Widget"))
    time.sleep(0.8)
    type_text("Gizmo")
    key(66)
    shot("xlsx_edited")
    assert_no_crash()
    before = export("Excel workbook")
    path = ext_after_export(before, "xlsx")
    import openpyxl
    ws = openpyxl.load_workbook(path)["Data"]
    assert ws["A2"].value == "Gizmo", ws["A2"].value
    assert ws["B5"].value == "=SUM(B2:B3)", ws["B5"].value
    log("   XLSX cell changed, formula kept")


def test_open_with_picker_and_split_view():
    go_home()
    tap(find(lambda n: n.desc == "OPEN" or n.text == "OPEN", 10, "OPEN tile"))
    system_pick("sample.pptx")
    wait_log("dom ready PPTX")
    time.sleep(1.5)
    assert_no_crash()
    menu("Open second document (split view)")
    system_pick("sample.odt")
    wait_log("dom ready ODT")
    time.sleep(2)
    shot("split_view")
    views = [n for n in dump() if n.text == "VIEW"]
    assert len(views) == 2, f"expected two panes, found {len(views)}"
    assert_no_crash()
    log("   opened through the system picker; two documents side by side")
    # a second document opened from home must show that document, not the earlier window
    key(3)  # home button, app stays alive
    sh(f"am start -W -n {PKG}/.HomeActivity")
    time.sleep(2)
    adb("logcat", "-c")
    tap(find(lambda n: n.desc == "OPEN" or n.text == "OPEN", 10, "OPEN tile"))
    system_pick("sample.rtf")
    wait_log("dom ready RTF")
    time.sleep(1.5)
    by_text(r"^sample\.rtf", exact=False, timeout=10)
    assert_no_crash()
    log("   opening another document from home shows that document")


def setup():
    adb("install", "-r", "-g", "app/build/outputs/apk/debug/app-debug.apk", check=True, timeout=180)
    sh(f"run-as {PKG} mkdir -p files/fx")
    for f in sorted(os.listdir(FX)):
        adb("push", f"{FX}/{f}", f"/data/local/tmp/{f}")
        sh(f"cat /data/local/tmp/{f} | run-as {PKG} sh -c 'cat > files/fx/{f}'")
        adb("push", f"{FX}/{f}", f"{DL}/{f}")
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{DL}/{f} >/dev/null")
    sh("content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1")
    sh("settings put secure show_ime_with_hard_keyboard 0")


def main():
    setup()
    tests = [t for name, t in sorted(globals().items()) if name.startswith("test_")]
    only = sys.argv[1:]
    for t in [t for t in tests if not only or t.__name__ in only]:
        log(f"== {t.__name__}")
        adb("logcat", "-c")
        try:
            t()
            results.append((t.__name__, True, ""))
            log(f"PASS {t.__name__}")
        except Exception as e:
            shot(f"FAIL_{t.__name__}")
            with open(f"{OUT}/FAIL_{t.__name__}.txt", "w") as f:
                f.write(traceback.format_exc() + "\n\nSCREEN: " + screen_text() + "\n\n" + logcat()[-20000:])
            results.append((t.__name__, False, str(e)))
            log(f"FAIL {t.__name__}: {e}")
            log("   screen: " + screen_text()[:600])
            log("   app log:\n" + adb("logcat", "-d", "-s", "DocFacilitator:I")[-2500:])
            log("   tasks:\n" + "\n".join(l for l in sh("dumpsys activity activities").splitlines() if "documentfacilitator" in l)[:2000])
        sh(f"am force-stop {PKG}")
    log("")
    for name, ok, msg in results:
        log(f"{'PASS' if ok else 'FAIL'}  {name}  {msg[:200]}")
    failed = sum(1 for r in results if not r[1])
    log(f"ui tests: {len(results) - failed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
