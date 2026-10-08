# Полётные логи — нативное Android-приложение

Kotlin + Jetpack Compose. Реализация дизайна из `../design/` (стиль Industry:
Barlow / Barlow Condensed, квадратные углы, волосяные границы, «+»-метки).

## Что работает

- **Разбор логов на устройстве** — без сети и сторонних сервисов:
  - ArduPilot **DataFlash `.bin`** — FMT-описания, все типы полей, восстановление после повреждённых участков;
  - **MAVLink `.tlog`** (v1 и v2, проверка CRC) — HEARTBEAT, GLOBAL_POSITION_INT, GPS_RAW_INT,
    ATTITUDE, VFR_HUD, SYS_STATUS, BATTERY_STATUS, VIBRATION, STATUSTEXT, PARAM_VALUE.
- **Библиотека полётов** — импорт файла через системный выбор (память, Google Диск, Telegram…),
  фильтр по источнику, удаление (долгое нажатие), налёт, статус диагностики.
- **Расшифровка** — реальные этапы разбора с прогрессом и скоростью.
- **Полёт**:
  - *Сводка* — диагностика (вибрации > 30 м/с², клиппинг IMU, деградация/потеря GPS,
    напряжение ниже `BATT_LOW_VOLT`, failsafe и ошибки подсистем `ERR`), метрики, сведения о борте;
    тап по проблеме — переход к графику в нужный момент;
  - *Трек* — раскраска по скорости / высоте / батарее, точка дома, предупреждения, текущее положение;
  - *Графики* — каналы с общим курсором, пороги, перемотка тапом и перетаскиванием;
  - *События* — MODE / EV / ERR / MSG с фильтром;
  - *PARM* — поиск, отличия от значений по умолчанию (поле `Default` в PARM);
  - *Плеер* — 10× / 30× / 60×, метки событий на шкале.
- **Сравнение двух полётов** — наложение каналов от момента взведения, таблица метрик с дельтой, различия параметров.
- **Экспорт** — PDF-отчёт (разделы на выбор), CSV, KML, GPX, `.param`; «Поделиться» и сохранение в «Загрузки».
- Светлая / тёмная тема (по системе или кнопкой), метрические / имперские единицы.
- **Демо-полёты** — два синтетических лога ArduCopter в `app/src/main/assets/demo/`.

## Ещё не сделано

- PX4 ULog, DJI FlightRecord (нужен ключ DJI API), Blackbox — парсеры в планах.
- USB-OTG / MAVLink FTP загрузка с контроллера.
- Подложка карты (MapLibre с офлайн-тайлами) — пока трек на сетке.

## Структура

```
core/   чистый Kotlin (JVM): парсеры, модель полёта, диагностика, сравнение, экспорт + тесты
app/    Android: Compose UI, хранилище логов, PDF, экспорт
tools/make_sample_logs.py  генератор демо- и тестовых логов (сверка с pymavlink)
```

`core` не зависит от Android и тестируется на обычной JVM: тесты сверяют результат
разбора с эталонными значениями, посчитанными `pymavlink` на тех же файлах.

## Сборка

APK собирает GitHub Actions (workflow «Build APK», job «Native app»):
**Actions → Build APK → Artifacts → flight-log-reader-debug**.

Локально (JDK 17, Android SDK):

```bash
./gradlew :core:test            # тесты ядра — Android SDK не нужен
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Модуль `:app` подключается, только если найден Android SDK (`ANDROID_HOME` или `local.properties`).

Перегенерировать демо- и тестовые логи:

```bash
pip install pymavlink
python3 tools/make_sample_logs.py
```

Шрифты Barlow — SIL Open Font License (`app/src/main/assets/OFL-Barlow.txt`); иконки — Lucide (ISC).
