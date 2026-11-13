package org.soulstone.overwatch.scan

/**
 * Keeps Bluetooth scan starts under the limit Android's stack enforces.
 *
 * The stack refuses a scan when the app's last five finished scans all began
 * within the last 30 seconds (AppScanStats.isScanningTooFrequently; both numbers
 * are defaults a vendor can change), and it refuses without a word: the
 * framework's comment is "don't report anything to the app". No error, no
 * results, a scan that looks started and hears nothing. Every change of scan
 * mode is a start, so a screen that wakes and sleeps three times in half a
 * minute gets there. Read in AOSP main, 2026-11-12; never provoked on a device.
 *
 * This allows [maxStarts] in any [windowMs] and says how long to hold the next
 * one. One instance serves the whole process, because the stack counts per app
 * and a restarted service must not start counting from nothing.
 */
class ScanStartBudget(
    private val maxStarts: Int = 4,
    private val windowMs: Long = 31_000L,
    private val nowMs: () -> Long
) {
    private val starts = ArrayDeque<Long>()

    /** How long until a start is safe, in ms; 0 when it is safe now. */
    @Synchronized
    fun waitMs(): Long {
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        return if (starts.size < maxStarts) 0L else starts.first() + windowMs - now
    }

    /** Record a start that has just been made. */
    @Synchronized
    fun started() {
        starts.addLast(nowMs())
    }
}
