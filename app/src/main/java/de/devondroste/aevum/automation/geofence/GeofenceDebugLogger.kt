package de.devondroste.aevum.automation.geofence

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory debug log for geofence events.
 * Survives process restarts via a minimal local buffer.
 * 
 * M7.1: Helps diagnose why geofence triggers did/didn't fire.
 * Accessible from GeofenceDebugScreen.
 */
@Singleton
class GeofenceDebugLogger @Inject constructor() {
    private val buffer = mutableListOf<DebugEntry>()
    private val maxSize = 200

    fun log(tag: String, message: String) {
        synchronized(buffer) {
            buffer.add(DebugEntry(System.currentTimeMillis(), tag, message))
            if (buffer.size > maxSize) {
                buffer.removeAt(0)
            }
        }
    }

    fun entries(): List<DebugEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    data class DebugEntry(
        val timestamp: Long,
        val tag: String,
        val message: String
    )
}
