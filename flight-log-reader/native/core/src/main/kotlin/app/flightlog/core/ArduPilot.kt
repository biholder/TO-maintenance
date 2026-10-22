package app.flightlog.core

/** Справочники ArduPilot: режимы полёта, подсистемы ошибок, события. */
object ArduPilot {
    val COPTER_MODES = mapOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALT_HOLD", 3 to "AUTO", 4 to "GUIDED", 5 to "LOITER",
        6 to "RTL", 7 to "CIRCLE", 9 to "LAND", 11 to "DRIFT", 13 to "SPORT", 14 to "FLIP",
        15 to "AUTOTUNE", 16 to "POSHOLD", 17 to "BRAKE", 18 to "THROW", 19 to "AVOID_ADSB",
        20 to "GUIDED_NOGPS", 21 to "SMART_RTL", 22 to "FLOWHOLD", 23 to "FOLLOW", 24 to "ZIGZAG",
        25 to "SYSTEMID", 26 to "AUTOROTATE", 27 to "AUTO_RTL", 28 to "TURTLE",
    )
    val PLANE_MODES = mapOf(
        0 to "MANUAL", 1 to "CIRCLE", 2 to "STABILIZE", 3 to "TRAINING", 4 to "ACRO", 5 to "FBWA",
        6 to "FBWB", 7 to "CRUISE", 8 to "AUTOTUNE", 10 to "AUTO", 11 to "RTL", 12 to "LOITER",
        13 to "TAKEOFF", 14 to "AVOID_ADSB", 15 to "GUIDED", 17 to "QSTABILIZE", 18 to "QHOVER",
        19 to "QLOITER", 20 to "QLAND", 21 to "QRTL", 22 to "QAUTOTUNE", 23 to "QACRO", 24 to "THERMAL",
        25 to "LOITER_ALT_QLAND",
    )
    val ROVER_MODES = mapOf(
        0 to "MANUAL", 1 to "ACRO", 3 to "STEERING", 4 to "HOLD", 5 to "LOITER", 6 to "FOLLOW",
        7 to "SIMPLE", 8 to "DOCK", 9 to "CIRCLE", 10 to "AUTO", 11 to "RTL", 12 to "SMART_RTL",
        15 to "GUIDED",
    )
    val SUB_MODES = mapOf(
        0 to "STABILIZE", 1 to "ACRO", 2 to "ALT_HOLD", 3 to "AUTO", 4 to "GUIDED", 7 to "CIRCLE",
        9 to "SURFACE", 16 to "POSHOLD", 19 to "MANUAL",
    )

    fun modeName(vehicleType: String, num: Int): String {
        val table = when (vehicleType) {
            "Plane" -> PLANE_MODES
            "Rover" -> ROVER_MODES
            "Sub" -> SUB_MODES
            else -> COPTER_MODES
        }
        return table[num] ?: "MODE $num"
    }

    val ERR_SUBSYS = mapOf(
        1 to "Основной контур", 2 to "Радиоканал", 3 to "Компас", 4 to "Оптический поток",
        5 to "Failsafe: радио", 6 to "Failsafe: батарея", 7 to "Failsafe: GPS", 8 to "Failsafe: GCS",
        9 to "Failsafe: геозона", 10 to "Режим полёта", 11 to "GPS", 12 to "Обнаружение аварии",
        13 to "Flip", 14 to "Autotune", 15 to "Парашют", 16 to "Проверка EKF",
        17 to "Failsafe: EKF/INAV", 18 to "Барометр", 19 to "CPU", 20 to "Failsafe: ADSB",
        21 to "Рельеф", 22 to "Навигация", 23 to "Failsafe: рельеф", 24 to "Смена ядра EKF",
        25 to "Потеря тяги", 26 to "Failsafe: датчики", 27 to "Failsafe: протечка",
        28 to "Ввод пилота", 29 to "Failsafe: вибрации", 30 to "Внутренняя ошибка",
        31 to "Failsafe: счисление пути",
    )

    /** Расшифровка кода ошибки ERR (AP_Logger LogErrorCode) для подсистемы. */
    fun errCodeText(subsys: Int, code: Int, vehicleType: String = ""): String {
        if (code == 0) return when (subsys) {
            16 -> "дисперсия EKF в норме"
            else -> "восстановлено"
        }
        return when (subsys) {
            1 -> if (code == 1) "задержка данных IMU" else "ошибка $code"
            2 -> if (code == 2) "запоздавший кадр радио" else "ошибка $code"
            3, 4, 18 -> when (code) {
                1 -> "не инициализирован"; 4 -> "неисправен"; 2 -> if (subsys == 18) "скачок показаний" else "ошибка 2"
                3 -> if (subsys == 18) "ошибка глубины" else "ошибка 3"
                else -> "ошибка $code"
            }
            5, 6, 7, 8, 9, 17, 20, 23, 26, 27, 29, 31 -> if (code == 1) "сработал" else "код $code"
            10 -> "не удалось включить режим ${modeName(vehicleType, code)}"
            11 -> if (code == 2) "GPS glitch — скачок координат" else "ошибка $code"
            12 -> when (code) { 1 -> "обнаружено падение"; 2 -> "потеря управления"; else -> "код $code" }
            13 -> if (code == 2) "переворот прерван" else "код $code"
            15 -> when (code) { 2 -> "слишком низко для выпуска"; 3 -> "борт на земле"; else -> "код $code" }
            16 -> if (code == 2) "плохая дисперсия EKF" else "код $code"
            21 -> if (code == 2) "нет данных рельефа" else "код $code"
            22 -> when (code) {
                2 -> "не удалось задать точку"; 3 -> "RTL перезапущен"; 4 -> "не удалось начать CIRCLE"
                5 -> "точка за геозоной"; 6 -> "RTL без дальномера"; else -> "код $code"
            }
            24 -> "основным стало ядро EKF $code"
            25 -> "мотор $code"
            30 -> "обнаружены внутренние ошибки"
            else -> "ошибка $code"
        }
    }

    /** События EV (AP_Logger LogEvent). */
    val EV_NAMES = mapOf(
        10 to "Моторы взведены", 11 to "Моторы разоружены", 15 to "Автовзведение",
        17 to "Посадка: возможно", 18 to "Посадка завершена", 19 to "Потеря GPS",
        21 to "Flip: начало", 22 to "Flip: конец", 25 to "Дом установлен", 28 to "Взлёт",
        30 to "Autotune: старт", 31 to "Autotune: выключен", 32 to "Autotune: перезапуск",
        33 to "Autotune: успешно", 34 to "Autotune: неудача", 35 to "Autotune: достигнут предел",
        37 to "Autotune: коэффициенты сохранены", 38 to "Сохранение трима",
        41 to "Геозона включена", 42 to "Геозона выключена",
        49 to "Парашют отключён", 50 to "Парашют включён", 51 to "Парашют выпущен",
        52 to "Шасси выпущено", 53 to "Шасси убрано",
        54 to "Аварийная остановка моторов", 55 to "Аварийная остановка снята",
        56 to "Блокировка моторов включена", 57 to "Блокировка моторов снята",
        58 to "Ротор раскручен", 59 to "Обороты ротора ниже критических",
        60 to "EKF: сброс высоты", 61 to "Посадка отменена пилотом", 62 to "EKF: сброс курса",
        67 to "Смена основного GPS", 73 to "Перемещение при посадке",
        74 to "Режим ожидания включён", 75 to "Режим ожидания выключен",
        85 to "EKF3: основной источник", 86 to "EKF3: второй источник", 87 to "EKF3: третий источник",
    )

    fun vehicleTypeFrom(text: String): String? = when {
        text.contains("ArduCopter", true) || text.contains("Copter V", true) -> "Copter"
        text.contains("ArduPlane", true) || text.contains("Plane V", true) -> "Plane"
        text.contains("Rover", true) -> "Rover"
        text.contains("ArduSub", true) -> "Sub"
        text.contains("Blimp", true) -> "Blimp"
        else -> null
    }
}
