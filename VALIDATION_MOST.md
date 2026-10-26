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


## 0.4.2 — страны и автоматический маршрут

- Локально: `git diff --check`; новые XML-файлы разобраны, наборы ключей совпадают во всех поставляемых локалях.
- GitHub Actions: `:app:testPlaystoreDebugUnitTest --tests com.v2ray.ang.ui.main.MostPresentationTest --tests com.v2ray.ang.core.MostRoutePolicyTest :app:assemblePlaystoreDebug`; результат фиксируется запуском Actions. Проверяется выбор страны/подписки, исчерпание кандидатов, приоритет ручной команды, запоминание допустимого рабочего профиля и сопоставление страны выхода.
- Not run: эмулятор/физическое устройство, переключение и сбои при работе в VPN/proxy-only/root, нормальная/повторная остановка и остановка во время переключения, TalkBack/клавиатура/D-pad/IME, смена ориентации и восстановление экрана, реальная установка OTA. Пользователь попросил не запускать повторную широкую серию проверок; нативные зависимости и ABI не менялись.

## 0.4.3 — прямые подключения и фоновые уведомления

- Локально: проверка XML/наборов строк и `git diff --check`.
- Одна сборка в Actions: `:app:testPlaystoreDebugUnitTest --tests com.v2ray.ang.ui.main.MostPresentationTest --tests com.v2ray.ang.core.MostRoutePolicyTest --tests com.v2ray.ang.handler.MostUpdateNoticeTest :app:assemblePlaystoreDebug`. Проверяется разделение direct/bridge, исчерпание только своего типа, подсчёт стран, чтение прежнего JSON без directOnly, правило однократного уведомления и сравнение фактических кодов версии. Результат — в запуске Actions для этого коммита.
- Not run: устройство/эмулятор, переключение чекбокса и выбор страны при работающем VPN; повторное открытие/восстановление экрана; TalkBack/клавиатура/D-pad; WorkManager после закрытия приложения/перезагрузки/Doze; разрешение и запрет уведомлений на API 24/26/33+; отмена задания, реальный переход из уведомления и OTA-установка. По просьбе пользователя широкая серия и эмулятор не запускаются.
- Новых нативных зависимостей, серверов уведомлений и изменений VPN-сервисов нет. Фоновые проверки не запускают VPN и не загружают APK.
