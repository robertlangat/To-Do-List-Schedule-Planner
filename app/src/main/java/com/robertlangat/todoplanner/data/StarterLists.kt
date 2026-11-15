package com.robertlangat.todoplanner.data

/** Optional lists offered on first launch and in Settings. `key` doubles as the list icon key. */
object StarterLists {
    data class Starter(val key: String, val name: String, val color: Long)

    val all = listOf(
        Starter("personal", "Personal", 0xFF5C6BC0),
        Starter("work", "Work", 0xFF00897B),
        Starter("study", "Study", 0xFF8E24AA),
        Starter("fitness", "Fitness", 0xFFE53935),
        Starter("travel", "Travel", 0xFF039BE5),
        Starter("wishlist", "Wishlist", 0xFFF4511E),
        Starter("family", "Family events", 0xFFD81B60),
    )
}
