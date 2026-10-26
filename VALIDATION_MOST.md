# MOST 0.4.0 validation

Build status is recorded in BUILD_INFO_MOST.json after assembly.

Not run:
- :app:testPlaystoreDebugUnitTest — no extensive test campaign, as requested by user.
- Emulator/physical-device VPN connect, exit-IP observation, two failed probes, backup switch, user-stop/reload race, network handover and all run modes — user requested no emulator runs.
- Android API 24/26/28/33+ installer, unknown-source permission and real OTA upgrade — no device test campaign.
- TalkBack, IME traversal and complete layout/theme matrix — no emulator.
- Lint — not run in this focused build.

Only a successful assembly and signing check are claimed. No runtime or published-OTA validation is claimed.
Native dependencies and dependency versions were preserved. No real subscription was fetched during development.


## 0.4.1 — подписки и OTA CI

- Локально: Python CI-скрипты компилируются; shell-синтаксис и новые XML-ресурсы проверены.
- PASS: GitHub Actions собрал универсальный APK и выполнил `MostPresentationTest`: https://github.com/Tombstone1922/MOST/actions/runs/40318881759 .
- PASS: исходный подписанный 0.4.0 опубликован вместе с исходниками и OTA-манифестом; публичный адрес манифеста возвращает ожидаемые SHA-256, размер и код версии.
- 0.4.1 собран с отладочной подписью; публикация OTA ожидает настройки двух Actions Secrets.
- Not run: локальная Android-сборка (сборка успешно выполнена в CI), эмулятор/устройство, TalkBack/IME/восстановление экрана и реальная установка OTA. По просьбе пользователя повторные широкие прогоны не запускались.
- До настройки Secrets новые APK имеют отладочную подпись и не публикуются как OTA. Первоначальный 0.4.0 уже подписан постоянным ключом.
