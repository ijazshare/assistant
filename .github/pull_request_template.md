## What changed

<!-- One or two sentences. Reference the CLAUDE.md phase if this is build work. -->

## Why

## Verification

- [ ] `./gradlew spotlessCheck detekt` passes
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew assembleDebug` succeeds
- [ ] Tested on device (say which, and what you ran)

## Invariants

- [ ] No new network calls outside `ModelDownloadManager`
- [ ] No analytics, telemetry, or crash-reporting dependency added
- [ ] No change to a **Locked decision** in `CLAUDE.md` (or it was agreed first)
