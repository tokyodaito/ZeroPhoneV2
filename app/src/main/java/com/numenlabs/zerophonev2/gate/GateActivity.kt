package com.numenlabs.zerophonev2.gate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.max
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.core.gate.GateState
import com.numenlabs.zerophonev2.core.gate.GateStopReason
import com.numenlabs.zerophonev2.ui.components.CountdownRing
import com.numenlabs.zerophonev2.ZeroPhoneApp
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen 60-second attention gate. Wakes the screen (alarm can fire
 * while locked), keeps it on, runs the front camera + ML Kit face presence,
 * and STOPS the countdown on any distraction — restarting only via the
 * explicit «Попробовать снова» tap.
 */
class GateActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var viewModel: GateViewModel? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var faceAnalyzer: FaceAnalyzer? = null
    private var cameraPermissionRequested = false

    /** Created once, reused by AndroidView in the Compose tree and by camera binding. */
    private lateinit var previewView: androidx.camera.view.PreviewView

    /** The CAMERA permission dialog steals window focus — that is not a distraction. */
    private var permissionDialogShowing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bindCamera()
            } else {
                viewModel?.onCameraError()
            }
        }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) viewModel?.screenOff()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        viewModel =
            ViewModelProvider(
                this,
                GateViewModel.Factory(application as ZeroPhoneApp, targetPackage),
            )[GateViewModel::class.java]

        previewView =
            androidx.camera.view.PreviewView(this).apply {
                scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            }

        lifecycleScope.launch {
            viewModel!!.vmEvents.collect { event ->
                when (event) {
                    is GateViewModel.GateVmEvent.LaunchTarget -> {
                        val launched = launchTarget(event.packageName)
                        if (!launched) {
                            // No launch intent — do not leave an unusable open window.
                            viewModel?.requestGrantRevoke()
                        }
                        finish()
                    }

                    GateViewModel.GateVmEvent.OpenGrantFailed -> finish()
                }
            }
        }

        // Abandoning must destroy the gate — a terminal singleTask instance
        // would otherwise linger invisible with the camera still bound.
        lifecycleScope.launch {
            var previous: GateState = GateState.Idle
            viewModel!!.uiState.collect { state ->
                if (state is GateState.Abandoned) finish()
                // «Попробовать снова» after a camera failure must also RETRY the
                // camera itself, not just the countdown (binding happens in onStart).
                if (previous is GateState.Stopped &&
                    (previous as GateState.Stopped).reason == GateStopReason.CAMERA_ERROR &&
                    state is GateState.Watching &&
                    faceAnalyzer == null &&
                    ContextCompat.checkSelfPermission(this@GateActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    bindCamera()
                }
                previous = state
            }
        }

        createFallbackChannel()

        setContent {
            MaterialTheme(colorScheme = darkColorSchemeCompat()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    GateContent(
                        viewModel = viewModel!!,
                        previewView = previewView,
                        onOpenAppSettings = ::openAppSettings,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask routes re-starts here; a request for a different package
        // must not be silently dropped (it would grant the STALE target).
        val newTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (!newTarget.isNullOrBlank() && newTarget != viewModel?.targetPackage) {
            setIntent(intent)
            recreate() // Fresh ViewModel from the new extra.
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else if (!cameraPermissionRequested) {
            cameraPermissionRequested = true
            permissionDialogShowing = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            // Permission denied earlier in this session — only «Не сейчас»/settings remain.
            mainHandler.post { viewModel?.onCameraError() }
        }
    }

    override fun onStop() {
        unbindCamera()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        viewModel?.focusLost()
        super.onStop()
    }

    override fun onDestroy() {
        faceAnalyzer?.close()
        faceAnalyzer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            permissionDialogShowing = false
            return
        }
        // The system permission dialog also steals focus — that is not the user leaving.
        if (!permissionDialogShowing) viewModel?.focusLost() // Notification shade included — no lifecycle callback fires for it.
    }

    private fun bindCamera() {
        val vm = viewModel ?: return
        // One analyzer (and its native detector) per activity; rebound on every onStart.
        val analyzer =
            faceAnalyzer ?: FaceAnalyzer { frame ->
                mainHandler.post { vm.onFaceFrame(frame) }
            }.also { faceAnalyzer = it }
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview =
                        androidx.camera.core.Preview.Builder()
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    mainHandler.post { vm.onCameraError() }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun unbindCamera() {
        // Never block on the future here (onStop is on the main thread);
        // CameraX also auto-unbinds at ON_STOP for a lifecycle-bound use case.
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
    }

    private fun launchTarget(packageName: String): Boolean =
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun createFallbackChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_GATE_FALLBACK) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GATE_FALLBACK,
                    getString(R.string.channel_gate),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.channel_gate_desc) },
            )
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val CHANNEL_GATE_FALLBACK = "zerophonev2_gate"

        /** BAL-safe launch: the Device Owner uid is exempt from background-activity-launch restrictions. */
        fun start(
            context: Context,
            packageName: String,
        ) {
            try {
                val intent =
                    Intent(context, GateActivity::class.java)
                        .putExtra(EXTRA_PACKAGE_NAME, packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                context.startActivity(intent)
            } catch (_: Exception) {
                notifyGateBlocked(context, packageName)
            }
        }

        /**
         * BAL denial does NOT throw — callers that cannot rely on the device-owner
         * exemption must post this notification instead of (or in addition to) start().
         */
        fun notifyGateBlocked(
            context: Context,
            packageName: String,
        ) {
            try {
                val notification =
                    androidx.core.app.NotificationCompat.Builder(context, CHANNEL_GATE_FALLBACK)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                        .setContentTitle(context.getString(R.string.gate_notification_title))
                        .setContentText(context.getString(R.string.gate_notification_text))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(
                            android.app.PendingIntent.getActivity(
                                context,
                                packageName.hashCode(),
                                Intent(context, GateActivity::class.java)
                                    .putExtra(EXTRA_PACKAGE_NAME, packageName),
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                    android.app.PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                        .build()
                context.getSystemService(NotificationManager::class.java)
                    .notify(packageName.hashCode(), notification)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun darkColorSchemeCompat() =
    androidx.compose.material3.darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        background = Color.Black,
        surface = Color.Black,
        onSurface = Color.White,
    )

@Composable
private fun GateContent(
    viewModel: GateViewModel,
    previewView: androidx.camera.view.PreviewView,
    onOpenAppSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val totalSeconds by viewModel.totalSeconds.collectAsState()
    val faceOverlay by viewModel.faceOverlay.collectAsState()
    val lightOn by viewModel.lightOn.collectAsState()
    val lightLevel by viewModel.lightLevel.collectAsState()
    val countdownStarted by viewModel.countdownStarted.collectAsState()
    val entriesToday by viewModel.entriesToday.collectAsState()
    val context = LocalContext.current

    // Fill light also raises the window brightness proportionally; restore on dispose.
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(lightOn, lightLevel) {
        val initial = window?.attributes?.screenBrightness ?: -1f
        if (lightOn && window != null) {
            window.attributes =
                window.attributes.also { it.screenBrightness = 0.35f + 0.65f * lightLevel }
        }
        onDispose {
            if (lightOn && window != null) {
                window.attributes = window.attributes.also { it.screenBrightness = initial }
            }
        }
    }

    // The departing-light animation: camera fades out while its outline flies
    // apart toward the screen edges; the countdown layout appears afterwards.
    val fly = remember { Animatable(0f) }
    var showCountdown by remember { mutableStateOf(false) }
    LaunchedEffect(countdownStarted) {
        if (countdownStarted && fly.value < 1f) {
            fly.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            showCountdown = true
        }
    }

    var cameraRect by remember { mutableStateOf<Rect?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // The light itself. Phase 1: a soft glow AROUND the camera preview.
        // On «Запускаем» the glow FLOWS outward: the outline flies apart toward
        // the borders while the light settles into an edge-hugging frame that
        // STAYS for the whole countdown — the face remains illuminated.
        val rect = cameraRect
        if (lightOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = fly.value
                // Around the camera (phase 1) — dissolving during the launch.
                val previewAlpha = lightLevel * 0.85f * (1f - p)
                if (previewAlpha > 0.015f && rect != null) {
                    val center = rect.center
                    val radius = max(size.width, size.height) * 0.95f
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = previewAlpha), Color.Transparent),
                                center = center,
                                radius = radius,
                            ),
                        radius = radius,
                        center = center,
                    )
                }
                // Edge light (phase 2) — the glow FLOWS to the screen borders and
                // settles there as a soft frame: the face stays illuminated while
                // the center (where the user looks) stays dark. Reverse radial:
                // transparent core, white rim.
                val edgeAlpha = lightLevel * 0.9f * p
                if (edgeAlpha > 0.015f) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = max(size.width, size.height) * 0.62f
                    drawRect(
                        brush =
                            Brush.radialGradient(
                                colorStops =
                                    arrayOf(
                                        0.0f to Color.Transparent,
                                        0.45f to Color.Transparent,
                                        1.0f to Color.White.copy(alpha = edgeAlpha),
                                    ),
                                center = center,
                                radius = radius,
                            ),
                        size = size,
                    )
                }
                // The departing outline ("обводка") of the camera box.
                if (p > 0f && p < 1f && rect != null) {
                    val w = rect.width * (1f + 7f * p)
                    val h = rect.height * (1f + 7f * p)
                    val c = rect.center
                    drawRoundRect(
                        color = Color.White.copy(alpha = (1f - p) * 0.9f),
                        topLeft = Offset(c.x - w / 2f, c.y - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(48f, 48f),
                        style = Stroke(width = 4f + 8f * (1f - p)),
                    )
                }
            }
        }

        if (!showCountdown) {
            // ---- Phase 1: self-check preview ----
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.gate_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.gate_subtitle, totalSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                if (entriesToday > 0) {
                    Text(
                        text = stringResource(R.string.gate_progressive_note, entriesToday, totalSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFC107),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                val current = state
                val overlayStatus =
                    when {
                        current is GateState.Watching && current.attentionHeld ->
                            com.numenlabs.zerophonev2.ui.components.FaceOverlayStatus.ATTENTION

                        faceOverlay?.faceFound == true ->
                            com.numenlabs.zerophonev2.ui.components.FaceOverlayStatus.WARMING_UP

                        else -> com.numenlabs.zerophonev2.ui.components.FaceOverlayStatus.NO_FACE
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.55f)
                            .aspectRatio(3f / 4f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .background(Color(0xFF101010))
                            .onGloballyPositioned { cameraRect = it.boundsInRoot() }
                            .graphicsLayer {
                                alpha = 1f - fly.value
                                scaleX = 1f - 0.1f * fly.value
                                scaleY = 1f - 0.1f * fly.value
                            },
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    com.numenlabs.zerophonev2.ui.components.FaceOverlayCanvas(
                        data = faceOverlay,
                        status = overlayStatus,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = viewModel::toggleLight) {
                        Text(
                            stringResource(R.string.gate_fill_light) +
                                if (lightOn) " ✓" else "",
                        )
                    }
                    if (lightOn) {
                        Slider(
                            value = lightLevel,
                            onValueChange = viewModel::setLightLevel,
                            valueRange = 0.1f..1f,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val hint =
                    when {
                        faceOverlay == null -> stringResource(R.string.gate_wait_face)
                        faceOverlay?.faceFound == true -> stringResource(R.string.gate_preview_ok)
                        else -> stringResource(R.string.gate_wait_face)
                    }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Button(
                    onClick = viewModel::startCountdown,
                    enabled = !countdownStarted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.gate_start_button))
                }
                OutlinedButton(
                    onClick = viewModel::abandon,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.gate_not_now))
                }
                Text(
                    text = stringResource(R.string.gate_camera_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else {
            // ---- Phase 2: the countdown (camera hidden, detection keeps running) ----
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.gate_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.gate_subtitle, totalSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )

                Box(contentAlignment = Alignment.Center) {
                    CountdownRing(
                        progress =
                            when (val s = state) {
                                is GateState.Watching -> s.heldMillis / (totalSeconds * 1000f)
                                else -> 0f
                            },
                        modifier = Modifier.fillMaxWidth(0.6f),
                    )
                    val centerText =
                        when (val s = state) {
                            is GateState.Watching -> stringResource(R.string.gate_remaining_seconds, (totalSeconds - s.heldMillis / 1000).toInt())
                            else -> stringResource(R.string.gate_remaining_seconds, totalSeconds)
                        }
                    Text(text = centerText, color = Color.White, fontSize = 40.sp)
                }

                val (messageRes, showRetry) =
                    when (val s = state) {
                        is GateState.Watching ->
                            if (s.attentionHeld) R.string.gate_keep_watching to false else R.string.gate_wait_face to false

                        is GateState.Stopped ->
                            when (s.reason) {
                                GateStopReason.FACE_LOST -> R.string.gate_stopped_face to true
                                GateStopReason.FOCUS_LOST -> R.string.gate_stopped_focus to true
                                GateStopReason.SCREEN_OFF -> R.string.gate_stopped_screen to true
                                GateStopReason.CAMERA_ERROR -> R.string.gate_stopped_camera_error to true
                            }

                        GateState.Completed -> R.string.gate_completed to false
                        else -> R.string.gate_wait_face to false
                    }
                Text(
                    text = stringResource(messageRes),
                    color = if (state is GateState.Stopped) MaterialTheme.colorScheme.error else Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
                )

                if (showRetry) {
                    Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.gate_retry))
                    }
                }
                if (state is GateState.Stopped && (state as GateState.Stopped).reason == GateStopReason.CAMERA_ERROR) {
                    TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.gate_open_settings)) }
                }
                OutlinedButton(
                    onClick = viewModel::abandon,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.gate_not_now))
                }
                Text(
                    text = stringResource(R.string.gate_camera_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
