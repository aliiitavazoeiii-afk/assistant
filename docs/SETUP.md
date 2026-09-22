# Setup

## 1. OpenAI API

Create an API project/key in the OpenAI Platform. ChatGPT subscription billing and API billing are separate.

Copy:

```bash
cd server
cp .env.example .env
```

Edit `.env`:

```env
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-5.6
PORT=8787
HOST=0.0.0.0
ASSISTANT_APP_TOKEN=change-this-to-a-long-random-secret
```

If your OpenAI project exposes another Responses API model, set `OPENAI_MODEL` accordingly.

Start the backend:

```bash
node server.mjs
```

Health check:

```bash
curl http://127.0.0.1:8787/health
```

For a real phone, the backend must be reachable from that phone over LAN/VPN/HTTPS. Do not expose the server publicly without authentication and TLS.

## 2. Android build

Open the repository in Android Studio and build the `app` module. The project targets Android 15 APIs (`compileSdk 35`) with `minSdk 29`.

For an emulator, the default backend URL is `http://10.0.2.2:8787`. For a physical phone, set **Server URL** to your reachable backend URL.

In the app, set **App connection token** to the exact same `ASSISTANT_APP_TOKEN` value from the backend. This is separate from your OpenAI API key. Never enter the OpenAI key into the Android app.

## 3. Permissions

1. Tap **دادن Permissionهای پایه** for microphone, camera, contacts, call, SMS, location, notification, and media access.
2. Cross-app UI automation is an optional adapter and is not enabled in this connector-pushed build.
3. Tap **فعال‌کردن Notification Access** if you want the assistant to read recent notifications.
4. Tap **اجازه Exact Alarm** and grant exact-alarm access.
5. Optionally enable **Wake Word** after microphone access is granted.

## 4. First tests

- `این ایده رو توی قسمت ideas ذخیره کن: برای دارما صفحه پیشنهاد روزانه بسازم.`
- `ایده‌های من رو بخون.`
- `ده دقیقه دیگه یادم بنداز به انبار زنگ بزنم.`
- `فردا ساعت شش صبح با آهنگ Shape of My Heart بیدارم کن.`
- `فردا ساعت شش صبح سخت‌گیرانه بیدارم کن و تا عکس تازه نگرفتم خاموش نشه.`

Then test contacts/SMS and notification access.

## 5. Cross-app UI automation

The architecture reserves a separate adapter for cross-app UI automation, but the executable Android `AccessibilityService` controller is not included in this connector-pushed build. The current build can still open installed apps by name. Keep payment/authentication entry manual when that adapter is added.

## 6. Private deployment recommendation

The backend already supports an app-specific shared secret through `ASSISTANT_APP_TOKEN` / `X-Assistant-Token`. Keep it long and random, and put the service behind HTTPS or a private VPN. The current server is a single-user private backend.

## 7. What is not configured yet

VPN provisioning depends on the API/contract of your VPN panel or backend. The phone-side agent and tool loop are ready, but a `create_vpn_config`/`send_config_file` adapter needs the exact service endpoint or panel workflow. Keep those credentials server-side.
