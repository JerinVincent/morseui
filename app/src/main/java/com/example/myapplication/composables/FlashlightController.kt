package com.example.myapplication.composables

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutionException

class FlashlightController(private val context: Context) {

    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var cameraProvider: ProcessCameraProvider? = null

    init {
        Log.d("FlashTransmit", "FlashlightController initialized")
        initializeCameraControl()
    }

    private fun initializeCameraControl() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val camera = cameraProvider?.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA
                )
                cameraControl = camera?.cameraControl
                Log.d("FlashTransmit", "Camera bound successfully: ${cameraControl != null}")
            } catch (e: ExecutionException) {
                Log.e("FlashTransmit", "Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun turnOnFlashlight() {
        cameraControl?.enableTorch(true) ?: Log.w("FlashTransmit", "Failed to turn on flashlight: cameraControl is null")
    }

    fun turnOffFlashlight() {
        cameraControl?.enableTorch(false) ?: Log.w("FlashTransmit", "Failed to turn off flashlight: cameraControl is null")
    }

    fun transmitMorseCode(morseCode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("FlashTransmit", "Starting transmission with Morse: '$morseCode'")
            delay(1000) // Initial delay to ensure receiver is ready
            var flashCount = 0
            for (char in morseCode) {
                Log.d("FlashTransmit", "Processing character: '$char'")
                when (char) {
                    '.' -> {
                        blink(200) // Increased from 100ms to 200ms
                        flashCount++
                    }
                    '-' -> {
                        blink(600) // Increased from 400ms to 600ms
                        flashCount++
                    }
                    ' ' -> delay(1000) // Increased from 700ms to 1000ms
                }
            }
            Log.d("FlashTransmit", "Transmission complete. Total flashes: $flashCount")
        }
    }

    private suspend fun blink(duration: Long) {
        turnOnFlashlight()
        Log.d("FlashTransmit", "Flash ON for ${duration}ms")
        delay(duration)
        turnOffFlashlight()
        Log.d("FlashTransmit", "Flash OFF for 400ms")
        delay(400) // Increased from 200ms to 400ms for clearer gaps
    }
}