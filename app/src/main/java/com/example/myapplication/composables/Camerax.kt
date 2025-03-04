package com.example.myapplication.composables

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraPreviewScreen(
    onCameraControlReady: (CameraControl) -> Unit,
    onTextDecoded: (Pair<String, String>) -> Unit // Updated to Pair<String, String>
) {
    val lensFacing = CameraSelector.LENS_FACING_BACK
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val preview = Preview.Builder().build()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var isFullScreen by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    val maxZoom = 5f

    var brightnessLevel by remember { mutableDoubleStateOf(0.0) }
    var flashStartTime by remember { mutableStateOf<Long?>(null) }
    var flashEndCandidateTime by remember { mutableStateOf<Long?>(null) }
    var flashDurations by remember { mutableStateOf(listOf<Long>()) }
    var isFlashOn by remember { mutableStateOf(false) }
    var detectedMorse by remember { mutableStateOf("") }
    var decodedText by remember { mutableStateOf("") }

    LaunchedEffect(lensFacing, flashDurations) {
        val (morse, text) = decodeMorse(flashDurations)
        detectedMorse = morse
        decodedText = text
        Log.d("MorseDetection", "Durations: $flashDurations, Morse: $detectedMorse, Decoded: $decodedText")
        onTextDecoded(Pair(detectedMorse, decodedText))
    }

    LaunchedEffect(lensFacing) {
        val cameraProvider = context.getCameraProvider()
        cameraProvider.unbindAll()

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val brightness = analyzeBrightness(imageProxy)
                    brightnessLevel = brightness

                    val threshold = 110.0 // Can adjust based on testing
                    val debounceDuration = 100L
                    val currentTime = System.currentTimeMillis()

                    if (brightness > threshold) {
                        flashEndCandidateTime = null
                        if (!isFlashOn) {
                            flashStartTime = currentTime
                            isFlashOn = true
                        }
                    } else {
                        if (isFlashOn && flashStartTime != null) {
                            if (flashEndCandidateTime == null) {
                                flashEndCandidateTime = currentTime
                            } else if (currentTime - flashEndCandidateTime!! > debounceDuration) {
                                val duration = currentTime - flashStartTime!!
                                flashDurations = flashDurations + duration
                                flashStartTime = null
                                isFlashOn = false
                                flashEndCandidateTime = null
                            }
                        }
                    }
                    imageProxy.close()
                }
            }

        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            preview,
            imageAnalyzer
        )

        cameraControl = camera.cameraControl
        preview.setSurfaceProvider(previewView.surfaceProvider)
        cameraControl?.let(onCameraControlReady)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val newZoom = (currentZoom * zoomChange).coerceIn(1f, maxZoom)
                    if (newZoom != currentZoom) {
                        currentZoom = newZoom
                        cameraControl?.setZoomRatio(newZoom)
                    }
                }
            }
            .clickable { if (!isFullScreen) isFullScreen = true }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        if (isFullScreen) {
            IconButton(
                onClick = { isFullScreen = false },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close full screen",
                    tint = Color.White
                )
            }
        }
    }
}

private fun analyzeBrightness(imageProxy: ImageProxy): Double {
    val buffer = imageProxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val width = imageProxy.width
    val height = imageProxy.height

    val mat = Mat(height, width, CvType.CV_8UC1)
    mat.put(0, 0, bytes)

    val grayMat = Mat()
    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_YUV2GRAY_420)

    val roi = Rect(
        grayMat.width() / 4,
        grayMat.height() / 4,
        grayMat.width() / 2,
        grayMat.height() / 2
    )
    val roiMat = grayMat.submat(roi)
    Imgproc.GaussianBlur(roiMat, roiMat, Size(5.0, 5.0), 0.0)
    val thresholdMat = Mat()
    Imgproc.threshold(roiMat, thresholdMat, 110.0, 255.0, Imgproc.THRESH_BINARY)
    return Core.mean(thresholdMat).`val`[0]
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { cameraProvider ->
            cameraProvider.addListener({
                continuation.resume(cameraProvider.get())
            }, ContextCompat.getMainExecutor(this))
        }
    }

private fun decodeMorse(durations: List<Long>): Pair<String, String> {
    val dotThreshold = 200L
    val dashThreshold = 500L
    val letterPauseThreshold = 800L
    val wordPauseThreshold = 1400L

    val morseToText = mapOf(
        ".-" to "A", "-..." to "B", "-.-." to "C", "-.." to "D", "." to "E",
        "..-." to "F", "--." to "G", "...." to "H", ".." to "I", ".---" to "J",
        "-.-" to "K", ".-.." to "L", "--" to "M", "-." to "N", "---" to "O",
        ".--." to "P", "--.-" to "Q", ".-." to "R", "..." to "S", "-" to "T",
        "..-" to "U", "...-" to "V", ".--" to "W", "-..-" to "X", "-.--" to "Y",
        "--.." to "Z", "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3",
        "....-" to "4", "....." to "5", "-...." to "6", "--..." to "7", "---.." to "8",
        "----." to "9"
    )

    val symbols = durations.map { duration ->
        when {
            duration <= dotThreshold -> "."
            duration <= dashThreshold -> "-"
            duration <= letterPauseThreshold -> "/"
            duration <= wordPauseThreshold -> "//"
            else -> ""
        }
    }

    val morseCode = symbols.joinToString("")
    val text = morseCode.split("//").joinToString(" ") { word ->
        word.split("/").joinToString("") { morseToText[it] ?: "" }
    }.trim()

    return Pair(morseCode, text)
}