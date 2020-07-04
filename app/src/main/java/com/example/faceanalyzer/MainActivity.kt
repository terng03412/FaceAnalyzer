package com.example.faceanalyzer

import android.Manifest
import android.content.pm.PackageManager

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA , Manifest.permission.WRITE_EXTERNAL_STORAGE )
    private lateinit var cameraTextureView : TextureView


    private lateinit var faceDetect : FaceDetect

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraTextureView = findViewById(R.id.camera_textureView )
        if (allPermissionsGranted()) {
            cameraTextureView.post { startCamera() }
        }
        else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        cameraTextureView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        val boundingBoxOverlay = findViewById<BoundingBoxOverlay>(R.id.bbox_overlay )


        // Necessary to keep the Overlay above the TextureView so that the boxes are visible.
        boundingBoxOverlay.setWillNotDraw( false )
        boundingBoxOverlay.setZOrderOnTop( true )
        faceDetect = FaceDetect(this, boundingBoxOverlay)

    }

    // Start the camera preview once the permissions are granted.
    private fun startCamera() {

        var cameraLens =  CameraX.LensFacing.BACK
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(480*4, 640*4 ))
            setLensFacing( cameraLens)
        }.build()
        val preview = Preview(previewConfig)
        preview.setOnPreviewOutputUpdateListener {
            val parent = cameraTextureView.parent as ViewGroup
            parent.removeView(cameraTextureView)
            parent.addView(cameraTextureView, 0)
            val surfaceTexture: SurfaceTexture = it.surfaceTexture
            cameraTextureView.surfaceTexture = surfaceTexture
            updateTransform()
        }

        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            setImageReaderMode(
                    ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            setLensFacing( cameraLens )

        }.build()

        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            setAnalyzer( Executors.newSingleThreadExecutor() , faceDetect )
        }

        CameraX.bindToLifecycle(this, preview, analyzerUseCase )

    }
    private fun updateTransform() {
        val matrix = Matrix()
        val centerX = cameraTextureView.width.div(2f)
        val centerY = cameraTextureView.height.div(2f)
        val rotationDegrees = when(cameraTextureView.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX , centerY )
        cameraTextureView.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                cameraTextureView.post { startCamera() }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission( baseContext, it ) == PackageManager.PERMISSION_GRANTED
    }


}