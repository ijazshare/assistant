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

Labels decide what Assistant may touch. Put them on the entities themselves, not their devices:

- `assistant`: may be asked about and controlled.
- `assistant_read`: may only be asked about.
- No label: Assistant can't see it at all.

What you can say, starting with the trigger word (default "homie", a word you never say by
accident):

- Lights, switches and fans: "Homie, turn off the pink bedroom light", "Homie, pink bedroom
  lights off" (every light in that room).
- The thermostat: "Homie, cool to 72", "Homie, set the thermostat to heat", "Homie, make it
  warmer" (2 degrees), "Homie, turn off the thermostat". Only within the limits in the
  automation.
- Locks and covers: "Homie, lock the front door", "Homie, close the garage door". Nothing is
  ever unlocked or opened by voice, by Assistant or by the automation.
- Questions: "Homie, is the garage door open?", "Homie, what's the thermostat set to?",
  "Homie, what's the temperature in the kitchen?"
- Your own phrases: a script labelled `assistant` runs when you say its whole name. Name a
  script "Pink fan medium" and "Homie, pink fan medium" runs it. A script can do anything and
  nobody asks "are you sure?", so never give `assistant` to a script, or a switch, that unlocks
  or opens something (a gate relay, a door strike).

A device is its name in Home Assistant, or its name and its room in either order: "pink
bedroom light", "ceiling light in the den". Case, punctuation and "the" don't matter. Part of
a name never counts: "bedroom light" doesn't find "Pink Bedroom Light", since it could be the
wrong bedroom's. When two things answer to what you said, Assistant asks which, and every
word must be accounted for, so "turn on the light in ten minutes" is declined, not done now.
Home Assistant's own aliases aren't used; rename the entity instead (its entity id, and your
automations, stay as they are).

Assistant says a device is on, locked or closing only when Home Assistant saw it happen. For
a device Home Assistant can't see (one-way RF, such as Bond), it says it sent the command,
and answers a question with what Home Assistant last set.

Setup:

1. In Assistant's Context screen, under HOME ASSISTANT · KEY, tap **Make a key and copy it**.
2. In Home Assistant, create the labels `assistant` and `assistant_read` and put them on
   entities. Give every labelled device a room (area), and no two in one room the same name.
3. Find your phone's user id: Developer tools → Events, listen to `mobile_app.share`, then
   share any text to the Home Assistant app; it is `context.user_id`.
4. Create an automation, switch it to YAML, and paste the one below. Replace the key, the user
   id and `notify.mobile_app_YOUR_PHONE`, and set `lowest` and `highest` to the thermostat
   limits you want. A debug build of Assistant is `io.github.ijazshare.assistant.debug`.
5. Tell Assistant your devices: in Developer tools → Template, paste the template below and
   paste what it prints into the Context screen's "Home Assistant devices". Do it again after
   you rename, move or relabel something.
6. In the Home Assistant app, set Persistent connection to "Always" (or "On home network
   only") so replies don't go through Google's push service.

```jinja
{%- set ns = namespace(rows=[]) -%}
{%- for e in (label_entities('assistant') + label_entities('assistant_read')) | unique | sort -%}
  {%- set ns.rows = ns.rows + [{'area': area_name(e) or 'No area', 'name': state_attr(e, 'friendly_name') or e, 'id': e, 'read': e not in label_entities('assistant')}] -%}
{%- endfor -%}
{%- for area in ns.rows | groupby('area') %}
## {{ area.grouper }}
{%- for r in area.list %}
- {{ r.name }} ({{ r.id }}){{ ' read only' if r.read else '' }}
{%- endfor %}
{%- endfor %}
```

It needs Home Assistant 2025.7 or newer. Optional: leave `mobile_app.share` out of the
recorder (`recorder: exclude: event_types:`), so the key isn't stored with every command.

```yaml
alias: Assistant commands
description: >-
  Commands from Assistant on the phone, through the Home Assistant app. Runs only for
  Assistant's key and this phone's user. Assistant sends entity ids and this automation only
  checks them against the labels; it never matches names. It acts only on entities labelled
  "assistant": it switches lights, switches and fans on or off, runs scripts, locks locks,
  closes covers, and sets a thermostat's mode (heat, cool or off) and target temperature
  within the limits in the variables below. It tells the state of anything labelled
  "assistant" or "assistant_read". Nothing is ever unlocked or opened: there is no command for
  either. Anything else is refused.
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
  # The owner's limits for a set-temperature command, in Home Assistant's own unit
  # (for Celsius use about 16 and 29). The thermostat's own min_temp and max_temp apply too.
  lowest: 60
  highest: 85
  cmd: "{{ trigger.event.data.text | default('') | string | from_json(default={}) }}"
  # Home Assistant strips the spaces off a rendered result, so a padded "do" (" lock") must be
  # turned away here, or it would come out as "lock".
  want: "{{ cmd.do if cmd is mapping and cmd.do is string and cmd.do == cmd.do | trim else '' }}"
  # The entity ids: a list of 1 to 20 well-formed ids, otherwise an empty list.
  ids: >-
    {{ cmd.entity
       if cmd is mapping and cmd.entity is list and 1 <= cmd.entity | count <= 20
          and cmd.entity | select('string') | select('match', '^[a-z_]+[.][a-z0-9_]+$') | list | count
              == cmd.entity | count
       else [] }}
  reply_id: "{{ (cmd.id if cmd is mapping and cmd.id is string else '') | urlencode }}"
  ok: "{{ reply_id | regex_match('^i[0-9a-f]{16}$') and ids | count > 0 }}"
  # Every id carries "assistant", so all of them may be controlled.
  allowed: "{{ ok and ids | reject('in', label_entities('assistant')) | list | count == 0 }}"
  # The one id carries "assistant" or "assistant_read", so it may be read.
  readable: >-
    {{ ok and ids | count == 1
       and ids[0] in (label_entities('assistant') + label_entities('assistant_read')) }}
  # The domain of the one id, when there is exactly one and it may be controlled.
  kind: "{{ ids[0].split('.')[0] if allowed and ids | count == 1 else '' }}"
  # Every id is a light, switch or fan, and may be controlled.
  switchable: >-
    {{ allowed and ids | map('regex_replace', '[.].*$', '')
       | reject('in', ['light', 'switch', 'fan']) | list | count == 0 }}
actions:
  - choose:
      - alias: Tell the state
        conditions: "{{ want == 'state' and readable }}"
        sequence:
          - variables:
              extras: >-
                {%- set e = ids[0] -%}
                {%- set ex = ['id:' ~ reply_id ~ ':String.urlencoded', 'status:done:String',
                    'state:' ~ (state_translated(e) | string | urlencode) ~ ':String.urlencoded'] -%}
                {%- set u = state_attr(e, 'unit_of_measurement') -%}
                {%- set t = state_attr(e, 'current_temperature') -%}
                {%- set g = state_attr(e, 'temperature') if e.startswith('climate.') else none -%}
                {%- if u is not none -%}{%- set ex = ex + ['unit:' ~ (u | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {%- if t is not none -%}{%- set ex = ex + ['temperature:' ~ (t | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {%- if g is not none -%}{%- set ex = ex + ['target:' ~ (g | string | urlencode) ~ ':String.urlencoded'] -%}{%- endif -%}
                {%- if state_attr(e, 'assumed_state') == true -%}{%- set ex = ex + ['assumed:true:String'] -%}{%- endif -%}
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
      - alias: Switch them
        conditions: "{{ want in ['turn_on', 'turn_off'] and switchable }}"
        sequence:
          - if: "{{ want == 'turn_on' }}"
            then:
              - action: homeassistant.turn_on
                continue_on_error: true
                target:
                  entity_id: "{{ ids }}"
            else:
              - action: homeassistant.turn_off
                continue_on_error: true
                target:
                  entity_id: "{{ ids }}"
          - wait_template: >-
              {{ ids | select('is_state', 'on' if want == 'turn_on' else 'off') | list | count
                 == ids | count }}
            timeout: 5
            continue_on_timeout: true
          - variables:
              extras: >-
                {%- set ex = ['id:' ~ reply_id ~ ':String.urlencoded',
                    'status:' ~ ('done' if wait.completed else 'unsure') ~ ':String'] -%}
                {%- if ids | select('is_state_attr', 'assumed_state', true) | list | count > 0 -%}
                  {%- set ex = ex + ['assumed:true:String'] -%}
                {%- endif -%}
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
      - alias: Run it
        conditions: "{{ want == 'run' and kind == 'script' }}"
        sequence:
          # Starts the script and goes on, so a long one never holds up the next command.
          - action: script.turn_on
            target:
              entity_id: "{{ ids[0] }}"
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "id:{{ reply_id }}:String.urlencoded,status:done:String"
                ttl: 10000
                priority: high
      - alias: Lock it
        conditions: "{{ want == 'lock' and kind == 'lock' }}"
        sequence:
          - action: lock.lock
            continue_on_error: true
            target:
              entity_id: "{{ ids[0] }}"
          - wait_template: "{{ is_state(ids[0], 'locked') }}"
            timeout: 10
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
      - alias: Close it
        conditions: "{{ want == 'close' and kind == 'cover' }}"
        sequence:
          - action: cover.close_cover
            continue_on_error: true
            target:
              entity_id: "{{ ids[0] }}"
          - wait_template: "{{ states(ids[0]) in ['closing', 'closed'] }}"
            timeout: 5
            continue_on_timeout: true
          - variables:
              extras: >-
                {{ ['id:' ~ reply_id ~ ':String.urlencoded',
                    'status:' ~ ('done' if wait.completed else 'unsure') ~ ':String',
                    'state:' ~ (state_translated(ids[0]) | string | urlencode) ~ ':String.urlencoded'] | join(',') }}
          - action: "{{ phone }}"
            data:
              message: command_broadcast_intent
              data:
                intent_package_name: io.github.ijazshare.assistant
                intent_action: io.github.ijazshare.assistant.HA_REPLY
                intent_extras: "{{ extras }}"
                ttl: 10000
                priority: high
      - alias: Set the mode
        conditions: >-
          {{ want == 'hvac' and kind == 'climate'
             and cmd.value is string and cmd.value in ['heat', 'cool', 'off']
             and cmd.value in (state_attr(ids[0], 'hvac_modes') or []) }}
        sequence:
          - action: climate.set_hvac_mode
            continue_on_error: true
            target:
              entity_id: "{{ ids[0] }}"
            data:
              hvac_mode: "{{ cmd.value }}"
          - wait_template: "{{ is_state(ids[0], cmd.value) }}"
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
      - alias: Set the temperature
        conditions: >-
          {{ want == 'temperature' and kind == 'climate'
             and cmd.value is string and cmd.value == cmd.value | trim
             and cmd.value | regex_match('^[+-]?[0-9]{1,3}([.][0-9])?$')
             and ('mode' not in cmd
                  or (cmd.mode is string and cmd.mode in ['heat', 'cool']
                      and cmd.mode in (state_attr(ids[0], 'hvac_modes') or []))) }}
        sequence:
          # A signed value ("+2", "-2") moves the current target; a plain one ("72") is the target.
          # The value is always read from cmd, never from a variable: a variable holding "+2"
          # would be turned into the number 2 and the sign would be lost.
          - variables:
              thermostat: "{{ ids[0] }}"
              set_mode: "{{ cmd.mode if 'mode' in cmd else '' }}"
              eff_mode: "{{ set_mode if set_mode != '' else states(thermostat) }}"
              relative: "{{ cmd.value[0] in ['+', '-'] }}"
              current: "{{ state_attr(thermostat, 'temperature') | float(none) }}"
              target: >-
                {{ none if relative and current is none
                   else ((current + cmd.value | float) if relative else cmd.value | float) | round(1) }}
              low: >-
                {%- set m = state_attr(thermostat, 'min_temp') | float(none) -%}
                {{ [lowest, m] | max if m is not none else lowest }}
              high: >-
                {%- set m = state_attr(thermostat, 'max_temp') | float(none) -%}
                {{ [highest, m] | min if m is not none else highest }}
          - choose:
              - alias: Not heating or cooling, or no current target to move
                conditions: "{{ eff_mode not in ['heat', 'cool'] or target is none }}"
                sequence:
                  - action: "{{ phone }}"
                    data:
                      message: command_broadcast_intent
                      data:
                        intent_package_name: io.github.ijazshare.assistant
                        intent_action: io.github.ijazshare.assistant.HA_REPLY
                        intent_extras: "id:{{ reply_id }}:String.urlencoded,status:mode:String"
                        ttl: 10000
                        priority: high
              - alias: Outside the limits
                conditions: "{{ target < low or target > high }}"
                sequence:
                  - variables:
                      extras: >-
                        {%- set lo = low | int if low == low | int else low -%}
                        {%- set hi = high | int if high == high | int else high -%}
                        {{ ['id:' ~ reply_id ~ ':String.urlencoded', 'status:range:String',
                            'low:' ~ lo ~ ':String.urlencoded', 'high:' ~ hi ~ ':String.urlencoded'] | join(',') }}
                  - action: "{{ phone }}"
                    data:
                      message: command_broadcast_intent
                      data:
                        intent_package_name: io.github.ijazshare.assistant
                        intent_action: io.github.ijazshare.assistant.HA_REPLY
                        intent_extras: "{{ extras }}"
                        ttl: 10000
                        priority: high
            default:
              - action: climate.set_temperature
                continue_on_error: true
                target:
                  entity_id: "{{ thermostat }}"
                data: "{{ {'temperature': target, 'hvac_mode': set_mode} if set_mode != '' else {'temperature': target} }}"
              - wait_template: >-
                  {{ state_attr(thermostat, 'temperature') | float(none) == target
                     and (set_mode == '' or is_state(thermostat, set_mode)) }}
                timeout: 5
                continue_on_timeout: true
              - variables:
                  extras: >-
                    {%- set tt = target | int if target == target | int else target -%}
                    {{ ['id:' ~ reply_id ~ ':String.urlencoded',
                        'status:' ~ ('done' if wait.completed else 'unsure') ~ ':String',
                        'target:' ~ tt ~ ':String.urlencoded',
                        'state:' ~ (eff_mode | urlencode) ~ ':String.urlencoded'] | join(',') }}
              - action: "{{ phone }}"
                data:
                  message: command_broadcast_intent
                  data:
                    intent_package_name: io.github.ijazshare.assistant
                    intent_action: io.github.ijazshare.assistant.HA_REPLY
                    intent_extras: "{{ extras }}"
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
