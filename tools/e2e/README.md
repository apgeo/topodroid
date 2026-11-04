# TopoDroid ↔ SilexGIS end-to-end UI test

`silexgis_e2e.py` drives both TopoDroid builds on one Android emulator against a SilexGIS server with
`adb` and `uiautomator`. It needs only Python 3 (standard library) and the Android SDK's `adb`. It adds
nothing to the TopoDroid source.

## What it does

The two builds are installed on the same device: the normal app (`com.topodroid.TDX`, data in
`Documents/TDX`) and the side-by-side copy (`com.topodroid.TDX.silexgis`, data in `Documents/TDX-SilexGIS`).

| Step | Normal app | Side-by-side app |
|---|---|---|
| setup | `install -r` (its other surveys are kept) | cleared, its folder removed, installed fresh: the first-run dialogs and the Normal usage level are exercised |
| S1 access | SilexGIS settings → Account → URL + key → **Test** must name the account, the server and the compiler → OK | same |
| S2 import | `--zip-normal` through Import → ZIP → the system file picker (Downloads); shots and plots in the app database must equal the ZIP's | `--zip-sbs`, same |
| | each app must hold only its own survey (the two folders are separate) | |
| S3 export | to an **existing** cave found with the cave search (`--existing-cave-search`), `.th` on, compile on, Survex and CSV on (Advanced level), link for anyone | to a **new** cave `E2E <survey> <HHMM>`, signed-in users, link for signed-in members, compile on |
| | progress is followed to the result dialog; the upload is then read on the server by status, stage, error code, outputs asked, artefacts, file roles, link and link audience | |
| S4 second device | downloads the side-by-side app's upload from *Import → From SilexGIS…* and imports it: shots and plots must equal the original ZIP's | downloads the normal app's upload, same |
| S1b + S5 | a wrong (well-formed) key is set; the second upload of the same survey must reopen the access dialog; the right key and OK restart the upload, which is then stored **without** compiling | second upload of its survey, compiling |
| | the older-versions count on the server must grow by one, and the app's older-versions note must show exactly when the server says `warn` | |
| links | | from the second upload's result dialog: *Withdraw link* (the server must have no link any more), then *Make a new link* (a link again, with the audience) |

Not automated: the sketch auto-export offer (*Export sketches to Therion automatically* in the result
dialog, then opening and closing each sketch so it is saved), because it needs the sketch window; it was
checked by hand once (the next upload carried a `therionSketch` file per sketch). Downloading a compiled
output and *Open in Cave3D* need a compile that succeeds.

Each check prints `PASS`/`FAIL` with its evidence. Nothing of the surveys is printed: only counts,
states and codes. The results go to `$EMU_WORK/e2e-results/e2e-<time>.json`; screenshots of the dialogs
(access test, export dialog, progress, result, import list) go to `$EMU_WORK/shots/` (`--no-shots` to
skip them). The survey's own screens (shot list, sketches, survey info) are never captured. The access key
is read from `--key-file` and never printed; masked key prefixes are redacted in every dump.

The exit code is 0 only when every check passes.

## Running it

```sh
G=/srv/data/silexgis/scratch/topodroid-integration/gradle
A=/srv/data/silexgis/scratch/topodroid-integration/apk
# 1. build both copies (each build overwrites the same APK, so copy it out)
cd $G && ./build.sh apk && cp build/outputs/apk/debug/topodroid-silexgis-debug.apk $A/e2e-normal-debug.apk
./build.sh apk -PsideBySide=true && cp build/outputs/apk/debug/topodroid-silexgis-debug.apk $A/e2e-sidebyside-debug.apk
# 2. one emulator machine-wide: check first, then start (see the Android runbook)
pgrep -af qemu-system || $G/tools/emu/emu.sh start
# 3. run (about 15-25 minutes on the software-GL emulator)
python3 -I $G/tools/e2e/silexgis_e2e.py \
  --zip-normal /path/to/first-survey.zip --zip-sbs /path/to/second-survey.zip \
  --apk-normal $A/e2e-normal-debug.apk --apk-sbs $A/e2e-sidebyside-debug.apk
# 4. always stop the emulator afterwards
$G/tools/emu/emu.sh stop
```

Options: `--server` (default `https://silexgistest.speotopo.ro`), `--key-file` (default
`~/.config/silexgis/silexgistest-admin-access-key`), `--existing-cave-search` / `--existing-cave-re`
(an ASCII search text and the pattern the picked cave must match; default the demo cave "Demo Mic…"),
`--tag` (suffix of the new cave), `--keep-sbs` (do not clear the side-by-side app), `--serial`.
Leave out `--apk-*` to test what is installed.

Preconditions:

- the normal app's usage profile is **Advanced** or above (the runbook's AVD has it). A survey of the same
  name as a test ZIP is deleted through Survey Info → DELETE before it is imported again, which needs that level;
- the account behind the key may create caves and links, and the server is reachable from the emulator;
- the ZIPs are TopoDroid archives named after their survey (`<survey>.zip`), and their survey names differ.

`tdui.py` is the small driver (dump, find, tap, fill with typed-length check, filtered dump);
`python3 -I tdui.py dump` with `TD_PKG=<package>` prints the filtered view of the current screen.

## Known slow spots

- `uiautomator dump` takes 1-3 s on the software-GL emulator; every tap re-dumps on purpose, because the
  soft keyboard moves dialogs.
- `input text` drops characters on a long string, so text is typed in chunks of six and the typed length is
  checked (a masked field is checked by length only).
