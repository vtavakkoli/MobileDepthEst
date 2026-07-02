package com.ai.mob_dep

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OfflineDepthActivity : AppCompatActivity() {

    private lateinit var sourceImage: ImageView
    private lateinit var depthImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var metricText: TextView
    private lateinit var scaleText: TextView
    private lateinit var dimensionText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var meshView: DepthMeshView

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var estimator: DepthEstimator? = null
    private var selectedBitmap: Bitmap? = null
    private var lastResult: DepthResult? = null
    private var ratioMeters = 3.0f

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bitmap = contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        setSourceBitmap(bitmap)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap ?: return@registerForActivityResult
        setSourceBitmap(bitmap)
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null) else updateStatus("Camera permission denied. Use Gallery instead.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_depth)
        bindViews()
        configureActions()
        updateScaleText()

        estimator = DepthEstimator(applicationContext) { message ->
            runOnUiThread { updateStatus(message) }
        }
    }

    private fun bindViews() {
        sourceImage = findViewById(R.id.sourceImage)
        depthImage = findViewById(R.id.depthImage)
        statusText = findViewById(R.id.statusText)
        metricText = findViewById(R.id.metricText)
        scaleText = findViewById(R.id.scaleText)
        dimensionText = findViewById(R.id.dimensionText)
        progressBar = findViewById(R.id.progressBar)
        meshView = findViewById(R.id.meshView)
    }

    private fun configureActions() {
        findViewById<MaterialButton>(R.id.pickButton).setOnClickListener {
            galleryLauncher.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.captureButton).setOnClickListener {
            openCamera()
        }
        findViewById<Slider>(R.id.ratioSlider).addOnChangeListener { _, value, _ ->
            ratioMeters = value
            updateScaleText()
            lastResult?.let { result ->
                updateMetricText(result.copyWithScale(ratioMeters))
                meshView.setDepth(result.depth01, result.width, result.height, ratioMeters)
            }
        }
    }

    private fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) cameraLauncher.launch(null) else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setSourceBitmap(bitmap: Bitmap) {
        selectedBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        selectedBitmap = bitmap
        sourceImage.setImageBitmap(bitmap)
        depthImage.setImageDrawable(null)
        meshView.clear()
        updateStatus("Image loaded. Running LiteRT depth estimation…")
        runDepthEstimation(bitmap)
    }

    private fun runDepthEstimation(bitmap: Bitmap) {
        setBusy(true)
        executor.execute {
            try {
                val localEstimator = estimator ?: return@execute
                val start = System.nanoTime()
                val result = localEstimator.estimate(bitmap, ratioMeters)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                runOnUiThread {
                    lastResult = result
                    depthImage.setImageBitmap(result.depthBitmap)
                    updateMetricText(result)
                    meshView.setDepth(result.depth01, result.width, result.height, ratioMeters)
                    updateStatus("Depth ready in ${elapsedMs} ms using ${result.accelerator}.")
                    setBusy(false)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    updateStatus("Depth estimation failed: ${error.message ?: error.javaClass.simpleName}")
                    setBusy(false)
                }
            }
        }
    }

    private fun updateScaleText() {
        scaleText.text = "Depth ratio: ${formatMeters(ratioMeters)} m"
    }

    private fun updateMetricText(result: DepthResult) {
        val calibrated = result.copyWithScale(ratioMeters)
        metricText.text = "Estimated metric range: ${formatMeters(calibrated.minMeters)} m – ${formatMeters(calibrated.maxMeters)} m · Center: ${formatMeters(calibrated.centerMeters)} m"
        dimensionText.text = "3D view uses X/Y image position and Z = normalized depth × ${formatMeters(ratioMeters)} m."
    }

    private fun DepthResult.copyWithScale(scale: Float): DepthResult {
        val centerIndex = (height / 2) * width + (width / 2)
        return copy(
            minMeters = 0f,
            maxMeters = scale,
            centerMeters = (depth01.getOrNull(centerIndex) ?: 0f) * scale
        )
    }

    private fun setBusy(isBusy: Boolean) {
        progressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.pickButton).isEnabled = !isBusy
        findViewById<MaterialButton>(R.id.captureButton).isEnabled = !isBusy
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun formatMeters(value: Float): String = String.format(Locale.US, "%.2f", value)

    override fun onDestroy() {
        super.onDestroy()
        estimator?.close()
        executor.shutdownNow()
    }
}
