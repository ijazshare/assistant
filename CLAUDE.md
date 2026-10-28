# CLAUDE.md — Assistant

Assistant is a fully offline Android voice assistant that replaces Google Assistant: alarms, timers, reminders, answers to questions, and acting on the phone (messages, calls, opening apps, reading the screen and notifications). Voice in (Whisper), one grammar-constrained tool call from an on-device LLM (llama.cpp), voice out (Piper). Triggered by the phone's side button / assistant gesture by holding Android's default-assistant role. No telemetry, no account. The network is used only to download models and, if the owner turns it on, for the remote answer model, which is sent only the question text, fixed instructions and the current time.

- Repo: `ijazshare/assistant` · App display name: "Assistant" · License: **AGPL-3.0** · Distribution: GitHub Releases (no Play Store constraints)
- Owner: ijazshare. Ask before deviating from anything in **Locked decisions**.

## Locked decisions (do not revisit silently)

1. **Language/UI:** Kotlin + Jetpack Compose (Material 3), single `:app` Gradle module, native code under `app/src/main/cpp/` via CMake. `minSdk 33`, `targetSdk`/`compileSdk` = latest stable. Optimize for high-end devices (primary target: the owner's Samsung foldable, Snapdragon SM8850, about 11 GB RAM, Android 17); do not contort the code for low-RAM phones.
2. **STT:** whisper.cpp (git submodule, pinned commit). English only. Default model `ggml-tiny.en-q5_1` (speed over accuracy); `base.en-q5_1` is an alternative download in the Models screen, used when the default is not installed.
3. **LLM:** llama.cpp (git submodule, pinned commit). Gemma 3 QAT Q4_0 GGUF. The **1B routes every command** (one grammar-constrained tool call) and is the registry default; the **4B answers questions** (the largest installed model answers, so with only the 1B installed the 1B answers). There is no setting that makes the 4B the router. Architecture must allow adding model families later via the model registry.
4. **TTS:** sherpa-onnx with Piper voice `en_US-amy-medium` (female, offline). Voice is swappable by design (nicer/custom voices planned for v2) but the registry ships this one.
5. **LLM scope:** the 1B decides commands. A question is answered by the larger on-device model, or by a remote model the owner configures (off by default), which is sent only the question text, fixed instructions and the current time. Answers are one short sentence (the on-device path keeps only the first); questions about the screen are answered on-device only and may run to two. Anything that needs live data is declined, and an answer never claims to have acted.
6. **Alarms & timers:** delegate to the native Clock app via `AlarmClock` intents. Never build a custom alarm engine.
7. **Reminders/tasks:** Tasks and reminders live in `tasks.md`, a Markdown checklist the user can open and edit in the Context screen. A due time sets an exact alarm; each task's id and due time are kept in a hidden comment at the end of its line. (`AlarmManager.setExactAndAllowWhileIdle`; when exact alarms are not permitted it falls back to an inexact `setAndAllowWhileIdle`.)
8. **Follow-ups:** there is no general slot-filling loop. Three follow-ups exist. "Text Sam" with no message asks "What should I say to Sam?" and the next dictation (within 60 s) is the message; a reply that opens like an order is read back for a yes (30 s) instead of being sent. A reminder with neither a task nor a time asks "What should I remind you about?" and the answer (within 60 s) is added to the request and run as one command. A name that matches several contacts shows a contact selector. Anything else that is missing fails with a spoken reason.
9. **Device control (revised 2026-09-01 — supersedes the original stub-only rule).** The
   assistant is meant to *act on the phone*, not only set alarms. The accessibility service is a real
   device-control surface (gestures, global actions, launching apps, reading the screen), and the app
   requests the full sensor and data permission set: microphone, camera, screen capture, location,
   SMS/call log/contacts, notification access, and draw-over-other-apps. Permissions are declared and
   requested through onboarding first; features land on them incrementally.
   The original rule read: *"declared, permission-gated, and surfaced in settings — but no-op stubs in
   v1 … do not invent features on top of them."* It was reversed deliberately, not drifted past.
   Constraints that survive the change and are **not** negotiable: no telemetry; the network is used
   only for model downloads and the opt-in remote model, which is sent only the question text, fixed
   instructions and the current time; and anything read from the device (screen, notifications,
   contacts, messages) stays on the device.
10. **Model delivery:** APK ships without models. The Models screen downloads each asset with progress UI, SHA-256 verification and resume. Sources are Hugging Face (Whisper, Gemma GGUFs) and GitHub (the Piper voice from sherpa-onnx releases, Tesseract data, the Silero voice check). There is no manual import.
11. **Wake word:** v2. Do not add always-listening anything in v1.
12. **CI/CD:** GitHub Actions — build/lint/test on PR, signed release APK attached to GitHub Release on `v*` tags.

## Architecture

### Package layout (`io.github.ijazshare.assistant`, under `app/src/main/java/`)

```
assistant/    voice-interaction service and session: VoiceSession (the conductor), SpeechCapture (microphone, end of speech, Whisper), CallChecks (the model's call checked against the words), FollowUps (the questions asked back, PendingSend/PendingReminder, the contact chooser), overlay, CommandCache (learned phrases), answers without the model (LocalAnswers for clock and world time, Arithmetic for sums), mic foreground service
audio/        AudioRecord capture (16 kHz mono), energy-based endpointing, high-pass and frame meter, optional Silero voice check, sound cues
context/      AssistantFiles: profile.md, memories.md, tasks.md, notes.md under getExternalFilesDir(null)/context
diagnostics/  CrashLog: uncaught-exception traces kept on the device (shown in History), never uploaded
history/      QueryLog (every command, on the device) and CaptureArchive (saved recordings on debug builds)
llm/          LlamaEngine (JNI wrapper), ModelRouter (which model answers), prompt builder, tool-call parser, answer and screen prompts, RemoteAnswerer (opt-in)
models/       ModelRegistry, ModelStorage, ModelDownloader + WorkManager worker (resumable, SHA-256 verified), archive unpacking
nativebridge/ NativeBridge: loads `libassistant.so` and registers ggml's CPU backends
ocr/          ScreenReader (accessibility tree first, OCR when it has little text) and TesseractEngine
permissions/  permission catalog and inspector used by onboarding
services/     AssistantAccessibilityService (tap, scroll, navigate, read screen, screenshot) and AssistantNotificationListener
settings/     DataStore-backed settings: admin name, own number, remote model URL/key/name
stt/          WhisperEngine (JNI wrapper), contact-name bias, contact ranking, junk-transcript filter
tools/        AssistantTools (the tool list), ToolGrammar, ToolExecutor, time resolution, contact and app lookup, ReminderStore and its receivers
tts/          PiperEngine (sherpa-onnx)
ui/           Compose screens: main, models, permissions/onboarding, context editor, history, the assistant overlay frame
app/src/main/cpp/  CMakeLists, whisper.cpp + llama.cpp submodules, assistant_jni.cpp
```

### Voice session pipeline

```
side button → VoiceInteractionSession opens (overlay over current app)
→ record until 860 ms of trailing quiet, 2.6 s with nothing well above the room, or 2 s with nothing near the speaker's own level (cap 20 s; levels from a 400 Hz high-pass, and sounds almost entirely above 1.5 kHz never count as the speaker; an optional Silero voice check can only end a capture sooner; see audio/Endpointer.kt for known limits), live waveform + partial UI
→ whisper transcribe → show transcript
→ a pending "what should I say" answer, or a learned phrase (CommandCache), runs without the model; otherwise the 1B makes one grammar-constrained tool call
→ if the tool is `answer`: unload the 1B, then the larger on-device model or the remote model (if configured) answers; the on-device one is freed straight after
→ ToolExecutor runs the call → TTS speaks the result → session auto-dismisses after speech
```

One model in memory at a time. Latency budget on the reference device, warm: **< 3 s** from end-of-speech to start of spoken reply (tiny.en + 1B). Treat regressions as bugs.

### Tools

The 1B's single grammar-constrained tool call is the routing decision. The tool list lives in `tools/AssistantTools.kt`; the GBNF grammar is built from it in `tools/ToolGrammar.kt`. Fifteen tools: `set_alarm`, `set_timer`, `show_alarms`, `create_reminder`, `send_message`, `call_contact`, `open_app`, `read_screen`, `take_screenshot`, `tap_text`, `scroll`, `navigate`, `read_notifications`, `answer`, `unsupported`. Learned phrases (`assistant/CommandCache.kt`) can skip the model for the cacheable tools only: alarms, timers, show_alarms, create_reminder, open_app, read_screen, take_screenshot, read_notifications, scroll, navigate. It never caches `answer`, `unsupported`, `send_message`, `call_contact` or `tap_text`.

- **Never trust LLM date math.** The model only reports what it heard (hour, minute, hours/minutes/seconds, "tomorrow"); code decides the rest. `TimeResolver` totals durations and validates and reconciles the hour against am/pm and part of day. `StatedTime` replaces the model's time with the one the words state ("in 30 minutes" is now plus 30 minutes; "at 9:35" is 9:35) when the transcript states exactly one. A day beyond tomorrow is declined, and a reminder time equal to the current minute is treated as no time. Past times roll to the next day in `ReminderStore`. All heavily unit-tested.
- Context size 2048; the prompt cache (`.prompt-cache` beside each model) saves prefill, and is tied to the install that wrote it (a stamp checked in `LlamaEngine.load`).

### Tool execution

No Room database.

- `set_alarm` → `AlarmClock.ACTION_SET_ALARM` with `EXTRA_HOUR/MINUTES/MESSAGE` and `EXTRA_SKIP_UI=true` (stay in overlay). `set_timer` → `ACTION_SET_TIMER` with `EXTRA_LENGTH`, only if a unit or "timer" was said (numbers alone are not a length). `show_alarms` → `ACTION_SHOW_ALARMS`. `navigate` acts only on a target that was said ("Navigate to work" must not press Home).
- `create_reminder` → a line in `tasks.md` via `ReminderStore`; with a time, `AlarmManager.setExactAndAllowWhileIdle` (inexact fallback). `ReminderReceiver` posts the notification with Done and Snooze 10 min actions. `ReminderBootReceiver` re-arms every reminder on `BOOT_COMPLETED`, timezone change and time change, and after a reboot shows once more a reminder that fired but was never answered (Done, Snooze, swiped or opened): a fired reminder's line carries a `delivered` flag in place of its due time. Ticked-off tasks are left out of the answer model's context.
- `send_message` → `SmsManager`; the message is the user's words after the contact's name, taken from the transcript (the body the model writes is thrown away), and the assistant asks when it cannot tell; `call_contact` → `TelecomManager.placeCall` (falls back to the dialler), only when the whole sentence asks for a call (call, phone, ring or dial and the name, or "give Mum a ring", with only words of asking around them) and never to the owner's own number; anything else is declined, never made a text ("Tell me to call me back" rang the owner's phone). Contacts must match whole words of a display name (or, when no name matches, a note line that tags them: "Assistant" for "text my assistant"), a number is used only if its digits were said, and a sent text is reported as "Sending" because nothing confirms delivery.
- `open_app` → `AppLookup` and the app's launch intent; no accessibility needed. `tap_text` taps only words that were said, matched as whole words on screen. `tap_text`, `scroll`, `navigate`, `take_screenshot`, `read_screen` → the accessibility service (`read_screen` falls back to Tesseract OCR, if installed, when the tree has fewer than 3 lines or 40 characters of text). `read_notifications` → the notification listener.

### Model management

| Asset | Default file | ~Size |
|---|---|---|
| STT | `ggml-tiny.en-q5_1.bin` (alt: `ggml-base.en-q5_1.bin`) | 32 / 60 MB |
| LLM | Gemma 3 1B IT QAT Q4_0 GGUF (alt: 4B) | ~0.72 / ~2.5 GB |
| TTS | `vits-piper-en_US-amy-medium` (tar.bz2: onnx + tokens + espeak data) | ~67 MB (85 MB unpacked) |
| OCR | `eng.traineddata` (Tesseract fast), for screens with no accessibility text | ~4 MB |
| VAD | `silero_vad.onnx` (v5.1.2), optional: ends a capture sooner in noisy rooms | ~2.3 MB |

- Store under `getExternalFilesDir(null)/models/`. Registry = versioned JSON in `app/src/main/assets/models/registry-v1.json`: id, role, display name, URL, SHA-256, size, RAM requirement, licence.
- **Verify every URL/filename/SHA-256 against its live source (Hugging Face or GitHub) at implementation time — do not trust remembered paths.** Whisper: `ggerganov/whisper.cpp`. Piper: sherpa-onnx TTS model releases (`k2-fsa`). Gemma: the registry uses ggml-org's ungated GGUF conversions of the QAT weights so download works without an HF token; the earlier mirror's file did not tokenise the turn markers, so re-check any replacement.
- Downloads run in a foreground service (`dataSync`) via WorkManager: resumable (HTTP Range), checksum-verified, cancellable, with a storage-space preflight.
- Switching model in settings = download if absent, then swap. Show per-model downloaded/size state and a delete option.

### Runtime/perf policy

- llama/whisper: models are mmapped, threads = cores minus 2 clamped to 2..6, ggml's ARM CPU variants chosen at runtime (`GGML_CPU_ALL_VARIANTS`, i8mm/dotprod tiers). CPU-only; GPU (OpenCL/Adreno) is a v2 experiment.
- **One model in memory at a time.** The 1B is unloaded before a question goes to the larger model, which is freed as soon as it has answered (both resident put an 11 GB phone into swap).
- **Memory gate.** `ModelRouter` refuses to load the answering model unless free memory covers its weights plus 640 MB headroom, and says so aloud.
- Engines load on session start and are freed on session end (mmap page cache keeps warm reloads fast). A prompt cache is written to disk beside each LLM so prefill is not repaid; a learned phrase skips the LLM entirely. A "keep model in memory" toggle does not exist.

## Android integration

- **Assistant role:** implement `VoiceInteractionService` (+ `VoiceInteractionSessionService`, `VoiceInteractionSession`) with the `android.voice_interaction` metadata/XML so the app appears under *Default digital assistant app*. Onboarding must deep-link there (`Settings.ACTION_VOICE_INPUT_SETTINGS` or manage-default-apps) and, for One UI, show a hint: *Settings → Advanced features → Side button → Press and hold → Wake digital assistant*.
- **Permissions (revised 2026-09-01 — see Locked decision 9).** The original list was marked
  "exhaustive for v1"; it no longer is. Three tiers, because they are granted in three different ways
  and onboarding has to treat them differently:
  - *Core:* `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`,
    `RECEIVE_BOOT_COMPLETED`, `INTERNET` (model downloads, and the opt-in remote model), `com.android.alarm.permission.SET_ALARM`,
    `FOREGROUND_SERVICE` (+ `_DATA_SYNC`, `_MICROPHONE`, `_MEDIA_PROJECTION`, `_LOCATION`), `VIBRATE`.
  - *Sensor and data, runtime-granted:* `CAMERA`, `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`,
    `READ_CONTACTS`, and the SMS/call family (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `CALL_PHONE`).
    Note the last group is **hard-restricted** by the platform, which behaves differently for a
    sideloaded install than for a Play install — onboarding must handle that rather than assume a
    dialog will appear.
  - *Special access, granted through a Settings screen and never a dialog:* accessibility service,
    notification listener, `SYSTEM_ALERT_WINDOW` (draw over other apps), exact alarms, and
    `MediaProjection` — which cannot be pre-granted at all and re-prompts every session by design.
- **Restricted settings.** Android blocks a sideloaded app from enabling its accessibility service
  until the user opens App info → ⋮ → *Allow restricted settings*. Onboarding must walk through this
  explicitly; a plain deep link to accessibility settings lands on a disabled toggle and looks broken.
- Onboarding checklist screen (`ui/onboarding/AccessChecklist.kt`, catalog in `permissions/AssistantPermissions.kt`): mic → notifications → exact alarms → default assistant, then the optional control tier (accessibility, display over other apps, notification access, screen capture) and data tier (camera, location, contacts, messages and calls), each with a plain-language reason. Accessibility and notification access are used by the tools (tap, scroll, navigate, screenshots, reading the screen and notifications), so they are no longer "reserved".
- **Privacy invariants:** no analytics, no crash-reporting SDKs. The network is used only for model downloads (`ModelDownloader`) and the opt-in remote model (`RemoteAnswerer`; off until URL, key and model are all set), which receives only the question text, fixed instructions and the current time, never audio, the user's name, their notes or anything read from the device. Crash traces stay on the device (`CrashLog`). CI fails if a telemetry dependency appears. State this in README.

## Build, test, style

- Commands: `./gradlew assembleDebug` · `testDebugUnitTest` · `spotlessCheck` / `spotlessApply` · `detekt` · `connectedDebugAndroidTest` (device only).
- Gradle version catalog (`libs.versions.toml`); no DI framework (plain constructors); coroutines + Flow; DataStore (no SharedPreferences); conventional commits.
- Unit-test hard requirements (all present in `app/src/test`): `TimeResolver` and `StatedTime`; the tool list and grammar (`AssistantToolsTest`, `ToolGrammarTest`); contact matching and message-body checks; the tasks.md reader/writer (`AssistantFilesTaskTest`); model storage state and SHA-256 verification (`ModelStorageTest`); the command cache. Not unit-tested: the downloader's HTTP Range resume, and the tool-call parser, which is covered on device (`ToolCallOnDeviceTest`). JNI layers and the routing and honesty benchmarks are instrumented tests (`app/src/androidTest`).
- Every commit must build. Update the git submodule pins deliberately, never floating.
- **Verify current versions via web search when wiring things up** (AGP/Kotlin/Compose BOM, sherpa-onnx AAR, whisper.cpp/llama.cpp tags) — training-data versions are stale.

## CI/CD (GitHub Actions)

- `ci.yml`: on PR and push to `main` → spotless, detekt, unit tests, Android lint, `assembleDebug` (cache Gradle and the native build; submodules checked out recursively), plus a repository-invariants job (no source ignored by `.gitignore`, submodules unmodified, no telemetry dependency in the build files).
- `release.yml`: on `v*` tag (or a manual run naming an existing tag) → a preflight stops if any signing secret is missing, then builds the signed release APK using `SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, and attaches the APK and its `.sha256` to a GitHub Release with generated notes. It ran successfully for v0.1.0 on the upstream repo (the original author's, linked from the README) on 2026-09-03; this repo has no signing secrets and no release yet. README documents the one-time keystore setup.
- Repo hygiene: `.gitignore`, `LICENSE` (AGPL-3.0), README (features, screenshots later, install, model licenses/attribution for Gemma/Whisper/Piper), issue templates, `dependabot.yml` (gradle + actions).

## Next integration: Home Assistant (owner's choice, 2026-10-28)

Not started; plan it with the owner before writing code. It lives in this app on the phone, not as an add-on on the Home Assistant server, and it must be airtight: act only on devices the owner exposed, never guess a device, and confirm anything that unlocks or opens (locks, garage, alarm). The proposal so far (PROGRESS.md, "Next"): opt-in and off by default, the exact words forwarded to HA Assist, through the Home Assistant app on the phone if it accepts them from other apps, otherwise the HA API directly. Either way the words leave the phone for the owner's own server, so locked decision 9 and the privacy invariants are changed first, explicitly, by the owner.

## Out of scope for v1 (v2+ backlog — do not start unasked)

Wake word · nicer/custom TTS voices · additional LLM families (Qwen, Llama) · multi-turn chat · multilingual STT (Urdu/Arabic) · user-defined automations or macros built on the accessibility/notification services (the voice-triggered tools that use them exist) · GPU inference · "keep model warm" service.
