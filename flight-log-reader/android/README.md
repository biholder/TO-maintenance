# Полётные логи — просмотр логов БВС

Android-приложение для чтения и анализа полётных логов беспилотников. Интерфейс — интерактивный прототип: данные демонстрационные, реальные парсеры форматов ещё не подключены.

## Возможности прототипа

- **Журнал полётов** — фильтр по контроллеру, режим выбора двух полётов для сравнения.
- **Импорт** — файл с телефона или из облака, загрузка с полётного контроллера по USB-OTG (MAVLink FTP).
- **Расшифровка** — этапы разбора для каждого формата, включая расшифровку DJI FlightRecord.
- **Полёт**: сводка и диагностика, трек на карте с раскраской по скорости / высоте / батарее, графики каналов с общим курсором, журнал событий, параметры ArduPilot (PARM) с поиском и отличиями от умолчаний.
- **Воспроизведение** — таймлайн с метками событий, скорость 10× / 30× / 60×.
- **Сравнение полётов** — наложение графиков, таблица метрик с дельтой, различия параметров.
- **Экспорт** — PDF-отчёт, CSV, KML, GPX, .param.
- Светлая тема для работы в поле и тёмная для ночи.

## Поддерживаемые форматы (план)

| Формат | Источник |
| --- | --- |
| `.bin` DataFlash | ArduPilot |
| `.tlog` MAVLink | ArduPilot / Mission Planner / QGC |
| `.ulg` ULog | PX4 |
| `.txt` FlightRecord | DJI Fly / DJI Pilot 2 |
| `.DAT` | DJI (бортовой журнал) |
| `.bbl` / `.TXT` Blackbox | Betaflight / INAV |

## Структура

```
app/src/main/assets/index.html   — интерфейс (автономный HTML, работает офлайн)
app/src/main/java/.../MainActivity.kt — WebView-обёртка
../../.github/workflows/build-apk.yml — сборка APK (в корне репозитория)
```

## Сборка

APK собирается автоматически GitHub Actions (`.github/workflows/build-apk.yml` в корне репозитория) при push в `main`, затрагивающем `flight-log-reader/android/`, или вручную (Run workflow):
**Actions → Build APK → последний запуск → Artifacts → flight-log-reader-debug**.

При push тега вида `v0.1.0` APK прикрепляется к релизу.

Локально из папки `flight-log-reader/android` (нужны JDK 17, Android SDK и Gradle 8.7):

```bash
gradle :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Дальнейшие шаги

- Нативные парсеры: DataFlash / MAVLink (pymavlink-совместимый разбор), ULog, DJI FlightRecord (требует ключ DJI API), Blackbox.
- USB-OTG: USB Host API + MAVLink FTP для скачивания логов с SD-карты контроллера.
- Офлайн-тайлы карты.
