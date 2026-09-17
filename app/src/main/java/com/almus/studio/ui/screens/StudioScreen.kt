package com.almus.studio.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.almus.studio.audio.WaveformAnalyzer
import com.almus.studio.data.Project
import com.almus.studio.data.ProjectRepository
import com.almus.studio.ui.components.ClipWaveform
import com.almus.studio.ui.components.TimelineRuler
import com.almus.studio.ui.components.TrackHeader
import com.almus.studio.ui.components.TransportBar
import com.almus.studio.viewmodel.StudioViewModel
import java.io.File

private const val PIXELS_PER_BEAT = 40f

@Composable
fun StudioScreen(viewModel: StudioViewModel, onBack: () -> Unit) {
    val project by viewModel.currentProject.collectAsState()
    val transportState by viewModel.transportState.collectAsState()
    val playheadFrame by viewModel.playheadFrame.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val context = LocalContext.current

    var micPermissionGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionGranted = granted
    }

    var importTargetTrackId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val trackId = importTargetTrackId
        if (uri != null && trackId != null) {
            viewModel.importAudioFile(trackId, uri, uri.lastPathSegment ?: "import.wav")
        }
    }

    val currentProject = project ?: run {
        // Project was closed or not yet loaded; nothing to show.
        Scaffold(topBar = { TopAppBar(title = { Text("Almus Studio") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val bpm = currentProject.bpm
    val secondsPerBeat = 60f / bpm
    val framesPerBeat = secondsPerBeat * currentProject.sampleRate
    val pixelsPerFrame = PIXELS_PER_BEAT / framesPerBeat
    val playheadSeconds = playheadFrame / currentProject.sampleRate.toFloat()
    val positionLabel = "%02d:%02d.%02d".format(
        (playheadSeconds / 60).toInt(),
        (playheadSeconds % 60).toInt(),
        ((playheadSeconds % 1) * 100).toInt()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentProject.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.addTrack() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add track")
                    }
                }
            )
        },
        bottomBar = {
            TransportBar(
                transportState = transportState,
                isRecording = recordingState.isRecording,
                positionLabel = positionLabel,
                bpm = bpm,
                onPlay = viewModel::play,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
                onRecord = {
                    if (!micPermissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (recordingState.isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                }
            )
        }
    ) { padding ->
        if (!micPermissionGranted) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    "Microphone permission not granted yet — recording is disabled until you allow it. Editing and mixing imported audio still works fully offline.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val verticalScroll = rememberScrollState()
            val horizontalScroll = rememberScrollState()

            Row(Modifier.weight(1f)) {
                // Track headers (fixed, vertically scrolls with the timeline)
                Column(
                    modifier = Modifier
                        .verticalScroll(verticalScroll)
                        .padding(top = 28.dp) // aligns with ruler height
                ) {
                    currentProject.tracks.forEach { track ->
                        Box(Modifier.height(100.dp).padding(vertical = 4.dp)) {
                            TrackHeader(
                                track = track,
                                isArmed = recordingState.armedTrackId == track.id,
                                onVolumeChange = { viewModel.setTrackVolume(track.id, it) },
                                onPanChange = { viewModel.setTrackPan(track.id, it) },
                                onToggleMute = { viewModel.toggleMute(track.id) },
                                onToggleSolo = { viewModel.toggleSolo(track.id) },
                                onToggleArm = { viewModel.toggleArm(track.id) }
                            )
                        }
                    }
                }

                // Timeline: ruler + per-track clip lanes, scrolls both ways
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                ) {
                    TimelineRuler(
                        widthDp = 4000.dp,
                        pixelsPerBeat = PIXELS_PER_BEAT,
                        beatsPerBar = currentProject.timeSignatureNumerator
                    )
                    Column(modifier = Modifier.verticalScroll(verticalScroll)) {
                        currentProject.tracks.forEach { track ->
                            Box(Modifier.height(100.dp).padding(vertical = 4.dp)) {
                                TrackTimelineLane(
                                    projectId = currentProject.id,
                                    track = track,
                                    pixelsPerFrame = pixelsPerFrame,
                                    onImportRequested = {
                                        importTargetTrackId = track.id
                                        importLauncher.launch("audio/*")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackTimelineLane(
    projectId: String,
    track: com.almus.studio.data.Track,
    pixelsPerFrame: Float,
    onImportRequested: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }

    Box(Modifier.fillMaxSize()) {
        track.clips.forEach { clip ->
            val file = remember(clip.id) { File(repository.audioDir(projectId), clip.fileName) }
            val peaks by produceState<WaveformAnalyzer.Peaks?>(initialValue = null, clip.id) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    WaveformAnalyzer.analyze(file, bucketCount = 200)
                }
            }
            val widthDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                (clip.lengthFrames * pixelsPerFrame).toInt().coerceAtLeast(40).toFloat().toDp()
            }
            val offsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                (clip.startFrame * pixelsPerFrame).toDp()
            }
            Box(Modifier.offset(x = offsetDp)) {
                ClipWaveform(peaks = peaks, widthDp = widthDp, heightDp = 92.dp)
            }
        }

        androidx.compose.material3.IconButton(
            onClick = onImportRequested,
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
        ) {
            androidx.compose.material3.Icon(Icons.Filled.FileUpload, contentDescription = "Import audio")
        }
    }
}
