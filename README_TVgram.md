# TVgram — неофициальный клиент Telegram для Android TV

Форк официального [Telegram for Android](https://github.com/DrKLO/Telegram) (GPL v2+),
доработанный для телевизоров и ТВ-приставок. Не связан с Telegram FZ-LLC.

## Что изменено
- **Курсор на пульте** (`TvRemote.java`): стрелки двигают курсор (держишь — ускоряется),
  **OK** = нажатие, **долгий OK** = долгое нажатие (меню сообщения), **OK + стрелки** = перетаскивание
  (ползунки, перемотка видео). Упёрся курсором в край экрана — список прокручивается.
  **CH+/CH−** или **Page Up/Down** — прокрутка на страницу. **Назад** — как обычно.
- Иконка и баннер в лаунчере Android TV (`LEANBACK_LAUNCHER`), работа без тачскрина.
- Двухпанельный режим (список чатов слева, чат справа) на больших экранах ТВ.
- Своё имя, пакет `net.stemd.tvgram`, своя иконка; ставится рядом с обычным Telegram.
- Отключены: проверка обновлений официального Telegram, Google Play Billing, passkeys.
- Сборка только под ARM (armeabi-v7a + arm64-v8a).

## Сборка (GitHub Actions)
1. Секреты репозитория (Settings → Secrets and variables → Actions):
   `TG_API_ID`, `TG_API_HASH` — с https://my.telegram.org → API development tools;
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` — свой ключ подписи.
2. Actions → **Build TVgram APK** → Run workflow. Через ~1–1.5 ч APK будет в артефактах.
   Тег `v*` (например `v13.2.2-tv1`) — APK сразу публикуется в Releases.

Локально: Android Studio, NDK 27.2.12479018, CMake 3.22.1, SDK 36;
ключи в `gradle.properties` (`TG_API_ID`, `TG_API_HASH`), затем
`./gradlew :TMessagesProj_App:assembleAfatRelease`.
