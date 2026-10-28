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
  never to whichever contact the provider fuzzily returned. Not found beats wrong person. When
  no name matches, a contact whose notes have a line that is exactly the word said is used: tag
  a contact "Assistant" and "text my assistant" reaches it.
- **Only what you said.** A text's words are yours, taken from what you said after the name;
  the model's version is thrown away, and when it cannot tell where your message starts it
  asks. A call is placed only when you ask for one ("call Sam", "give Mum a ring"): "Remind me
  to call Sam" never rings Sam, and "call me" never rings your own phone. A number is texted or
  called only if its digits were said, it taps only words you said, a timer needs its length
  said, and "navigate" goes only where it was told.
- **Answers only what it can know.** General knowledge and definitions in one sentence; a
  sentence that is nothing but a sum is worked out exactly in code, before any model; live data
  (weather, prices, scores, counts) gets a plain "I can't know that offline". An answer never
  claims to have sent, called or set anything.
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
  several contacts shows a selector. "Text Sam I'm running late" sends "I'm running late";
  "Text Sam" with no message asks "What should I say to Sam?" and your next dictation is the
  message.
- **Screen and notifications.** Read what is on screen (from the accessibility tree, or by
  OCR when it has little or no text), tap, scroll, go back, take a screenshot, and read your
  notifications.
- **Questions.** The small model decides a request is a question; a larger on-device model
  (or a remote one you configure) answers in a sentence.
- **Home Assistant (optional, off by default).** "Homie, turn off the kitchen light",
  "Homie, is the garage door open?" and your own phrases ("Homie, pink fan medium" runs the
  script you named that) go through the Home Assistant app on your phone to your own Home
  Assistant, which acts only on the devices and scripts you labelled. See
  [Home Assistant](#home-assistant).
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
- When a command needs another app on the phone (the clock, calls and texts, or the Home
  Assistant link if you turn it on), Assistant hands it only what that command needs, never
  audio. What that app does next is covered by its own privacy policy. The Home Assistant link
  hands the Home Assistant app a short command (on, off, "state" or "run", and the name) with
  your key; Assistant opens no connection for it.
- Audio is never transmitted, with or without the remote model. Release builds process it
  in memory only. Debug builds, for now, also keep the last 500 recordings on the device
  (`history/captures/` in the app's external files folder, `Android/data/<package>/files`,
  readable over adb) to diagnose end-of-speech detection; that goes, or becomes a setting,
  once detection works. They are excluded from Android backup and device transfer, and
  CLEAR HISTORY deletes them.
- Android cloud backup excludes everything you said or wrote: the query log, recordings,
  notes, reminders, learned phrases, settings (which can hold the remote model's key) and
  the models.

## Home Assistant

Optional, and off until you make a key. Assistant never talks to your Home Assistant server
itself: it hands a short command to the Home Assistant app on the same phone, which sends it
to your server over the connection it already has, at home or away. An automation on your
server checks the command and answers.

What you can say, starting with the trigger word (default "homie", a word you never say by
accident):

- "Homie, turn on the porch light", "Homie, switch the desk fan off". Lights, switches and
  fans labelled `assistant` only, on or off only.
- "Homie, is the garage door open?", "Homie, is the front door locked?", "Homie, what's the
  thermostat set to?". Anything labelled `assistant` or `assistant_read`.
- Your own phrases: any other words run the script labelled `assistant` with exactly that
  name. Name a script "Pink fan medium" and "Homie, pink fan medium" runs it; want a second
  way to say it, make a second script that runs the first. A script can do anything and
  nobody asks "are you sure?", so never label one that unlocks or opens something.

The name must be the device's or script's name in Home Assistant, said exactly (case,
punctuation and a leading "the" don't matter). Nothing is guessed: no match, or two with the
same name, and nothing happens. Home Assistant's own aliases aren't used, since an automation
can't read them; rename the entity instead (its entity id, and your automations, stay as they
are). Assistant says a device is on or off only when Home Assistant saw the new state, and
that a script started, since Home Assistant runs it on its own.

Setup:

1. In Assistant's Context screen, under HOME ASSISTANT · KEY, tap **Make a key and copy it**.
2. In Home Assistant, create the labels `assistant` (may be switched) and `assistant_read`
   (may only be asked about), and put them on the entities themselves, not on their devices.
   Never give `assistant` to a lock, a garage door, a gate, or a switch that drives one; use
   `assistant_read` for those.
3. Find your phone's user id: Developer tools → Events, listen to `mobile_app.share`, then
   share any text to the Home Assistant app; it is `context.user_id`.
4. Create an automation, switch it to YAML, and paste the one below. Replace the key, the user
   id and `notify.mobile_app_YOUR_PHONE`. A debug build of Assistant is
   `io.github.ijazshare.assistant.debug`.
5. In the Home Assistant app, set Persistent connection to "Always" (or "On home network
   only") so replies don't go through Google's push service.

It needs Home Assistant 2025.7 or newer. Optional: leave `mobile_app.share` out of the
recorder (`recorder: exclude: event_types:`), so the key isn't stored with every command.

```yaml
alias: Assistant commands
description: >-
  Commands from Assistant on the phone, through the Home Assistant app. Runs only for
  Assistant's key and this phone's user. Switches only lights, switches and fans labelled
  "assistant", and runs scripts labelled "assistant" by their name; tells the state of anything
  labelled "assistant" or "assistant_read".
mode: queued
triggers:
  - trigger: event
    event_type: mobile_app.share
    event_data:
      subject: "PASTE_THE_KEY_FROM_ASSISTANT"
    context:
      user_id: YOUR_PHONE_USER_ID
variables:
  phone: notify.mobile_app_YOUR_PHONE
  cmd: "{{ trigger.event.data.text | default('') | string | from_json(default={}) }}"
  want: "{{ cmd.do if cmd is mapping and cmd.do is string else '' }}"
  wanted: >-
    {{ (cmd.name if cmd is mapping and cmd.name is string else '')
       | lower | regex_replace('[^a-z0-9]+', ' ') | trim }}
  reply_id: "{{ (cmd.id if cmd is mapping and cmd.id is string else '') | urlencode }}"
  ok: "{{ reply_id | regex_match('^i[0-9a-f]{16}$') and wanted | string != '' }}"
  switchable: >-
    {%- set ns = namespace(ids=[]) -%}
    {%- for e in label_entities('assistant')
        if e.split('.')[0] in ['light', 'switch', 'fan']
        and (state_attr(e, 'friendly_name') or '') | lower | regex_replace('[^a-z0-9]+', ' ') | trim
            | regex_replace('^(the|my) ', '') == wanted | string -%}
      {%- set ns.ids = ns.ids + [e] -%}
    {%- endfor -%}
    {{ ns.ids }}
  runnable: >-
    {%- set ns = namespace(ids=[]) -%}
    {%- for e in label_entities('assistant')
        if e.split('.')[0] == 'script'
        and (state_attr(e, 'friendly_name') or '') | lower | regex_replace('[^a-z0-9]+', ' ') | trim
            | regex_replace('^(the|my) ', '') == wanted | string -%}
      {%- set ns.ids = ns.ids + [e] -%}
    {%- endfor -%}
    {{ ns.ids }}
  readable: >-
    {%- set ns = namespace(ids=[]) -%}
    {%- for e in (label_entities('assistant') + label_entities('assistant_read')) | unique
        if (state_attr(e, 'friendly_name') or '') | lower | regex_replace('[^a-z0-9]+', ' ') | trim
            | regex_replace('^(the|my) ', '') == wanted | string -%}
      {%- set ns.ids = ns.ids + [e] -%}
    {%- endfor -%}
    {{ ns.ids }}
actions:
  - choose:
      - alias: Tell the state
        conditions: "{{ ok and want == 'state' and readable | count == 1 }}"
        sequence:
          - variables:
              extras: >-
                {%- set e = readable[0] -%}
                {%- set ex = ['id:' ~ reply_id ~ ':String.urlencoded', 'status:done:String',
                    'state:' ~ (state_translated(e) | string | urlencode) ~ ':String.urlencoded'] -%}
                {%- set u = state_attr(e, 'unit_of_measurement') -%}
                {%- set t = state_attr(e, 'current_temperature') -%}
                {%- set g = state_attr(e, 'temperature') if e.startswith('climate.') else none -%}
                {%- if u is not none -%}{%- set ex = ex + ['unit:' ~ (u | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {%- if t is not none -%}{%- set ex = ex + ['temperature:' ~ (t | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {%- if g is not none -%}{%- set ex = ex + ['target:' ~ (g | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {{ ex | join(',') }}
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "{{ extras }}"
                ttl: 10000
                priority: high
      - alias: Switch it
        conditions: "{{ ok and want in ['turn_on', 'turn_off'] and switchable | count == 1 }}"
        sequence:
          - if: "{{ want == 'turn_on' }}"
            then:
              - action: homeassistant.turn_on
                continue_on_error: true
                target:
                  entity_id: "{{ switchable[0] }}"
            else:
              - action: homeassistant.turn_off
                continue_on_error: true
                target:
                  entity_id: "{{ switchable[0] }}"
          - wait_template: "{{ is_state(switchable[0], 'on' if want == 'turn_on' else 'off') }}"
            timeout: 5
            continue_on_timeout: true
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "id:{{ reply_id }}:String.urlencoded,status:{{ 'done' if wait.completed else 'unsure' }}:String"
                ttl: 10000
                priority: high
      - alias: Run it
        conditions: "{{ ok and want == 'run' and runnable | count == 1 }}"
        sequence:
          # Starts the script and goes on, so a long one never holds up the next command.
          - action: script.turn_on
            target:
              entity_id: "{{ runnable[0] }}"
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "id:{{ reply_id }}:String.urlencoded,status:done:String"
                ttl: 10000
                priority: high
      - alias: More than one has that name
        conditions: >-
          {{ ok and ((want == 'state' and readable | count > 1)
                     or (want in ['turn_on', 'turn_off'] and switchable | count > 1)
                     or (want == 'run' and runnable | count > 1)) }}
        sequence:
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "id:{{ reply_id }}:String.urlencoded,status:ambiguous:String"
                ttl: 10000
                priority: high
    default:
      - action: "{{ phone }}"
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: io.github.ijazshare.assistant
            intent_action: io.github.ijazshare.assistant.HA_REPLY
            intent_extras: "id:{{ reply_id }}:String.urlencoded,status:refused:String"
            ttl: 10000
            priority: high
```

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

Gradle runs on JDK 21 while the app compiles to Java 17 bytecode, the well-trodden bytecode
target for Android.

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
