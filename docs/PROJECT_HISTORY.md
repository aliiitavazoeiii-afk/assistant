# Biyok Project History / Continuation

## Authority rule

Before future changes, re-fetch current `main` HEAD and relevant files. GitHub state is authoritative. Do not infer live server deployment from this file.

## Old Assistant generations

The repository originally contained a broad Persian Android assistant plus a Node/OpenAI control plane with phone tools, memory, monitoring nodes and integrations. That architecture was intentionally abandoned for the Android product because the user wanted a much smaller reminder-only workflow.

## Biyok v1 — local reminder app

Biyok was rebuilt around one job: hands-free reminder capture.

- Android package: `com.ali.biyok`.
- Local SQLite reminder store and AlarmManager notifications.
- User enrolls the word «بیوک» three times; only extracted feature templates are stored, not raw enrollment audio.
- Foreground microphone service performs adaptive local VAD and feature matching.
- v1 used Android `SpeechRecognizer` after wake.
- Deterministic Persian parser handled common relative/date/time phrases.
- v1 passed Android CI and real-device setup/testing.

Real-device feedback on v1:

- wake word required speaking too close to the phone;
- Android `SpeechRecognizer` could stop in the middle of a natural utterance/pause;
- user wanted future behavior/tuning to be server-driven instead of rebuilding/reinstalling for every adjustment.

## Biyok v2 — hybrid server brain

Development branch: `biyok-server-brain-v2`, based on main commit `58b58e851b7c5b3346d66c8603e95828c384506f`.

Design decisions:

- Wake listening remains local so ambient audio is not streamed continuously.
- Local wake VAD is intentionally more permissive so speech can be captured from farther away.
- Strong local wake matches are accepted immediately.
- Borderline wake candidates may be sent to the VPS for OpenAI transcription verification, reducing false positives while allowing looser local thresholds.
- Android `SpeechRecognizer` is removed from the command path.
- After the beep, Android records PCM directly until a configurable continuous-silence window is reached. This gives Biyok control over endpointing instead of Android deciding that speech ended.
- Command audio is sent to the VPS. The server uses `gpt-transcribe` for transcription and a configurable text model with Responses API Structured Outputs to resolve intent/time.
- The server returns only structured actions (`create_reminder`, `list_reminders`, `unknown`); reminders still live in the Android SQLite database.
- OpenAI API key remains only on the VPS.
- Authenticated Biyok requests reuse `ASSISTANT_APP_TOKEN` via `X-Assistant-Token`.
- Android defaults to `https://assistant.filmjadiid.ir`; server URL/token are entered once in the UI and retained locally.
- Remote tuning is read from `server/config/biyok.json`. Android refreshes config while the service is alive, so wake/VAD changes do not require a new APK.

### v2 endpoints

- `GET /health`
- `GET /v1/biyok/config` (public non-secret tuning only)
- `GET /v1/biyok/ping` (authenticated setup test)
- `POST /v1/biyok/wake-check` (authenticated bounded wake audio)
- `POST /v1/biyok/command` (authenticated bounded command audio)

### Remote-tunable settings

`server/config/biyok.json` controls:

- local strong wake score;
- maximum score eligible for server verification;
- wake VAD RMS floor/noise multipliers;
- wake silence and candidate duration;
- command VAD RMS floor/noise multipliers;
- command start timeout, end-of-speech silence, max duration and pre-roll.

### Privacy boundary

- No continuous cloud microphone streaming.
- Reminder database stays on-device.
- Wake enrollment raw audio is not persisted.
- Only borderline wake candidates and post-beep command audio can be uploaded to the server.

## Deployment rule

The currently installed v1 debug APK may have a CI debug signature that is not guaranteed to remain stable across future builds. Moving to a stable release signing key is still recommended before long-term native APK update workflows. Most v2 behavior changes should instead be done through the VPS config/backend so no Android update is required.
