# Assistant

Personal Android AI agent with Persian-first voice interaction, local device actions, alarms/reminders, notes, SMS/contacts, maps, notifications, and a private OpenAI-backed tool loop.

## Repository layout

- `app/` — Android app (Kotlin + Jetpack Compose)
- `server/` — lightweight Node.js backend that calls the OpenAI Responses API
- `docs/` — architecture, setup, permissions, and security notes

## Core capabilities

- Persian speech input and spoken responses
- Structured local memory for ideas/notes
- Exact alarms and spoken reminders
- Strict alarm mode with a fresh-selfie challenge
- Optional local music lookup for alarm sounds
- Call contacts and send/read SMS (with user-granted permissions)
- Read recent notifications after Notification Access is enabled
- Open Google Maps/navigation and arbitrary installed apps
- Iterative tool-calling loop for multi-step device actions
- Optional experimental hotword foreground service
- OpenAI key stays on the server; it is never embedded in the APK

Cross-app UI automation is kept as a separate optional adapter. The executable Android `AccessibilityService` controller is not included in this connector-pushed build.

## Quick start

1. Read `docs/SETUP.md`.
2. Create an OpenAI API key and configure `server/.env` from `server/.env.example`.
3. Run the server with Node 22+.
4. Open the repository in Android Studio, build/install the `app` module, and set the server URL in the app.
5. Grant only the permissions/features you want.

## Current implementation notes

This repository is designed for a privately-installed personal assistant. Some SMS/call capabilities are restricted by Google Play policy even when Android itself can grant them to a sideloaded app. OEM battery/background restrictions can affect hotword and alarm reliability, so device-specific testing is required.

The app uses Android speech recognition + local TTS for the first production path. The server is separated cleanly so OpenAI Realtime voice can replace the audio layer later without changing the device tool layer.
