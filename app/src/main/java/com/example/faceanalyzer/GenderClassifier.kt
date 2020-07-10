package com.example.faceanalyzer

import android.content.res.AssetManager
import android.graphics.Bitmap
import io.reactivex.Single
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.min

class GenderClassifier constructor (assetManager: AssetManager) {

    private var interpreter: Interpreter? = null
    private lateinit var labelProb: Array<FloatArray>
    private val labels = Vector<String>()
    private val intValues by lazy { IntArray(INPUT_SIZE * INPUT_SIZE) }
    private lateinit var imgData: ByteBuffer

    private val MODEL_PATH = "gender2.tflite"
    private val LABEL_PATH = "labels.txt"
    private val INPUT_SIZE = 224
    private val MAX_RESULTS = 1
    private val DIM_BATCH_SIZE = 1
    private val DIM_PIXEL_SIZE = 3
    private val DIM_IMG_SIZE_X = 224
    private val DIM_IMG_SIZE_Y = 224

    private val IMAGE_MEAN = 128
    private val IMAGE_STD = 128.0f

    init {
        try {
            val br = BufferedReader(InputStreamReader(assetManager.open(LABEL_PATH)))
            while (true) {
                val line = br.readLine() ?: break
                labels.add(line)
            }
            br.close()
        } catch (e: IOException) {
            throw RuntimeException("Problem reading label file!", e)
        }
        labelProb = Array(1) { FloatArray(labels.size) }
        imgData = ByteBuffer.allocateDirect(4* DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE)
        imgData.order(ByteOrder.nativeOrder())
        try {
            interpreter = Interpreter(loadModelFile(assetManager, MODEL_PATH))
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun loadModelFile(assets: AssetManager, modelFilename: String): ByteBuffer {
        val inputStream = assets.open(modelFilename)
        val byteArray = inputStream.run { readBytes() }
        val byteBuffer = ByteBuffer.allocateDirect(byteArray.size)
        byteBuffer.order(ByteOrder.nativeOrder())
        return byteBuffer.put(byteArray)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        imgData.rewind()
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until DIM_IMG_SIZE_X) {
            for (j in 0 until DIM_IMG_SIZE_Y) {
                val value = intValues[pixel++]
                imgData.putFloat(((value shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((value shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((value and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
    }

    fun recognizeImage(bitmap: Bitmap): Single<List<Result>> {
        return Single.just(bitmap).flatMap {
            convertBitmapToByteBuffer(it)
            interpreter!!.run(imgData, labelProb)
            val pq = PriorityQueue(3,
                Comparator<Result> { lhs, rhs ->
                    // Intentionally reversed to put high confidence at the head of the queue.
                    (rhs.confidence!!).compareTo(lhs.confidence!!)
                })
            for (i in labels.indices) {
                pq.add(Result("" + i, if (labels.size > i) labels[i] else "unknown", labelProb[0][i].toFloat(), null))
            }
            val recognitions = ArrayList<Result>()
            val recognitionsSize = min(pq.size, MAX_RESULTS)
            for (i in 0 until recognitionsSize) recognitions.add(pq.poll()!!)
            return@flatMap Single.just(recognitions)
        }
    }

    fun close() {
        interpreter?.close()
    }



}
