package com.adas.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DualCameraManager(private val context: Context) {
    companion object {
        private const val TAG = "DualCameraManager"
        private val ANALYSIS_SIZE = Size(640, 480)
        private const val SKIP = 3  // process every Nth frame
    }

    private var provider: ProcessCameraProvider? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)

    var onFrontFrame: ((Bitmap, Long) -> Unit)? = null
    var onRearFrame:  ((Bitmap, Long) -> Unit)? = null

    var frontEnabled = true
    var rearEnabled  = true

    private var nFront = 0; private var nRear = 0

    fun start(owner: LifecycleOwner, frontPV: PreviewView?, rearPV: PreviewView?) {
        ProcessCameraProvider.getInstance(context).addListener({
            provider = ProcessCameraProvider.getInstance(context).get()
            bind(owner, frontPV, rearPV)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(owner: LifecycleOwner, frontPV: PreviewView?, rearPV: PreviewView?) {
        val p = provider ?: return
        try {
            p.unbindAll()

            // ── Back camera (road ahead) ─────────────────────────────────
            if (frontEnabled) {
                val previewUC = frontPV?.let {
                    Preview.Builder().build().also { pr -> pr.setSurfaceProvider(it.surfaceProvider) }
                }
                val analysisUC = buildAnalysis { bmp ->
                    nFront++
                    if (nFront % SKIP == 0) onFrontFrame?.invoke(bmp, System.currentTimeMillis())
                }
                val useCases = listOfNotNull(previewUC, analysisUC)
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                Log.i(TAG, "Back camera bound")
            }

            // ── Front camera (rear traffic) ──────────────────────────────
            if (rearEnabled) {
                try {
                    val previewUC = rearPV?.let {
                        Preview.Builder().build().also { pr -> pr.setSurfaceProvider(it.surfaceProvider) }
                    }
                    val analysisUC = buildAnalysis { bmp ->
                        nRear++
                        if (nRear % SKIP == 0) onRearFrame?.invoke(bmp, System.currentTimeMillis())
                    }
                    val useCases = listOfNotNull(previewUC, analysisUC)
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases.toTypedArray())
                    Log.i(TAG, "Front camera (rear-view) bound")
                } catch (e: Exception) {
                    Log.w(TAG, "Front camera unavailable: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind error: ${e.message}")
        }
    }

    private fun buildAnalysis(onFrame: (Bitmap) -> Unit): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(ANALYSIS_SIZE)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().also { analysis ->
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val buffer = proxy.planes[0].buffer
                        val bytes  = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                        onFrame(bmp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame error: ${e.message}")
                    } finally {
                        proxy.close()
                    }
                }
            }
    }

    fun rebind(owner: LifecycleOwner, frontPV: PreviewView?, rearPV: PreviewView?) {
        provider?.unbindAll()
        bind(owner, frontPV, rearPV)
    }

    fun stop() {
        provider?.unbindAll()
        if (!executor.isShutdown) executor.shutdown()
    }
}
