# Recorded SilexGIS answers (contract version 1)

Copies of the `response.json` files the SilexGIS server records from its own test suite
(`contract/topodroid-sync/v1/<exchange>/response.json` in the SilexGIS repository, as recorded
at its commit 154380d0). They are copied here, not read in place, because the server records
them again whenever its answers change: a change shows up here only when it is copied on purpose.

The placeholders the recording uses for values that change on every run (`<upload>`, `<cave>`,
`<survey>`, `<timestamp>`, `<token>`, ...) are replaced by invented values when a test loads a
file (`SilexGisContractTest`). Every value in these files is invented.
