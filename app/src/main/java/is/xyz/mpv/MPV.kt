package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * MPV native bridge.
 *
 * IMPORTANT: libmpvplayer.so looks up `nativeHandle` via JNI GetFieldID (instance field).
 * This must be a real class (not Kotlin object) so that nativeHandle is a proper instance field.
 *
 * All call sites (MPV.create(), MPV.command(), etc.) use the companion object which
 * exposes the singleton INSTANCE. The companion delegates via INSTANCE so existing
 * call sites do not need to change.
 */
@Suppress("unused")
class MPV private constructor() {

    // Instance field required by libmpvplayer.so (via JNI GetFieldID)
    @JvmField
    var nativeHandle: Long = 0L

    // JNI instance methods
    external fun create(appctx: Context)
    external fun init()
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun command(cmd: Array<out String>)
    external fun setOptionString(name: String, value: String): Int
    external fun grabThumbnail(dimension: Int): Bitmap?
    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)
    external fun observeProperty(property: String, format: Int)

    // Native event callbacks (called on INSTANCE by libmpvplayer.so event thread)
    fun eventProperty(property: String, value: Long) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }
    fun eventProperty(property: String, value: Boolean) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }
    fun eventProperty(property: String, value: Double) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }
    fun eventProperty(property: String, value: String) {
        synchronized(observers) { for (o in observers) o.eventProperty(property, value) }
    }
    fun eventProperty(property: String) {
        synchronized(observers) { for (o in observers) o.eventProperty(property) }
    }
    fun event(eventId: Int) {
        synchronized(observers) { for (o in observers) o.event(eventId) }
    }
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(log_observers) { for (o in log_observers) o.logMessage(prefix, level, text) }
    }

    private val observers = mutableListOf<EventObserver>()
    private val log_observers = mutableListOf<LogObserver>()

    fun addObserver(o: EventObserver) { synchronized(observers) { observers.add(o) } }
    fun removeObserver(o: EventObserver) { synchronized(observers) { observers.remove(o) } }
    fun addLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.add(o) } }
    fun removeLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.remove(o) } }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE: Int = 0
        const val MPV_FORMAT_STRING: Int = 1
        const val MPV_FORMAT_OSD_STRING: Int = 2
        const val MPV_FORMAT_FLAG: Int = 3
        const val MPV_FORMAT_INT64: Int = 4
        const val MPV_FORMAT_DOUBLE: Int = 5
        const val MPV_FORMAT_NODE: Int = 6
        const val MPV_FORMAT_NODE_ARRAY: Int = 7
        const val MPV_FORMAT_NODE_MAP: Int = 8
        const val MPV_FORMAT_BYTE_ARRAY: Int = 9
    }

    object MpvEvent {
        const val MPV_EVENT_NONE: Int = 0
        const val MPV_EVENT_SHUTDOWN: Int = 1
        const val MPV_EVENT_LOG_MESSAGE: Int = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY: Int = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY: Int = 4
        const val MPV_EVENT_COMMAND_REPLY: Int = 5
        const val MPV_EVENT_START_FILE: Int = 6
        const val MPV_EVENT_END_FILE: Int = 7
        const val MPV_EVENT_FILE_LOADED: Int = 8
        const val MPV_EVENT_IDLE: Int = 11
        const val MPV_EVENT_TICK: Int = 14
        const val MPV_EVENT_VIDEO_RECONFIG: Int = 17
        const val MPV_EVENT_AUDIO_RECONFIG: Int = 18
        const val MPV_EVENT_SEEK: Int = 20
        const val MPV_EVENT_PLAYBACK_RESTART: Int = 21
        const val MPV_EVENT_PROPERTY_CHANGE: Int = 22
        const val MPV_EVENT_QUEUE_OVERFLOW: Int = 24
    }

    companion object {
        /** Singleton instance. Use MPV.INSTANCE.create(), MPV.INSTANCE.command(), etc. */
        @JvmField
        val INSTANCE: MPV = MPV()

        var isLoaded = false
            private set
        var loadError: String? = null
            private set

        private val LIB_NAMES = arrayOf("mpv", "mpvplayer")
        private var staticInitFailed = false

        @JvmStatic
        fun loadLibraries(context: Context) {
            if (isLoaded) return
            if (!staticInitFailed) return
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            try {
                for (lib in LIB_NAMES) {
                    val soFile = File(nativeLibDir, "lib$lib.so")
                    if (soFile.exists()) System.load(soFile.absolutePath)
                    else System.loadLibrary(lib)
                }
                isLoaded = true
                loadError = null
            } catch (e: Throwable) {
                isLoaded = false
                loadError = e.message ?: e.toString()
                Log.e("MPV", "Failed to load MPV libraries: $loadError")
            }
        }

        init {
            try {
                for (lib in LIB_NAMES) System.loadLibrary(lib)
                isLoaded = true
                staticInitFailed = false
            } catch (e: Throwable) {
                isLoaded = false
                staticInitFailed = true
                loadError = e.message ?: e.toString()
                Log.w("MPV", "Static init failed: $loadError")
            }
        }
    }
}




