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
        28 to "Ввод пилота", 29 to "Failsafe: вибрации",
    )

    val EV_NAMES = mapOf(
        10 to "Моторы взведены", 11 to "Моторы разоружены", 15 to "Автовзведение",
        16 to "Посадка: возможно", 17 to "Посадка: возможно", 18 to "Посадка завершена",
        19 to "Ошибка: потеря управления", 25 to "Дом установлен", 28 to "Взлёт",
        54 to "Аварийная остановка моторов", 57 to "Аварийная остановка снята",
        60 to "Парашют выпущен", 62 to "EKF: смена высоты", 63 to "EKF: смена позиции",
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
