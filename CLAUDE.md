# CLAUDE.md — The Machine

The Machine is a fully offline Android voice assistant that replaces Google Assistant for basic tasks: alarms, timers, reminders, and tasks. Voice in (Whisper), intent parsing (on-device LLM), voice out (Piper). Triggered by the phone's side button / assistant gesture by holding Android's default-assistant role. No telemetry, no cloud — the network is touched exactly once, to download models after install.

- Repo: `hasan-ismail/themachine` · App display name: "The Machine" · License: **AGPL-3.0** · Distribution: GitHub Releases (no Play Store constraints)
- Owner: Hasan. Ask before deviating from anything in **Locked decisions**.

## Locked decisions (do not revisit silently)

1. **Language/UI:** Kotlin + Jetpack Compose (Material 3), single `:app` Gradle module, native code under `app/src/main/cpp/` via CMake. `minSdk 33`, `targetSdk`/`compileSdk` = latest stable. Optimize for high-end devices (primary target: Galaxy S26 Ultra); do not contort the code for low-RAM phones.
2. **STT:** whisper.cpp (git submodule, pinned tag). English only. Default model `ggml-tiny.en-q5_1` (speed over accuracy); `base.en-q5_1` selectable in settings.
3. **LLM:** llama.cpp (git submodule, pinned tag). Gemma 3 QAT Q4_0 GGUF — **1B default, 4B selectable in settings**. Architecture must allow adding model families later (Qwen etc.) via a model registry, but v1 ships Gemma only.
4. **TTS:** sherpa-onnx with Piper voice `en_US-amy-medium` (female, offline). Voice is swappable by design (nicer/custom voices planned for v2) but v1 hardcodes this one.
5. **LLM scope:** command parsing **only**. Non-command queries get a polite spoken decline ("I can only help with alarms, timers, reminders and tasks right now."). No general chat in v1.
6. **Alarms & timers:** delegate to the native Clock app via `AlarmClock` intents. Never build a custom alarm engine.
7. **Reminders/tasks:** one concept — a *task* with an optional due datetime. Tasks with a due time fire an exact-alarm notification (that's a "reminder"). Stored locally in Room.
8. **Follow-ups:** basic slot-filling supported (max 2 clarification rounds per command).
9. **Device control (revised 2026-09-01 by Hasan — supersedes the original stub-only rule).** The
   assistant is meant to *act on the phone*, not only set alarms. The accessibility service is a real
   device-control surface (gestures, global actions, launching apps, reading the screen), and the app
   requests the full sensor and data permission set: microphone, camera, screen capture, location,
   SMS/call log/contacts, notification access, and draw-over-other-apps. Permissions are declared and
   requested through onboarding first; features land on them incrementally.
   The original rule read: *"declared, permission-gated, and surfaced in settings — but no-op stubs in
   v1 … do not invent features on top of them."* It was reversed deliberately, not drifted past.
   Two constraints survive the change and are **not** negotiable: the privacy invariants below (no
   telemetry, no network outside model downloads) still hold, and anything read from the device stays
   on the device.
10. **Model delivery:** APK ships without models. First-run downloads from Hugging Face with progress UI, SHA-256 verification, and resume. Manual GGUF import from storage as fallback.
11. **Wake word:** v2. Do not add always-listening anything in v1.
12. **CI/CD:** GitHub Actions — build/lint/test on PR, signed release APK attached to GitHub Release on `v*` tags.

## Architecture

### Package layout (`io.github.hasanismail.themachine`)

```
app/
  assistant/     VoiceInteractionService, SessionService, VoiceInteractionSession, session state machine
  audio/         AudioRecord capture (16 kHz mono PCM), energy-based endpointing (VAD)
  stt/           WhisperEngine (JNI wrapper)
  llm/           LlamaEngine (JNI wrapper), grammar/schema, prompt builder, prompt-cache handling
  tts/           PiperEngine (sherpa-onnx)
  intents/       Intent model (sealed classes), IntentParser (LLM json -> model), IntentExecutor per intent
  tasks/         Room DB, repository, exact-alarm scheduler, boot receiver, notification actions
  models/        ModelRegistry, ModelDownloadManager (WorkManager + foreground service), storage
  settings/      DataStore-backed settings + Compose screens
  ui/            Main activity (task list, model manager, settings, onboarding), assistant overlay composables
  services/      MachineAccessibilityService (stub), MachineNotificationListener (stub)
  cpp/           CMakeLists, whisper.cpp + llama.cpp submodules, thin JNI bridges
```

### Voice session pipeline

```
side button → VoiceInteractionSession opens (overlay over current app)
→ record until ~800 ms trailing silence (cap 15 s), live waveform + partial UI
→ whisper transcribe → show transcript
→ llama parse (grammar-constrained JSON) → Intent
→ if clarify: TTS asks, re-listen, merge slots (≤2 rounds)
→ IntentExecutor runs → TTS speaks confirmation → session auto-dismisses after speech
```

Latency budget on the reference device, warm: **< 3 s** from end-of-speech to start of spoken reply (tiny.en + 1B). Treat regressions as bugs.

### Intent schema (v1 — exhaustive)

LLM must emit exactly one JSON object matching this schema. Use llama.cpp's JSON-schema→GBNF grammar so output is valid by construction; greedy sampling (temp 0), ctx 1024, max ~200 tokens.

```json
{ "intent": "set_alarm|set_timer|dismiss_alarm|show_alarms|create_task|complete_task|delete_task|list_tasks|clarify|unsupported",
  "time": "ISO-8601 local datetime, alarms/tasks only",
  "duration_seconds": 0,
  "label": "string?",
  "repeat_days": ["MON","..."],
  "task_query": "string, for complete/delete lookup",
  "filter": "today|all",
  "question": "string, clarify only" }
```

- **Time resolution:** the system prompt embeds current local datetime + timezone; the LLM outputs absolute ISO datetimes. Kotlin `TimeResolver` then validates: past datetimes roll forward (7:00 → tomorrow 7:00), rejects nonsense, is unit-tested heavily. Never trust LLM date math blindly.
- **Prompt:** compact system prompt (schema + 2–3 few-shots). Save llama prompt-cache state to disk per model so prefill isn't repaid every session.
- **Task lookup** (`complete/delete`): fuzzy title match in Kotlin against the Room DB; if >1 candidate, speak options and clarify.

### Intent execution

- `set_alarm` → `AlarmClock.ACTION_SET_ALARM` with `EXTRA_HOUR/MINUTES/MESSAGE/DAYS` and `EXTRA_SKIP_UI=true` (stay in overlay). `set_timer` → `ACTION_SET_TIMER` likewise. `dismiss_alarm` → `ACTION_DISMISS_ALARM` search-mode (best-effort; on ambiguity, open Clock via `ACTION_SHOW_ALARMS`).
- Tasks: Room + `AlarmManager.setExactAndAllowWhileIdle`; notification with Done/Snooze-10-min actions; `BOOT_COMPLETED` receiver reschedules everything.

### Model management

| Asset | Default file | ~Size |
|---|---|---|
| STT | `ggml-tiny.en-q5_1.bin` (opt: `ggml-base.en-q5_1.bin`) | 31 / 57 MB |
| LLM | Gemma 3 1B IT QAT Q4_0 GGUF (opt: 4B) | ~0.9 / ~2.6 GB |
| TTS | `vits-piper-en_US-amy-medium` (onnx + tokens + espeak data) | ~65 MB |

- Store under `getExternalFilesDir(null)/models/`. Registry = versioned JSON in assets: id, display name, URL, SHA-256, size, RAM requirement.
- **Verify every URL/filename/SHA-256 against Hugging Face at implementation time — do not trust remembered paths.** Whisper: `ggerganov/whisper.cpp`. Piper: sherpa-onnx TTS model releases (`k2-fsa`/`csukuangfj`). Gemma: `google/gemma-3-{1b,4b}-it-qat-q4_0-gguf` is license-gated — prefer a reputable **ungated** mirror of the same QAT weights (e.g. unsloth/bartowski/ggml-org) so download works without an HF token; document whichever is chosen.
- Downloads run in a foreground service (`dataSync`) via WorkManager: resumable (HTTP Range), checksum-verified, cancellable, with a storage-space preflight.
- Switching model in settings = download if absent, then swap. Show per-model downloaded/size state and a delete option.

### Runtime/perf policy

- llama/whisper: mmap on, threads = big-core count, ARM runtime feature dispatch (i8mm/dotprod) enabled in CMake flags. CPU-only v1; GPU (OpenCL/Adreno) is a v2 experiment.
- Engines load on session start, free on session end (mmap page cache keeps warm reloads fast). Prompt-cache file eliminates prefill cost. A "keep model in memory" toggle can come later — not v1.

## Android integration

- **Assistant role:** implement `VoiceInteractionService` (+ `VoiceInteractionSessionService`, `VoiceInteractionSession`) with the `android.voice_interaction` metadata/XML so The Machine appears under *Default digital assistant app*. Onboarding must deep-link there (`Settings.ACTION_VOICE_INPUT_SETTINGS` or manage-default-apps) and, for One UI, show a hint: *Settings → Advanced features → Side button → Press and hold → Wake digital assistant*.
- **Permissions (revised 2026-09-01 — see Locked decision 9).** The original list was marked
  "exhaustive for v1"; it no longer is. Three tiers, because they are granted in three different ways
  and onboarding has to treat them differently:
  - *Core:* `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`,
    `RECEIVE_BOOT_COMPLETED`, `INTERNET` (downloads only), `com.android.alarm.permission.SET_ALARM`,
    `FOREGROUND_SERVICE` (+ `_DATA_SYNC`, `_MICROPHONE`, `_MEDIA_PROJECTION`, `_LOCATION`), `VIBRATE`.
  - *Sensor and data, runtime-granted:* `CAMERA`, `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`,
    `READ_CONTACTS`, and the SMS/call-log family (`READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`).
    Note the last group is **hard-restricted** by the platform, which behaves differently for a
    sideloaded install than for a Play install — onboarding must handle that rather than assume a
    dialog will appear.
  - *Special access, granted through a Settings screen and never a dialog:* accessibility service,
    notification listener, `SYSTEM_ALERT_WINDOW` (draw over other apps), exact alarms, and
    `MediaProjection` — which cannot be pre-granted at all and re-prompts every session by design.
- **Restricted settings.** Android blocks a sideloaded app from enabling its accessibility service
  until the user opens App info → ⋮ → *Allow restricted settings*. Onboarding must walk through this
  explicitly; a plain deep link to accessibility settings lands on a disabled toggle and looks broken.
- Onboarding checklist screen: mic → notifications → exact alarms → default assistant → (optional, off by default) accessibility + notification access with a plain-language "reserved for future automations" explanation.
- **Privacy invariants:** no analytics, no crash reporting SDKs, no network calls outside `ModelDownloadManager`. State this in README.

## Build, test, style

- Commands: `./gradlew assembleDebug` · `testDebugUnitTest` · `spotlessCheck` / `spotlessApply` · `detekt` · `connectedDebugAndroidTest` (device only).
- Gradle version catalog (`libs.versions.toml`); Hilt for DI; coroutines + Flow; DataStore (no SharedPreferences); conventional commits.
- Unit-test hard requirements: `TimeResolver`, intent JSON → model mapping, task fuzzy matcher, download resume/checksum logic. JNI layers get thin instrumented smoke tests.
- Every commit must build. Update the git submodule pins deliberately, never floating.
- **Verify current versions via web search when wiring things up** (AGP/Kotlin/Compose BOM, sherpa-onnx AAR, whisper.cpp/llama.cpp tags) — training-data versions are stale.

## CI/CD (GitHub Actions)

- `ci.yml`: on PR/push → spotless, detekt, unit tests, `assembleDebug` (cache Gradle + NDK; submodules checked out recursively).
- `release.yml`: on `v*` tag → build signed release APK using secrets `SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`; attach APK + checksums to a GitHub Release with generated notes. README documents one-time keystore setup.
- Repo hygiene: `.gitignore`, `LICENSE` (AGPL-3.0), README (features, screenshots later, install, model licenses/attribution for Gemma/Whisper/Piper), issue templates, `dependabot.yml` (gradle + actions).

## Out of scope for v1 (v2+ backlog — do not start unasked)

Wake word · nicer/custom TTS voices · additional LLM families (Qwen, Llama) · general chat mode toggle · multilingual STT (Urdu/Arabic) · automations built on the accessibility/notification services · GPU inference · "keep model warm" service.
