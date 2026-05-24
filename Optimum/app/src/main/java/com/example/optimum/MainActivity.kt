package com.example.optimum

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import brut.androlib.Config

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        
        init {
            try {
                // Apktool OSDetection looks for these. If they are null, it crashes with NPE.
                val props = System.getProperties()
                props["os.name"] = "Linux"
                props["sun.arch.data.model"] = "64" 
                props["user.home"] = "/data/data/com.example.optimum/files"
                
                Log.d("OS_FIX", "Forced Properties: os.name=${System.getProperty("os.name")}, arch=${System.getProperty("sun.arch.data.model")}")
            } catch (e: Exception) {
                Log.e("OS_FIX", "Failed to force properties", e)
            }
        }
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var pickApkButton: Button
    private lateinit var saveButton: Button
    private var optimizedFile: File? = null

    private val apkPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleApkUri(it) }
    }

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri: Uri? ->
        uri?.let { exportFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("OptimumCrash", "Uncaught exception", throwable)
            runOnUiThread {
                appendLog("CRASH in ${thread.name}: ${throwable.message}")
            }
        }

        setContentView(createLayout())
        checkEnvironment()

        pickApkButton.setOnClickListener {
            apkPicker.launch("*/*")
        }

        saveButton.setOnClickListener {
            saveLauncher.launch("optimized_app.apk")
        }
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logText.append("\n> $msg")
            val scroll = logText.parent as? android.widget.ScrollView
            scroll?.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun checkEnvironment() {
        appendLog("Checking environment...")
        val aapt2File = File(applicationInfo.nativeLibraryDir, "aapt2.so")
        appendLog("AAPT2 path: ${aapt2File.absolutePath}")
        appendLog("AAPT2 exists: ${aapt2File.exists()}")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val streams = arrayOf("prebuilt/android-framework.jar", "brut/androlib/android-framework.jar")
                var found = false
                val loader = Config::class.java.classLoader
                if (loader == null) {
                    appendLog("CRITICAL: ClassLoader is NULL!")
                    return@launch
                }
                for (s in streams) {
                    loader.getResourceAsStream(s)?.use {
                        appendLog("Found internal framework: $s")
                        found = true
                    }
                    if (found) break
                }
                if (!found) appendLog("WARNING: Internal framework NOT found in classpath!")
            } catch (e: Exception) {
                appendLog("Env check error: ${e.message}")
            }
        }
    }

    private fun createLayout(): android.view.View {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        pickApkButton = Button(this).apply { text = "Pick APK" }
        root.addView(pickApkButton)

        statusText = TextView(this).apply { 
            text = "Status: Idle" 
            textSize = 18f
            setPadding(0, 20, 0, 20)
        }
        root.addView(statusText)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        logText = TextView(this).apply {
            setTextColor(android.graphics.Color.GREEN)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(10, 10, 10, 10)
        }
        scroll.addView(logText)
        root.addView(scroll)

        saveButton = Button(this).apply { text = "Save Optimized APK"; isEnabled = false }
        root.addView(saveButton)

        return root
    }

    private fun handleApkUri(uri: Uri) {
        appendLog("Selected URI: $uri")
        statusText.text = "Processing..."
        pickApkButton.isEnabled = false
        saveButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val inputFile = File(cacheDir, "input_raw.apk")
            val output = File(cacheDir, "output_optimized.apk")

            try {
                if (inputFile.exists()) inputFile.delete()
                if (output.exists()) output.delete()

                appendLog("Streaming APK to local file...")
                contentResolver.openInputStream(uri)?.use { input ->
                    inputFile.outputStream().use { outStream ->
                        input.copyTo(outStream)
                    }
                }
                appendLog("File streamed. Size: ${inputFile.length()} bytes")

                val engine = OptimizationEngine(this@MainActivity) { progress ->
                    appendLog(progress)
                    lifecycleScope.launch(Dispatchers.Main) {
                        statusText.text = "Status: $progress"
                    }
                }
                val result = engine.runPipeline(inputFile, output)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        optimizedFile = output
                        statusText.text = "Success!"
                        saveButton.isEnabled = true
                    } else {
                        statusText.text = "Failed"
                    }
                    pickApkButton.isEnabled = true
                }
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusText.text = "Error"
                    pickApkButton.isEnabled = true
                }
            }
        }
    }

    private fun exportFile(destinationUri: Uri) {
        val fileToSave = optimizedFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { output ->
                    fileToSave.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("Save failed: ${e.message}")
            }
        }
    }
}
