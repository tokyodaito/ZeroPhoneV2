package com.numenlabs.zerophonev2.gate

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.numenlabs.zerophonev2.R
import com.numenlabs.zerophonev2.ZeroPhoneApp
import com.numenlabs.zerophonev2.core.gate.AttentionPolicy
import com.numenlabs.zerophonev2.ui.components.FaceOverlayCanvas
import com.numenlabs.zerophonev2.ui.components.FaceOverlayStatus
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Entry gate for PROTECTED apps (e.g. banking): system biometrics first
 * (fingerprint / face, hardware-backed BiometricPrompt), then a short ~3 s
 * on-camera face presence check. On success the app is unsuspended and
 * launched; the window lives until the app is minimized (per-session).
 * Degrades gracefully: no camera / no permission → biometrics alone.
 */
class BiometricGateActivity : FragmentActivity() {
    private enum class Phase { Biometric, FaceCheck }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val phaseState = mutableStateOf(Phase.Biometric)
    private val faceOverlayState = mutableStateOf<FaceUiData?>(null)
    private val attendedMillisState = mutableStateOf(0L)
    private val appNameState = mutableStateOf("")

    private var targetPackage: String? = null
    private var promptShown = false
    private var succeeded = false
    private var analyzer: FaceAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var previewView: PreviewView
    private val attention = AttentionPolicy(presentAfterFrames = 3, lostAfterMillis = 1_500)
    private var attentionSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        appNameState.value =
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(targetPackage!!, 0),
                ).toString()
            } catch (_: Exception) {
                targetPackage!!
            }
        previewView =
            PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }

        setContentSafely()

        // Run the biometric prompt as soon as we are visible (once).
        if (savedInstanceState == null) {
            promptShown = true
            window.decorView.post { showBiometricPrompt() }
        }
    }

    private fun setContentSafely() {
        setContent {
            MaterialTheme(
                colorScheme =
                    androidx.compose.material3.darkColorScheme(
                        primary = Color.White,
                        background = Color.Black,
                        surface = Color.Black,
                        onSurface = Color.White,
                    ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    BiometricGateContent()
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        val canBiometric =
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (canBiometric != BiometricManager.BIOMETRIC_SUCCESS) {
            // Nothing enrolled / hardware absent — fall through to the face check.
            startFaceCheck()
            return
        }
        val prompt =
            BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        startFaceCheck()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        finish() // user cancelled / locked out — no entry.
                    }
                },
            )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biogate_title))
                .setSubtitle(appNameState.value)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    private fun startFaceCheck() {
        phaseState.value = Phase.FaceCheck
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else {
            // Graceful degrade: biometrics already passed — camera not required.
            onVerified()
        }
    }

    private fun bindCamera() {
        val analyzer =
            analyzer ?: FaceAnalyzer { frame ->
                mainHandler.post { onFaceFrame(frame) }
            }.also { analyzer = it }
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (_: Exception) {
                    onVerified() // camera failed — biometrics already passed.
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun onFaceFrame(frame: FaceUiData) {
        if (succeeded) return
        faceOverlayState.value = frame
        val looking = frame.faceFound && frame.eyesOpenEnough
        val now = SystemClock.elapsedRealtime()
        when (attention.onFrame(now, looking)) {
            AttentionPolicy.Event.Present -> attentionSince = now

            AttentionPolicy.Event.Lost -> {
                attentionSince = 0L
                attendedMillisState.value = 0L
            }

            null -> Unit
        }
        if (attention.isPresent && attentionSince > 0L) {
            val attended = now - attentionSince
            attendedMillisState.value = attended.coerceAtMost(FACE_CHECK_MILLIS)
            if (attended >= FACE_CHECK_MILLIS) onVerified()
        }
    }

    private fun onVerified() {
        if (succeeded) return
        succeeded = true
        val pkg = targetPackage ?: return
        val app = applicationContext as ZeroPhoneApp
        lifecycleScope.launch {
            try {
                app.container.engine.openGrant(pkg)
            } catch (_: Exception) {
            }
            try {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            } catch (_: Exception) {
            }
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (phaseState.value == Phase.FaceCheck && analyzer != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        }
    }

    override fun onStop() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        analyzer?.close()
        analyzer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        super.onDestroy()
    }

    @Composable
    private fun BiometricGateContent() {
        val phase by phaseState
        val faceOverlay by faceOverlayState
        val attended by attendedMillisState
        val appName by appNameState

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.biogate_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = appName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            if (phase == Phase.Biometric) {
                Text(
                    text = stringResource(R.string.biogate_wait_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.55f)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF101010)),
                ) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    FaceOverlayCanvas(
                        data = faceOverlay,
                        status =
                            if (attended > 0) FaceOverlayStatus.ATTENTION else FaceOverlayStatus.NO_FACE,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                val secondsLeft = ((FACE_CHECK_MILLIS - attended) / 1000).coerceAtLeast(0)
                Text(
                    text = stringResource(R.string.biogate_face_progress, secondsLeft),
                    color = Color.White,
                    fontSize = 20.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = { finish() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.gate_not_now)) }
        }
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val FACE_CHECK_MILLIS = 3_000L

        fun start(
            context: Context,
            packageName: String,
        ) {
            try {
                context.startActivity(
                    Intent(context, BiometricGateActivity::class.java)
                        .putExtra(EXTRA_PACKAGE_NAME, packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            } catch (_: Exception) {
            }
        }
    }
}
