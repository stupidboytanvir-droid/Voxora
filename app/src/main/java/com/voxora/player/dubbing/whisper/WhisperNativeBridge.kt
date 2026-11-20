package com.voxora.player.dubbing.whisper

/**
 * Callback invoked directly from native whisper.cpp C++ code during inference.
 * Method names and signatures must stay strictly aligned with `voxora_whisper_jni.cpp`.
 */
interface WhisperNativeCallback {
    fun onProgress(progressPercent: Int)
    fun onNewSegment(startMs: Long, endMs: Long, text: String)
}

/**
 * Real Android JNI bridge to compiled native `libvoxora_whisper.so` (whisper.cpp v1.9.5).
 * Never fakes native library loading: if the `.so` fails to load on an unsupported ABI,
 * [isNativeLibraryLoaded] stays false and [loadError] exposes the real error.
 */
object WhisperNativeBridge {

    const val RESULT_CANCELLED = -99

    @Volatile
    var isNativeLibraryLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("voxora_whisper")
            isNativeLibraryLoaded = true
            loadError = null
        } catch (err: UnsatisfiedLinkError) {
            isNativeLibraryLoaded = false
            loadError = "Native whisper.cpp library could not be loaded on this ABI: ${err.message}"
        } catch (err: Throwable) {
            isNativeLibraryLoaded = false
            loadError = "Unexpected native bridge error: ${err.message}"
        }
    }

    external fun nativeInitContext(modelPath: String): Long

    external fun nativeFreeContext(contextPtr: Long)

    external fun nativeRequestAbort()

    external fun nativeResetAbort()

    external fun nativeTranscribeChunk(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        chunkOffsetMs: Long,
        language: String,
        callback: WhisperNativeCallback?
    ): Int

    external fun nativeGetSegmentCount(contextPtr: Long): Int

    external fun nativeGetSegmentText(contextPtr: Long, index: Int): String

    external fun nativeGetSegmentStartMs(contextPtr: Long, index: Int): Long

    external fun nativeGetSegmentEndMs(contextPtr: Long, index: Int): Long

    external fun nativeGetSystemInfo(): String

    fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(1, 4)
    }
}
