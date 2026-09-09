package com.conveyorg.network

import com.conveyorg.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.math.max

// ====================================================================
// 1. Исключения и Модели Ошибок GitHub API
// ====================================================================

class GitHubApiException(
    val statusCode: HttpStatusCode,
    val githubMessage: String,
    val documentationUrl: String? = null,
    val rawBody: String? = null
) : Exception("GitHub API Error [$statusCode]: $githubMessage (${documentationUrl ?: "No doc link"})")

class GitHubRateLimitException(
    val resetEpochSeconds: Long,
    val retryAfterSeconds: Long,
    val isSecondaryLimit: Boolean,
    override val message: String
) : Exception(message)

@Serializable
internal data class GitHubApiErrorDto(
    val message: String = "",
    @SerialName("documentation_url") val documentationUrl: String? = null,
    val errors: List<JsonElement>? = null
)

// ====================================================================
// 2. Строго типизированные DTO Спецификации GitHub REST API (2022-11-28)
// ====================================================================

@Serializable
data class GitHubRepositoryDto(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    val private: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("html_url") val htmlUrl: String = ""
)

@Serializable
data class GitHubRefDto(
    val ref: String,
    @SerialName("node_id") val nodeId: String? = null,
    val url: String = "",
    @SerialName("object") val targetObject: GitHubRefObjectDto
)

@Serializable
data class GitHubRefObjectDto(
    val sha: String,
    val type: String,
    val url: String
)

@Serializable
internal data class CreateRefRequest(
    val ref: String,
    val sha: String
)

@Serializable
internal data class UpdateRefRequest(
    val sha: String,
    val force: Boolean = false
)

@Serializable
internal data class CreateBlobRequest(
    val content: String,
    val encoding: String
)

@Serializable
data class CreateBlobResponse(
    val sha: String,
    val url: String
)

@Serializable
data class GitTreeEntryDto(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
    val size: Long? = null,
    val content: String? = null
)

@Serializable
internal data class CreateTreeRequest(
    @SerialName("base_tree") val baseTree: String? = null,
    val tree: List<GitTreeEntryDto>
)

@Serializable
data class GitHubTreeResponseDto(
    val sha: String,
    val url: String,
    val tree: List<GitTreeEntryDto> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
internal data class CreateCommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String>
)

@Serializable
data class GitHubCommitResponseDto(
    val sha: String,
    val message: String? = null,
    val tree: GitHubRefObjectDto? = null,
    val parents: List<GitHubRefObjectDto> = emptyList()
)

@Serializable
data class GitHubCompareDto(
    val status: String = "",
    @SerialName("ahead_by") val aheadBy: Int = 0,
    @SerialName("behind_by") val behindBy: Int = 0,
    @SerialName("total_commits") val totalCommits: Int = 0,
    val files: List<GitHubCompareFileDto> = emptyList()
)

@Serializable
data class GitHubCompareFileDto(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null
)

@Serializable
data class GitHubWorkflowDto(
    val id: Long,
    val name: String,
    val path: String,
    val state: String
)

@Serializable
internal data class WorkflowListResponse(
    @SerialName("total_count") val totalCount: Int,
    val workflows: List<GitHubWorkflowDto>
)

@Serializable
internal data class WorkflowDispatchRequest(
    val ref: String,
    val inputs: Map<String, String> = emptyMap()
)

@Serializable
data class GitHubWorkflowRunDto(
    val id: Long,
    val name: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String? = null,
    val status: String,
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
internal data class WorkflowRunsResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("workflow_runs") val workflowRuns: List<GitHubWorkflowRunDto>
)

@Serializable
data class GitHubJobDto(
    val id: Long,
    @SerialName("run_id") val runId: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val steps: List<GitHubJobStepDto> = emptyList()
)

@Serializable
data class GitHubJobStepDto(
    val name: String,
    val status: String,
    val conclusion: String? = null,
    val number: Int
)

@Serializable
internal data class JobsListResponse(
    @SerialName("total_count") val totalCount: Int,
    val jobs: List<GitHubJobDto>
)

@Serializable
data class GitHubPullRequestDto(
    val id: Long,
    val number: Int,
    val title: String,
    val state: String,
    @SerialName("html_url") val htmlUrl: String,
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null
)

@Serializable
internal data class CreatePullRequestPayload(
    val title: String,
    val body: String,
    val head: String,
    val base: String,
    val draft: Boolean = false
)

@Serializable
internal data class MergePullRequestPayload(
    @SerialName("commit_title") val commitTitle: String?,
    @SerialName("commit_message") val commitMessage: String?,
    @SerialName("merge_method") val mergeMethod: String
)

@Serializable
data class GitHubMergeResultDto(
    val sha: String? = null,
    val merged: Boolean = false,
    val message: String = ""
)

@Serializable
data class GitHubCodeSearchItemDto(
    val name: String,
    val path: String,
    val sha: String,
    @SerialName("html_url") val htmlUrl: String
)

@Serializable
internal data class CodeSearchResponse(
    @SerialName("total_count") val totalCount: Int,
    val items: List<GitHubCodeSearchItemDto>
)

@Serializable
data class GitHubArtifactDto(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val sizeInBytes: Long,
    @SerialName("archive_download_url") val archiveDownloadUrl: String,
    val expired: Boolean
)

@Serializable
internal data class ArtifactsResponse(
    @SerialName("total_count") val totalCount: Int,
    val artifacts: List<GitHubArtifactDto>
)

@Serializable
internal data class GraphQLRequest(
    val query: String,
    val variables: Map<String, String> = emptyMap()
)

data class PushResult(
    val commitSha: String,
    val treeSha: String,
    val updatedFilesCount: Int,
    val branch: String
)

// ====================================================================
// 3. Высокопроизводительное Ядро: GitHubEngine
// ====================================================================

class GitHubEngine(
    private val tokenProvider: () -> String,
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val shouldCloseHttpClient: Boolean = true
) : Closeable {

    companion object {
        private const val API_BASE_URL = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"

        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 64
                endpoint {
                    maxConnectionsPerRoute = 32
                    pipelineMaxSize = 1
                    keepAliveTime = 120_000
                    connectTimeout = 30_000
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 180_000L
                socketTimeoutMillis = 120_000L
                connectTimeoutMillis = 30_000L
            }
        }
    }

    private fun getAuthToken(): String {
        val token = tokenProvider().trim()
        if (token.isBlank()) {
            throw GitHubApiException(
                statusCode = HttpStatusCode.Unauthorized,
                githubMessage = "GitHub Personal Access Token (PAT) не задан."
            )
        }
        return token
    }

    private fun HttpRequestBuilder.applyStandardHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    private suspend fun checkResponseErrors(response: HttpResponse) {
        val status = response.status
        if (status.isSuccess()) return

        val headers = response.headers
        val remaining = headers["x-ratelimit-remaining"]?.toIntOrNull() ?: -1
        val resetEpoch = headers["x-ratelimit-reset"]?.toLongOrNull() ?: 0L
        val retryAfter = headers["retry-after"]?.toLongOrNull()

        val rawBody = response.bodyAsText()
        val parsedError = runCatching {
            json.decodeFromString(GitHubApiErrorDto.serializer(), rawBody)
        }.getOrNull()

        if (status == HttpStatusCode.Forbidden || status == HttpStatusCode.TooManyRequests) {
            if (remaining == 0 || retryAfter != null) {
                val nowEpoch = System.currentTimeMillis() / 1000L
                val calculatedWait = when {
                    retryAfter != null -> retryAfter
                    resetEpoch > nowEpoch -> resetEpoch - nowEpoch
                    else -> 60L
                }
                throw GitHubRateLimitException(
                    resetEpochSeconds = resetEpoch,
                    retryAfterSeconds = max(1L, calculatedWait),
                    isSecondaryLimit = retryAfter != null,
                    message = "Превышен лимит запросов GitHub (Лимит сбросится через $calculatedWait сек)."
                )
            }
        }

        throw GitHubApiException(
            statusCode = status,
            githubMessage = parsedError?.message?.ifBlank { "Сбой API GitHub" } ?: "HTTP ${status.value}",
            documentationUrl = parsedError?.documentationUrl,
            rawBody = rawBody
        )
    }

    suspend fun getRepository(owner: String, repo: String): GitHubRepositoryDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubRepositoryDto.serializer(), response.bodyAsText())
    }

    suspend fun getRef(owner: String, repo: String, ref: String): GitHubRefDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val cleanRef = ref.removePrefix("refs/")
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/git/ref/$cleanRef") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubRefDto.serializer(), response.bodyAsText())
    }

    suspend fun createBranch(owner: String, repo: String, newBranchName: String, fromSha: String): GitHubRefDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val fullRef = "refs/heads/${newBranchName.removePrefix("heads/").removePrefix("refs/")}"
        val payload = CreateRefRequest(ref = fullRef, sha = fromSha)

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/git/refs") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateRefRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Ветка $fullRef успешно создана от $fromSha")
        json.decodeFromString(GitHubRefDto.serializer(), response.bodyAsText())
    }

    suspend fun updateRef(owner: String, repo: String, ref: String, newSha: String, force: Boolean = false): GitHubRefDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val cleanRef = ref.removePrefix("refs/")
        val payload = UpdateRefRequest(sha = newSha, force = force)

        val response = httpClient.patch("$API_BASE_URL/repos/$owner/$repo/git/refs/$cleanRef") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateRefRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubRefDto.serializer(), response.bodyAsText())
    }

    suspend fun getTreeRecursive(
        owner: String,
        repo: String,
        branch: String
    ): GitHubTreeResponseDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val cleanBranch = branch.removePrefix("refs/heads/").removePrefix("heads/")
        val endpoint = "$API_BASE_URL/repos/$owner/$repo/git/trees/$cleanBranch?recursive=1"

        val response = httpClient.get(endpoint) {
            applyStandardHeaders(token)
        }

        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Conflict) {
            return@withContext GitHubTreeResponseDto(
                sha = "",
                url = "",
                tree = emptyList(),
                truncated = false
            )
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubTreeResponseDto.serializer(), response.bodyAsText())
    }

    suspend fun deleteRef(owner: String, repo: String, ref: String): Boolean = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val cleanRef = ref.removePrefix("refs/")
        val response = httpClient.delete("$API_BASE_URL/repos/$owner/$repo/git/refs/$cleanRef") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Ссылка $cleanRef удалена.")
        true
    }

    suspend fun compareCommits(owner: String, repo: String, base: String, head: String): GitHubCompareDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/compare/$base...$head") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubCompareDto.serializer(), response.bodyAsText())
    }

    suspend fun downloadAndUnpackZipball(
        owner: String,
        repo: String,
        ref: String,
        targetDir: File
    ): File = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val canonicalTargetDir = targetDir.canonicalFile
        if (!canonicalTargetDir.exists()) canonicalTargetDir.mkdirs()

        val endpoint = "$API_BASE_URL/repos/$owner/$repo/zipball/$ref"
        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Запрос zipball архива репозитория $owner/$repo ($ref)...")

        val initialResponse = httpClient.prepareGet(endpoint) {
            applyStandardHeaders(token)
        }.execute()

        val s3Location = when (initialResponse.status) {
            HttpStatusCode.Found, HttpStatusCode.MovedPermanently, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect -> {
                initialResponse.headers[HttpHeaders.Location]
                    ?: throw GitHubApiException(initialResponse.status, "302 Redirect без заголовка Location")
            }
            HttpStatusCode.OK -> null
            else -> {
                checkResponseErrors(initialResponse)
                null
            }
        }

        val downloadBlock: suspend (InputStream) -> Unit = { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val segments = entryName.split('/')
                    if (segments.size > 1) {
                        val relativePath = segments.drop(1).joinToString("/")
                        if (relativePath.isNotBlank()) {
                            val destFile = File(canonicalTargetDir, relativePath).canonicalFile

                            if (!destFile.path.startsWith(canonicalTargetDir.path + File.separator) && destFile != canonicalTargetDir) {
                                throw SecurityException("Обнаружена попытка выхода за пределы рабочей директории (Zip Slip): $entryName")
                            }

                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { fos ->
                                    val buffer = ByteArray(8192)
                                    var read: Int
                                    while (zipIn.read(buffer).also { read = it } != -1) {
                                        fos.write(buffer, 0, read)
                                    }
                                }

                                val fileName = destFile.name
                                if (fileName == "gradlew" || fileName.endsWith(".sh")) {
                                    destFile.setExecutable(true, false)
                                }
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }

        if (s3Location != null) {
            AppLogger.d(AppLogger.TAG_NET, "GitHubEngine: Скачивание архива из защищенного хранилища S3...")
            httpClient.prepareGet(s3Location).execute { s3Response ->
                if (!s3Response.status.isSuccess()) {
                    throw GitHubApiException(s3Response.status, "Ошибка скачивания zipball из хранилища: ${s3Response.status}")
                }
                downloadBlock(s3Response.bodyAsChannel().toInputStream())
            }
        } else {
            downloadBlock(initialResponse.bodyAsChannel().toInputStream())
        }

        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Репозиторий успешно распакован в ${canonicalTargetDir.absolutePath}")
        canonicalTargetDir
    }

    suspend fun createBlob(owner: String, repo: String, contentBytes: ByteArray, isBinary: Boolean): String = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = if (isBinary) {
            CreateBlobRequest(
                content = Base64.getEncoder().encodeToString(contentBytes),
                encoding = "base64"
            )
        } else {
            CreateBlobRequest(
                content = String(contentBytes, Charsets.UTF_8),
                encoding = "utf-8"
            )
        }

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/git/blobs") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateBlobRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(CreateBlobResponse.serializer(), response.bodyAsText())
        parsed.sha
    }

    suspend fun createTree(
        owner: String,
        repo: String,
        baseTreeSha: String?,
        entries: List<GitTreeEntryDto>
    ): GitHubTreeResponseDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = CreateTreeRequest(baseTree = baseTreeSha, tree = entries)

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/git/trees") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateTreeRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubTreeResponseDto.serializer(), response.bodyAsText())
    }

    suspend fun createCommit(
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentsSha: List<String>
    ): GitHubCommitResponseDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = CreateCommitRequest(message = message, tree = treeSha, parents = parentsSha)

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/git/commits") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCommitRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubCommitResponseDto.serializer(), response.bodyAsText())
    }

    suspend fun pushAtomicCommit(
        owner: String,
        repo: String,
        branch: String,
        commitMessage: String,
        modifiedFiles: Map<String, ByteArray>,
        deletedFiles: List<String> = emptyList()
    ): PushResult = withContext(Dispatchers.IO) {
        if (modifiedFiles.isEmpty() && deletedFiles.isEmpty()) {
            throw IllegalArgumentException("Список измененных и удаленных файлов пуст. Нечего коммитить.")
        }

        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Старт атомарного пуша (${modifiedFiles.size} изм, ${deletedFiles.size} уд) в $branch...")

        val cleanBranch = branch.removePrefix("refs/heads/").removePrefix("heads/")
        val headRef = getRef(owner, repo, "heads/$cleanBranch")
        val parentCommitSha = headRef.targetObject.sha

        val token = getAuthToken()
        val commitResponse = httpClient.get("$API_BASE_URL/repos/$owner/$repo/git/commits/$parentCommitSha") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(commitResponse)
        val parentCommitDto = json.decodeFromString(GitHubCommitResponseDto.serializer(), commitResponse.bodyAsText())
        val baseTreeSha = parentCommitDto.tree?.sha
            ?: throw IllegalStateException("Не удалось получить базовое дерево родительского коммита $parentCommitSha")

        val binaryExtensions = setOf("png", "jpg", "jpeg", "webp", "jar", "so", "class", "keystore", "jks", "zip", "ico")
        val modifiedEntries = coroutineScope {
            modifiedFiles.map { (path, bytes) ->
                async {
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val isBinary = ext in binaryExtensions
                    val blobSha = createBlob(owner, repo, bytes, isBinary)

                    val fileName = path.substringAfterLast('/')
                    val mode = if (fileName == "gradlew" || fileName.endsWith(".sh")) "100755" else "100644"

                    GitTreeEntryDto(
                        path = path.replace('\\', '/').removePrefix("/"),
                        mode = mode,
                        type = "blob",
                        sha = blobSha
                    )
                }
            }.awaitAll()
        }

        val deletedEntries = deletedFiles.map { path ->
            GitTreeEntryDto(
                path = path.replace('\\', '/').removePrefix("/"),
                mode = "100644",
                type = "blob",
                sha = null
            )
        }

        val treeEntries = modifiedEntries + deletedEntries
        val newTree = createTree(owner, repo, baseTreeSha = baseTreeSha, entries = treeEntries)

        val newCommit = createCommit(
            owner = owner,
            repo = repo,
            message = commitMessage,
            treeSha = newTree.sha,
            parentsSha = listOf(parentCommitSha)
        )

        updateRef(owner, repo, "heads/$cleanBranch", newCommit.sha, force = false)

        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: УСПЕХ! Зафиксирован атомарный коммит ${newCommit.sha} в ветке $cleanBranch")

        PushResult(
            commitSha = newCommit.sha,
            treeSha = newTree.sha,
            updatedFilesCount = modifiedFiles.size + deletedFiles.size,
            branch = cleanBranch
        )
    }

    suspend fun listWorkflows(owner: String, repo: String): List<GitHubWorkflowDto> = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/actions/workflows") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(WorkflowListResponse.serializer(), response.bodyAsText())
        parsed.workflows
    }

    suspend fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowIdOrFileName: String,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = WorkflowDispatchRequest(ref = ref, inputs = inputs)

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/actions/workflows/$workflowIdOrFileName/dispatches") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(WorkflowDispatchRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Workflow $workflowIdOrFileName запущен для $ref")
        true
    }

    suspend fun getWorkflowRuns(
        owner: String,
        repo: String,
        branch: String? = null
    ): List<GitHubWorkflowRunDto> = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val url = "$API_BASE_URL/repos/$owner/$repo/actions/runs"
        val response = httpClient.get(url) {
            applyStandardHeaders(token)
            if (!branch.isNullOrBlank()) {
                parameter("branch", branch.removePrefix("refs/heads/"))
            }
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(WorkflowRunsResponse.serializer(), response.bodyAsText())
        parsed.workflowRuns
    }

    suspend fun pollWorkflowRunConclusion(
        owner: String,
        repo: String,
        runId: Long,
        pollIntervalMs: Long = 6000L,
        timeoutMs: Long = 600_000L
    ): GitHubWorkflowRunDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val startTime = System.currentTimeMillis()

        while (isActive) {
            val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/actions/runs/$runId") {
                applyStandardHeaders(token)
            }
            checkResponseErrors(response)
            val run = json.decodeFromString(GitHubWorkflowRunDto.serializer(), response.bodyAsText())

            if (run.status.equals("completed", ignoreCase = true)) {
                AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Сборка $runId завершена с вердиктом: ${run.conclusion}")
                return@withContext run
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw GitHubApiException(HttpStatusCode.GatewayTimeout, "Превышено время ожидания сборки CI ($timeoutMs мс)")
            }

            delay(pollIntervalMs)
        }
        throw kotlinx.coroutines.CancellationException("Опрос сборки был отменен")
    }

    suspend fun getWorkflowRunJobs(owner: String, repo: String, runId: Long): List<GitHubJobDto> = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/actions/runs/$runId/jobs") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(JobsListResponse.serializer(), response.bodyAsText())
        parsed.jobs
    }

    suspend fun downloadJobFailureLog(owner: String, repo: String, jobId: Long): String = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val endpoint = "$API_BASE_URL/repos/$owner/$repo/actions/jobs/$jobId/logs"

        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: Запрос лога задачи $jobId...")

        val initialResponse = httpClient.prepareGet(endpoint) {
            applyStandardHeaders(token)
        }.execute()

        val s3Location = when (initialResponse.status) {
            HttpStatusCode.Found, HttpStatusCode.MovedPermanently, HttpStatusCode.SeeOther, HttpStatusCode.TemporaryRedirect -> {
                initialResponse.headers[HttpHeaders.Location]
            }
            HttpStatusCode.OK -> null
            else -> {
                checkResponseErrors(initialResponse)
                null
            }
        }

        val logTextBuilder = StringBuilder()
        val streamBlock: suspend (InputStream) -> Unit = { inputStream ->
            val reader = inputStream.bufferedReader(Charsets.UTF_8)
            val errorContextLines = mutableListOf<String>()
            var isInsideCompilationError = false

            reader.forEachLine { line ->
                val lower = line.lowercase()
                if (lower.contains("e: ") || lower.contains("failure:") || lower.contains("compilation error") || lower.contains("what went wrong:")) {
                    isInsideCompilationError = true
                }

                if (isInsideCompilationError) {
                    errorContextLines.add(line)
                    if (errorContextLines.size >= 120) {
                        isInsideCompilationError = false
                    }
                }
            }

            if (errorContextLines.isNotEmpty()) {
                logTextBuilder.append(errorContextLines.joinToString("\n"))
            } else {
                logTextBuilder.append("Ошибки компилятора не выделены автоматически. Код задачи завершился сбоем.")
            }
        }

        if (s3Location != null) {
            httpClient.prepareGet(s3Location).execute { s3Response ->
                if (!s3Response.status.isSuccess()) {
                    throw GitHubApiException(s3Response.status, "Ошибка выкачки лога из S3: ${s3Response.status}")
                }
                streamBlock(s3Response.bodyAsChannel().toInputStream())
            }
        } else {
            streamBlock(initialResponse.bodyAsChannel().toInputStream())
        }

        logTextBuilder.toString()
    }

    suspend fun cancelWorkflowRun(owner: String, repo: String, runId: Long): Boolean = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/actions/runs/$runId/cancel") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        true
    }

    suspend fun rerunWorkflow(owner: String, repo: String, runId: Long): Boolean = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/actions/runs/$runId/rerun") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        true
    }

    suspend fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        body: String,
        headBranch: String,
        baseBranch: String = "main",
        isDraft: Boolean = false
    ): GitHubPullRequestDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = CreatePullRequestPayload(
            title = title,
            body = body,
            head = headBranch.removePrefix("refs/heads/"),
            base = baseBranch.removePrefix("refs/heads/"),
            draft = isDraft
        )

        val response = httpClient.post("$API_BASE_URL/repos/$owner/$repo/pulls") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreatePullRequestPayload.serializer(), payload))
        }
        checkResponseErrors(response)
        AppLogger.i(AppLogger.TAG_NET, "GitHubEngine: PR создан: $title")
        json.decodeFromString(GitHubPullRequestDto.serializer(), response.bodyAsText())
    }

    suspend fun getPullRequest(owner: String, repo: String, pullNumber: Int): GitHubPullRequestDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/pulls/$pullNumber") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubPullRequestDto.serializer(), response.bodyAsText())
    }

    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        pullNumber: Int,
        commitTitle: String? = null,
        commitMessage: String? = null,
        mergeMethod: String = "squash"
    ): GitHubMergeResultDto = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = MergePullRequestPayload(
            commitTitle = commitTitle,
            commitMessage = commitMessage,
            mergeMethod = mergeMethod
        )

        val response = httpClient.put("$API_BASE_URL/repos/$owner/$repo/pulls/$pullNumber/merge") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MergePullRequestPayload.serializer(), payload))
        }
        checkResponseErrors(response)
        json.decodeFromString(GitHubMergeResultDto.serializer(), response.bodyAsText())
    }

    suspend fun searchCode(owner: String, repo: String, query: String): List<GitHubCodeSearchItemDto> = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val qualifiedQuery = "$query repo:$owner/$repo"
        val response = httpClient.get("$API_BASE_URL/search/code") {
            applyStandardHeaders(token)
            parameter("q", qualifiedQuery)
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(CodeSearchResponse.serializer(), response.bodyAsText())
        parsed.items
    }

    suspend fun listArtifacts(owner: String, repo: String, runId: Long): List<GitHubArtifactDto> = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val response = httpClient.get("$API_BASE_URL/repos/$owner/$repo/actions/runs/$runId/artifacts") {
            applyStandardHeaders(token)
        }
        checkResponseErrors(response)
        val parsed = json.decodeFromString(ArtifactsResponse.serializer(), response.bodyAsText())
        parsed.artifacts
    }

    suspend fun executeRawRest(
        method: String,
        endpointPath: String,
        queryParams: Map<String, String> = emptyMap(),
        jsonBody: String? = null
    ): String = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val cleanPath = endpointPath.removePrefix("/")
        val url = if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) cleanPath else "$API_BASE_URL/$cleanPath"

        val response = httpClient.request(url) {
            this.method = HttpMethod.parse(method.uppercase())
            applyStandardHeaders(token)
            queryParams.forEach { (k, v) -> parameter(k, v) }
            if (!jsonBody.isNullOrBlank()) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }
        checkResponseErrors(response)
        response.bodyAsText()
    }

    suspend fun executeGraphQL(query: String, variables: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        val payload = GraphQLRequest(query = query, variables = variables)

        val response = httpClient.post("$API_BASE_URL/graphql") {
            applyStandardHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GraphQLRequest.serializer(), payload))
        }
        checkResponseErrors(response)
        response.bodyAsText()
    }

    override fun close() {
        if (shouldCloseHttpClient) {
            httpClient.close()
        }
    }
}