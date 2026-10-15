package de.teddycloud.teddyremote.repository

/** Invalidates work from previous connection attempts before asynchronous shutdown begins. */
internal class ConnectionIntent {
    private var revision = 0L
    @Volatile
    var wanted = false
        private set

    @Synchronized
    fun request(): Long {
        wanted = true
        return ++revision
    }

    @Synchronized
    fun stop() {
        wanted = false
        revision++
    }
    @Synchronized fun invalidate(): Long = ++revision
    @Synchronized fun token(): Long = revision
    @Synchronized fun isCurrent(token: Long): Boolean = wanted && token == revision
    @Synchronized fun apply(token: Long, action: () -> Unit) {
        if (isCurrent(token)) action()
    }
}
