# بیوک (Biyok)

اپ Android شخصی برای ثبت یادآوری با wake word «بیوک». نسخه‌ی v2 یک معماری hybrid دارد: شنود wake روی گوشی می‌ماند، اما تشخیص گفتار و فهم دستور بعد از بیدارشدن روی سرور انجام می‌شود.

## جریان اصلی

1. گوشی روی میز یا قفل است.
2. کاربر می‌گوید «بیوک».
3. wake محلی اگر مطمئن باشد فوراً قبول می‌کند؛ candidateهای ضعیف‌تر می‌توانند با سرور verify شوند.
4. بعد از beep، اپ با `AudioRecord` خودش تا سکوت واقعی صدا را ضبط می‌کند. Android `SpeechRecognizer` دیگر استفاده نمی‌شود.
5. صوت همان فرمان به VPS فرستاده می‌شود.
6. سرور با `gpt-transcribe` متن را استخراج می‌کند و مدل متنی Biyok intent/time را به structured JSON تبدیل می‌کند.
7. reminder در SQLite روی گوشی ذخیره می‌شود و در صورت داشتن زمان با AlarmManager/notification اجرا می‌شود.

## چرا v2

نسخه‌ی local-only دو مشکل عملی داشت: wake از فاصله‌ی بیشتر سخت‌تر فعال می‌شد و Android SpeechRecognizer وسط مکث‌های طبیعی گاهی فرمان را تمام‌شده فرض می‌کرد. v2 پایان گفتار را خودش کنترل می‌کند و پارامترهای VAD/wake از سرور قابل تنظیم‌اند.

## Server-driven configuration

`server/config/biyok.json` بدون build جدید Android قابل تغییر است. موارد مهم:

- آستانه‌ی شروع wake و نسبت آن با نویز محیط
- حد local strong match و server verification
- طول silence برای تمام‌شدن wake candidate
- طول silence فرمان بعد از beep
- حداکثر زمان فرمان و pre-roll

Android هر چند دقیقه config را دوباره می‌گیرد؛ بنابراین tuning روزمره بدون APK جدید انجام می‌شود.

## API endpoints

- `GET /health`
- `GET /v1/biyok/config` — public tuning values، بدون secret
- `GET /v1/biyok/ping` — تست token
- `POST /v1/biyok/wake-check` — transcription کوتاه برای verify wake candidate
- `POST /v1/biyok/command` — transcription + structured reminder intent

endpointهای هزینه‌دار با `X-Assistant-Token` محافظت می‌شوند. OpenAI API key فقط در `server/.env` روی VPS است.

## حریم خصوصی

- reminderها در `biyok.db` روی گوشی باقی می‌مانند.
- templateهای wake فقط feature صوتی هستند و فایل خام آموزش ذخیره نمی‌شود.
- صدای محیط ۲۴ساعته به سرور stream نمی‌شود.
- فقط wake candidate مشکوک و فرمانی که بعد از beep گفته می‌شود ممکن است به سرور ارسال شود.

## Android

- package: `com.ali.biyok`
- minSdk 29 / targetSdk 35
- foreground microphone service برای wake listening
- custom adaptive VAD برای wake و command
- SQLite + AlarmManager برای reminder

## Server

Node.js 22، بدون dependency خارجی. تنظیم‌های اصلی:

```env
OPENAI_API_KEY=...
BIYOK_TEXT_MODEL=gpt-5.6-luna
BIYOK_TRANSCRIPTION_MODEL=gpt-transcribe
ASSISTANT_APP_TOKEN=...
HOST=127.0.0.1
PORT=8787
```

نصب/به‌روزرسانی backend:

```bash
cd /opt/assistant
sudo git pull
sudo bash server/install.sh
```

برای deployment پشت Caddy، فقط پورت localhost 8787 استفاده شود و 8787 مستقیماً روی اینترنت باز نشود.
