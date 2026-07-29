package com.haven.guiqi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 归栖完整备份。
 *
 * 备份范围只包含持久数据：
 * - files：聊天、画匣、书架、记忆、日记、梦境、头像、聊天图片等
 * - shared_prefs：设置、API 配置、主题、住户状态等
 * - databases：以后新增的数据库也能一起带走
 * - no_backup：应用主动放在 noBackupFilesDir 的持久文件
 *
 * cache/code_cache 不属于用户数据，不导出。
 */
class FullBackupManager(context: Context) {

    data class ExportResult(
        val fileCount: Int,
        val totalBytes: Long,
        val createdAt: Long
    )

    data class RestoreResult(
        val fileCount: Int,
        val totalBytes: Long,
        val createdAt: Long
    )

    private data class RootSpec(val archiveName: String, val currentDir: File)
    private data class FileRecord(val path: String, val size: Long, val sha256: String)

    private val appContext = context.applicationContext
    private val dataDir = File(appContext.applicationInfo.dataDir)
    private val roots: List<RootSpec>
        get() = listOf(
            RootSpec("files", appContext.filesDir),
            RootSpec("shared_prefs", File(dataDir, "shared_prefs")),
            RootSpec("databases", File(dataDir, "databases")),
            RootSpec("no_backup", appContext.noBackupFilesDir)
        )

    private val safetyDir: File
        get() = File(appContext.cacheDir, SAFETY_DIR_NAME)
    private val safetyRootsDir: File
        get() = File(safetyDir, "roots")
    private val safetyMarkerFile: File
        get() = File(safetyDir, "marker.json")
    private val rescheduleMarkerFile: File
        get() = File(appContext.cacheDir, RESCHEDULE_MARKER_NAME)

    /** 导出完整 ZIP。此方法会读大量文件，应在后台线程调用。 */
    @Synchronized
    fun exportTo(uri: Uri): ExportResult {
        val serviceWasRunning = HavenService.isRunning(appContext)
        if (serviceWasRunning) stopServiceForDataOperation()

        try {
            flushSharedPreferences()

            val records = mutableListOf<FileRecord>()
            var totalBytes = 0L
            val createdAt = System.currentTimeMillis()
            val output = appContext.contentResolver.openOutputStream(uri, "w")
                ?: error("无法创建备份文件")

            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw, BUFFER_SIZE)).use { zip ->
                    for (root in roots) {
                        if (!root.currentDir.exists()) continue
                        val rootCanonical = root.currentDir.canonicalFile
                        val files = root.currentDir.walkTopDown()
                            .filter { it.isFile }
                            .sortedBy { it.relativeTo(root.currentDir).invariantSeparatorsPath }
                            .toList()

                        for (file in files) {
                            val canonical = file.canonicalFile
                            if (!isInside(rootCanonical, canonical)) continue

                            val relative = canonical.relativeTo(rootCanonical).invariantSeparatorsPath
                            val archivePath = "${root.archiveName}/$relative"
                            val digest = MessageDigest.getInstance("SHA-256")
                            var copied = 0L

                            zip.putNextEntry(ZipEntry(archivePath).apply {
                                time = file.lastModified().coerceAtLeast(0L)
                            })
                            FileInputStream(canonical).use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    zip.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    copied += read
                                }
                            }
                            zip.closeEntry()

                            records += FileRecord(archivePath, copied, digest.digest().toHex())
                            totalBytes += copied
                        }
                    }

                    val manifest = JSONObject().apply {
                        put("format", FORMAT_NAME)
                        put("format_version", FORMAT_VERSION)
                        put("package", appContext.packageName)
                        put("app_version", currentAppVersion())
                        put("created_at", createdAt)
                        put("contains_private_data", true)
                        put("roots", JSONArray().apply {
                            roots.forEach { put(it.archiveName) }
                        })
                        put("files", JSONArray().apply {
                            records.forEach { record ->
                                put(JSONObject().apply {
                                    put("path", record.path)
                                    put("size", record.size)
                                    put("sha256", record.sha256)
                                })
                            }
                        })
                    }

                    zip.putNextEntry(ZipEntry(MANIFEST_NAME).apply { time = createdAt })
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.finish()
                }
            }

            return ExportResult(records.size, totalBytes, createdAt)
        } finally {
            if (serviceWasRunning) HavenService.start(appContext)
        }
    }

    /**
     * 从完整备份恢复。
     *
     * 导入前的四个数据目录会用“目录换位”保存在 cache/guiqi_restore_safety，
     * 所以恢复本身失败时可以原地回滚；恢复成功后也允许用户手动撤销一次。
     */
    @Synchronized
    fun restoreFrom(uri: Uri): RestoreResult {
        if (hasRestoreSafety()) {
            error("上次导入前的保护副本还在，请先在设置里处理它")
        }

        val stageDir = File(appContext.cacheDir, "guiqi_restore_stage_${UUID.randomUUID()}")
        if (!stageDir.mkdirs()) error("无法创建导入临时目录")

        try {
            val result = extractAndValidate(uri, stageDir)
            roots.forEach { File(stageDir, it.archiveName).mkdirs() }

            cancelCurrentReminderAlarms()
            stopServiceForDataOperation()
            try {
                swapStagedDataIntoPlace(stageDir, result.createdAt)
                markReminderRescheduleNeeded()
                return result
            } catch (e: Exception) {
                HavenService.start(appContext)
                throw e
            }
        } finally {
            stageDir.deleteRecursively()
        }
    }

    fun hasRestoreSafety(): Boolean =
        safetyMarkerFile.isFile && safetyRootsDir.isDirectory

    fun restoreSafetyCreatedAt(): Long = try {
        JSONObject(safetyMarkerFile.readText()).optLong("created_at", 0L)
    } catch (_: Exception) {
        0L
    }

    /** 删除导入前的本地保护副本，不影响当前数据。 */
    @Synchronized
    fun discardRestoreSafety() {
        safetyDir.deleteRecursively()
    }

    /** 撤销最近一次完整导入。此方法应在后台线程调用。 */
    @Synchronized
    fun rollbackLastRestore(): RestoreResult {
        if (!hasRestoreSafety()) error("没有可撤销的完整导入")

        val marker = JSONObject(safetyMarkerFile.readText())
        val originalCreatedAt = marker.optLong("created_at", 0L)
        val rollbackTemp = File(appContext.cacheDir, "guiqi_rollback_${UUID.randomUUID()}")
        val rollbackRoots = File(rollbackTemp, "roots")
        if (!rollbackRoots.mkdirs()) error("无法创建撤销临时目录")

        cancelCurrentReminderAlarms()
        stopServiceForDataOperation()

        val completed = mutableListOf<RootSpec>()
        try {
            for (root in roots) {
                val current = root.currentDir
                val saved = File(safetyRootsDir, root.archiveName)
                val displaced = File(rollbackRoots, root.archiveName)

                if (!saved.exists()) saved.mkdirs()
                if (current.exists() && !moveDirectory(current, displaced)) {
                    error("无法暂存当前 ${root.archiveName} 数据")
                }
                if (!moveDirectory(saved, current)) {
                    if (displaced.exists()) moveDirectory(displaced, current)
                    error("无法恢复 ${root.archiveName} 数据")
                }
                completed += root
            }

            val countAndSize = countFilesAndBytes(roots.map { it.currentDir })
            safetyDir.deleteRecursively()
            rollbackTemp.deleteRecursively()
            markReminderRescheduleNeeded()
            return RestoreResult(countAndSize.first, countAndSize.second, originalCreatedAt)
        } catch (e: Exception) {
            for (root in completed.asReversed()) {
                val current = root.currentDir
                val saved = File(safetyRootsDir, root.archiveName)
                val displaced = File(rollbackRoots, root.archiveName)
                if (current.exists()) moveDirectory(current, saved)
                if (displaced.exists()) moveDirectory(displaced, current)
            }
            HavenService.start(appContext)
            throw e
        } finally {
            rollbackTemp.deleteRecursively()
        }
    }

    private fun extractAndValidate(uri: Uri, stageDir: File): RestoreResult {
        val actual = linkedMapOf<String, FileRecord>()
        var manifestText: String? = null
        var totalBytes = 0L

        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("无法读取备份文件")
        input.use { raw ->
            ZipInputStream(BufferedInputStream(raw, BUFFER_SIZE)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = sanitizeEntryName(entry.name)
                    if (name == MANIFEST_NAME) {
                        if (entry.isDirectory) error("备份清单格式错误")
                        manifestText = readSmallEntry(zip, MAX_MANIFEST_BYTES)
                        zip.closeEntry()
                        continue
                    }

                    val rootName = name.substringBefore('/', missingDelimiterValue = name)
                    if (rootName !in ALLOWED_ROOTS) error("备份包含不允许的路径：$name")
                    if (name == rootName && !entry.isDirectory) error("备份路径格式错误：$name")

                    val target = safeDestination(stageDir, name)
                    if (entry.isDirectory) {
                        if (!target.exists() && !target.mkdirs()) error("无法创建目录：$name")
                        zip.closeEntry()
                        continue
                    }
                    if (actual.containsKey(name)) error("备份中存在重复文件：$name")
                    target.parentFile?.let {
                        if (!it.exists() && !it.mkdirs()) error("无法创建目录：${it.name}")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            copied += read
                            totalBytes += read
                            if (copied > MAX_SINGLE_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                error("备份文件体积异常，已停止导入")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    if (entry.time > 0L) target.setLastModified(entry.time)
                    actual[name] = FileRecord(name, copied, digest.digest().toHex())
                    zip.closeEntry()
                }
            }
        }

        val manifestString = manifestText ?: error("缺少归栖完整备份清单")
        val manifest = JSONObject(manifestString)
        if (manifest.optString("format") != FORMAT_NAME) error("这不是归栖完整备份")
        if (manifest.optInt("format_version", -1) != FORMAT_VERSION) {
            error("备份格式版本不受支持")
        }
        if (manifest.optString("package") != appContext.packageName) {
            error("备份所属应用不匹配")
        }

        val expectedArray = manifest.optJSONArray("files") ?: error("备份清单缺少文件列表")
        val expected = linkedMapOf<String, FileRecord>()
        for (i in 0 until expectedArray.length()) {
            val obj = expectedArray.getJSONObject(i)
            val path = sanitizeEntryName(obj.getString("path"))
            val rootName = path.substringBefore('/', missingDelimiterValue = path)
            if (rootName !in ALLOWED_ROOTS) error("备份清单包含不允许的路径：$path")
            if (expected.containsKey(path)) error("备份清单包含重复文件：$path")
            expected[path] = FileRecord(
                path = path,
                size = obj.getLong("size"),
                sha256 = obj.getString("sha256").lowercase()
            )
        }

        if (actual.keys != expected.keys) {
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            val detail = buildString {
                if (missing.isNotEmpty()) append("缺少 ${missing.size} 个文件")
                if (extra.isNotEmpty()) {
                    if (isNotEmpty()) append("，")
                    append("多出 ${extra.size} 个文件")
                }
            }
            error("备份内容与清单不一致：$detail")
        }

        for ((path, expectedRecord) in expected) {
            val actualRecord = actual.getValue(path)
            if (actualRecord.size != expectedRecord.size ||
                !actualRecord.sha256.equals(expectedRecord.sha256, ignoreCase = true)
            ) {
                error("备份文件校验失败：$path")
            }
        }

        return RestoreResult(
            fileCount = actual.size,
            totalBytes = totalBytes,
            createdAt = manifest.optLong("created_at", 0L)
        )
    }

    private fun swapStagedDataIntoPlace(stageDir: File, importedCreatedAt: Long) {
        if (safetyDir.exists()) safetyDir.deleteRecursively()
        if (!safetyRootsDir.mkdirs()) error("无法创建导入保护目录")

        val completed = mutableListOf<RootSpec>()
        try {
            for (root in roots) {
                val current = root.currentDir
                val incoming = File(stageDir, root.archiveName).apply { mkdirs() }
                val saved = File(safetyRootsDir, root.archiveName)

                if (current.exists() && !moveDirectory(current, saved)) {
                    error("无法保护当前 ${root.archiveName} 数据")
                }
                if (!moveDirectory(incoming, current)) {
                    if (saved.exists()) moveDirectory(saved, current)
                    error("无法写入 ${root.archiveName} 数据")
                }
                completed += root
            }

            safetyMarkerFile.writeText(JSONObject().apply {
                put("created_at", System.currentTimeMillis())
                put("imported_backup_created_at", importedCreatedAt)
                put("format_version", FORMAT_VERSION)
            }.toString(2))
        } catch (e: Exception) {
            for (root in completed.asReversed()) {
                val current = root.currentDir
                val incoming = File(stageDir, root.archiveName)
                val saved = File(safetyRootsDir, root.archiveName)
                if (current.exists()) moveDirectory(current, incoming)
                if (saved.exists()) moveDirectory(saved, current)
            }
            safetyDir.deleteRecursively()
            throw e
        }
    }

    private fun stopServiceForDataOperation() {
        HavenService.stopForDataRestore(appContext)
        repeat(30) {
            if (!HavenService.isRunning(appContext)) {
                Thread.sleep(80L)
                return
            }
            Thread.sleep(50L)
        }
    }

    private fun cancelCurrentReminderAlarms() {
        try {
            ReminderStorage(appContext).getAllPending().forEach { reminder ->
                ReminderScheduler.cancel(appContext, reminder.id)
            }
        } catch (_: Exception) {
            // 备份恢复本身不应因某一条旧闹钟无法取消而失败。
        }
    }

    private fun markReminderRescheduleNeeded() {
        try {
            rescheduleMarkerFile.writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) {
        }
    }

    private fun flushSharedPreferences() {
        val dir = File(dataDir, "shared_prefs")
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            try {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            } catch (_: Exception) {
            }
        }
    }

    private fun currentAppVersion(): String = try {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun sanitizeEntryName(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) error("备份中存在空路径")
        val parts = normalized.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) {
            error("备份中存在非法路径：$raw")
        }
        return parts.joinToString("/")
    }

    private fun safeDestination(base: File, relative: String): File {
        val target = File(base, relative).canonicalFile
        if (!isInside(base.canonicalFile, target)) error("备份路径越界：$relative")
        return target
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        return child.path == root.path || child.path.startsWith(rootPath)
    }

    private fun readSmallEntry(zip: ZipInputStream, maxBytes: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() + read > maxBytes) error("备份清单体积异常")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun moveDirectory(source: File, destination: File): Boolean {
        if (!source.exists()) {
            return destination.exists() || destination.mkdirs()
        }
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.deleteRecursively()
        if (source.renameTo(destination)) return true

        return try {
            copyDirectory(source, destination)
            if (!source.deleteRecursively()) error("无法清理旧目录 ${source.name}")
            true
        } catch (_: Exception) {
            destination.deleteRecursively()
            false
        }
    }

    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) error("无法创建 ${destination.path}")
            source.listFiles()?.forEach { child ->
                copyDirectory(child, File(destination, child.name))
            }
        } else {
            destination.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            destination.setLastModified(source.lastModified())
        }
    }

    private fun countFilesAndBytes(dirs: List<File>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        dirs.forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                count++
                bytes += file.length()
            }
        }
        return count to bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val FORMAT_NAME = "guiqi_full_backup"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_NAME = "manifest.json"
        private const val SAFETY_DIR_NAME = "guiqi_restore_safety"
        private const val RESCHEDULE_MARKER_NAME = "guiqi_restore_reschedule_reminders"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        private const val MAX_SINGLE_FILE_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
        private val ALLOWED_ROOTS = setOf("files", "shared_prefs", "databases", "no_backup")

        /** 新进程启动后，用恢复后的数据重新注册 AI 提醒。 */
        fun handleApplicationStart(context: Context) {
            val marker = File(context.cacheDir, RESCHEDULE_MARKER_NAME)
            if (!marker.exists()) return
            try {
                ReminderScheduler.rescheduleAll(context.applicationContext)
                marker.delete()
            } catch (_: Exception) {
                // 留下 marker，下次启动继续尝试。
            }
        }
    }
}
