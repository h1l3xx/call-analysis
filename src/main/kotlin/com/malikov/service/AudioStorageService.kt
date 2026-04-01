package com.malikov.service

import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

class AudioStorageService(private val basePath: String) {

    private val log = LoggerFactory.getLogger(AudioStorageService::class.java)

    init {
        val dir = File(basePath)
        if (!dir.exists()) {
            dir.mkdirs()
            log.info("Created audio storage directory: {}", basePath)
        }
    }

    /**
     * Copies [sourceFile] into persistent storage at {basePath}/{schema}/{callId}.{ext}.
     * Returns the relative path (used as audio_s3_key in DB).
     */
    fun save(schema: String, callId: UUID, ext: String, sourceFile: File): String {
        val schemaDir = File(basePath, schema)
        if (!schemaDir.exists()) schemaDir.mkdirs()

        val relativePath = "$schema/$callId.$ext"
        val target = File(basePath, relativePath)

        sourceFile.copyTo(target, overwrite = true)
        log.debug("Audio saved: {} ({} bytes)", relativePath, target.length())
        return relativePath
    }

    /**
     * Resolves a relative path to an absolute File.
     * Returns null if the file does not exist on disk.
     */
    fun getFile(relativePath: String): File? {
        val file = File(basePath, relativePath)
        return if (file.exists() && file.isFile) file else null
    }

    /**
     * Deletes the audio file from disk.
     */
    fun delete(relativePath: String): Boolean {
        val file = File(basePath, relativePath)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) log.debug("Audio deleted: {}", relativePath)
            else log.warn("Failed to delete audio: {}", relativePath)
            deleted
        } else {
            false
        }
    }
}
