# Assistant

> Assistant is developed independently. It started as a fork of
> [Hasan Ismail's offline voice assistant](https://github.com/hasan-ismail/themachine) and has
> been modified since September 2026. Like the original, it is licensed under AGPL-3.0 — see
> [LICENSE](LICENSE).

A fully offline voice assistant for Android. It replaces Google Assistant for the things
people actually use an assistant for — alarms, timers, reminders, questions, and acting on the
phone — and does the whole job on the phone: speech recognition, choosing what to do, and
speech synthesis all run locally.

There is no network use outside model downloads (the models after install, and any new
optional model such as the voice check that helps end-of-speech detection) and one opt-in
exception, a remote model for answering questions, described under Privacy. Otherwise
airplane mode changes nothing.

> **Status: a working build, developed and tested on the owner's phone. No release has been
> published on this repo yet** — build it from source (see Building). [PROGRESS.md](PROGRESS.md) is the
> running log of what was built and verified.

## What this fork changes

- **Named "Assistant"** and restyled after the original Halo trilogy: chamfered panels, cyan
  rims and scanlines. The app itself is opaque; only the assistant overlay is translucent, so
  it shows the app underneath.
- **Acts on the phone.** Silent SMS through `SmsManager`, direct calls, opening apps, reading
  the screen and notifications, tapping and scrolling through the accessibility service.
- **Contacts are matched, not guessed.** A spoken name must match whole words of a contact's
  display name; "me" goes only to the number you save on the Context screen (YOUR NUMBER),
  never to whichever contact the provider fuzzily returned. Not found beats wrong person.
- **Answers only what it can know.** General knowledge, definitions and arithmetic in one
  sentence; live data (weather, prices, scores, counts) gets a plain "I can't know that
  offline". An answer never claims to have sent, called or set anything.
- **Everything is testable over adb without speaking.** Instrumented probes drive the real
  models and log to logcat: `RoutingBenchmarkTest` (tag `BENCH`, 57 typed commands),
  `AnswerHonestyTest` (`HONEST`), and `PipelineProbeTest` (`PIPE`) with `-e runcmd "<text>"`,
  `-e resolve "<name>|<name>"`, `-e setown <number>`, and `-e live true` to actually execute.
  Without `-e live true` nothing is sent.

The privacy invariants are unchanged: no telemetry, no network outside model downloads and
the opt-in remote answer model (which receives only the question text, fixed instructions and
the current time), and nothing read from the device leaves it.

## Why

Every mainstream phone assistant streams your voice to a server. For "set a timer for ten
minutes" that trade is absurd. Assistant does the same job with the microphone data never
leaving the device.

## Features

What it does today:

- **Hold the side button, speak, done.** The app registers as Android's default digital
  assistant, so the existing assistant gesture wakes it. You can also type a command.
- **Alarms and timers** are delegated to your phone's own Clock app via standard `AlarmClock`
  intents — no second alarm engine to fight with, and no alarms trapped inside this app.
- **Tasks and reminders** live in `tasks.md`, a Markdown checklist you can open and edit on
  the Context screen. A task with a due time fires an exact-alarm notification with *Done*
  and *Snooze 10 min* actions, and survives a reboot.
- **Messages, calls and apps.** Text or call a contact, open an app. A name that matches
  several contacts shows a selector. "Text Sam" with no message asks "What should I say to
  Sam?" and your next dictation is the message.
- **Screen and notifications.** Read what is on screen (from the accessibility tree, or by
  OCR when it has little or no text), tap, scroll, go back, take a screenshot, and read your
  notifications.
- **Questions.** The small model decides a request is a question; a larger on-device model
  (or a remote one you configure) answers in a sentence.
- **Learned phrases.** A phrase whose meaning is fixed is learned on first success and runs
  without the model afterwards.
- **No telemetry.** No analytics, no crash reporting, no ad SDK, no account.

Out of scope for now (and not accidentally, see `CLAUDE.md`): wake word, multi-turn chat,
multilingual recognition, and user-defined automations.

## Requirements

- Android 13 (API 33) or newer, `arm64-v8a`
- ~1 GB free storage for the default models (about 3.5 GB with the larger 4B model)
- Developed and tested on the owner's Samsung foldable (Snapdragon SM8850, about 11 GB RAM, Android 17); tuned for high-end hardware rather than the low end

## Install

This repo has no Releases yet, so build it from source (see Building) and install the debug
APK with `adb install`. There is no Play Store build. Open the Permissions screen to grant
what the assistant needs and set it as your default assistant, then download the models from
the Models screen.

## Models

The APK ships without models. They are downloaded from the Models screen, verified by
SHA-256, and resumable if the download is interrupted. Sources are Hugging Face (Whisper,
Gemma) and GitHub (the Piper voice, the OCR data, the voice check).

| Role | Default | Alternative |
|---|---|---|
| Speech recognition | Whisper `tiny.en` (Q5\_1) | `base.en` (Q5\_1), slower but more accurate |
| Choosing what to do | Gemma 3 1B IT (QAT Q4\_0) | — |
| Answering questions | the largest installed Gemma, normally Gemma 3 4B IT (QAT Q4\_0) | a remote model you configure (opt-in) |
| Speech synthesis | Piper `en_US-amy-medium` | — (one voice for now) |
| Screen OCR | Tesseract English (fast) | — |
| End-of-speech voice check | Silero VAD v5 (optional) | — |

Exact files, sizes, sources and checksums are recorded in the model registry,
`app/src/main/assets/models/registry-v1.json`.

## Privacy

These are enforced, not just promised:

- No analytics, telemetry or crash-reporting SDK. CI fails the build if such a dependency
  appears in the version catalog or a build file.
- Network access is model downloading, plus one strictly opt-in exception: a remote
  language model for general-knowledge answers, which you point at any OpenAI-compatible
  endpoint (OpenRouter, or your own llama.cpp server at home). It is off until you enter
  a URL, key and model, and it only ever receives the transcript text of a question, fixed instructions and the
  current time — never your name or your notes.
- Audio is never transmitted, with or without the remote model. Release builds process it
  in memory only. Debug builds, for now, also keep the last 500 recordings on the device
  (`history/captures/` in the app's external files folder, `Android/data/<package>/files`,
  readable over adb) to diagnose end-of-speech detection; that goes, or becomes a setting,
  once detection works. They are excluded from Android backup and device transfer, and
  CLEAR HISTORY deletes them.
- Android cloud backup excludes everything you said or wrote: the query log, recordings,
  notes, reminders, learned phrases, settings (which can hold the remote model's key) and
  the models.

## Building

You need JDK 21 and the Android SDK. Everything else the build fetches itself.

```bash
git clone --recurse-submodules https://github.com/ijazshare/assistant.git
cd assistant
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
`v*` tag is pushed. It ran successfully for v0.1.0 on the upstream repo; this repo has no
signing secrets yet and has published no release. The workflow expects four repository
secrets. To set them up once:

```bash
# 1. Create a keystore (keep the .jks and these passwords somewhere safe and offline —
#    losing them means you can never ship an update that upgrades an installed copy).
keytool -genkeypair -v \
  -keystore release.jks -alias assistant \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. Base64-encode it for the secret.
base64 -w0 release.jks > release.jks.b64
```

Then add, under *Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | contents of `release.jks.b64` |
| `SIGNING_KEYSTORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `assistant` |
| `SIGNING_KEY_PASSWORD` | the key password |

Delete `release.jks.b64` afterwards. The workflow refuses to run if any secret is missing —
it will not quietly publish an unsigned APK — and shreds the decoded keystore when it finishes.

Locally, release signing reads the same values from an untracked `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the project root. Both that file
and `*.jks` are gitignored.

## Licence and attribution

Assistant is licensed under the **GNU Affero General Public License v3.0** — see
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
