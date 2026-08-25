package com.tunex.audio.engine

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Uses the DUMP permission (grantable only via ADB: `adb shell pm grant
 * <package> android.permission.DUMP`) to read AudioFlinger's internal debug
 * dump and pull out session IDs for apps that are playing audio but don't
 * broadcast ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION - Spotify and YouTube
 * Music being the notable examples. This mirrors what Poweramp Equalizer's
 * "Advanced Player Tracking" does.
 *
 * IMPORTANT CAVEATS, read before relying on this:
 * - This reaches into a hidden system API (`ServiceManager`) via reflection
 *   and parses undocumented, human-readable debug text - not a stable public
 *   API. The exact wording of that debug output can differ across Android
 *   versions and OEM skins, and could change in a future OS update.
 * - Every step is wrapped so a failure here can never crash the app or
 *   affect the working session-0 / broadcast-based paths - this is
 *   purely additive. If parsing finds nothing, we just log it and move on.
 * - If the regex below stops matching real output on your device, check
 *   the raw dump logged under the DumpSessionScanner tag (only the first
 *   4KB is logged) and adjust SESSION_ID_PATTERN accordingly.
 */
object DumpSessionScanner {
    private const val TAG = "DumpSessionScanner"

    // AudioFlinger's dump text tends to print lines containing something like
    // "Session: 123" or "session Id: 123" near each active track. This is a
    // best-effort pattern, not a guarantee - see the class doc above.
    private val SESSION_ID_PATTERN = Regex("""(?i)session\s*(?:id)?\s*[:=]\s*(\d+)""")

    /**
     * Returns whatever session IDs we could find, or an empty set if the
     * DUMP permission isn't granted, the hidden API isn't reachable on this
     * device/Android version, or nothing matched. Never throws.
     */
    fun scanForSessionIds(): Set<Int> {
        val dump = dumpAudioFlinger() ?: return emptySet()
        return try {
            SESSION_ID_PATTERN.findAll(dump)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .filter { it > 0 }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse audio_flinger dump", e)
            emptySet()
        }
    }

    private fun dumpAudioFlinger(): String? {
        val binder = getServiceBinder("media.audio_flinger") ?: return null
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            // dump() writes into writeSide; we read the other end. Do the
            // write-side dump call on this thread, then close it so the
            // reader sees EOF once AudioFlinger is done writing.
            binder.dump(writeSide.fileDescriptor, arrayOf())
            writeSide.close()

            val text = ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                input.bufferedReader().readText()
            }
            Log.d(TAG, "audio_flinger dump (first 4KB): ${text.take(4096)}")
            text
        } catch (e: Throwable) {
            // Includes SecurityException (DUMP not granted) and anything
            // reflection might throw on a locked-down device.
            Log.w(TAG, "dump() failed - is android.permission.DUMP granted?", e)
            null
        }
    }

    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        } catch (e: Throwable) {
            Log.w(TAG, "Could not reach ServiceManager.getService($name)", e)
            null
        }
    }
}
