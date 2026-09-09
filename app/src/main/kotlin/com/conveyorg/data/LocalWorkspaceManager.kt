package com.conveyorg.data

import android.content.Context
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

data class FileReadResult(
    val relativePath: String,
    val content: String,
    val totalLines: Int,
    val isBinary: Boolean,
    val isTruncated: Boolean,
    val startLine: Int = 1,
    val endLine: Int = totalLines
)

data class SearchMatchDto(
    val relativePath: String,
    val lineNumber: Int,
    val lineContent: String
)

data class WorkspaceTreeNodeDto(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

data class WorkspaceTreeDto(
    val nodes: List<WorkspaceTreeNodeDto>,
    val totalFiles: Int,
    val totalDirectories: Int
)

data class WorkspaceDeltaPayload(
    val modifiedFiles: Map<String, ByteArray>,
    val deletedFiles: List<String>,
    val addedCount: Int,
    val modifiedCount: Int,
    val deletedCount: Int
)

class LocalWorkspaceManager(
    val sessionId: String,
    private val context: Context
) {
    val workspaceRoot: File by lazy {
        File(context.noBackupFilesDir, "workspaces/$sessionId/workspace").apply {
            if (!exists()) mkdirs()
        }
    }

    private val baselineBackupDir: File by lazy {
        File(context.noBackupFilesDir, "workspaces/$sessionId/.baseline_orig").apply {
            if (!exists()) mkdirs()
        }
    }

    private val baselineHashes = ConcurrentHashMap<String, String>()
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val registryMutex = Mutex()

    companion object {
        private const val HASH_BUFFER_SIZE = 16384
        private const val BINARY_PROBE_SIZE = 1024
        private const val MAX_DIFF_LINE_SUM = 4000
        private const val MAX_TOTAL_AUDIT_BYTES = 10 * 1024 * 1024

        private val DEFAULT_IGNORED_DIRS = setOf(".git", ".gradle", "build", ".idea", ".kotlin", "out", "bin")
        private val DEFAULT_IGNORED_EXTENSIONS = setOf("class", "hprof", "apk", "aab", "dex", "pyc", "jar", "so")
    }

    fun resolveSafeFile(relativePath: String): File {
        val normalized = relativePath.trim().replace('\\', '/').removePrefix("/")
        val target = File(workspaceRoot, normalized).canonicalFile
        val rootCanonical = workspaceRoot.canonicalFile
        if (!target.path.startsWith(rootCanonical.path + File.separator) && target != rootCanonical) {
            throw SecurityException("CWE-22: Попытка выхода за пределы песочницы: $relativePath")
        }
        return target
    }

    private fun getRelativePath(file: File): String {
        return file.canonicalFile.relativeTo(workspaceRoot.canonicalFile).path.replace('\\', '/')
    }

    private suspend fun getFileMutex(relativePath: String): Mutex {
        val normalized = relativePath.trim().replace('\\', '/').removePrefix("/")
        return fileLocks[normalized] ?: registryMutex.withLock {
            fileLocks.getOrPut(normalized) { Mutex() }
        }
    }

    suspend fun <T> withFileLock(relativePath: String, block: suspend () -> T): T {
        return getFileMutex(relativePath).withLock { block() }
    }

    suspend fun captureBaseline(): Int = withContext(Dispatchers.IO) {
        baselineHashes.clear()
        var scannedCount = 0
        workspaceRoot.walkTopDown()
            .filter { file ->
                if (file.isDirectory) file.name !in DEFAULT_IGNORED_DIRS
                else file.extension.lowercase() !in DEFAULT_IGNORED_EXTENSIONS && file.parentFile?.name !in DEFAULT_IGNORED_DIRS
            }
            .filter { it.isFile }
            .forEach { file ->
                val relPath = getRelativePath(file)
                baselineHashes[relPath] = calculateSha256(file)
                scannedCount++
            }
        AppLogger.i(AppLogger.TAG_APP, "LocalWorkspace: Базовый снимок ($scannedCount файлов, session=$sessionId)")
        scannedCount
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun readFile(relativePath: String, startLine: Int? = null, endLine: Int? = null): FileReadResult = withContext(Dispatchers.IO) {
        withFileLock(relativePath) {
            val file = resolveSafeFile(relativePath)
            if (!file.exists() || !file.isFile) throw NoSuchFileException(file, reason = "Файл не существует: $relativePath")
            if (isBinaryFile(file)) {
                return@withFileLock FileReadResult(relativePath, "[BINARY FILE]", 0, isBinary = true, isTruncated = false)
            }

            val lines = mutableListOf<String>()
            var totalLinesCount = 0
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var rawLine: String?
                while (reader.readLine().also { rawLine = it } != null) {
                    var line = rawLine!!
                    if (totalLinesCount == 0 && line.startsWith("\uFEFF")) line = line.removePrefix("\uFEFF")
                    totalLinesCount++
                    lines.add(line)
                }
            }

            val from = max(1, startLine ?: 1)
            val to = min(totalLinesCount, endLine ?: totalLinesCount)
            val content = if (totalLinesCount == 0 || from > totalLinesCount) "" else lines.subList(from - 1, to).joinToString("\n")
            val isTruncated = (startLine != null && startLine > 1) || (endLine != null && endLine < totalLinesCount)

            FileReadResult(relativePath, content, totalLinesCount, false, isTruncated, from, to)
        }
    }

    fun isBinaryFile(file: File): Boolean {
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BINARY_PROBE_SIZE)
            val bytesRead = fis.read(buffer)
            if (bytesRead <= 0) return false
            for (i in 0 until bytesRead) {
                if (buffer[i] == 0.toByte()) return true
            }
        }
        return false
    }

    suspend fun writeFileAtomic(relativePath: String, contentBytes: ByteArray, isExecutable: Boolean? = null): File = withContext(Dispatchers.IO) {
        withFileLock(relativePath) {
            val targetFile = resolveSafeFile(relativePath)
            targetFile.parentFile?.mkdirs()

            if (targetFile.exists() && baselineHashes.containsKey(relativePath)) {
                val backupFile = File(baselineBackupDir, relativePath)
                if (!backupFile.exists()) {
                    backupFile.parentFile?.mkdirs()
                    targetFile.copyTo(backupFile, overwrite = true)
                }
            }

            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp_${System.nanoTime()}")
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(contentBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                val makeExec = isExecutable ?: (targetFile.name == "gradlew" || targetFile.extension == "sh")
                if (makeExec) targetFile.setExecutable(true, false)
                targetFile
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
        }
    }

    suspend fun writeTextFileAtomic(relativePath: String, content: String, isExecutable: Boolean? = null): File {
        return writeFileAtomic(relativePath, content.toByteArray(Charsets.UTF_8), isExecutable)
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        withFileLock(relativePath) {
            val targetFile = resolveSafeFile(relativePath)
            if (!targetFile.exists()) return@withFileLock false
            if (baselineHashes.containsKey(relativePath)) {
                val backupFile = File(baselineBackupDir, relativePath)
                if (!backupFile.exists()) {
                    backupFile.parentFile?.mkdirs()
                    targetFile.copyTo(backupFile, overwrite = true)
                }
            }
            targetFile.delete()
        }
    }

    fun hasConfiguredCiWorkflows(): Boolean {
        val workflowDir = File(workspaceRoot, ".github/workflows")
        return workflowDir.exists() && workflowDir.walkTopDown().any {
            it.isFile && (it.extension.equals("yml", ignoreCase = true) || it.extension.equals("yaml", ignoreCase = true))
        }
    }

    suspend fun collectAllTextFilesForAudit(): Map<String, String> = withContext(Dispatchers.IO) {
        val filesMap = mutableMapOf<String, String>()
        var accumulatedBytes = 0L
        workspaceRoot.walkTopDown()
            .filter { file ->
                if (file.isDirectory) file.name !in DEFAULT_IGNORED_DIRS && !file.name.startsWith(".baseline")
                else file.extension.lowercase() !in DEFAULT_IGNORED_EXTENSIONS && file.parentFile?.name !in DEFAULT_IGNORED_DIRS && !file.name.contains(".tmp_")
            }
            .filter { it.isFile && !isBinaryFile(it) }
            .forEach { file ->
                if (accumulatedBytes + file.length() <= MAX_TOTAL_AUDIT_BYTES) {
                    filesMap[getRelativePath(file)] = file.readText(Charsets.UTF_8)
                    accumulatedBytes += file.length()
                }
            }
        filesMap
    }

    suspend fun computeAggregatedDiff(): String = withContext(Dispatchers.IO) {
        val diffBuilder = StringBuilder()
        val delta = computeChangedFiles()

        for (relPath in delta.modifiedFiles.keys.sorted()) {
            val backupFile = File(baselineBackupDir, relPath)
            val currentFile = resolveSafeFile(relPath)
            val oldLines = if (backupFile.exists() && !isBinaryFile(backupFile)) backupFile.readLines(Charsets.UTF_8) else emptyList()
            val newLines = if (currentFile.exists() && !isBinaryFile(currentFile)) currentFile.readLines(Charsets.UTF_8) else emptyList()
            val fileDiff = generateMyersUnifiedDiff(relPath, oldLines, newLines)
            if (fileDiff.isNotBlank()) diffBuilder.append(fileDiff).append("\n")
        }

        for (relPath in delta.deletedFiles.sorted()) {
            val backupFile = File(baselineBackupDir, relPath)
            val oldLines = if (backupFile.exists() && !isBinaryFile(backupFile)) backupFile.readLines(Charsets.UTF_8) else emptyList()
            val fileDiff = generateMyersUnifiedDiff(relPath, oldLines, emptyList())
            if (fileDiff.isNotBlank()) diffBuilder.append(fileDiff).append("\n")
        }

        if (diffBuilder.isEmpty()) "No changes detected in workspace." else diffBuilder.toString().trim()
    }

    private fun generateMyersUnifiedDiff(path: String, oldLines: List<String>, newLines: List<String>, contextLines: Int = 3): String {
        val n = oldLines.size
        val m = newLines.size
        val maxD = n + m
        if (n == 0 && m == 0) return ""
        if (maxD > MAX_DIFF_LINE_SUM) {
            return "--- a/$path\n+++ b/$path\n@@ -1,$n +1,$m @@\n- [Размер превышает $MAX_DIFF_LINE_SUM строк]\n+ [Новых строк: $m]"
        }

        val v = IntArray(2 * maxD + 1)
        val trace = ArrayList<IntArray>()
        var foundD = -1

        for (d in 0..maxD) {
            val vCopy = v.clone()
            trace.add(vCopy)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[k - 1 + maxD] < v[k + 1 + maxD])) v[k + 1 + maxD] else v[k - 1 + maxD] + 1
                var y = x - k
                while (x < n && y < m && oldLines[x] == newLines[y]) { x++; y++ }
                v[k + maxD] = x
                if (x >= n && y >= m) { foundD = d; break }
                k += 2
            }
            if (foundD != -1) break
        }

        val edits = ArrayList<DiffEdit>()
        var currX = n
        var currY = m

        for (d in foundD downTo 1) {
            val vPrev = trace[d]
            val k = currX - currY
            val prevK = if (k == -d || (k != d && vPrev[k - 1 + maxD] < vPrev[k + 1 + maxD])) k + 1 else k - 1
            val prevX = vPrev[prevK + maxD]
            val prevY = prevX - prevK

            while (currX > prevX && currY > prevY) {
                currX--; currY--; edits.add(DiffEdit(DiffOp.EQUAL, currX, currY, oldLines[currX]))
            }
            if (currX > prevX) {
                currX--; edits.add(DiffEdit(DiffOp.DELETE, currX, currY, oldLines[currX]))
            } else if (currY > prevY) {
                currY--; edits.add(DiffEdit(DiffOp.INSERT, currX, currY, newLines[currY]))
            }
        }

        while (currX > 0 && currY > 0) {
            currX--; currY--; edits.add(DiffEdit(DiffOp.EQUAL, currX, currY, oldLines[currX]))
        }
        edits.reverse()
        return formatUnifiedHunks(path, edits, contextLines)
    }

    private enum class DiffOp { EQUAL, INSERT, DELETE }
    private data class DiffEdit(val op: DiffOp, val oldIdx: Int, val newIdx: Int, val text: String)

    private fun formatUnifiedHunks(path: String, edits: List<DiffEdit>, context: Int): String {
        if (edits.none { it.op != DiffOp.EQUAL }) return ""
        val sb = StringBuilder()
        sb.append("--- a/$path\n+++ b/$path\n")

        var i = 0
        while (i < edits.size) {
            if (edits[i].op == DiffOp.EQUAL) { i++; continue }
            val hunkStart = max(0, i - context)
            var hunkEnd = min(edits.size, i + context + 1)
            var lookAhead = i + 1
            while (lookAhead < edits.size) {
                if (edits[lookAhead].op != DiffOp.EQUAL && lookAhead - hunkEnd <= context * 2) {
                    hunkEnd = min(edits.size, lookAhead + context + 1)
                }
                lookAhead++
            }

            val slice = edits.subList(hunkStart, hunkEnd)
            val oldStart = slice.firstOrNull { it.op != DiffOp.INSERT }?.oldIdx?.plus(1) ?: 1
            val newStart = slice.firstOrNull { it.op != DiffOp.DELETE }?.newIdx?.plus(1) ?: 1
            val oldCount = slice.count { it.op == DiffOp.EQUAL || it.op == DiffOp.DELETE }
            val newCount = slice.count { it.op == DiffOp.EQUAL || it.op == DiffOp.INSERT }

            sb.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            for (edit in slice) {
                when (edit.op) {
                    DiffOp.EQUAL -> sb.append(" ").append(edit.text).append("\n")
                    DiffOp.DELETE -> sb.append("-").append(edit.text).append("\n")
                    DiffOp.INSERT -> sb.append("+").append(edit.text).append("\n")
                }
            }
            i = hunkEnd
        }
        return sb.toString().trimEnd()
    }

    suspend fun computeChangedFiles(): WorkspaceDeltaPayload = withContext(Dispatchers.IO) {
        val modifiedMap = mutableMapOf<String, ByteArray>()
        val deletedList = mutableListOf<String>()
        var addedCount = 0
        var modifiedCount = 0

        val currentFilesMap = mutableMapOf<String, File>()
        workspaceRoot.walkTopDown()
            .filter { file ->
                if (file.isDirectory) file.name !in DEFAULT_IGNORED_DIRS && !file.name.startsWith(".baseline")
                else file.extension.lowercase() !in DEFAULT_IGNORED_EXTENSIONS && file.parentFile?.name !in DEFAULT_IGNORED_DIRS && !file.name.contains(".tmp_")
            }
            .filter { it.isFile }
            .forEach { file -> currentFilesMap[getRelativePath(file)] = file }

        for ((relPath, file) in currentFilesMap) {
            val baseHash = baselineHashes[relPath]
            if (baseHash == null) {
                modifiedMap[relPath] = file.readBytes()
                addedCount++
            } else if (calculateSha256(file) != baseHash) {
                modifiedMap[relPath] = file.readBytes()
                modifiedCount++
            }
        }

        for (relPath in baselineHashes.keys) {
            if (!currentFilesMap.containsKey(relPath)) deletedList.add(relPath)
        }

        WorkspaceDeltaPayload(modifiedMap, deletedList, addedCount, modifiedCount, deletedList.size)
    }

    suspend fun getProjectTree(maxDepth: Int = 8, customIgnore: Set<String> = emptySet()): WorkspaceTreeDto = withContext(Dispatchers.IO) {
        val nodes = mutableListOf<WorkspaceTreeNodeDto>()
        var totalFiles = 0
        var totalDirs = 0
        val ignored = DEFAULT_IGNORED_DIRS + customIgnore

        fun walk(dir: File, currentDepth: Int) {
            if (currentDepth > maxDepth) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (child in children) {
                if (child.name in ignored || child.name.startsWith(".baseline") || child.name.contains(".tmp_")) continue
                val relPath = getRelativePath(child)
                if (child.isDirectory) {
                    totalDirs++
                    nodes.add(WorkspaceTreeNodeDto(relPath, true, 0L))
                    walk(child, currentDepth + 1)
                } else if (child.extension.lowercase() !in DEFAULT_IGNORED_EXTENSIONS) {
                    totalFiles++
                    nodes.add(WorkspaceTreeNodeDto(relPath, false, child.length()))
                }
            }
        }

        walk(workspaceRoot, 1)
        WorkspaceTreeDto(nodes, totalFiles, totalDirs)
    }

    suspend fun searchSymbol(query: String, fileExtensions: Set<String> = emptySet(), maxResults: Int = 80): List<SearchMatchDto> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchMatchDto>()
        val queryLower = query.lowercase()
        val candidateFiles = workspaceRoot.walkTopDown()
            .filter { file ->
                file.isFile && !file.name.contains(".tmp_") && file.parentFile?.name !in DEFAULT_IGNORED_DIRS &&
                (fileExtensions.isEmpty() || file.extension.lowercase() in fileExtensions) &&
                file.extension.lowercase() !in DEFAULT_IGNORED_EXTENSIONS
            }
            .toList()

        coroutineScope {
            val jobs = candidateFiles.chunked(15).map { chunk ->
                async {
                    val chunkMatches = mutableListOf<SearchMatchDto>()
                    for (file in chunk) {
                        if (isBinaryFile(file)) continue
                        val relPath = getRelativePath(file)
                        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                            var lineNum = 1
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line!!.lowercase().contains(queryLower)) {
                                    chunkMatches.add(SearchMatchDto(relPath, lineNum, line!!.trim()))
                                    if (chunkMatches.size >= maxResults) break
                                }
                                lineNum++
                            }
                        }
                        if (chunkMatches.size >= maxResults) break
                    }
                    chunkMatches
                }
            }
            for (job in jobs.awaitAll()) {
                results.addAll(job)
                if (results.size >= maxResults) break
            }
        }
        results.take(maxResults)
    }

    suspend fun wipeWorkspace(): Boolean = withContext(Dispatchers.IO) {
        baselineHashes.clear()
        fileLocks.clear()
        val sessionFolder = File(context.noBackupFilesDir, "workspaces/$sessionId")
        if (!sessionFolder.exists()) return@withContext true
        sessionFolder.walkBottomUp().all { if (it.exists()) it.delete() else true }
    }

    fun getBaselinePaths(): Set<String> = baselineHashes.keys.toSet()
}