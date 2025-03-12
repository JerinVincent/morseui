package com.example.myapplication.composables

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.util.concurrent.ExecutionException

@Composable
fun CameraPreviewScreen(
    onCameraControlReady: (CameraControl) -> Unit,
    onTextDecoded: (Triple<String, String, List<Long>>) -> Unit, // Updated to include durations
    shouldCapture: Boolean
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

    var roiWidth by remember { mutableStateOf(0f) }
    var roiHeight by remember { mutableStateOf(0f) }
    var roiX by remember { mutableStateOf(0f) }
    var roiY by remember { mutableStateOf(0f) }

    LaunchedEffect(lensFacing, flashDurations) {
        if (flashDurations.isNotEmpty() && shouldCapture) {
            try {
                val (morse, text) = decodeMorse(flashDurations)
                detectedMorse = morse
                decodedText = text
                Log.d("MorseDetection", "Durations: $flashDurations, Morse: $morse, Decoded: $text")
                onTextDecoded(Triple(morse, text, flashDurations)) // Pass durations along
                flashDurations = emptyList() // Reset after decoding
            } catch (e: Exception) {
                Log.e("MorseDetection", "Error decoding Morse: ${e.message}", e)
            }
        }
    }

    LaunchedEffect(lensFacing, shouldCapture) {
        try {
            val cameraProvider = context.getCameraProvider()
            cameraProvider.unbindAll()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                        try {
                            val (brightness, roi) = analyzeBrightness(imageProxy)
                            brightnessLevel = brightness
                            roiWidth = roi.width.toFloat()
                            roiHeight = roi.height.toFloat()
                            roiX = roi.x.toFloat()
                            roiY = roi.y.toFloat()

                            Log.d("MorseDetection", "Brightness: $brightness, ROI: x=$roiX, y=$roiY, w=$roiWidth, h=$roiHeight")

                            if (shouldCapture) {
                                val onThreshold = 50.0 // Lowered for better sensitivity
                                val offThreshold = 30.0
                                val debounceDuration = 50L
                                val currentTime = System.currentTimeMillis()

                                if (brightness > onThreshold && !isFlashOn) {
                                    flashStartTime = currentTime
                                    isFlashOn = true
                                    flashEndCandidateTime = null
                                    Log.d("MorseDetection", "Flash ON at $currentTime, brightness: $brightness")
                                } else if (brightness < offThreshold && isFlashOn && flashStartTime != null) {
                                    if (flashEndCandidateTime == null) {
                                        flashEndCandidateTime = currentTime
                                    } else if (currentTime - flashEndCandidateTime!! > debounceDuration) {
                                        val duration = flashEndCandidateTime!! - flashStartTime!!
                                        if (duration > 50L) {
                                            flashDurations = flashDurations + duration
                                            Log.d("MorseDetection", "Flash OFF, duration: $duration, brightness: $brightness")
                                        }
                                        flashStartTime = null
                                        isFlashOn = false
                                        flashEndCandidateTime = null
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MorseDetection", "Error analyzing image: ${e.message}", e)
                        } finally {
                            imageProxy.close()
                        }
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
            cameraControl?.let {
                onCameraControlReady(it)
                Log.d("CameraPreview", "Camera control ready")
            } ?: Log.w("CameraPreview", "Camera control is null")
        } catch (e: Exception) {
            Log.e("CameraPreview", "Failed to initialize camera: ${e.message}", e)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val newZoom = (currentZoom * zoomChange).coerceIn(1f, maxZoom)
                    if (newZoom != currentZoom) {
                        currentZoom = newZoom
                        cameraControl?.setZoomRatio(newZoom)?.addListener(
                            { Log.d("CameraPreview", "Zoom set to $newZoom") },
                            ContextCompat.getMainExecutor(context)
                        ) ?: Log.w("CameraPreview", "Camera control null during zoom")
                    }
                }
            }
            .clickable { if (!isFullScreen) isFullScreen = true }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (roiWidth > 0 && roiHeight > 0) {
                // Center the ROI on the screen
                val scaleX = size.width / 640f
                val scaleY = size.height / 480f
                val scaledWidth = roiWidth * scaleX
                val scaledHeight = roiHeight * scaleY
                val scaledX = (size.width - scaledWidth) / 2 // Center horizontally
                val scaledY = (size.height - scaledHeight) / 2 // Center vertically

                Log.d("MorseDetection", "Drawing ROI: scaledX=$scaledX, scaledY=$scaledY, scaledW=$scaledWidth, scaledH=$scaledHeight, Canvas size=${size.width}x${size.height}")

                drawRect(
                    color = Color.Red.copy(alpha = 0.5f),
                    topLeft = Offset(scaledX, scaledY),
                    size = Size(scaledWidth, scaledHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )
            } else {
                Log.w("MorseDetection", "ROI not drawn: width=$roiWidth, height=$roiHeight")
            }
        }

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

private fun analyzeBrightness(imageProxy: ImageProxy): Pair<Double, Rect> {
    try {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val width = imageProxy.width
        val height = imageProxy.height
        Log.d("MorseDetection", "Image size: ${width}x${height}")

        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)

        val roiSize = minOf(width, height) / 2
        val roiX = (width - roiSize) / 2
        val roiY = (height - roiSize) / 2
        val roi = Rect(roiX, roiY, roiSize, roiSize)

        val roiMat = mat.submat(roi)
        val grayMat = Mat()
        Imgproc.cvtColor(roiMat, grayMat, Imgproc.COLOR_YUV2GRAY_420)

        val mean = Core.mean(grayMat).`val`[0]

        mat.release()
        roiMat.release()
        grayMat.release()

        return Pair(mean, roi)
    } catch (e: Exception) {
        Log.e("MorseDetection", "Error in analyzeBrightness: ${e.message}", e)
        return Pair(0.0, Rect(0, 0, 0, 0))
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { cameraProvider ->
            cameraProvider.addListener({
                try {
                    continuation.resume(cameraProvider.get())
                } catch (e: ExecutionException) {
                    Log.e("CameraPreview", "Failed to get camera provider: ${e.message}", e)
                    continuation.resumeWith(Result.failure(e))
                }
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

    val symbols = mutableListOf<String>()
    var lastDurationEnd = durations.firstOrNull() ?: 0L

    durations.forEachIndexed { index, duration ->
        val symbol = when {
            duration <= dotThreshold -> "."
            duration <= dashThreshold -> "-"
            else -> ""
        }
        if (symbol.isNotEmpty()) symbols.add(symbol)

        if (index < durations.size - 1) {
            val gap = durations[index + 1] - (lastDurationEnd + duration)
            when {
                gap >= wordPauseThreshold -> symbols.add("//")
                gap >= letterPauseThreshold -> symbols.add("/")
            }
        }
        lastDurationEnd += duration
    }

    val morseCode = symbols.joinToString("")
    val text = morseCode.split("//").joinToString(" ") { word ->
        word.split("/").joinToString("") { morseToText[it] ?: "" }
    }.trim()

    return Pair(morseCode, text)
}