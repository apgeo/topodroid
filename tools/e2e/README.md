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

After S5 (`--scenarios all`, the default) or right after S2 (`--scenarios new`):

| Step | App | What is checked |
|---|---|---|
| S6 a | normal | an upload to a new cave whose visibility is changed on purpose (to *private*): the visibility is sent (fake server: the opening body; real server: the cave's visibility) and the link to the server survey is stored |
| S6 b | normal | the stored link is made dead (the app is stopped and its preferences edited through `run-as`: the stored survey id becomes one that does not exist). The next upload must show the dead-link dialog with three buttons; *Upload as new* opens the cave dialog, a new cave is made with the visibility left as it started, the upload succeeds and the stored link is replaced |
| S7 | normal | that new cave was opened without a visibility (fake server: no `visibility` member; real server: the cave has the account's default, the club of the account, else private) |
| S9 | normal | from that result dialog a PDF result is downloaded: the dialog says `Downloaded to Download/<name>`, the file is in `/sdcard/Download`, and a VIEW intent with type `application/pdf`, a `content:` URI and the read grant starts another app |
| S6 c | normal | the link is made dead again; *Pick a cave* opens the cave search, the picked existing cave takes the upload, the stored link is replaced |
| S8 | normal | the cave dialog offers *Forget the link to the server survey*; it clears the stored link without uploading, and the export dialog falls back to the automatic target |
| S7, S9 | side-by-side | the export dialog's target says *visibility the server decides* when it is left as it started; a compiled upload to that new cave succeeds (fake: no `visibility` sent); its PDF download as above |

The screenshots of a run are copied beside its results, to `e2e-results/shots-<time>/`.

Against the local fake server (`../../../fake-server/fake_silexgis.py`, which with `--strict` refuses an
unknown `caveSurvey.id` with `404 survey_upload.survey_not_found` and a club cave for its account, which is in
no club, with `400 survey_upload.visibility_invalid`):

```sh
F=/srv/data/silexgis/scratch/topodroid-integration/fake-server
python3 -I $F/fake_silexgis.py --port 18767 --strict --artefacts $F/artefacts-invented --work $F/work-x &
python3 -I $G/tools/e2e/silexgis_e2e.py --scenarios new --server http://10.0.2.2:18767 \
  --server-host http://127.0.0.1:18767 --fake-work $F/work-x --key-file $F/fake-key \
  --existing-cave-search Pestera --existing-cave-re "Pestera Inventata" --zip-normal ... --zip-sbs ...
```

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

Options: `--server` (default `https://silexgistest.speotopo.ro`; as the app reaches it), `--server-host` (as
this script reaches it, when it differs), `--fake-work`, `--scenarios all|old|new`, `--key-file` (default
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
