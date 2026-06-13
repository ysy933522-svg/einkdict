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
        colorTemp: Int = 0,
        printCleanStrength: Float = 0f   // 新增：文字清洗强度 0~1
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

        // 4. 调整色温
        if (colorTemp != 0) {
            val rgb = Mat()
            Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB_FULL)
            val bgrChannels = ArrayList<Mat>()
            Core.split(rgb, bgrChannels)
            val adjust = colorTemp / 200.0
            Core.multiply(bgrChannels[0], Scalar(1.0 + adjust * 0.3), bgrChannels[0])
            Core.multiply(bgrChannels[2], Scalar(1.0 - adjust * 0.3), bgrChannels[2])
            Core.merge(bgrChannels, rgb)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV_FULL)
        }

        // 5. 转回 RGB
        val rgbMat = Mat()
        Imgproc.cvtColor(hsv, rgbMat, Imgproc.COLOR_HSV2RGB_FULL)

        // 6. 转灰度
        val gray = Mat()
        Imgproc.cvtColor(rgbMat, gray, Imgproc.COLOR_RGB2GRAY)

        // ============================================================
        // ★ 影印文字清洗管线（printCleanStrength > 0 时启用）
        // ============================================================
        if (printCleanStrength > 0.01f) {
            // A. 中值去椒盐（强度线性映射）
            val medianK = (1 + printCleanStrength * 2).toInt().coerceIn(1, 3) * 2 + 1  // 3 or 5 or 7
            Imgproc.medianBlur(gray, gray, medianK)

            // B. 闭运算补虫洞（核大小 3×3 ~ 5×5）
            val closeSize = (1 + printCleanStrength * 1).toInt().coerceIn(1, 2) * 2 + 1  // 3 or 5
            val closeKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(closeSize.toDouble(), closeSize.toDouble())
            )
            Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_CLOSE, closeKernel)
            closeKernel.release()

            // C. 开运算去毛刺（核大小固定 3×3）
            val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_OPEN, openKernel)
            openKernel.release()

            // D. 自适应二值化（杀掉脏灰背景）
            // blockSize 随强度增大：15 → 21 → 31
            val blockSize = (15 + printCleanStrength * 16).toInt().let { if (it % 2 == 0) it + 1 else it }
            val c = 10.0 + printCleanStrength * 5.0   // C 常数
            Imgproc.adaptiveThreshold(
                gray, gray,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                blockSize,
                c
            )
            // 清洗后已经是黑白二值图，后续的 CLAHE / 锐化 / 伽马 / 二值化 / 降噪都不再需要
            // 直接跳到输出步骤
            val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, result)
            // 释放所有临时 Mat
            srcMat.release(); hsv.release(); rgbMat.release(); gray.release()
            return result
        }
        // ============================================================
        // 非清洗模式：走原有管线
        // ============================================================

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

        // 11. 降噪
        if (denoise > 0f) {
            val kernelSize = (denoise.toInt() * 2 + 1).coerceIn(3, 17)
            if (kernelSize % 2 == 1) {
                Imgproc.medianBlur(enhancedGray, enhancedGray, kernelSize)
            }
        }

        // 12. 亮度/对比度
        if (brightness != 0f || contrast != 1f) {
            enhancedGray.convertTo(enhancedGray, -1, contrast.toDouble(), brightness.toDouble())
        }

        // 13. 输出
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhancedGray, result)

        srcMat.release(); hsv.release(); rgbMat.release(); gray.release(); enhancedGray.release()
        return result
    }
}