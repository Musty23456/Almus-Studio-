package com.almus.studio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.almus.studio.audio.AudioEngine
import com.almus.studio.data.AudioClip
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed interface TransportState { data object Stopped : TransportState; data object Playing : TransportState; data object Paused : TransportState }

data class RecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val armedTrackId: String? = null,
    val inputLevelDb: Float = -96f,
    val isClipping: Boolean = false
)

/** Maps stable [Track.id]/[AudioClip.id] strings to native integer handles. */
private class HandleTable {
    val trackHandles = mutableMapOf<String, Int>()
    val clipHandles = mutableMapOf<String, Int>()
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)
    private val handles = HandleTable()
    private val sampleRate = 48000

    private val _recentProjects = MutableStateFlow<List<Project>>(emptyList())
    val recentProjects: StateFlow<List<Project>> = _recentProjects.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Stopped)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _playheadFrame = MutableStateFlow(0L)
    val playheadFrame: StateFlow<Long> = _playheadFrame.asStateFlow()

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _masterPeaksDb = MutableStateFlow(floatArrayOf(-96f, -96f))
    val masterPeaksDb: StateFlow<FloatArray> = _masterPeaksDb.asStateFlow()

    init {
        AudioEngine.nativeInit(sampleRate, 256)
        refreshRecentProjects()
        startUiPollingLoop()
    }

    private fun startUiPollingLoop() {
        viewModelScope.launch {
            while (coroutineContext.isActive) {
                _playheadFrame.value = AudioEngine.getPlayheadFrame()
                _masterPeaksDb.value = AudioEngine.getMasterPeaksDb()
                if (_recordingState.value.isRecording) {
                    _recordingState.value = _recordingState.value.copy(
                        inputLevelDb = AudioEngine.getInputLevelDb(),
                        isClipping = AudioEngine.isInputClipping()
                    )
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    fun refreshRecentProjects() {
        _recentProjects.value = repository.listProjects()
    }

    fun createProject(name: String, bpm: Int) {
        val project = repository.createProject(name.ifBlank { "Untitled Project" }, bpm)
        openProject(project.id)
        refreshRecentProjects()
    }

    fun openProject(projectId: String) {
        val project = repository.load(projectId) ?: return
        _currentProject.value = project
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        project.tracks.forEach { track ->
            val handle = AudioEngine.addTrack(track.id)
            handles.trackHandles[track.id] = handle
            AudioEngine.setTrackVolumeDb(handle, track.volumeDb)
            AudioEngine.setTrackPan(handle, track.pan)
            AudioEngine.setTrackMuted(handle, track.muted)
            AudioEngine.setTrackSolo(handle, track.solo)
            track.clips.forEach { clip -> scheduleClipOnEngine(track.id, clip, project) }
        }
    }

    private fun scheduleClipOnEngine(trackId: String, clip: AudioClip, project: Project) {
        val trackHandle = handles.trackHandles[trackId] ?: return
        val file = File(repository.audioDir(project.id), clip.fileName)
        if (!file.exists()) return
        val clipHandle = AudioEngine.scheduleClip(
            trackHandle, file.absolutePath, clip.startFrame, clip.sourceOffsetFrames,
            clip.lengthFrames, clip.gainDb, clip.looping
        )
        if (clipHandle >= 0) handles.clipHandles[clip.id] = clipHandle
    }

    fun closeProject() {
        handles.trackHandles.values.forEach { AudioEngine.removeTrack(it) }
        handles.trackHandles.clear()
        handles.clipHandles.clear()
        _currentProject.value = null
        _transportState.value = TransportState.Stopped
    }

    // --- Transport ----------------------------------------------------------

    fun play() { AudioEngine.play(); _transportState.value = TransportState.Playing }
    fun pause() { AudioEngine.pause(); _transportState.value = TransportState.Paused }
    fun stop() { AudioEngine.stop(); _transportState.value = TransportState.Stopped }
    fun seekTo(frame: Long) { AudioEngine.seekToFrame(frame) }

    // --- Track editing --------------------------------------------------------

    fun setTrackVolume(trackId: String, volumeDb: Float) =
        updateTrack(trackId) { it.copy(volumeDb = volumeDb) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackVolumeDb(h, volumeDb) }
        }

    fun setTrackPan(trackId: String, pan: Float) =
        updateTrack(trackId) { it.copy(pan = pan) }.also {
            handles.trackHandles[trackId]?.let { h -> AudioEngine.setTrackPan(h, pan) }
        }

    fun toggleMute(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.muted
        updateTrack(trackId) { it.copy(muted = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackMuted(it, newValue) }
    }

    fun toggleSolo(trackId: String) {
        val track = _currentProject.value?.tracks?.find { it.id == trackId } ?: return
        val newValue = !track.solo
        updateTrack(trackId) { it.copy(solo = newValue) }
        handles.trackHandles[trackId]?.let { AudioEngine.setTrackSolo(it, newValue) }
    }

    fun toggleArm(trackId: String) {
        val current = _recordingState.value
        _recordingState.value = current.copy(
            armedTrackId = if (current.armedTrackId == trackId) null else trackId
        )
    }

    fun addTrack() {
        val project = _currentProject.value ?: return
        val newTrack = Track(id = UUID.randomUUID().toString(), name = "Track ${project.tracks.size + 1}")
        val updated = project.copy(tracks = project.tracks + newTrack)
        _currentProject.value = updated
        val handle = AudioEngine.addTrack(newTrack.id)
        handles.trackHandles[newTrack.id] = handle
        persist()
    }

    private fun updateTrack(trackId: String, transform: (Track) -> Track) {
        val project = _currentProject.value ?: return
        val updated = project.copy(tracks = project.tracks.map { if (it.id == trackId) transform(it) else it })
        _currentProject.value = updated
        persist()
    }

    private fun persist() {
        _currentProject.value?.let { repository.save(it) }
    }

    // --- Recording ------------------------------------------------------------

    fun startRecording() {
        val project = _currentProject.value ?: return
        val armedTrackId = _recordingState.value.armedTrackId ?: project.tracks.firstOrNull()?.id ?: return
        val trackHandle = handles.trackHandles[armedTrackId] ?: return
        val outFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
        val started = AudioEngine.startRecording(trackHandle, outFile.absolutePath)
        if (started) {
            lastRecordedFile = outFile
            _recordingState.value = _recordingState.value.copy(isRecording = true, isPaused = false)
        }
    }

    fun pauseRecording() {
        AudioEngine.pauseRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = true)
    }

    fun resumeRecording() {
        AudioEngine.resumeRecording()
        _recordingState.value = _recordingState.value.copy(isPaused = false)
    }

    fun stopRecording() {
        val framesWritten = AudioEngine.stopRecording()
        val project = _currentProject.value
        val armedTrackId = _recordingState.value.armedTrackId
        _recordingState.value = _recordingState.value.copy(isRecording = false, isPaused = false)

        if (project != null && armedTrackId != null && framesWritten > 0) {
            val track = project.tracks.find { it.id == armedTrackId } ?: return
            // The file was already written by the native engine to audioDir();
            // we just need to know its name, which we don't have here since it
            // was generated inside startRecording(). In a full implementation
            // this filename would be threaded back through a callback; Phase 1
            // keeps the UI responsible for remembering it via lastRecordedFile.
            lastRecordedFile?.let { file ->
                val clip = AudioClip(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    startFrame = 0L,
                    sourceOffsetFrames = 0L,
                    lengthFrames = framesWritten
                )
                updateTrack(armedTrackId) { it.copy(clips = it.clips + clip) }
                scheduleClipOnEngine(armedTrackId, clip, project)
            }
        }
    }

    private var lastRecordedFile: File? = null

    // --- Import -----------------------------------------------------------

    /** Copies an externally-picked audio file into the project and schedules it on [trackId]. */
    fun importAudioFile(trackId: String, sourceUri: android.net.Uri, displayName: String) {
        val project = _currentProject.value ?: return
        val context = getApplication<Application>()
        val destFile = File(repository.audioDir(project.id), "${UUID.randomUUID()}.wav")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        // Length is unknown until decoded; the engine reports -1 if the WAV
        // could not be parsed, in which case we simply don't add the clip.
        val trackHandle = handles.trackHandles[trackId] ?: return
        val clipHandle = AudioEngine.scheduleClip(trackHandle, destFile.absolutePath, 0L, 0L, -1L, 0f, false)
        if (clipHandle < 0) {
            destFile.delete()
            return
        }
        val realFrameCount = com.almus.studio.audio.WaveformAnalyzer.frameCount(destFile) ?: 0L
        val clip = AudioClip(
            id = UUID.randomUUID().toString(), fileName = destFile.name,
            startFrame = 0L, sourceOffsetFrames = 0L, lengthFrames = realFrameCount
        )
        handles.clipHandles[clip.id] = clipHandle
        updateTrack(trackId) { it.copy(clips = it.clips + clip) }
    }

    override fun onCleared() {
        super.onCleared()
        AudioEngine.nativeShutdown()
    }
}
