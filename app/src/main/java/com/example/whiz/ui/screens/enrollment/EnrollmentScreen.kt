package com.example.whiz.ui.screens.enrollment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.example.whiz.services.WakeWordService
import com.example.whiz.wakeword.WakeWordEngine
import com.example.whiz.wakeword.detection.CamPlusOrtEmbedder
import com.example.whiz.wakeword.enrollment.EmbeddingSeeder
import com.example.whiz.wakeword.enrollment.EnrolledEmbeddingStore
import com.example.whiz.wakeword.enrollment.EnrollmentRecorder
import com.example.whiz.wakeword.enrollment.EnrollmentRepository
import com.example.whiz.wakeword.enrollment.EnrollmentState
import com.example.whiz.wakeword.enrollment.EnrollmentViewModel
import com.example.whiz.wakeword.enrollment.ModelHash
import com.example.whiz.wakeword.enrollment.WakeWordEngineEnrollmentScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasMicPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // Pause wake-word service while enrolling so we don't compete for the mic;
    // restart on exit. (Service no-ops if it wasn't running.)
    DisposableEffect(Unit) {
        WakeWordService.stop(context)
        onDispose {
            // Restart only if user had it enabled — service itself reads the pref.
            val prefs = context.getSharedPreferences("wake_word_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("wake_word_enabled", false)) {
                WakeWordService.start(context)
            }
        }
    }

    val repo = remember { buildRepository(context) }
    val store = remember { buildStore(context) }
    val viewModel = remember {
        EnrollmentViewModel(
            scorer = WakeWordEngineEnrollmentScorer(context),
            saveClip = { idx, pcm -> repo.saveClip(idx, pcm) },
            saveSidecar = { sidecar -> repo.saveSidecar(sidecar) },
            persistThreshold = { /* persisted on the toggle path; nothing to do here */ },
            modelHash = {
                context.assets.open(WakeWordEngine.DEFAULT_CLASSIFIER_ASSET).use { ModelHash.sha256(it) }
            },
            scope = scope,
        )
    }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice match enrollment") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (!hasMicPermission) {
                MissingPermissionContent()
                return@Box
            }
            when (val s = state) {
                is EnrollmentState.NotEnrolled -> NotEnrolledContent(
                    hasExistingEnrollment = repo.loadSidecar() != null,
                    onStart = { viewModel.start() },
                    onClearExisting = {
                        scope.launch(Dispatchers.IO) {
                            repo.deleteAll()
                            EnrolledEmbeddingStore(
                                dir = wakeWordDir(context),
                                dim = 192,
                                cap = 50,
                                protectedCap = 5,
                                mirrorDir = wakeWordExportDir(context),
                            ).seedProtected(emptyList())
                        }
                    },
                )
                is EnrollmentState.Recording -> RecordingContent(
                    clipIndex = s.clipIndex,
                    total = EnrollmentViewModel.TARGET_CLIPS,
                    lastAcceptedScore = s.lastAcceptedScore,
                    onClipPcm = { pcm -> viewModel.onClipRecorded(pcm) },
                    onRedoPrevious = { viewModel.redoPreviousClip() },
                )
                is EnrollmentState.RetryClip -> RetryContent(
                    clipIndex = s.clipIndex,
                    lastScore = s.lastScore,
                    retriesLeft = s.retriesLeft,
                    onRetry = { viewModel.retryCurrentClip() },
                )
                is EnrollmentState.Complete -> {
                    var seeding by remember { mutableStateOf(true) }
                    LaunchedEffect(s) {
                        // Compute embeddings off the main thread, seed the store.
                        withContext(Dispatchers.IO) {
                            EmbeddingSeeder.seed(context, repo, store)
                        }
                        seeding = false
                    }
                    CompleteContent(
                        threshold = s.threshold,
                        seeding = seeding,
                        onDone = { navController.popBackStack() },
                    )
                }
                is EnrollmentState.Error -> ErrorContent(
                    message = s.message,
                    onRetry = { viewModel.reset() },
                )
            }
        }
    }
}

@Composable
private fun MissingPermissionContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Microphone permission required",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Grant the microphone permission to enroll your voice.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NotEnrolledContent(
    hasExistingEnrollment: Boolean,
    onStart: () -> Unit,
    onClearExisting: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Enroll your voice",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Say \"Hey Whiz\" five times in a normal voice. We'll save a voice " +
                "fingerprint on this device so the wake word can be matched to you only.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text(if (hasExistingEnrollment) "Re-enroll" else "Start") }
        if (hasExistingEnrollment) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClearExisting) { Text("Delete existing enrollment") }
        }
    }
}

@Composable
private fun RecordingContent(
    clipIndex: Int,
    total: Int,
    lastAcceptedScore: Float?,
    onClipPcm: (ShortArray) -> Unit,
    onRedoPrevious: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Clip $clipIndex of $total",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Press and hold the mic, say \"Hey Whiz\", then release.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            shape = CircleShape,
            color = if (isRecording) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (isRecording) {
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            val released = AtomicBoolean(false)
                            isRecording = true
                            scope.launch {
                                val pcm = withContext(Dispatchers.IO) {
                                    EnrollmentRecorder().recordHeldClip(
                                        shouldStop = { released.get() }
                                    )
                                }
                                isRecording = false
                                onClipPcm(pcm)
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                released.set(true)
                            }
                        }
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRecording) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Record",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        if (lastAcceptedScore != null && clipIndex > 1) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Clip ${clipIndex - 1}: ✓ ${"%.2f".format(lastAcceptedScore)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onRedoPrevious,
                enabled = !isRecording,
            ) { Text("Re-record clip ${clipIndex - 1}") }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { (clipIndex - 1).toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RetryContent(
    clipIndex: Int,
    lastScore: Float,
    retriesLeft: Int,
    onRetry: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Try again",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Clip $clipIndex didn't match \"Hey Whiz\" closely enough (score " +
                "${"%.2f".format(lastScore)}). Make sure you're in a quiet spot and " +
                "speak clearly. $retriesLeft tries left.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun CompleteContent(threshold: Float, seeding: Boolean, onDone: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Voice enrolled",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Threshold: ${"%.2f".format(threshold)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (seeding) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Computing voice fingerprint…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Turn on \"Match my voice only\" in Settings to require your voice for the wake word.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Start over") }
    }
}

// ----- helpers -----

private fun wakeWordDir(context: Context): File =
    File(context.filesDir, "wake_word").apply { mkdirs() }

/** External-storage mirror dir for the enrollment, pullable via plain `adb pull`. */
private fun wakeWordExportDir(context: Context): File =
    File(context.getExternalFilesDir(null), "wake_word")

private fun buildRepository(context: Context): EnrollmentRepository =
    EnrollmentRepository(context, File(wakeWordDir(context), "enrollment"))

private fun buildStore(context: Context): EnrolledEmbeddingStore =
    EnrolledEmbeddingStore(
        dir = wakeWordDir(context),
        dim = CamPlusOrtEmbedder.EMBEDDING_DIM,
        cap = 50,
        protectedCap = 5,
        mirrorDir = wakeWordExportDir(context),
    )

