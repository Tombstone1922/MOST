# MOST — Android VPN client

Версия **0.4.1**, приложение `net.h1cloud.most`. Основано на [v2rayNG](https://github.com/2dust/v2rayNG), лицензия GPL-3.0.

- VLESS, Reality и подписки с HWID.
- IP и страна фактического выхода через VPN.
- Автовосстановление и выбранный запасной сервер.
- Выбор установленных приложений, работающих без VPN.
- Обновления через GitHub Releases с проверкой файла и подписи.

## Файлы проекта

- `V2rayNG/` — Android-приложение (Kotlin, Compose, Gradle).
- `AndroidLibXrayLite/`, `hev-socks5-tunnel/` — исходники нативных компонентов.
- [Что изменилось](README_MOST_RU.md), [сведения о сборке](BUILD_INFO_MOST.json), [проведённые проверки](VALIDATION_MOST.md).
- [Подпись обновлений](SIGNING_MOST.md). Закрытого ключа в репозитории нет.
- [Исходный README v2rayNG](README_UPSTREAM.md).

## Сборка

JDK 21 и Android SDK, указанные в `.github/workflows/build.yml`. Проект импортирован с включёнными исходниками нативных компонентов. Готовые AAR, SO, GeoIP/GeoSite, локальные настройки и результаты сборки исключены из Git.

1. Подготовить нативные библиотеки по шагу **Native libraries** в `.github/workflows/build.yml` (NDK, `compile-hevtun.sh`, загрузка закреплённого `libv2ray.aar` с проверкой SHA-256).
2. Из каталога `V2rayNG` выполнить `./gradlew :app:assemblePlaystoreDebug` (Windows: `gradlew.bat`).
3. Для обновления ранее установленного MOST подписать APK постоянным ключом MOST, который хранится отдельно.

OTA использует `Tombstone1922/MOST`. Для работы обновлений требуется релиз с APK и `most-update.json`; одна загрузка исходников не публикует релиз.

Сборка 0.4.0 ранее собрана и подпись проверена. Проверки на устройстве не проводились. При переносе в GitHub код приложения не менялся и повторная сборка не запускалась.

## Автоматические OTA-релизы

GitHub Actions запускается при изменении Android-проекта в `main` или вручную: **Actions → MOST Android and OTA → Run workflow**. Выполняются сборка APK и один тестовый класс для названий подписок. Эмулятор и широкая тестовая серия не запускаются.

Первый запуск публикует уже подписанный APK 0.4.0 и его исходники из коммита `2b587ebdf3dbb396a175125e8cab8fb998dbf272`. Бинарные части в `release-inputs/0.4.0` служат только для этой однократной публикации; перед выпуском проверяется SHA-256 целого APK. Пользователи скачивают один обычный APK из Releases.

### Однократная настройка постоянной подписи

Для новых APK нужны **Actions Secrets** `MOST_SIGNING_KEY_BASE64` и `MOST_SIGNING_PASSWORD`. Это тот же постоянный ключ MOST; новый ключ создавать нельзя, иначе Android не примет обновление поверх установленного приложения.

На Windows установить GitHub CLI (`winget install --id GitHub.cli --exact`), скачать исходники проекта и исходный приватный архив `MOST-Android-Signing-Key.zip`. Из папки проекта выполнить:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\ci\Setup-OTA.ps1 -SigningKeyZip "$env:USERPROFILE\Downloads\MOST-Android-Signing-Key.zip"
```

Скрипт предложит вход в GitHub, зашифрует ключ и пароль через `gh secret set`, сохранит их в Secrets и запустит выпуск. Содержимое ключа и пароль в консоль и Git не выводятся. Без Secrets CI сохранит только отладочную сборку в Artifacts; она не публикуется в OTA.

### Следующие версии

Увеличить `versionName` и `versionCode` в `V2rayNG/app/build.gradle.kts`, добавить `ci/release-notes-X.Y.Z.md`, отправить изменение в `main`. CI подпишет универсальный APK, сверит постоянный сертификат, сформирует `most-update.json` по фактическому APK и опубликует полный релиз. Готовые публичные релизы не заменяются; код версии должен расти. Исходники каждого релиза соответствуют точному коммиту сборки.

Стандартный адрес проверки обновлений:
`https://github.com/Tombstone1922/MOST/releases/latest/download/most-update.json`
