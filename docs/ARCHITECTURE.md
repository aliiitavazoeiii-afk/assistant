# Architecture

## Goal

A Persian-first personal agent that can understand natural language, keep local personal notes, schedule reliable alarms/reminders, and perform user-authorized actions on Android.

## Components

### Android app

- `AssistantViewModel` — conversation/tool loop coordinator
- `AssistantApi` — HTTPS client to the private backend
- `ToolExecutor` — maps model tool calls to Android actions
- `AssistantDb` — local SQLite storage for notes and schedules
- `AlarmScheduler` — exact alarms backed by `AlarmManager`
- `AlarmSoundService` / `AlarmActivity` — wake-up UI/audio and strict challenge
- `ReminderService` — spoken reminders
- `ContactsSmsTools` — contacts, phone calls, SMS
- `DeviceTools` — Maps, installed apps, location
- `AssistantNotificationListener` — bounded local cache of recent notifications
- `HotwordService` — experimental continuously restarted Android speech recognizer
- Cross-app UI automation is an optional adapter boundary; its executable AccessibilityService controller is not included in this connector-pushed build.

### Backend

`server/server.mjs` keeps the OpenAI API key off the phone. The phone sends a user request and device context. The server calls the Responses API with function tools and returns tool calls to Android. Android executes them locally and returns tool outputs. The server continues the same response using `previous_response_id` until the model produces a final reply.

## Security boundary

The OpenAI API key never ships in the APK. Payment and authentication entry should remain manual. The agent prompt explicitly excludes payment/authentication secrets.

## Privacy model

Notes remain local until the user asks for them. SMS and notification access follow the same local-query model: only requested results are returned to the model.

## Reliability model

Alarms/reminders are scheduled locally and do not depend on OpenAI being reachable at trigger time.

## Known platform constraints

- A normal Android app cannot make itself literally impossible to force-stop, uninstall, or defeat by powering off the phone.
- Exact alarms require special access on modern Android versions.
- Full-screen alarm behavior and background launching vary by OEM configuration.
- Cross-app UI control is intentionally isolated as an optional future adapter and is not part of this connector-pushed build.
- Continuous hotword listening through `SpeechRecognizer` is experimental and OEM-dependent.
- Google Play has policy restrictions around SMS/call permissions. This repository is intended first for private sideloading.
