package dev.msk.norouterdrop

/** In-memory log kept for the whole life of the process (not only while the screen is open). Nothing is written to disk. */
object AppLog {
    private val lines = ArrayList<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized fun add(text: String) {
        lines.add(fmt.format(java.util.Date()) + "  " + text)
        if (lines.size > 800) lines.removeAt(0)
    }

    @Synchronized fun all(): List<String> = lines.toList()
    @Synchronized fun text(): String = lines.joinToString("\n")
}
