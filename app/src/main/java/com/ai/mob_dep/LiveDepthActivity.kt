package com.ai.mob_dep

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LiveDepthActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var depthOverlay: ImageView
    private lateinit var scaleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var meshView: DepthMeshView

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var estimator: DepthEstimator? = null
    private var ratioMeters = 3.0f
    private var isProcessing = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            statusText.text = "Camera permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_depth)

        viewFinder = findViewById(R.id.viewFinder)
        depthOverlay = findViewById(R.id.depthOverlay)
        scaleText = findViewById(R.id.liveScaleText)
        statusText = findViewById(R.id.liveStatusText)
        progressBar = findViewById(R.id.liveProgressBar)
        meshView = findViewById(R.id.liveMeshView)

        findViewById<Slider>(R.id.liveRatioSlider).addOnChangeListener { _, value, _ ->
            ratioMeters = value
            scaleText.text = "Depth ratio: ${String.format(Locale.US, "%.2f", ratioMeters)} m"
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        estimator = DepthEstimator(this) { message ->
            runOnUiThread { statusText.text = message }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val bitmap = imageProxy.toBitmap()
        
        // Correct rotation
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        estimator?.let { est ->
            try {
                val result = est.estimate(rotatedBitmap, ratioMeters)
                runOnUiThread {
                    depthOverlay.setImageBitmap(result.depthBitmap)
                    meshView.setDepth(result.depth01, result.width, result.height, ratioMeters)
                    statusText.text = "Live: ${result.accelerator}"
                    isProcessing = false
                    imageProxy.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Estimation failed", e)
                runOnUiThread { isProcessing = false; imageProxy.close() }
            }
        } ?: run {
            isProcessing = false
            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        estimator?.close()
    }

    companion object {
        private const val TAG = "LiveDepthActivity"
    }
}
