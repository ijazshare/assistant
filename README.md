# Assistant

> **Fork.** This is a personal fork of [hasan-ismail/themachine](https://github.com/hasan-ismail/themachine),
> renamed and redesigned. Licensed under AGPL-3.0, same as upstream — see [LICENSE](LICENSE).
> Changes here include a modern UI redesign and expanded on-device actions.

A fully offline voice assistant for Android. It replaces Google Assistant for the things
people actually use an assistant for — alarms, timers, reminders and tasks — and does the
whole job on the phone: speech recognition, intent parsing and speech synthesis all run
locally.

The network is touched exactly once, to download the models after install. After that,
airplane mode changes nothing.

> **Status: in development.** Phase P0 (repo and toolchain) is complete. The voice pipeline
> lands over phases P1–P8; see [PROGRESS.md](PROGRESS.md) for what works today.

## Why

Every mainstream phone assistant streams your voice to a server. For "set a timer for ten
minutes" that trade is absurd. The Machine does the same job with the microphone data never
leaving the device.

## Features

Planned for v1, tracked in [PROGRESS.md](PROGRESS.md):

- **Hold the side button, speak, done.** The Machine registers as Android's default digital
  assistant, so the existing assistant gesture wakes it.
- **Alarms and timers** are delegated to your phone's own Clock app via standard `AlarmClock`
  intents — no second alarm engine to fight with, and no alarms trapped inside this app.
- **Tasks and reminders** are one idea: a task with an optional due time. Tasks with a due
  time fire an exact-alarm notification with *Done* and *Snooze 10 min* actions, and survive
  a reboot.
- **Follow-up questions.** If a command is missing something, it asks — up to two rounds.
- **No telemetry.** No analytics, no crash reporting, no ad SDK, no account.

Out of scope for v1 (and not accidentally, see `CLAUDE.md`): wake word, general chat,
multilingual recognition, and automations.

## Requirements

- Android 13 (API 33) or newer, `arm64-v8a`
- ~1.1 GB free storage for the default models (~2.8 GB if you pick the larger language model)
- Tested against a Galaxy S26 Ultra; tuned for high-end hardware rather than the low end

## Install

Download the APK from [Releases](https://github.com/hasan-ismail/themachine/releases) and
sideload it. Verify the download first:

```bash
sha256sum -c the-machine-v0.1.0.apk.sha256
```

There is no Play Store build. On first launch the app walks you through the permissions it
needs and downloads the models.

## Models

The APK ships without models — they are downloaded on first run, verified by SHA-256, and
resumable if the download is interrupted. You can also import a GGUF file from storage.

| Role | Default | Alternative |
|---|---|---|
| Speech recognition | Whisper `tiny.en` (Q5\_1) | `base.en` (Q5\_1), slower but more accurate |
| Intent parsing | Gemma 3 1B IT (QAT Q4\_0) | Gemma 3 4B IT (QAT Q4\_0) |
| Speech synthesis | Piper `en_US-amy-medium` | — (v1 ships one voice) |

Exact files, sizes, sources and checksums are recorded in the model registry and reproduced
in [PROGRESS.md](PROGRESS.md) as each is verified.

## Privacy

These are enforced, not just promised:

- No analytics, telemetry or crash-reporting SDK. CI fails the build if such a dependency
  appears in the version catalog or a build file.
- The only network access in the app is model downloading. `INTERNET` is used for nothing
  else.
- Audio is processed in memory and never written to disk or transmitted.

## Building

You need JDK 21 and the Android SDK. Everything else the build fetches itself.

```bash
git clone --recurse-submodules https://github.com/hasan-ismail/themachine.git
cd themachine
./gradlew assembleDebug
```

Point the build at your SDK with a `local.properties` containing `sdk.dir=...`, or set
`ANDROID_HOME`.

| Task | What it does |
|---|---|
| `./gradlew assembleDebug` | Builds the debug APK |
| `./gradlew testDebugUnitTest` | Runs unit tests |
| `./gradlew spotlessApply` | Formats Kotlin, Gradle and XML |
| `./gradlew spotlessCheck detekt` | The formatting and static-analysis gate |
| `./gradlew lintDebug` | Android lint (warnings are errors) |
| `./gradlew connectedDebugAndroidTest` | Instrumented tests (needs a device) |

Gradle runs on JDK 21 while the app compiles to Java 17 bytecode. That split is deliberate:
Robolectric needs a 21 JVM to emulate recent SDK levels, and Java 17 is the well-trodden
bytecode target for Android.

### Release signing

Releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml) when a
`v*` tag is pushed. It expects four repository secrets. To set them up once:

```bash
# 1. Create a keystore (keep the .jks and these passwords somewhere safe and offline —
#    losing them means you can never ship an update that upgrades an installed copy).
keytool -genkeypair -v \
  -keystore release.jks -alias themachine \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. Base64-encode it for the secret.
base64 -w0 release.jks > release.jks.b64
```

Then add, under *Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | contents of `release.jks.b64` |
| `SIGNING_KEYSTORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `themachine` |
| `SIGNING_KEY_PASSWORD` | the key password |

Delete `release.jks.b64` afterwards. The workflow refuses to run if any secret is missing —
it will not quietly publish an unsigned APK — and shreds the decoded keystore when it finishes.

Locally, release signing reads the same values from an untracked `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root. Both that file
and `*.jks` are gitignored.

## Licence and attribution

The Machine is licensed under the **GNU Affero General Public License v3.0** — see
[LICENSE](LICENSE).

It builds on work by others, each under its own licence:

| Component | Project | Licence |
|---|---|---|
| Speech recognition | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | MIT |
| Whisper models | [OpenAI Whisper](https://github.com/openai/whisper) | MIT |
| Language model runtime | [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT |
| Language model | [Gemma 3](https://ai.google.dev/gemma) | Gemma Terms of Use |
| Speech synthesis | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 |
| Voice | [Piper](https://github.com/rhasspy/piper) `en_US-amy-medium` | see the voice's model card |

Model weights are downloaded from their upstream hosts at runtime and are not redistributed
by this project. Using Gemma means accepting the Gemma Terms of Use.

## Contributing

Issues and pull requests are welcome. Before opening either, read `CLAUDE.md` — it records
the decisions that are settled and the reasoning behind them, so a proposal that reverses one
needs to argue with the reasoning rather than restate the alternative.
