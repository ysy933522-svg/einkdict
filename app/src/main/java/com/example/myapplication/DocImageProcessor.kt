package com.example.myapplication

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object DocImageProcessor {

    /**
     * 使用 OpenCV 对文档截图进行增强
     * @param src 原始 Bitmap（ARGB_8888）
     * @param clipLimit CLAHE 对比度限制（推荐 2.0~4.0）
     * @param sharpenStrength 锐化强度（0 表示不锐化，推荐 0.3~0.8）
     * @return 增强后的 Bitmap
     */
    fun enhance(src: Bitmap, clipLimit: Double = 3.0, sharpenStrength: Double = 0.5): Bitmap {
        // 1. Bitmap → Mat（RGBA）
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // 2. 转为灰度图（文档处理灰度就够了）
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

        // 3. CLAHE 增强局部对比度
        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        // 4. 锐化（Unsharp Mask）
        if (sharpenStrength > 0) {
            val blurred = Mat()
            Imgproc.GaussianBlur(enhanced, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(enhanced, 1.0 + sharpenStrength, blurred, -sharpenStrength, 0.0, enhanced)
            blurred.release()
        }

        // 5. Mat → Bitmap（结果依然是灰度图，但显示为 ARGB）
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, result)

        // 6. 释放 Mat 内存
        srcMat.release()
        gray.release()
        enhanced.release()

        return result
    }
}