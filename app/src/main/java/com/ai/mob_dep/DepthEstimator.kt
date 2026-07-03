package com.ai.mob_dep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * LiteRT depth estimation wrapper for litert-community/Depth-Anything-3-Small.
 *
 * Model contract:
 *   Input:  [1, 3, 896, 504] float32 NCHW, RGB, ImageNet normalized
 *   Output: [1, 1, 896, 504] float32 relative depth
 */
class DepthEstimator(
    private val context: Context,
    private val progress: (String) -> Unit = {}
) : AutoCloseable {

    private var compiledModel: CompiledModel? = null
    private var activeAccelerator: String = "GPU"

    fun estimate(source: Bitmap, ratioMeters: Float): DepthResult {
        val model = compiledModel ?: loadModel().also { compiledModel = it }
        val input = preprocess(source)
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()

        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        val rawDepth = outputs[0].readFloat()

        return postprocess(rawDepth, ratioMeters, activeAccelerator)
    }

    private fun loadModel(): CompiledModel {
        val modelFile = ensureModelFile()
        progress("Compiling LiteRT model with GPU accelerator…")
        return try {
            activeAccelerator = "GPU"
            CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.GPU),
                null
            )
        } catch (gpuError: Throwable) {
            progress("GPU compile failed, using CPU fallback: ${gpuError.message ?: gpuError.javaClass.simpleName}")
            activeAccelerator = "CPU"
            CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.CPU),
                null
            )
        }
    }

    private fun ensureModelFile(): File {
        val modelFile = File(context.filesDir, MODEL_FILE)
        if (modelFile.exists() && modelFile.length() > 50L * 1024L * 1024L) {
            progress("Model ready: ${modelFile.name} (${modelFile.length() / 1024 / 1024} MB)")
            return modelFile
        }

        if (copyBundledAssetIfPresent(modelFile)) {
            progress("Model copied from app assets.")
            return modelFile
        }

        progress("Downloading Depth Anything 3 Small LiteRT model…")
        downloadModel(modelFile)
        return modelFile
    }

    private fun copyBundledAssetIfPresent(target: File): Boolean {
        return try {
            context.assets.open(MODEL_FILE).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            target.exists() && target.length() > 50L * 1024L * 1024L
        } catch (_: Throwable) {
            false
        }
    }

    private fun downloadModel(target: File) {
        val tmp = File(target.parentFile, "$MODEL_FILE.download")
        if (tmp.exists()) tmp.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} while downloading model")
            }

            val total = connection.contentLengthLong.takeIf { it > 0L } ?: MODEL_SIZE_APPROX
            var downloaded = 0L
            var lastMb = -1L

            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val mb = downloaded / 1024L / 1024L
                        if (mb != lastMb) {
                            lastMb = mb
                            val percent = ((downloaded * 100f) / max(1L, total)).coerceIn(0f, 100f)
                            progress("Downloading model: $mb MB · ${"%.0f".format(percent)}%")
                        }
                    }
                }
            }

            if (tmp.length() < 50L * 1024L * 1024L) {
                throw IllegalStateException("Downloaded file is too small: ${tmp.length()} bytes")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            progress("Model downloaded successfully.")
        } finally {
            connection.disconnect()
        }
    }

    private fun preprocess(source: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(source, INPUT_WIDTH, INPUT_HEIGHT, true)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        if (scaled !== source) scaled.recycle()

        val input = FloatArray(3 * INPUT_WIDTH * INPUT_HEIGHT)
        val channelSize = INPUT_WIDTH * INPUT_HEIGHT
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            input[i] = (r - MEAN[0]) / STD[0]
            input[channelSize + i] = (g - MEAN[1]) / STD[1]
            input[2 * channelSize + i] = (b - MEAN[2]) / STD[2]
        }
        return input
    }

    private fun postprocess(raw: FloatArray, ratioMeters: Float, accelerator: String): DepthResult {
        val expected = INPUT_WIDTH * INPUT_HEIGHT
        val values = if (raw.size >= expected) raw.copyOfRange(0, expected) else raw.copyOf(expected)

        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isFinite()) {
                minValue = min(minValue, v)
                maxValue = max(maxValue, v)
            }
        }
        if (!minValue.isFinite() || !maxValue.isFinite() || maxValue <= minValue) {
            minValue = 0f
            maxValue = 1f
        }

        val normalized = FloatArray(expected)
        val colors = IntArray(expected)
        val range = max(1e-6f, maxValue - minValue)
        for (i in 0 until expected) {
            val depth01 = ((values[i] - minValue) / range).coerceIn(0f, 1f)
            normalized[i] = depth01
            colors[i] = depthToColor(depth01)
        }

        val depthBitmap = Bitmap.createBitmap(colors, INPUT_WIDTH, INPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        val centerIndex = (INPUT_HEIGHT / 2) * INPUT_WIDTH + (INPUT_WIDTH / 2)
        val centerMeters = normalized[centerIndex] * ratioMeters

        return DepthResult(
            depth01 = normalized,
            depthBitmap = depthBitmap,
            width = INPUT_WIDTH,
            height = INPUT_HEIGHT,
            minMeters = 0f,
            maxMeters = ratioMeters,
            centerMeters = centerMeters,
            accelerator = accelerator
        )
    }

    private fun depthToColor(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val r = (255f * smoothBand(x, 0.42f, 0.95f)).toInt().coerceIn(0, 255)
        val g = (255f * smoothBand(x, 0.12f, 0.78f)).toInt().coerceIn(0, 255)
        val b = (255f * (1f - smoothBand(x, 0.35f, 0.92f))).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun smoothBand(x: Float, low: Float, high: Float): Float {
        if (x <= low) return 0f
        if (x >= high) return 1f
        val t = (x - low) / (high - low)
        return t * t * (3f - 2f * t)
    }

    override fun close() {
        compiledModel?.close()
        compiledModel = null
    }

    companion object {
        const val INPUT_WIDTH = 504
        const val INPUT_HEIGHT = 896
        const val MODEL_FILE = "da3_small_gpu_fp16.tflite"
        const val MODEL_URL = "https://huggingface.co/litert-community/Depth-Anything-3-Small/resolve/main/da3_small_gpu_fp16.tflite"
        const val MODEL_SIZE_APPROX = 55_700_000L
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

data class DepthResult(
    val depth01: FloatArray,
    val depthBitmap: Bitmap,
    val width: Int,
    val height: Int,
    val minMeters: Float,
    val maxMeters: Float,
    val centerMeters: Float,
    val accelerator: String
)
