# MOST — Android VPN client

Версия **0.4.0**, приложение `net.h1cloud.most`. Основано на [v2rayNG](https://github.com/2dust/v2rayNG), лицензия GPL-3.0.

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
