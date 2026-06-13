package com.example.myapplication

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object DocImageProcessor {

    fun enhance(
        src: Bitmap,
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1f,
        clipLimit: Float = 3.0f,
        sharpenStrength: Float = 0.5f,
        gamma: Float = 1.0f,
        threshold: Int = 0,
        denoise: Float = 0f,
        colorTemp: Int = 0
    ): Bitmap {
        // 1. Bitmap → Mat
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // 2. 转 HSV 用于调整饱和度和色温
        val hsv = Mat()
        Imgproc.cvtColor(srcMat, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV_FULL)

        // 3. 调整饱和度
        if (saturation != 1f) {
            val channels = ArrayList<Mat>()
            Core.split(hsv, channels)
            Core.multiply(channels[1], Scalar(saturation.toDouble()), channels[1])
            Core.merge(channels, hsv)
        }

        // 4. 调整色温（通过修改蓝色通道比例）
        if (colorTemp != 0) {
            val rgb = Mat()
            Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB_FULL)
            val bgrChannels = ArrayList<Mat>()
            Core.split(rgb, bgrChannels)
            // 色温正数=偏蓝（增加蓝色，减少红色），负数=偏黄（增加红色，减少蓝色）
            val adjust = colorTemp / 200.0
            Core.multiply(bgrChannels[0], Scalar(1.0 + adjust * 0.3), bgrChannels[0]) // B
            Core.multiply(bgrChannels[2], Scalar(1.0 - adjust * 0.3), bgrChannels[2]) // R
            Core.merge(bgrChannels, rgb)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV_FULL)
        }

        // 5. 转回 RGB 准备后续处理
        val rgbMat = Mat()
        Imgproc.cvtColor(hsv, rgbMat, Imgproc.COLOR_HSV2RGB_FULL)

        // 6. 转灰度用于 CLAHE 和锐化
        val gray = Mat()
        Imgproc.cvtColor(rgbMat, gray, Imgproc.COLOR_RGB2GRAY)

        // 7. CLAHE
        val clahe = Imgproc.createCLAHE(clipLimit.toDouble(), Size(8.0, 8.0))
        val enhancedGray = Mat()
        clahe.apply(gray, enhancedGray)

        // 8. 锐化
        if (sharpenStrength > 0f) {
            val blurred = Mat()
            Imgproc.GaussianBlur(enhancedGray, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(enhancedGray, 1.0 + sharpenStrength.toDouble(), blurred, -sharpenStrength.toDouble(), 0.0, enhancedGray)
            blurred.release()
        }

        // 9. 伽马校正
        if (gamma != 1.0f) {
            val lut = Mat(1, 256, CvType.CV_8UC1)
            val invGamma = 1.0 / gamma
            for (i in 0 until 256) {
                lut.put(0, i, Math.pow(i / 255.0, invGamma) * 255.0)
            }
            Core.LUT(enhancedGray, lut, enhancedGray)
            lut.release()
        }

        // 10. 二值化
        if (threshold > 0) {
            Imgproc.threshold(enhancedGray, enhancedGray, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        }

        // 11. 降噪（使用中值滤波）
        if (denoise > 0f) {
            val kernelSize = (denoise.toInt() * 2 + 1).coerceIn(3, 33)
            if (kernelSize % 2 == 1) {
                Imgproc.medianBlur(enhancedGray, enhancedGray, kernelSize)
            }
        }

        // 12. 亮度/对比度调整（在灰度图上）
        if (brightness != 0f || contrast != 1f) {
            enhancedGray.convertTo(enhancedGray, -1, contrast.toDouble(), brightness.toDouble())
        }

        // 13. 将灰度图输出为 ARGB_8888
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhancedGray, result)

        // 释放
        srcMat.release()
        hsv.release()
        rgbMat.release()
        gray.release()
        enhancedGray.release()

        return result
    }
}