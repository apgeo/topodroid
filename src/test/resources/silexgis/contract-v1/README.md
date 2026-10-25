# Recorded SilexGIS answers (contract version 1)

Copies of the files the SilexGIS server records from its own test suite
(`contract/topodroid-sync/v1/<exchange>/` in the SilexGIS repository, as recorded at its commit
02a43551; the answers are byte for byte those of the earlier copy from 154380d0):

| File here | Recording |
|---|---|
| `NN-name.json` | `NN-name/response.json` |
| `NN-name.request.json` | `NN-name/request.json` (the body the recorded tool sent) |
| `11-errors-<case>.json` | `11-errors/<case>/response.json` |

They are copied here, not read in place, because the server records them again whenever its
answers change: a change shows up here only when it is copied on purpose.

The placeholders the recording uses for values that change on every run (`<upload>`, `<cave>`,
`<survey>`, `<timestamp>`, `<token>`, ...) are replaced by invented values when a test loads a
file (`SilexGisContractTest`). Every value in these files is invented.
