package com.kowsiq.creativescan

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class CreativeScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenCV native libraries are bundled in the APK; no download is needed.
        if (!OpenCVLoader.initLocal()) {
            Log.e("CreativeScan", "OpenCV failed to initialise")
        }
    }
}
