package com.example.optimum

import android.util.Log
import java.io.File

object ApkOptimizer {
    private const val TAG = "ApkOptimizer"
    private const val MAX_SW_DP = 400
    private const val MAX_DIMENSION_DP = 800
    private val DENSITY_PRIORITY = mapOf(
        "ldpi" to 0, "mdpi" to 1, "hdpi" to 2, "xhdpi" to 3, "xxhdpi" to 4, "xxxhdpi" to 5
    )
    private val TARGET_FOLDERS = setOf("drawable", "mipmap")
    private val KEEP_LANGS = setOf("en", "bn")

    private fun isLanguageQualifier(qualifier: String): Pair<Boolean, String> {
        val match = Regex("^([a-z]{2,3})(?:-r[A-Z]{2,3})?$|^b\\+([a-zA-Z0-9]+).*$").matchEntire(qualifier)
        if (match != null) {
            val lang = match.groupValues[1].ifEmpty { match.groupValues[2] }
            return true to lang.lowercase()
        }
        return false to ""
    }

    private data class ParsedFolder(
        val drop: Boolean,
        val density: String? = null,
        val densityRank: Int = -1,
        val familyKey: String = ""
    )

    private fun parseResourceFolder(folderName: String): ParsedFolder? {
        val parts = folderName.split("-")
        val baseType = parts[0]
        val qualifiers = parts.drop(1)

        if (qualifiers.any { it == "watch" || it == "tv" || it == "car" }) return ParsedFolder(drop = true)

        for (q in qualifiers) {
            Regex("^sw(\\d+)dp$").matchEntire(q)?.let {
                if (it.groupValues[1].toInt() > MAX_SW_DP) return ParsedFolder(drop = true)
            }
            Regex("^w(\\d+)dp$").matchEntire(q)?.let {
                if (it.groupValues[1].toInt() > MAX_DIMENSION_DP) return ParsedFolder(drop = true)
            }
            Regex("^h(\\d+)dp$").matchEntire(q)?.let {
                if (it.groupValues[1].toInt() > MAX_DIMENSION_DP) return ParsedFolder(drop = true)
            }
            val (isLang, baseLang) = isLanguageQualifier(q)
            if (isLang && baseLang !in KEEP_LANGS) return ParsedFolder(drop = true)
        }

        if (baseType !in TARGET_FOLDERS) return null
        val density = qualifiers.firstOrNull { it in DENSITY_PRIORITY }
        val familyQualifiers = qualifiers.filter { it != density }.sorted().joinToString("-")
        return ParsedFolder(false, density, DENSITY_PRIORITY[density] ?: -1, "$baseType-$familyQualifiers")
    }

    private data class ResEntry(val file: File, val filename: String, val density: String?, val densityRank: Int, val familyKey: String, val drop: Boolean)

    fun cleanResFolder(resDir: File) {
        if (!resDir.exists() || !resDir.isDirectory) return
        Log.i(TAG, "Starting cleanup of ${resDir.absolutePath}")
        
        val entries = mutableListOf<ResEntry>()
        val removedFileNames = mutableSetOf<String>()

        resDir.listFiles()?.forEach { folder ->
            if (folder.isDirectory) {
                val parsed = parseResourceFolder(folder.name)
                if (parsed != null) {
                    Log.d(TAG, "Parsing folder: ${folder.name} -> drop=${parsed.drop}, density=${parsed.density}")
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile) entries.add(ResEntry(file, file.name, parsed.density, parsed.densityRank, parsed.familyKey, parsed.drop))
                    }
                } else {
                    Log.d(TAG, "Skipping folder (not target): ${folder.name}")
                }
            }
        }

        val densityGroups = mutableMapOf<Pair<String, String>, MutableList<ResEntry>>()
        for (e in entries) { 
            if (!e.drop && e.density != null) {
                densityGroups.getOrPut(Pair(e.familyKey, e.filename)) { mutableListOf() }.add(e) 
            }
        }

        val toRemove = mutableSetOf<File>()
        for (items in densityGroups.values) {
            if (items.size > 1) {
                val sorted = items.sortedByDescending { it.densityRank }
                Log.d(TAG, "Keeping highest density for ${items[0].filename}: ${sorted[0].density}")
                for (i in 1 until sorted.size) {
                    Log.d(TAG, "  Dropping redundant density: ${sorted[i].density}")
                    toRemove.add(sorted[i].file)
                }
            }
        }
        for (e in entries) { 
            if (e.drop) {
                Log.d(TAG, "Dropping due to filter rules: ${e.file.parentFile?.name}/${e.filename}")
                toRemove.add(e.file) 
            }
        }

        var deletedCount = 0
        toRemove.forEach { 
            removedFileNames.add(it.nameWithoutExtension)
            if (it.delete()) deletedCount++
        }
        Log.i(TAG, "Deleted $deletedCount files.")

        // --- CLEAN XML REFERENCES ---
        val valuesDirs = resDir.listFiles()?.filter { it.name.startsWith("values") }
        valuesDirs?.forEach { valuesDir ->
            valuesDir.listFiles()?.filter { it.extension == "xml" }?.forEach { xmlFile ->
                try {
                    val content = xmlFile.readText()
                    var modified = content
                    removedFileNames.forEach { name ->
                        // Match drawable/mipmap references in item/public tags
                        val regex = Regex("<(item|public)[^>]+name=\"$name\"[^>]*/>\\s*", RegexOption.IGNORE_CASE)
                        modified = modified.replace(regex, "")
                    }
                    if (modified != content) {
                        Log.d(TAG, "Updated XML references in: ${xmlFile.parentFile?.name}/${xmlFile.name}")
                        xmlFile.writeText(modified)
                    }
                } catch (e: Exception) { Log.e(TAG, "XML clean error: ${xmlFile.name}", e) }
            }
        }

        resDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.walkBottomUp().forEach { if (it.isDirectory && it.listFiles()?.isEmpty() == true) it.delete() }
        }
    }
}
