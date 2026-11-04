"""Small uiautomator-over-adb driver for the TopoDroid end-to-end test.

Only the Python standard library and adb are used. Every screen dump is read in memory; what is
printed or kept on disk is filtered so that no survey measurement leaves the device:

* `safe_nodes()` keeps only nodes whose resource id is in SAFE_IDS (dialog fields, buttons, menu
  rows, titles) or that sit inside one of the SilexGIS lists, and never the access-key field;
* screenshots are taken only through `shot()`, which the scenarios call on dialogs alone.

Run `python3 -I tdui.py dump` to print the filtered view of the current screen.
"""
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/.local/opt/android-sdk")), "platform-tools", "adb")
WORK = os.environ.get("EMU_WORK", "/srv/data/silexgis/scratch/topodroid-integration/emu")

# resource ids whose text may be printed: dialog chrome, the SilexGIS dialogs, menus and settings rows.
# Survey info fields (team, comment) and every shot-list id are deliberately absent.
SAFE_IDS = re.compile(r"^(title|title_text|message|message_text|summary|value|row_text|spin|handle|checkbox|"
                      r"button_\w+|btn_\w+|silexgis_(?!key$)\w+|therion_\w+|layout_\w+|header_title|breadcrumb_text|"
                      r"preview_icon|details|date|item_root|sub_menu_\w+|option_menu_\w+|edittext|"
                      r"android:id/\w+|alertTitle|toolbar)$")
LIST_PARENTS = {"silexgis_list", "silexgis_caves", "silexgis_artefacts", "silexgis_notes"}


class Node:
    def __init__(self, el, in_list):
        self.rid = el.get("resource-id", "")
        self.id = self.rid.split("/")[-1] if "/" in self.rid else self.rid
        self.text = el.get("text") or ""
        self.desc = el.get("content-desc") or ""
        self.cls = (el.get("class") or "").split(".")[-1]
        self.clickable = el.get("clickable") == "true"
        self.enabled = el.get("enabled") == "true"
        self.checkable = el.get("checkable") == "true"
        self.checked = el.get("checked") == "true"
        self.password = el.get("password") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", el.get("bounds")))
        self.bounds = (x1, y1, x2, y2)
        self.x, self.y = (x1 + x2) // 2, (y1 + y2) // 2
        self.in_list = in_list
        self.pkg = el.get("package") or ""

    def safe(self):
        return (self.id == "" and self.cls == "Button") or bool(SAFE_IDS.match(self.id)) or self.in_list

    def line(self):
        t = "<masked>" if (self.password or self.id == "silexgis_key") else re.sub(r"sgx_\S*", "sgx_<masked>", self.text)
        extra = f" checked={self.checked}" if self.checkable else ""
        en = "" if self.enabled else " DISABLED"
        return f"({self.x},{self.y}) {self.cls} id={self.id} text={t!r} desc={self.desc!r}{extra}{en}"


class Device:
    def __init__(self, serial=None, pkg="com.topodroid.TDX"):
        self.serial = serial
        self.pkg = pkg
        self.nodes = []
        os.makedirs(os.path.join(WORK, "shots"), exist_ok=True)

    # -- adb -----------------------------------------------------------------------------------
    def adb(self, *args, check=True, timeout=120, capture=True):
        cmd = [ADB] + (["-s", self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=capture, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {args[0]} failed ({r.returncode}): {r.stderr.decode(errors='replace')[-300:]}")
        return r.stdout.decode(errors="replace") if capture else ""

    def sh(self, cmd, **kw):
        return self.adb("shell", cmd, **kw)

    # -- screen ---------------------------------------------------------------------------------
    def dump(self):
        """read the current window hierarchy into self.nodes (never written to disk)"""
        for _ in range(4):
            out = self.adb("exec-out", "uiautomator dump /dev/tty", check=False)
            m = re.search(r"<\?xml.*</hierarchy>", out, re.S)
            if m:
                break
            time.sleep(1)
        else:
            raise RuntimeError("uiautomator dump failed")
        root = ET.fromstring(m.group(0))
        nodes = []

        def walk(el, in_list):
            for ch in el:
                if ch.tag != "node":
                    continue
                n = Node(ch, in_list)
                nodes.append(n)
                walk(ch, in_list or n.id in LIST_PARENTS)
        walk(root, False)
        self.nodes = nodes
        return nodes

    def safe_nodes(self):
        return [n for n in self.nodes if (n.text or n.desc or n.clickable) and n.safe()]

    def print_safe(self, dump=True):
        if dump:
            self.dump()
        for n in self.safe_nodes():
            print("   ", n.line())

    def find(self, key, dump=True, index=0):
        """key: a resource id, 'text=...', 'textre=...', 'desc=...'"""
        if dump:
            self.dump()
        hits = [n for n in self.nodes if self._match(n, key)]
        return hits[index] if len(hits) > index else None

    def find_all(self, key, dump=False):
        if dump:
            self.dump()
        return [n for n in self.nodes if self._match(n, key)]

    @staticmethod
    def _match(n, key):
        if key.startswith("text="):
            return n.text == key[5:]
        if key.startswith("textre="):
            return re.search(key[7:], n.text) is not None
        if key.startswith("desc="):
            return n.desc == key[5:]
        return n.id == key

    def exists(self, key, dump=True):
        return self.find(key, dump) is not None

    def wait(self, key, timeout=30, gone=False, interval=1.0):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(key)
            if (n is None) == gone:
                return n if not gone else True
            time.sleep(interval)
        return None

    def wait_any(self, keys, timeout=30, interval=1.0):
        end = time.time() + timeout
        while time.time() < end:
            self.dump()
            for k in keys:
                n = self.find(k, dump=False)
                if n is not None:
                    return k, n
            time.sleep(interval)
        return None, None

    def tap(self, key, timeout=15, index=0):
        end = time.time() + timeout
        while True:
            n = self.find(key, index=index)
            if n is not None:
                self.sh(f"input tap {n.x} {n.y}")
                return n
            if time.time() > end:
                raise LookupError(f"not on screen: {key}")
            time.sleep(1)

    def tap_xy(self, x, y):
        self.sh(f"input tap {x} {y}")

    def swipe_up(self, y1=2600, y2=900):
        self.sh(f"input swipe 720 {y1} 720 {y2} 400")
        time.sleep(1)

    def scroll_to(self, key, tries=6, y1=2600, y2=900):
        for _ in range(tries):
            n = self.find(key)
            if n is not None and n.y < 2950:
                return n
            self.swipe_up(y1, y2)
        raise LookupError(f"not found after scrolling: {key}")

    def keyboard_shown(self):
        return "mInputShown=true" in self.sh("dumpsys input_method", check=False)

    def hide_keyboard(self):
        if self.keyboard_shown():
            self.sh("input keyevent KEYCODE_BACK")
            time.sleep(0.8)

    def clear_field(self, n=60):
        self.sh("input keyevent KEYCODE_MOVE_END")
        self.sh("input keyevent " + " ".join(["KEYCODE_DEL"] * n))

    def type_text(self, text, chunk=6):
        """type in short chunks: a long `input text` drops characters on a slow emulator"""
        for i in range(0, len(text), chunk):
            part = text[i:i + chunk].replace(" ", "%s")
            self.sh(f"input text '{part}'")
            time.sleep(0.25)

    def fill(self, rid, value, secret=False):
        """tap a field, clear it, type the value, check the typed length (a masked field is checked by length only)"""
        for attempt in range(3):
            self.tap(rid)
            time.sleep(0.8)
            self.clear_field()
            self.type_text(value)
            n = self.find(rid)
            got = n.text if n else ""
            if (len(got) == len(value)) if (secret or (n and n.password)) else (got == value):
                self.hide_keyboard()
                return True
        raise RuntimeError(f"could not type into {rid}" + ("" if secret else f": {got!r}"))

    def activity(self):
        out = self.sh("dumpsys activity activities", check=False)
        m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out)
        return m.group(1) if m else ""

    def shot(self, name):
        """screenshot: call only on screens without survey data (dialogs, settings, results)"""
        path = os.path.join(WORK, "shots", name + ".png")
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB] + (["-s", self.serial] if self.serial else []) + ["exec-out", "screencap", "-p"],
                                   capture_output=True, timeout=60).stdout)
        return path

    # -- app ------------------------------------------------------------------------------------
    def start_app(self):
        self.sh(f"am start -W -n {self.pkg}/com.topodroid.TDX.MainWindow", check=False)
        time.sleep(2)

    def stop_app(self):
        self.sh(f"am force-stop {self.pkg}", check=False)

    def data_dir(self):
        return "/storage/emulated/0/Documents/" + ("TDX-SilexGIS" if self.pkg.endswith(".silexgis") else "TDX")

    def db_counts(self, survey):
        """(surveys with that name, shots of it, plots of it) read from a copy of the app database"""
        import sqlite3
        with tempfile.TemporaryDirectory() as d:
            local = os.path.join(d, "db.sqlite")
            remote = self.data_dir() + "/TopoDroid/distox14.sqlite"
            self.adb("pull", remote, local)
            try:
                self.adb("pull", remote + "-journal", local + "-journal", check=False)
            except Exception:
                pass
            con = sqlite3.connect(local)
            try:
                rows = con.execute("select id from surveys where name = ?", (survey,)).fetchall()
                if not rows:
                    return 0, 0, 0
                sid = rows[0][0]
                shots = con.execute("select count(*) from shots where surveyId = ?", (sid,)).fetchone()[0]
                plots = con.execute("select count(*) from plots where surveyId = ?", (sid,)).fetchone()[0]
                return len(rows), shots, plots
            finally:
                con.close()

    def surveys(self):
        """survey names in the app database"""
        import sqlite3
        with tempfile.TemporaryDirectory() as d:
            local = os.path.join(d, "db.sqlite")
            self.adb("pull", self.data_dir() + "/TopoDroid/distox14.sqlite", local)
            con = sqlite3.connect(local)
            try:
                return [r[0] for r in con.execute("select name from surveys order by id")]
            finally:
                con.close()


def zip_counts(path):
    """(shots, plots) of a TopoDroid archive, counted from its survey.sql without printing it"""
    import zipfile
    z = zipfile.ZipFile(path)
    sql = [n for n in z.namelist() if n.endswith(".sql")][0]
    t = z.read(sql).decode("utf-8", "replace")
    count = lambda table: len(re.findall(r"(?im)^\s*insert\s+into\s+" + table + r"\b", t))
    return count("shots"), count("plots")


if __name__ == "__main__":
    d = Device(os.environ.get("ANDROID_SERIAL"), os.environ.get("TD_PKG", "com.topodroid.TDX"))
    if len(sys.argv) > 1 and sys.argv[1] == "dump":
        print("activity:", d.activity())
        d.print_safe()
    elif len(sys.argv) > 2 and sys.argv[1] == "tap":
        print(d.tap(sys.argv[2]).line())
    else:
        print("usage: tdui.py dump | tap <key>")
