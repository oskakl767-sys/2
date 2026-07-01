package com.mdm.agent.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {

    private const val TAG = "ZipUtils"
    private const val BUFFER_SIZE = 8192

    fun zipFiles(files: List<File>, outputFile: File, prefix: String = ""): File? {
        if (files.isEmpty()) return null

        try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
                val buffer = ByteArray(BUFFER_SIZE)
                val usedNames = mutableSetOf<String>()

                for (file in files) {
                    if (!file.exists()) continue

                    var name = if (prefix.isNotEmpty()) "${prefix}_${file.name.takeLast(40).replace("/", "_")}" else file.name
                    while (usedNames.contains(name)) {
                        name = "1_$name"
                    }
                    usedNames.add(name)

                    try {
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(file).use { fis ->
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                        }
                        zos.closeEntry()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add ${file.name}: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "ZIP created: ${outputFile.absolutePath} (${outputFile.length()} bytes, ${files.size} files)")
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "ZIP creation failed: ${e.message}")
            return null
        }
    }
}
