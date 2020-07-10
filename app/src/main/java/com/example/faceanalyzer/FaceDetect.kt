package com.example.faceanalyzer

import android.content.Context
import android.graphics.*
import android.media.Image
import android.os.Environment
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.firebase.ml.custom.*
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import io.reactivex.rxkotlin.subscribeBy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean


// Analyser class to process frames and produce detections.
class FaceDetect( context: Context , private var boundingBoxOverlay: BoundingBoxOverlay ) : ImageAnalysis.Analyzer {


    private lateinit var genderClassifier: GenderClassifier

    data class Recognition(
        var id: String = "",
        var title: String = "",
        var confidence: Float = 0F
    )  {
        override fun toString(): String {
            return "Title = $title, Confidence = $confidence)"
        }
    }

    val realTimeOpts: FirebaseVisionFaceDetectorOptions = FirebaseVisionFaceDetectorOptions.Builder()
        .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
        .build()


    private val detector = FirebaseVision.getInstance().getVisionFaceDetector(realTimeOpts)

    // Used to determine whether the incoming frame should be dropped or processed.
    private var isProcessing = AtomicBoolean(false)
    private val metadata = FirebaseVisionImageMetadata.Builder()
        .setWidth(640)
        .setHeight(480)
        .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21 )
        .setRotation(degreesToFirebaseRotation(90))
        .build()

    // Here's where we receive our frames.
    override fun analyze(image: ImageProxy?, rotationDegrees: Int) {

        var imgCount = 0

        // android.media.Image -> android.graphics.Bitmap
        val bitmap = toBitmap( image?.image!! )

        // If the previous frame is still being processed, then skip this frame
        if (isProcessing.get()) {
            return
        }
        else {
            // Declare that the current frame is being processed.
            isProcessing.set(true)

            val inputImage = FirebaseVisionImage.fromByteArray( BitmaptoNv21( bitmap ) , metadata )
            detector.detectInImage(inputImage)
                .addOnSuccessListener { faces ->
                    Thread {
                        val predictions = ArrayList<Prediction>()
                        for (face in faces) {


                            try {
                                var FaceBB = face.boundingBox
                                var faceBitmap = cropRectFromBitmap( bitmap , FaceBB , true )

                                var res = "Unknown"

//                                genderClassifier.recognizeImage(faceBitmap).subscribeBy(
//                                    onSuccess = {
//                                        Log.d("Predict",  "${it.toString()}")
//                                        res = it.toString()
//                                    }
//                                )

                                val filePath: String =
                                    Environment.getExternalStorageDirectory().absolutePath
                                        .toString() +
                                            "/faceOut"
                                val dir = File(filePath)
                                if (!dir.exists()) dir.mkdirs()
                                var filename = "faces$imgCount.png"
                                File(filePath, filename).writeBitmap(faceBitmap, Bitmap.CompressFormat.PNG, 100)
                                imgCount += 1

                                predictions.add(
                                    Prediction(
                                        face.boundingBox,
                                        res
                                    )
                                )

                            }
                            catch ( e : Exception ) {
                                // If any exception occurs if this box and continue with the next boxes.
                                continue
                            }
                        }

                        // Clear the BoundingBoxOverlay and set the new results ( boxes ) to be displayed.
                        boundingBoxOverlay.faceBoundingBoxes = predictions
                        boundingBoxOverlay.invalidate()

                        // Declare that the processing has been finished and the system is ready for the next frame.
                        isProcessing.set(false)

                    }.start()
                }
                .addOnFailureListener { e ->
                    e.message?.let { Log.e("Error", it) }
                }
        }
    }

    private fun predictGender(bitmap: Bitmap): Int {
        var ans = -1

        val localModel = FirebaseCustomLocalModel.Builder()
            .setAssetFilePath("gender.tflite")
            .build()
        val options = FirebaseModelInterpreterOptions.Builder(localModel).build()
        val interpreter = FirebaseModelInterpreter.getInstance(options)


        val inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
            .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
            .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 2))
            .build()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        val batchNum = 0
        val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
        for (x in 0..223) {
            for (y in 0..223) {
                val pixel = scaledBitmap.getPixel(x, y)
                // Normalize channel values to [-1.0, 1.0]. This requirement varies by
                // model. For example, some models might require values to be normalized
                // to the range [0.0, 1.0] instead.
                input[batchNum][x][y][0] = (Color.red(pixel) ) / 255.0f
                input[batchNum][x][y][1] = (Color.green(pixel) ) / 255.0f
                input[batchNum][x][y][2] = (Color.blue(pixel) ) / 255.0f
            }
        }

        val inputs = FirebaseModelInputs.Builder()
            .add(input) // add() as many input arrays as your model requires
            .build()
        interpreter?.run(inputs, inputOutputOptions)
            ?.addOnSuccessListener { result ->
            val output = result.getOutput<Array<FloatArray>>(0)
            val probabilities = output[0]

            Log.i("Output", "${probabilities[0]}, ${probabilities[1]}")

        }?.addOnFailureListener { e ->
            Log.d("Fail", "$e")
            ans = -2
        }
        return ans
    }

    private fun File.writeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int) {
        outputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.flush()
        }
    }

    private fun cropRectFromBitmap(source: Bitmap, rect: Rect , preRotate : Boolean ): Bitmap {
        return Bitmap.createBitmap(
            if ( preRotate ) rotateBitmap( source , 90f )!! else source,
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap? {
        val matrix = Matrix()
        matrix.postRotate( angle )
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix , false )
    }

    private fun degreesToFirebaseRotation(degrees: Int): Int = when(degrees) {
        0 -> FirebaseVisionImageMetadata.ROTATION_0
        90 -> FirebaseVisionImageMetadata.ROTATION_90
        180 -> FirebaseVisionImageMetadata.ROTATION_180
        270 -> FirebaseVisionImageMetadata.ROTATION_270
        else -> throw Exception("Rotation must be 0, 90, 180, or 270.")
    }

    private fun BitmaptoNv21( bitmap: Bitmap ): ByteArray {
        val argb = IntArray(bitmap.width * bitmap.height )
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val yuv = ByteArray(bitmap.height * bitmap.width + 2 * Math.ceil(bitmap.height / 2.0).toInt()
                * Math.ceil(bitmap.width / 2.0).toInt())
        encodeYUV420SP( yuv, argb, bitmap.width, bitmap.height)
        return yuv
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff shr 0
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128
                yuv420sp[yIndex++] = (if (Y < 0) 0 else if (Y > 255) 255 else Y).toByte()
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = (if (V < 0) 0 else if (V > 255) 255 else V).toByte()
                    yuv420sp[uvIndex++] = (if (U < 0) 0 else if (U > 255) 255 else U).toByte()
                }
                index++
            }
        }
    }

    private fun toBitmap( image : Image ): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
        val yuv = out.toByteArray()
        return BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
    }

}

