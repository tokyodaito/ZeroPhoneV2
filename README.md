# ZeroPhoneV2

Личный «цифровой минимализм»-контролер: приложение становится **владельцем устройства (Device Owner)** и enforce'ит три вещи, которые пользователь не может снять с самого телефона:

1. **Принудительный grayscale** всего экрана (через secure-настройки далтонизатора — тот же механизм, что у системного Bedtime mode; переприменяется при попытке отключить).
2. **Гейт для отвлекающих приложений**: выбранные приложения приостановлены на уровне ОС (`setPackagesSuspended`) — запустить их невозможно ниоткуда. Вход — только через **60-секундную проверку внимания** (фронтальная камера + ML Kit: лицо с открытыми глазами; любое отвлечение останавливает таймер, перезапуск — по кнопке «Попробовать снова», с нуля). После прохождения — окно на 5 минут, по истечении приложение закрывается системой и проверка появляется снова.
3. **Принудительный DNS** (Private DNS / DNS-over-TLS, по умолчанию NextDNS-хост из настроек) + блокировка страницы Private DNS в системных Настройках.

Полный откат — **двухступенчатый**:
- **до блокировки настроек** — кнопка «Полная деактивация» в приложении (удержание 3 с + диалог);
- **после «Сохранить и заблокировать настройки»** — выключатели с телефона исчезают (grayscale, DNS, деактивация); единственный выход — команда с компьютера:

```bash
adb shell am broadcast --include-stopped-packages -a com.numenlabs.zerophonev2.action.ADB_DEACTIVATE -n com.numenlabs.zerophonev2/.enforcement.AdbDeactivateReceiver
```

Приёмник экспортирован, но защищён правом `WRITE_SECURE_SETTINGS` — доставить может только adb shell, система или само приложение; сторонние приложения отправить не могут физически. Команда возвращает цвет (сохранённые оригинальные настройки далтонизатора), DNS → «Автоматически», снимает приостановку всех приложений, снимает ограничения и удаляет права владельца устройства.

## Установка (один раз, с компьютера)

Телефон должен быть **чистым/сброшенным, без аккаунтов** (иначе Android не позволит назначить device owner):

```bash
# 1. Собрать и установить
./gradlew installDebug

# 2. Сделать приложение владельцем устройства
adb shell dpm set-device-owner com.numenlabs.zerophonev2/.admin.ZeroDeviceAdminReceiver

# 3. Дать право писать secure-настройки (обязательно для grayscale)
adb shell pm grant com.numenlabs.zerophonev2 android.permission.WRITE_SECURE_SETTINGS

# 4. (Опционально) убрать из Doze — будильники окончания окна будут точнее
adb shell dumpsys deviceidle whitelist +com.numenlabs.zerophonev2
```

Команда №3 нужно **повторять после каждой переустановки** приложения (грант слетает при uninstall). Приложение само показывает статус и копируемые команды на экране «Установка».

Дальше в приложении: «Настройки» → выбрать отвлекающие приложения; включить grayscale; указать DoT-хост (например `12345.dns.nextdns.io`) и нажать «Проверить и применить». При первом входе в проверку внимания приложение запросит доступ к камере.

## Как это работает внутри

- **Суспензия** — `DevicePolicyManager.setPackagesSuspended`: suspended-приложение не запускается ни из лончера, ни из уведомлений, ни из «недавних»; при окончании 5-минутного окна система сама закрывает его (RecentTasks `suspended-package`). Никогда не суспендим: себя, `com.android.*`, Settings/SystemUI/телефон, активную клавиатуру, текущий лончер.
- **Grayscale** — запись `accessibility_display_daltonizer_enabled=1` + `accessibility_display_daltonizer=0` (нужно `WRITE_SECURE_SETTINGS`). Переживает ребут; живой foreground-сервис с `ContentObserver` возвращает режим <1 с после попытки выключения; WorkManager-сторож и boot-ресивер — страховка.
- **DNS** — `DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost` (блокирующий вызов, только не на главном потоке) + `DISALLOW_CONFIG_PRIVATE_DNS`, чтобы страница в Настройках стала серой. Внимание: строгий режим без fallback — если хост недоступен или порт 853 закрыт сетью, DNS на устройстве перестанет работать (откат — из приложения).
- **Будильник окна** — `setExactAndAllowWhileIdle` (+ fallback), дедлайн персистентен; переведённые часы не продлевают окно (clock-skew guard из V1).
- **Гейт** — отдельная Activity: `FLAG_KEEP_SCREEN_ON`, гашение/уход со экрана/шторка (`onWindowFocusChanged`) останавливают таймер; камера через CameraX + bundled ML Kit face detection, всё on-device.

## Чек-лист проверки на устройстве

1. `adb shell dumpsys device_policy | grep -A3 "Device Owner"` — наш компонент.
2. `adb shell dumpsys package <отвлекающий.pkg> | grep -i suspend` → `suspended=true`; тап по иконке в системном лончере → диалог «ZeroPhone: откройте приложение ZeroPhone, чтобы войти».
3. Пройти гейт → приложение открылось; `adb shell dumpsys alarm | grep -B2 -A8 zerophonev2` — exact alarm ~через 5 мин.
4. Сидеть в приложении до истечения → оно закрылось, появился гейт, пакет снова suspended.
5. Отвернуться/шторка/Home в гейте → «таймер остановлен», «Попробовать снова» начинает с нуля.
6. `adb shell settings get secure accessibility_display_daltonizer_enabled` = 1 и `accessibility_display_daltonizer` = 0; переключить QS-плитку «Коррекция цвета» → вернулось <1 с; ребут → всё ещё grayscale.
7. `adb shell am force-stop com.numenlabs.zerophonev2` → сервис вернулся (STICKY) или сторож поднял ≤15 мин (`adb shell dumpsys activity services com.numenlabs.zerophonev2`).
8. DNS применён → Настройки → Private DNS серые; `adb shell settings get global private_dns_mode` = `hostname`.
9. Перевод часов на −2 ч → остаток окна не изменился.
10. «Полная деактивация» → цвет вернулся, DNS opportunistic, `dumpsys device_policy` пуст, приложение удаляется.

## Тесты

```bash
./gradlew testDebugUnitTest
```

Чистое ядро (машина состояний гейта, гистерезис внимания, ledger грантов, suspend-набор, grayscale-политика, clock-skew) покрыто JVM-тестами без Android-зависимостей.
