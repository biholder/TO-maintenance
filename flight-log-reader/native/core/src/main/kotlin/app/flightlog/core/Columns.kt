package app.flightlog.core

/** Растущий массив double без боксинга. */
internal class DoubleList(capacity: Int = 256) {
    private var data = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Double = data[i]
    fun toDoubleArray(): DoubleArray = data.copyOf(size)
    fun toFloatArray(): FloatArray = FloatArray(size) { data[it].toFloat() }
}

/** Таблица одного типа сообщений: колонки чисел и строк. */
class MessageTable internal constructor(val name: String, val columns: List<String>) {
    internal val numeric = HashMap<String, DoubleList>()
    internal val text = HashMap<String, MutableList<String>>()
    var rows = 0
        internal set

    fun has(col: String): Boolean = numeric.containsKey(col) || text.containsKey(col)
    fun num(col: String): DoubleArray = numeric[col]?.toDoubleArray() ?: DoubleArray(0)
    fun float(col: String): FloatArray = numeric[col]?.toFloatArray() ?: FloatArray(0)
    fun str(col: String): List<String> = text[col] ?: emptyList()
    fun numAt(col: String, row: Int): Double = numeric[col]?.get(row) ?: Double.NaN
}
