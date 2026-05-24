package com.example.optimum

import android.app.Application
import android.util.Log
import brut.apktool.ApktoolAndroid

class OptimumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // THE ABSOLUTE EARLIEST POINT TO FIX JVM PROPERTIES
            val props = System.getProperties()
            props["os.name"] = "linux"
            props["user.home"] = filesDir.absolutePath
            Log.d("OptimumApp", "Forced OS to: ${System.getProperty("os.name")}")
            
            // APKTOOL-ANDROID INITIALIZATION
            // This is the ported library that handles libaapt2.so correctly
            ApktoolAndroid.configure(this)
        } catch (e: Exception) {
            Log.e("OptimumApp", "Failed to set properties", e)
        }
    }
}
