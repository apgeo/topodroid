#!/usr/bin/env python3
"""TopoDroid <-> SilexGIS end-to-end UI test on an Android emulator (see README.md).

Two builds on one device: the normal app (com.topodroid.TDX, Documents/TDX) and the side-by-side
copy (com.topodroid.TDX.silexgis, Documents/TDX-SilexGIS). Each imports one survey ZIP through the
system file picker, uploads it to SilexGIS, downloads the other one's upload as the "second device",
and uploads its own survey a second time. The normal app's second upload first meets a wrong access
key and must reopen the access dialog and finish once the right key is given.

What is checked is said by counts and states only (shots, plots, upload status, error code, outputs,
older versions); no survey content is printed. The access key is read from a file and never printed.
"""
import argparse
import datetime as dt
import json
import os
import re
import secrets
import string
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tdui  # noqa: E402

NORMAL = "com.topodroid.TDX"
SBS = "com.topodroid.TDX.silexgis"
PERMS = ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "CAMERA",
         "RECORD_AUDIO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"]


class Run:
    def __init__(self, args):
        self.args = args
        self.key = open(os.path.expanduser(args.key_file)).read().strip()
        self.server = args.server.rstrip("/")
        self.checks = []
        self.facts = {}
        self.t0 = time.time()
        self.stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.tag = args.tag or dt.datetime.now().strftime("%H%M")

    # -- reporting ------------------------------------------------------------------------------
    def log(self, msg):
        print(f"[{time.time() - self.t0:6.0f}s] {msg}", flush=True)

    def check(self, name, ok, evidence=""):
        self.checks.append({"check": name, "pass": bool(ok), "evidence": evidence})
        self.log(f"{'PASS' if ok else 'FAIL'} {name}: {evidence}")
        return ok

    def shot(self, d, name):
        if self.args.shots:
            d.shot(f"e2e-{self.stamp}-{name}")

    # -- server (read by code and state only) ---------------------------------------------------
    def api(self, path, key=None):
        req = urllib.request.Request(self.server + path, headers={"Authorization": "Bearer " + (key or self.key)})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, None

    def find_upload(self, survey, since):
        """the newest upload of the survey made at or after `since` (UTC datetime), with its status"""
        st, page = self.api("/api/v1/survey-uploads?source=topodroid&pageSize=50")
        if st != 200:
            return None
        want = norm(survey)
        hits = [u for u in page["items"]
                if want in (norm(u.get("surveyName") or ""), norm(u.get("surveyTitle") or ""))
                and parse_time(u["createdAt"]) >= since]
        if not hits:
            return None
        u = max(hits, key=lambda x: x["createdAt"])
        st, s = self.api("/api/v1/survey-uploads/" + u["id"])
        if st != 200:
            return None
        return {
            "id": u["id"],
            "createdAt": u["createdAt"],
            "cave": (s.get("cave") or {}).get("name") if isinstance(s.get("cave"), dict) else u.get("caveName"),
            "caveId": u.get("caveId"),
            "caveCreated": s.get("caveCreated"),
            "status": s.get("status"),
            "stage": s.get("stage"),
            "errorCode": (s.get("error") or {}).get("code"),
            "compile": s.get("compile"),
            "outputsAsked": s.get("outputs"),
            "artefacts": sorted(a.get("kind") or "" for a in s.get("artefacts") or []),
            "fileRoles": sorted(f.get("role") or "" for f in s.get("files") or []),
            "files": len(s.get("files") or []),
            "older": s.get("olderVersions"),
            "link": bool(s.get("publicLink")),
            "linkAudience": (s.get("publicLink") or {}).get("audience") if isinstance(s.get("publicLink"), dict) else None,
            "linkRefusal": s.get("publicLinkRefusal"),
        }

    # -- device setup ---------------------------------------------------------------------------
    def install(self, d, apk, fresh):
        if fresh:
            d.sh(f"pm clear {d.pkg}", check=False)
            d.sh(f"rm -rf {d.data_dir()}", check=False)
        self.log(f"install {os.path.basename(apk)} -> {d.pkg}")
        d.adb("install", "-r", apk, timeout=300)
        d.sh(f"appops set {d.pkg} MANAGE_EXTERNAL_STORAGE allow", check=False)
        for p in PERMS:
            d.sh(f"pm grant {d.pkg} android.permission.{p}", check=False)

    def launch(self, d):
        """a fresh main window: the app is stopped and started, first-run dialogs are passed"""
        d.stop_app()
        d.start_app()
        for _ in range(12):
            k, n = d.wait_any(["handle", "btn_done", "btn_next", "btn_ok"], timeout=20)
            if k == "handle" and d.find("text=TopoDroid", dump=False) and "MainWindow" in d.activity():
                if not d.find("btn_ok", dump=False) and not d.find("btn_next", dump=False):
                    return
            if k in ("btn_ok", "btn_next", "btn_done"):
                d.tap(k)
                time.sleep(1.5)
        raise RuntimeError("main window not reached")

    def open_silexgis_settings(self, d):
        d.tap("desc=Menu")
        d.tap("text=SETTINGS")
        d.wait("text=IMPORT_EXPORT", 20)
        d.tap("text=IMPORT_EXPORT")
        d.wait("text=TopoDroid import/export settings", 20)
        d.scroll_to("text=SilexGIS")
        d.tap("text=SilexGIS")
        d.wait("text=SilexGIS export settings", 20)

    def access_dialog(self, d, key, test=True, url=None):
        """fill the access dialog (already open); with test, press Test and return the result lines"""
        if url is not None:
            d.fill("silexgis_url", url)
        d.fill("silexgis_key", key, secret=True)
        lines = None
        if test:
            d.tap("button_test")
            end = time.time() + 90
            while time.time() < end:
                n = d.find("silexgis_result")
                if n and n.text and not n.text.lower().startswith("testing") and d.find("button_test", dump=False).enabled:
                    lines = n.text.splitlines()
                    break
                time.sleep(1.5)
            self.shot(d, f"{short(d)}-access-test")
        d.tap("button_ok")
        time.sleep(1.5)
        return lines

    def configure(self, d):
        self.launch(d)
        self.open_silexgis_settings(d)
        d.tap("text=Account")
        d.wait("silexgis_url", 20)
        lines = self.access_dialog(d, self.key, test=True, url=self.server) or []
        ok = bool(lines) and lines[0].startswith("Account:") and any("compiles" in l for l in lines)
        self.check(f"{short(d)} S1 access Test", ok, " | ".join(re.sub(r"<[^>]*>", "<e-mail>", l) for l in lines))
        acc = d.find("value")
        self.check(f"{short(d)} S1 account row shows the account", acc is not None and acc.text not in ("", "---"),
                   "account row set" if acc and acc.text not in ("", "---") else "account row empty")

    def set_key(self, d, key):
        """set the access key without testing it (used to give a wrong one)"""
        self.launch(d)
        self.open_silexgis_settings(d)
        d.tap("text=Account")
        d.wait("silexgis_url", 20)
        self.access_dialog(d, key, test=False)

    # -- surveys --------------------------------------------------------------------------------
    def open_survey_info(self, d, survey):
        self.launch(d)
        n = d.scroll_to(f"text={survey}")
        d.sh(f"input swipe {n.x} {n.y} {n.x} {n.y} 1200")  # long tap: Survey Info, not the shot list
        if not d.wait("text=Survey Info", 20):
            raise RuntimeError("survey info not opened")

    def delete_survey(self, d, survey):
        self.open_survey_info(d, survey)
        d.tap("desc=Menu")
        time.sleep(1)
        if not d.find("text=DELETE"):
            raise RuntimeError("no DELETE in the survey menu: the usage profile must be Advanced or above")
        d.tap("text=DELETE")
        time.sleep(1)
        d.tap("button1")
        time.sleep(3)

    def import_zip(self, d, zip_path):
        survey = os.path.splitext(os.path.basename(zip_path))[0]
        if survey in d.surveys():
            self.log(f"{short(d)}: deleting the earlier copy of the survey before the import")
            self.delete_survey(d, survey)
        d.adb("push", zip_path, "/sdcard/Download/")
        self.launch(d)
        d.tap_xy(335, 336)  # import
        d.wait("spin", 15)
        if not d.find("text=ZIP"):
            raise RuntimeError("import type is not ZIP")
        d.tap("button_ok")
        name = os.path.basename(zip_path)
        self.pick_download(d, name)
        t = time.time()
        while time.time() - t < 120 and survey not in d.surveys():
            time.sleep(3)
        time.sleep(3)
        z_shots, z_plots = tdui.zip_counts(zip_path)
        n, shots, plots = d.db_counts(survey)
        self.facts[f"{short(d)} import zip"] = {"shots": shots, "plots": plots}
        self.check(f"{short(d)} S2 import {name}", n == 1 and shots == z_shots and plots == z_plots,
                   f"surveys={n} shots={shots}/{z_shots} plots={plots}/{z_plots} in {time.time() - t:.0f}s")
        return survey

    def pick_download(self, d, name):
        """in the system file picker, open Downloads and pick the file (the picker opens wherever it was last)"""
        d.wait_any(["desc=Show roots", f"text={name}"], timeout=30)
        time.sleep(2)  # the picker fills its list after it shows
        for _ in range(4):
            n = d.find(f"text={name}")
            if n is not None:
                d.tap_xy(n.x, n.y)
                return
            if d.find("desc=Show roots", dump=False):
                d.tap("desc=Show roots")
                time.sleep(2)
                roots = [m for m in d.find_all("text=Downloads", dump=True) if m.id == "title"]
                if roots:
                    d.tap_xy(roots[0].x, roots[0].y)
                    time.sleep(3)
                    d.wait(f"text={name}", 10)
        raise RuntimeError(f"{name} not found in the file picker's Downloads")

    # -- export ---------------------------------------------------------------------------------
    def open_export(self, d, survey):
        self.open_survey_info(d, survey)
        d.tap("desc=Menu")
        d.tap("text=EXPORT")
        d.wait("spin", 15)
        d.tap("spin")
        d.tap("text=SilexGIS")
        if not d.wait("silexgis_target", 15):
            raise RuntimeError("SilexGIS export block not shown")

    def spinner(self, d, rid, label):
        n = d.find(rid)
        cur = [m for m in d.nodes if m.id == "message_text" and n and n.bounds[1] <= m.y <= n.bounds[3]]
        if cur and cur[0].text == label:
            return
        d.tap(rid)
        time.sleep(1.2)
        d.tap(f"text={label}")
        time.sleep(1.2)

    def checkbox(self, d, rid, want):
        n = d.find(rid)
        if n is None:
            return None  # not shown at this usage level
        if n.checked != want:
            d.tap(rid)
            time.sleep(0.6)
        return want

    def target_new(self, d, cave, visibility):
        d.tap("silexgis_target")
        d.wait("silexgis_new_name", 30)
        d.fill("silexgis_new_name", cave)
        self.spinner(d, "silexgis_new_visibility", visibility)
        self.checkbox(d, "silexgis_new_protected", False)
        d.tap("button_ok")
        time.sleep(1.5)

    def target_existing(self, d, search, cave_re):
        d.tap("silexgis_target")
        d.wait("silexgis_search", 30)
        d.fill("silexgis_search", search)
        end = time.time() + 30
        while time.time() < end:
            d.dump()
            rows = [n for n in d.nodes if n.in_list and n.text and re.search(cave_re, n.text)]
            if rows:
                d.tap_xy(rows[0].x, rows[0].y)
                time.sleep(1.5)
                return rows[0].text.splitlines()[0]
            time.sleep(1.5)
        raise RuntimeError(f"no cave found for the search {search!r}")

    def follow(self, d, label, timeout=600):
        """press Upload and follow the progress to the result; returns what the result dialog says"""
        d.tap("button_ok")
        t = time.time()
        steps, last = [], None
        while time.time() - t < timeout:
            d.dump()
            if d.find("silexgis_reason", dump=False) and d.find("button_test", dump=False):
                reason = d.find("silexgis_reason", dump=False).text
                return {"keyDialog": True, "reason": reason, "steps": steps, "seconds": round(time.time() - t)}
            if d.find("button_close", dump=False) and d.find("silexgis_result", dump=False):
                break
            s = d.find("silexgis_step", dump=False)
            if s and s.text and s.text != last:
                steps.append(re.sub(r"\(\d+ of \d+\)", "(k of n)", s.text))
                last = s.text
                if len(steps) == 3:
                    self.shot(d, f"{label}-progress")
            time.sleep(1.0)
        else:
            raise RuntimeError("no result within the time allowed")
        g = lambda rid: (d.find(rid, dump=False).text if d.find(rid, dump=False) else "")
        arts = [n.text for n in d.nodes if n.in_list and n.id == "message_text" and n.text]
        res = {"keyDialog": False, "result": g("silexgis_result"), "notes": g("silexgis_notes"),
               "link": bool(re.search(r"https?://", g("silexgis_link"))), "linkNote": g("silexgis_link_note"),
               "artefacts": [re.split(r"\s{2,}", a)[0] for a in arts], "steps": steps,
               "seconds": round(time.time() - t)}
        self.shot(d, f"{label}-result")
        return res

    def export(self, d, survey, label, target, compile_, with_th=True, extra=None, link=None, documents=None):
        self.open_export(d, survey)
        if target is not None:
            kind, a, b = target
            cave = self.target_new(d, a, b) if kind == "new" else self.target_existing(d, a, b)
        self.checkbox(d, "silexgis_with_th", with_th)
        self.checkbox(d, "silexgis_compile", compile_)
        shown = {}
        for rid, want in (extra or {}).items():
            shown[rid] = self.checkbox(d, rid, want)
        if documents:
            self.spinner(d, "silexgis_visibility", documents)
        if link and d.find("silexgis_link"):
            self.spinner(d, "silexgis_link", link)
        tgt = d.find("silexgis_target").text
        self.shot(d, f"{label}-dialog")
        since = dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=5)
        res = self.follow(d, label)
        res["target"] = tgt
        res["advancedShown"] = shown
        return res, since

    def link_withdraw_and_make(self, d, survey, since):
        """from the result dialog: withdraw the link, then make a new one (the access key is enough for both)"""
        d.tap("button_link_withdraw")
        time.sleep(5)
        gone = not (self.find_upload(survey, since) or {}).get("link")
        n = d.find("silexgis_link")
        self.check(f"{short(d)} S3 link withdrawn from the phone", gone and d.find("button_link_withdraw", dump=False) is None,
                   f"server link gone={gone}; app says withdrawn={bool(n and 'withdrawn' in n.text)}")
        d.tap("button_link_make")
        time.sleep(6)
        up = self.find_upload(survey, since) or {}
        n = d.find("silexgis_link")
        self.check(f"{short(d)} S3 new link made from the phone", up.get("link") and bool(n and "https://" in n.text),
                   f"server link={up.get('link')} audience={up.get('linkAudience')}; app shows the address={bool(n and 'https://' in n.text)}")

    def close_result(self, d):
        if d.find("button_close"):
            d.tap("button_close")
            time.sleep(1)

    # -- download on the other device ------------------------------------------------------------
    def import_from_silexgis(self, d, survey, cave_re, row_time):
        if survey in d.surveys():
            self.log(f"{short(d)}: deleting the earlier copy of {survey} before the download")
            self.delete_survey(d, survey)
        self.launch(d)
        d.tap_xy(335, 336)
        d.tap("button_silexgis")
        d.wait("silexgis_list", 30)
        end = time.time() + 60
        row = None
        while time.time() < end and row is None:
            d.dump()
            items = [n for n in d.nodes if n.in_list and n.text]
            header = None
            for n in items:
                if "\n" not in n.text:
                    header = n.text
                elif header and re.search(cave_re, header) and row_time in n.text:
                    row = n
                    break
            if row is None:
                time.sleep(2)
        if row is None:
            raise RuntimeError("the upload is not in the SilexGIS import list")
        d.tap_xy(row.x, row.y)
        time.sleep(1)
        self.shot(d, f"{short(d)}-import-list")
        d.tap("button_ok")
        t = time.time()
        while time.time() - t < 180 and survey not in d.surveys():
            time.sleep(3)
        time.sleep(3)
        return d.db_counts(survey), round(time.time() - t)


def norm(s):
    return re.sub(r"[\s_]+", " ", s).strip().lower()


def parse_time(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def short(d):
    return "sbs" if d.pkg == SBS else "normal"


def row_time(created):
    return parse_time(created).astimezone(dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default="https://silexgistest.speotopo.ro")
    ap.add_argument("--key-file", default="~/.config/silexgis/silexgistest-admin-access-key")
    ap.add_argument("--zip-normal", required=True, help="TopoDroid ZIP imported into the normal app")
    ap.add_argument("--zip-sbs", required=True, help="TopoDroid ZIP imported into the side-by-side app")
    ap.add_argument("--apk-normal", help="install this APK as the normal app first")
    ap.add_argument("--apk-sbs", help="install this APK as the side-by-side app first")
    ap.add_argument("--keep-sbs", action="store_true", help="do not clear the side-by-side app and its folder before installing")
    ap.add_argument("--existing-cave-search", default="Demo Mic", help="ASCII search text for the existing cave")
    ap.add_argument("--existing-cave-re", default=r"Demo Mic", help="regex the picked cave name must match")
    ap.add_argument("--tag", help="suffix of the new cave name (default HHMM)")
    ap.add_argument("--no-shots", dest="shots", action="store_false", help="take no screenshots of the dialogs")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    args = ap.parse_args()

    r = Run(args)
    n = tdui.Device(args.serial, NORMAL)
    s = tdui.Device(args.serial, SBS)
    a_survey = os.path.splitext(os.path.basename(args.zip_normal))[0]
    b_survey = os.path.splitext(os.path.basename(args.zip_sbs))[0]
    new_cave = f"E2E {b_survey.replace('_', ' ')} {r.tag}"
    try:
        # setup: both builds on one device, each with its own folder
        if args.apk_normal:
            r.install(n, args.apk_normal, fresh=False)
        if args.apk_sbs:
            r.install(s, args.apk_sbs, fresh=not args.keep_sbs)
        pk = n.sh("pm list packages com.topodroid")
        r.check("setup both builds installed", f"package:{NORMAL}\n" in pk + "\n" and f"package:{SBS}" in pk,
                ", ".join(sorted(l.split(":")[1] for l in pk.split())))

        # S1 access
        r.configure(n)
        r.configure(s)

        # an earlier run leaves both surveys in an app that was not cleared: start each app without them
        for d in (n, s):
            for name in (a_survey, b_survey):
                if name in d.surveys():
                    r.log(f"{short(d)}: deleting {name} left by an earlier run")
                    r.delete_survey(d, name)

        # S2 import the two ZIPs, one per app
        r.import_zip(n, args.zip_normal)
        r.import_zip(s, args.zip_sbs)
        n_names, s_names = set(n.surveys()), set(s.surveys())
        r.check("setup each app keeps its own folder", a_survey in n_names and a_survey not in s_names
                and b_survey in s_names and b_survey not in n_names,
                f"{a_survey}: normal={a_survey in n_names} sbs={a_survey in s_names}; "
                f"{b_survey}: normal={b_survey in n_names} sbs={b_survey in s_names}")

        # S3 export: the side-by-side app makes a new cave, the normal app uploads to an existing one
        res_s, since_s = r.export(s, b_survey, "sbs-up1", ("new", new_cave, "signed-in users"), compile_=True,
                                  link="Link for signed-in members", documents="signed-in users")
        up_s = r.find_upload(b_survey, since_s)
        r.facts["sbs upload 1"] = {"app": res_s, "server": up_s}
        report_upload(r, "sbs S3 upload (new cave)", res_s, up_s, want_compile=True, want_link="organization")
        r.close_result(s)

        res_n, since_n = r.export(n, a_survey, "normal-up1", ("existing", args.existing_cave_search, args.existing_cave_re),
                                  compile_=True, link="Link for anyone",
                                  extra={"silexgis_with_svx": True, "silexgis_with_csv": True})
        up_n = r.find_upload(a_survey, since_n)
        r.facts["normal upload 1"] = {"app": res_n, "server": up_n}
        report_upload(r, "normal S3 upload (existing cave)", res_n, up_n, want_compile=True, want_link="public")
        r.close_result(n)

        # S4 the other app downloads each upload and imports it
        if up_n:
            (cnt, shots, plots), secs = r.import_from_silexgis(s, a_survey, args.existing_cave_re, row_time(up_n["createdAt"]))
            zs, zp = tdui.zip_counts(args.zip_normal)
            r.check(f"sbs S4 download+import {a_survey}", cnt == 1 and shots == zs and plots == zp,
                    f"surveys={cnt} shots={shots}/{zs} plots={plots}/{zp} in {secs}s")
        if up_s:
            (cnt, shots, plots), secs = r.import_from_silexgis(n, b_survey, re.escape(new_cave), row_time(up_s["createdAt"]))
            zs, zp = tdui.zip_counts(args.zip_sbs)
            r.check(f"normal S4 download+import {b_survey}", cnt == 1 and shots == zs and plots == zp,
                    f"surveys={cnt} shots={shots}/{zs} plots={plots}/{zp} in {secs}s")

        # S1b + S5 normal: a wrong key, then the second upload of the same survey (without compiling)
        wrong = "sgx_" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(len(r.key) - 4))
        r.set_key(n, wrong)
        res2, since2 = r.export(n, a_survey, "normal-up2", None, compile_=False)
        r.check("normal S1 wrong key reopens the access dialog at upload", res2.get("keyDialog") is True,
                res2.get("reason", "no access dialog")[:120])
        if res2.get("keyDialog"):
            r.shot(n, "normal-key-refused")
            r.access_dialog(n, r.key, test=False)  # OK restarts the upload with the right key
            res2 = wait_result(r, n, "normal-up2")
        up2 = r.find_upload(a_survey, since2)
        r.facts["normal upload 2"] = {"app": res2, "server": up2}
        report_upload(r, "normal S5 second upload (no compile)", res2, up2, want_compile=False, want_link="public")
        report_versions(r, "normal S5", up_n, up2, res2)
        r.close_result(n)

        # S5 side-by-side: the second upload of its survey, compiling
        res_s2, since_s2 = r.export(s, b_survey, "sbs-up2", None, compile_=True)
        up_s2 = r.find_upload(b_survey, since_s2)
        r.facts["sbs upload 2"] = {"app": res_s2, "server": up_s2}
        report_upload(r, "sbs S5 second upload", res_s2, up_s2, want_compile=True, want_link="organization")
        report_versions(r, "sbs S5", up_s, up_s2, res_s2)
        if up_s2 and res_s2.get("link"):
            r.link_withdraw_and_make(s, b_survey, since_s2)
        r.close_result(s)
    except Exception as e:  # a step that cannot go on is a failure of the run, reported with what was reached
        r.check("run reached its end", False, f"{type(e).__name__}: {e}")
        for d in (n, s):
            try:
                if d.pkg in d.activity():
                    r.shot(d, f"{short(d)}-stopped")
            except Exception:
                pass
    finally:
        out_dir = os.path.join(tdui.WORK, "e2e-results")
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, f"e2e-{r.stamp}.json")
        with open(out, "w") as f:
            json.dump({"checks": r.checks, "facts": r.facts, "seconds": round(time.time() - r.t0)}, f, indent=1, default=str)
        fails = [c for c in r.checks if not c["pass"]]
        r.log(f"{len(r.checks) - len(fails)} passed, {len(fails)} failed; results in {out}")
    sys.exit(1 if fails else 0)


def wait_result(r, d, label, timeout=600):
    """after the access dialog restarted the upload: follow it to the result dialog"""
    t = time.time()
    steps, last = [], None
    while time.time() - t < timeout:
        d.dump()
        if d.find("button_close", dump=False) and d.find("silexgis_result", dump=False):
            break
        s = d.find("silexgis_step", dump=False)
        if s and s.text and s.text != last:
            steps.append(re.sub(r"\(\d+ of \d+\)", "(k of n)", s.text))
            last = s.text
        time.sleep(1.0)
    else:
        raise RuntimeError("no result after the restarted upload")
    g = lambda rid: (d.find(rid, dump=False).text if d.find(rid, dump=False) else "")
    arts = [n.text for n in d.nodes if n.in_list and n.id == "message_text" and n.text]
    r.shot(d, f"{label}-result")
    return {"keyDialog": False, "restarted": True, "result": g("silexgis_result"), "notes": g("silexgis_notes"),
            "link": bool(re.search(r"https?://", g("silexgis_link"))), "linkNote": g("silexgis_link_note"),
            "artefacts": [re.split(r"\s{2,}", a)[0] for a in arts], "steps": steps, "seconds": round(time.time() - t)}


def report_upload(r, name, app, up, want_compile, want_link):
    if app.get("keyDialog"):
        r.check(name, False, "the access dialog opened: " + app.get("reason", "")[:100])
        return
    if not up:
        r.check(name + " reached the server", False, "no upload of the survey found on the server after the start")
        return
    r.check(name + " reached the server", True,
            f"status={up['status']} stage={up['stage']} files={up['files']} roles={','.join(sorted(set(up['fileRoles'])))} "
            f"caveCreated={up['caveCreated']} in {app['seconds']}s, steps={len(app['steps'])}")
    r.check(name + " app result agrees with the server",
            (up["status"] == "succeeded") == (not re.search(r"could not|failed|error", app["result"], re.I)),
            f"app: {app['result'][:140]!r}; server: {up['status']}/{up['errorCode']}")
    if want_compile:
        r.check(name + " compiled", up["status"] == "succeeded" and len(up["artefacts"]) > 1,
                f"status={up['status']} error={up['errorCode']} asked={up['outputsAsked']} artefacts={up['artefacts']}")
    else:
        r.check(name + " stored without compiling", up["status"] == "succeeded" and not up["compile"],
                f"status={up['status']} compile={up['compile']} artefacts={up['artefacts']}")
    r.check(name + " link", up["link"] and app["link"] and (up["linkAudience"] in (None, want_link)),
            f"server link={up['link']} audience={up['linkAudience']} refusal={up['linkRefusal']}; app shows link={app['link']}")


def report_versions(r, name, first, second, app):
    if not (first and second):
        r.check(name + " versions", False, "an upload is missing on the server")
        return
    c1 = (first.get("older") or {}).get("count", 0)
    c2 = (second.get("older") or {}).get("count", 0)
    warn = (second.get("older") or {}).get("warn", False)
    note = "older versions" in (app.get("notes") or "")
    r.check(name + " versions", second["id"] != first["id"] and c2 == c1 + 1 and note == bool(warn),
            f"older versions {c1} -> {c2}, size {(second.get('older') or {}).get('sizeBytes')} B, warn={warn}, "
            f"app note shown={note}")


if __name__ == "__main__":
    main()
