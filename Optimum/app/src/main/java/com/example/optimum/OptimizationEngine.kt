package com.example.optimum

import android.content.Context
import android.util.Log
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.Config
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.io.FileOutputStream

class OptimizationEngine(
    private val context: Context,
    private val onProgress: (String) -> Unit
) {
    init {
        // CRITICAL: Set this in init to ensure it's set before ANY Apktool classes are loaded/initialized
        if (System.getProperty("os.name").isNullOrEmpty()) {
            System.setProperty("os.name", "linux")
        }
        if (System.getProperty("user.home").isNullOrEmpty()) {
            System.setProperty("user.home", context.filesDir.absolutePath)
        }
    }

    fun runPipeline(inputFile: File, outputFile: File): Result<Boolean> {
        onProgress("Initializing Engine...")
        Log.d("OptimizationEngine", "OS: ${System.getProperty("os.name")}, Home: ${System.getProperty("user.home")}")

        // --- 1. SETUP VERBOSE LOG INTERCEPTOR ---
        val brutLogger = Logger.getLogger("brut")
        brutLogger.level = Level.ALL
        
        val logHandler = object : Handler() {
            override fun publish(record: LogRecord) {
                val msg = record.message ?: ""
                Log.d("ApktoolDebug", "[${record.level}] $msg")
                
                // Show more granular updates on the UI
                if (record.level.intValue() >= Level.INFO.intValue() || 
                    msg.contains("resource", ignoreCase = true) || 
                    msg.contains("entry", ignoreCase = true)) {
                    onProgress(msg)
                }
            }
            override fun flush() {}
            override fun close() {}
        }
        brutLogger.addHandler(logHandler)

        val workDir = File(context.cacheDir, "optimization_workspace")
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        try {
            val frameworkDir = File(context.filesDir, "apktool_framework")
            if (!frameworkDir.exists()) frameworkDir.mkdirs()

            // --- 2. CRITICAL: INSTALL SYSTEM FRAMEWORK ---
            // If framework is missing, resolution will hang indefinitely.
            onProgress("Installing system framework...")
            installFramework(frameworkDir)

            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val aapt2File = File(nativeDir, "aapt2.so")
            aapt2File.setExecutable(true)

            // --- 3. CONFIGURATION WITH STABILITY FLAGS ---
            val config = Config("3.0.2")
            config.isVerbose = true
            
            config.setDecodeSources(Config.DecodeSources.NONE)
            config.setDecodeAssets(Config.DecodeAssets.NONE)
            
            // USE ONLY_MANIFEST to get a partial decompile that allows rebuilding
            // but skips the heavy resource decoding that crashes with ImageIO
            config.setDecodeResources(Config.DecodeResources.ONLY_MANIFEST)
            
            // DEFAULT is the safest and most standard
            config.setDecodeResolve(Config.DecodeResolve.DEFAULT) 
            config.setIgnoreRawValues(true)
            config.setKeepBrokenResources(true)               
            
            config.setFrameworkDirectory(frameworkDir.absolutePath)
            config.setAaptBinary(aapt2File.absolutePath)
            config.setJobs(1) 
            config.setForced(true)

            // --- 4. DECOMPILE ---
            onProgress("Stage 1/3: Decoding APK structure...")
            Log.d("OptimizationEngine", "Starting decode. Input: ${inputFile.absolutePath}")
            
            try {
                val decoder = ApkDecoder(inputFile, config)
                decoder.decode(workDir)
                
                // MANUALLY EXTRACT 'res' folder since ONLY_MANIFEST might not extract raw files
                onProgress("Extracting raw resource files...")
                extractResFolder(inputFile, workDir)
                
                Log.d("OptimizationEngine", "Structure & Res extraction complete.")
            } catch (e: Throwable) {
                Log.e("OptimizationEngine", "DECODE CRASH", e)
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                onProgress("DECODE CRASH: ${e.message}\n$sw")
                return Result.failure(e)
            }

            // --- 5. STRIP ---
            onProgress("Stage 2/3: Stripping unwanted resources...")
            val resDir = File(workDir, "res")
            if (resDir.exists()) {
                val sizeBefore = getFolderSize(resDir)
                onProgress("Res size before: ${sizeBefore / 1024} KB")
                
                ApkOptimizer.cleanResFolder(resDir)
                
                val sizeAfter = getFolderSize(resDir)
                onProgress("Res size after: ${sizeAfter / 1024} KB")
                
                if (sizeBefore == sizeAfter && sizeBefore > 0) {
                    onProgress("WARNING: No resources were stripped!")
                }
            } else {
                throw Exception("FAILED: 'res' folder still missing after extraction!")
            }

            // --- 6. REBUILD ---
            onProgress("Stage 3/3: Rebuilding optimized APK & ARSC...")
            val builder = ApkBuilder(workDir, config)
            builder.build(outputFile)

            // --- 7. AUTO-SIGN (Optional but highly requested by user) ---
            // Rebuilding with Apktool clears original signatures.
            // We'll rename the output to 'unsigned' and try to provide a 'signed' version.
            onProgress("Optimization Successful! (Note: APK is unsigned)")
            return Result.success(true)
        } catch (e: Exception) {
            val errorMsg = "ERROR: ${e.message}\n${e.stackTrace.take(3).joinToString("\n")}"
            Log.e("OptimizationEngine", errorMsg, e)
            onProgress(errorMsg)
            return Result.failure(e)
        } finally {
            brutLogger.removeHandler(logHandler)
        }
    }

    private fun getFolderSize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    private fun extractResFolder(apkFile: File, outDir: File) {
        val resDir = File(outDir, "res")
        if (!resDir.exists()) resDir.mkdirs()

        java.util.zip.ZipInputStream(apkFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("res/") && !entry.isDirectory) {
                    val outFile = File(outDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        zip.copyTo(out)
                    }
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun installFramework(frameworkDir: File) {
        val frameworkFile = File(frameworkDir, "1.apk")
        // If it exists and is reasonable size, skip
        if (frameworkFile.exists() && frameworkFile.length() > 1000000) {
            Log.d("OptimizationEngine", "Framework already exists: ${frameworkFile.length()} bytes")
            return
        }

        try {
            // Try different possible paths for the internal resource
            val resourcePaths = arrayOf(
                "prebuilt/android-framework.jar",
                "brut/androlib/android-framework.jar"
            )
            
            var inputStream: java.io.InputStream? = null
            val loader = Config::class.java.classLoader
            if (loader == null) {
                Log.e("OptimizationEngine", "ClassLoader is NULL!")
                onProgress("Internal Error: ClassLoader missing")
                return
            }
            
            for (path in resourcePaths) {
                inputStream = loader.getResourceAsStream(path)
                if (inputStream != null) {
                    Log.d("OptimizationEngine", "Found framework at: $path")
                    break
                }
            }

            if (inputStream == null) {
                Log.e("OptimizationEngine", "COULD NOT FIND internal android-framework.jar in classpath!")
                onProgress("Internal Error: Framework binary missing")
                return
            }

            inputStream.use { input ->
                FileOutputStream(frameworkFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("OptimizationEngine", "Framework installed: ${frameworkFile.length()} bytes")
        } catch (e: Exception) { 
            Log.e("OptimizationEngine", "Framework installation failed", e) 
            onProgress("Framework Install Failed: ${e.message}")
        }
    }
}
