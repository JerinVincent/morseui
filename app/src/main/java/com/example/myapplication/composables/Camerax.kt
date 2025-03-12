package com.example.myapplication.composables

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
//import android.graphics.Rect
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.compose.material3.Text
import kotlin.math.abs
import androidx.compose.ui.graphics.Brush
import org.opencv.core.Mat.*
import org.opencv.core.*
import kotlin.math.max


@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewScreen(onCameraControlReady: (CameraControl) -> Unit) {
    val lensFacing = CameraSelector.LENS_FACING_BACK
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val preview = Preview.Builder().build()
    val previewView = remember { PreviewView(context) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    // Zoom
    var currentZoom by remember { mutableFloatStateOf(1.5f) }
    val maxZoom = 6f  // Adjust based on camera capabilities

    // ROI size
    var roiX by remember { mutableFloatStateOf(0f) }
    var roiY by remember { mutableFloatStateOf(0f) }
    var roiSize by remember { mutableFloatStateOf(0f) }
    var roiSizeRatio by remember { mutableIntStateOf(3) } // increase to make ROI smaller

    var brightness by remember { mutableDoubleStateOf(0.0) }
    var flashStartTime by remember { mutableStateOf<Long?>(null) }
    var flashEndTime by remember { mutableStateOf<Long?>(null) }
    var flashEndCandidateTime by remember { mutableStateOf<Long?>(null) }
    var flashDurations by remember { mutableStateOf(listOf<Long>()) }
    var isFlashOn by remember { mutableStateOf(false) }
    var previousBrightness by remember { mutableDoubleStateOf(0.0) }

    var maxTemporalDelta by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = context.getCameraProvider()
        cameraProvider.unbindAll()
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    CoroutineScope(Dispatchers.Default).launch {
                        val result = analyzeBrightness(imageProxy, roiSizeRatio)
                        brightness = result.brightness
                        roiX = result.roiX.toFloat()
                        roiY = result.roiY.toFloat()
                        roiSize = result.roiSize.toFloat()

                        if(previousBrightness == 0.0){
                            previousBrightness = brightness
                        }

                        Log.d("MyTag", "b: $brightness")

                        val temporalDelta = brightness - previousBrightness
                        maxTemporalDelta = max(maxTemporalDelta,temporalDelta)

                        Log.d("MyTag", "temporalDelta: $temporalDelta")
                        Log.d("MyTag", "pb: $previousBrightness")

                        previousBrightness = brightness

                        val deltaThreshold = 20.0
                        //val currentTime = System.currentTimeMillis()

                        Log.d("MyTag", "maxTemporal: $maxTemporalDelta ")

                        Log.d("MyTag","$flashDurations")
                        if (abs(temporalDelta) > deltaThreshold) {
                            val currentTime = System.currentTimeMillis()


                            if (temporalDelta > deltaThreshold && !isFlashOn) {
                                // Ensure enough time has passed since last flash-off to prevent false triggers
                                flashEndTime?.let { endTime ->
                                    val offDuration = currentTime - endTime
                                    if (offDuration > 200) { // Only register a new flash if off duration is significant
                                        flashDurations = flashDurations + (-offDuration)
                                        flashStartTime = currentTime
                                        isFlashOn = true
                                    }
                                } ?: run { // If there was no previous flash-off, register normally
                                    flashStartTime = currentTime
                                    isFlashOn = true
                                }

                            } else if (temporalDelta < -deltaThreshold && isFlashOn) {
                                // Flash ends
                                flashStartTime?.let { startTime ->
                                    val onDuration = currentTime - startTime
                                    flashDurations = flashDurations + onDuration
                                }

                                flashEndTime = currentTime
                                isFlashOn = false
                            }
                        }

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
        camera.cameraInfo.exposureState?.let { exposureState ->
            Log.d("CameraExposure", "Exposure compensation range: ${exposureState.exposureCompensationRange}")
            Log.d("CameraExposure", "Current exposure compensation: ${exposureState.exposureCompensationIndex}")

            val minExposure = exposureState.exposureCompensationRange.lower
            val maxExposure = exposureState.exposureCompensationRange.upper

            val targetExposure = 0 // Adjust this value if needed
            val clampedExposure = targetExposure.coerceIn(minExposure, maxExposure)

            cameraControl?.setExposureCompensationIndex(clampedExposure)
        }
        cameraControl?.let {
            val camera2Control = Camera2CameraControl.from(it)
            camera2Control.captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 100) // Lock ISO to 100
                .build()
        }


        cameraControl?.setZoomRatio(currentZoom)
        preview.setSurfaceProvider(previewView.surfaceProvider)
        cameraControl?.let(onCameraControlReady)
    }

    // The Box will now always fill the parent (Card) size
    Box(
        modifier = Modifier
            .fillMaxSize() // Default to full size within the Card
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val newZoom = (currentZoom * zoomChange).coerceIn(1f, maxZoom)
                    if (newZoom != currentZoom) {
                        currentZoom = newZoom
                        cameraControl?.setZoomRatio(newZoom)
                    }
                }
            }
    )
    {
        AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())

        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(
                color = Color.Red,
                topLeft = Offset(roiX, roiY),
                size = Size(roiSize, roiSize),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }

    // Flash detection info displayed below the preview if needed
    Column {
        Text(
            text = if (isFlashOn) "Flash Detected!" else "No Flash Detected (new)",
            color = Color.White
        )
        Text("Brightness: $brightness")
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = "Flash Durations (ms):", color = Color.White)
            flashDurations.forEach { duration ->
                Text(text = "$duration ms", color = Color.White)
            }
        }

    }

}


data class BrightnessResult(val brightness: Double, val roiX: Int, val roiY: Int, val roiSize: Int)
private fun analyzeBrightness(imageProxy: ImageProxy, roiSizeRatio: Int ): BrightnessResult {
    return try {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val width = imageProxy.width
        val height = imageProxy.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_YUV2GRAY_420)

//        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_YUV2BGR)
//        Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // Apply CLAHE (Contrast Limited Adaptive Histogram Equalization) to enhance contrast
        val clahe = Imgproc.createCLAHE(3.0)
        val enhancedMat = Mat()
        clahe.apply(grayMat, enhancedMat)

        // Apply Gaussian Blur to reduce noise
        //Imgproc.GaussianBlur(enhancedMat, enhancedMat, Size(5.0, 5.0), 0.0)
        // Define the larger ROI
        val roiSize = minOf(width, height) / roiSizeRatio

        val roiX = (width - roiSize) / 2
        val roiY = (height - roiSize) / 2

        if (roiX < 0 || roiY < 0 || roiX + roiSize > enhancedMat.cols() || roiY + roiSize > enhancedMat.rows()) {
            val brightness = Core.mean(enhancedMat).`val`[0]
            enhancedMat.release()

            grayMat.release()
            mat.release()
            return BrightnessResult(brightness, roiX, roiY, roiSize)
        }

        val roi = enhancedMat.submat(Rect(roiX, roiY, roiSize, roiSize))

        // Find the brightest pixel in the ROI
        val minMax = Core.minMaxLoc(roi)
        val maxBrightness = minMax.maxVal
        // Convert to 1D array
        val roiPixels = ByteArray(roi.total().toInt())
        roi.get(0, 0, roiPixels)
        // Define threshold (e.g., 70% of max brightness)
        val minBrightnessThreshold = maxBrightness * 0.7
        // Filter pixels above threshold and compute mean
        val brightPixels = roiPixels.filter { it.toInt() and 0xFF > minBrightnessThreshold }
        val avgBrightness = if (brightPixels.isNotEmpty()) {
            brightPixels.sumOf { it.toInt() and 0xFF } / brightPixels.size.toDouble()
        } else {
            0.0
        }
        // Release resources

        roi.release()
        enhancedMat.release()
        grayMat.release()
        mat.release()

        // Return brightness and larger ROI coordinates
        BrightnessResult(avgBrightness, roiX, roiY, roiSize)

    } catch (e: Exception) {
        BrightnessResult(0.0, 0, 0, 0)
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { cameraProvider ->
            cameraProvider.addListener({
                continuation.resume(cameraProvider.get())
            }, ContextCompat.getMainExecutor(this))
        }
    }