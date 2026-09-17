package com.almus.studio.audio

/**
 * Thin Kotlin wrapper around the native (C++/Oboe) multitrack audio engine.
 *
 * Design note: every method here that touches audio state ends up on a
 * lock-free command queue inside the native engine (see audio_engine.cpp).
 * Nothing in this class blocks the Oboe real-time callback -- Kotlin/JNI
 * calls only ever *enqueue* a change; the audio thread applies it between
 * buffers. This is what keeps the UI thread and the audio thread decoupled.
 */
object AudioEngine {

    init {
        System.loadLibrary("almus_audio")
    }

    // --- Engine lifecycle -------------------------------------------------

    /** Must be called once before any other method, typically from onCreate(). */
    external fun nativeInit(sampleRate: Int, framesPerBurst: Int)

    external fun nativeShutdown()

    // --- Track management ---------------------------------------------------

    /** Registers a track in the native mixer; returns a native track handle. */
    external fun addTrack(trackId: String): Int

    external fun removeTrack(trackHandle: Int)

    external fun setTrackVolumeDb(trackHandle: Int, db: Float)

    external fun setTrackPan(trackHandle: Int, pan: Float)

    external fun setTrackMuted(trackHandle: Int, muted: Boolean)

    external fun setTrackSolo(trackHandle: Int, solo: Boolean)

    // --- Clip scheduling ------------------------------------------------------

    /**
     * Schedules a decoded WAV file to play on [trackHandle] starting at
     * [startFrame] (project timeline position, in frames at engine sample rate).
     * Returns a native clip handle, or -1 if the file could not be decoded.
     */
    external fun scheduleClip(
        trackHandle: Int,
        filePath: String,
        startFrame: Long,
        sourceOffsetFrames: Long,
        lengthFrames: Long,
        gainDb: Float,
        looping: Boolean
    ): Int

    external fun removeClip(clipHandle: Int)

    external fun moveClip(clipHandle: Int, newStartFrame: Long)

    // --- Transport --------------------------------------------------------

    external fun play()

    external fun pause()

    external fun stop()

    external fun seekToFrame(frame: Long)

    /** Current playhead position in frames, safe to poll frequently from the UI thread. */
    external fun getPlayheadFrame(): Long

    external fun setLoopRegion(startFrame: Long, endFrame: Long, enabled: Boolean)

    // --- Recording ----------------------------------------------------------

    /**
     * Starts recording microphone input into a new WAV file at [outputFilePath]
     * while it is simultaneously routed to [monitorTrackHandle] for the meter.
     * Returns false if the microphone is unavailable or already in use.
     */
    external fun startRecording(monitorTrackHandle: Int, outputFilePath: String): Boolean

    external fun pauseRecording()

    external fun resumeRecording()

    /** Stops recording and returns the number of frames written. */
    external fun stopRecording(): Long

    /** Instantaneous input level in dBFS, or Float.NEGATIVE_INFINITY if not recording. */
    external fun getInputLevelDb(): Float

    external fun isInputClipping(): Boolean

    // --- Master / metering --------------------------------------------------

    external fun setMasterVolumeDb(db: Float)

    /** Peak level in dBFS for [left, right], for the master meter. */
    external fun getMasterPeaksDb(): FloatArray

    // --- Offline export -------------------------------------------------------

    /**
     * Renders the project to a WAV file on a background native thread, not the
     * real-time audio thread. [totalFrames] should be the end of the furthest
     * clip on the timeline (computed on the Kotlin side, where the project
     * model lives) so the render stops exactly where the arrangement ends.
     * Progress is reported through [ExportProgressListener]; returns immediately.
     */
    external fun startOfflineExport(outputFilePath: String, totalFrames: Long, listener: ExportProgressListener)

    external fun cancelOfflineExport()

    interface ExportProgressListener {
        fun onProgress(fractionComplete: Float)
        fun onComplete(outputFilePath: String)
        fun onError(message: String)
    }
}
