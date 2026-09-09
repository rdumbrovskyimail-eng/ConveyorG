package com.conveyorg.network

import com.conveyorg.data.LocalWorkspaceManager
import com.conveyorg.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class GeminiToolDto(
    @SerialName("functionDeclarations") val functionDeclarations: List<FunctionDeclarationDto>? = null,
    @SerialName("googleSearch") val googleSearch: Map<String, String>? = null
)

@Serializable
data class FunctionDeclarationDto(
    val name: String,
    val description: String,
    val parameters: FunctionParametersSchemaDto
)

@Serializable
data class FunctionParametersSchemaDto(
    val type: String = "OBJECT",
    val properties: Map<String, ParameterPropertyDto>,
    val required: List<String> = emptyList()
)

@Serializable
data class ParameterPropertyDto(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val items: ParameterPropertyDto? = null,
    val properties: Map<String, ParameterPropertyDto>? = null
)

@Serializable
data class FunctionCallDto(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val id: String? = null
)

@Serializable
data class FunctionResponseDto(
    val name: String,
    val response: JsonObject,
    val id: String? = null
)

@Serializable
data class FunctionResponsePartDto(
    @SerialName("functionResponse") val functionResponse: FunctionResponseDto
)

data class ToolExecutionResult(
    val toolName: String,
    val callId: String?,
    val isSuccess: Boolean,
    val payload: JsonObject,
    val executionDurationMs: Long,
    val thoughtSignature: String? = null
)

interface OrchestratorBridge {
    fun getToolDeclarations(): GeminiToolDto
    suspend fun dispatchToolCall(call: FunctionCallDto, thoughtSignature: String? = null): FunctionResponsePartDto
}

class OrchestratorToolBridge(
    private val workspaceManager: LocalWorkspaceManager,
    private val gitHubEngine: GitHubEngine
) : OrchestratorBridge {

    companion object {
        private const val TOOL_EXECUTION_TIMEOUT_MS = 60_000L
        private const val MAX_OUTPUT_CHARS = 40_000

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    override fun getToolDeclarations(): GeminiToolDto {
        return GeminiToolDto(
            functionDeclarations = listOf(
                FunctionDeclarationDto(
                    name = "workspace_get_tree",
                    description = "Возвращает структуру дерева локального репозитория.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "max_depth" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Максимальная глубина рекурсивного сканирования папок (по умолчанию 8)."
                            )
                        )
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_read_file",
                    description = "Читает содержимое локального файла из рабочей области. Поддерживает постраничное чтение.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Относительный путь к файлу."
                            ),
                            "start_line" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Начальный номер строки (1-based, опционально)."
                            ),
                            "end_line" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Конечный номер строки (опционально)."
                            )
                        ),
                        required = listOf("path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_write_file",
                    description = "Атомарно записывает текстовый файл на диск. Применяйте для конфигов (.gitignore, .gitattributes, gradle.properties), мелких скриптов и правок без вызова роя.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Относительный путь к файлу."
                            ),
                            "content" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Полный исходный код файла без Markdown-разметки."
                            ),
                            "is_executable" to ParameterPropertyDto(
                                type = "BOOLEAN",
                                description = "Установить chmod +x (true для gradlew и .sh файлов)."
                            )
                        ),
                        required = listOf("path", "content")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_batch_write",
                    description = "Атомарно создает группу файлов за 1 шаг. Идеально для одновременного создания .gitignore, .gitattributes и сборочных файлов.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "files" to ParameterPropertyDto(
                                type = "OBJECT",
                                description = "Словарь вида { 'путь': 'содержимое' }.",
                                properties = emptyMap()
                            )
                        ),
                        required = listOf("files")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_delete_file",
                    description = "Удаляет локальный файл из рабочей области.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Относительный путь к файлу."
                            )
                        ),
                        required = listOf("path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_search_symbol",
                    description = "Выполняет быстрый поиск символа (Grep) по проекту.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "query" to ParameterPropertyDto(type = "STRING", description = "Строка поиска."),
                            "file_extensions" to ParameterPropertyDto(
                                type = "ARRAY",
                                description = "Фильтр расширений (например, ['kt', 'kts']).",
                                items = ParameterPropertyDto(type = "STRING", description = "Расширение")
                            ),
                            "max_results" to ParameterPropertyDto(type = "INTEGER", description = "Лимит результатов (по умолчанию 50).")
                        ),
                        required = listOf("query")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_read_diff",
                    description = "Генерирует сводный Unified Diff всех локальных изменений.",
                    parameters = FunctionParametersSchemaDto(properties = emptyMap())
                ),
                FunctionDeclarationDto(
                    name = "github_push_atomic_commit",
                    description = "Формирует дельту на диске, создает блобы, дерево и отправляет в ветку GitHub ОДНИМ атомарным коммитом.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "branch" to ParameterPropertyDto(type = "STRING", description = "Целевая ветка."),
                            "commit_message" to ParameterPropertyDto(type = "STRING", description = "Сообщение коммита.")
                        ),
                        required = listOf("owner", "repo", "branch", "commit_message")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_trigger_ci_build",
                    description = "Запускает GitHub Actions. Вызывайте ТОЛЬКО если в .github/workflows/*.yml есть настроенный workflow.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Репозиторий."),
                            "workflow_id" to ParameterPropertyDto(type = "STRING", description = "Имя файла воркфлоу."),
                            "ref" to ParameterPropertyDto(type = "STRING", description = "Ветка.")
                        ),
                        required = listOf("owner", "repo", "workflow_id", "ref")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_status",
                    description = "Опрашивает статус Actions. Вызывайте ТОЛЬКО если CI запущен и есть реальный run_id.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Репозиторий."),
                            "run_id" to ParameterPropertyDto(type = "INTEGER", description = "Идентификатор запуска.")
                        ),
                        required = listOf("owner", "repo", "run_id")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_logs",
                    description = "Скачивает лог упавшей задачи Actions для извлечения ошибок компилятора.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Репозиторий."),
                            "job_id" to ParameterPropertyDto(type = "INTEGER", description = "Идентификатор задачи.")
                        ),
                        required = listOf("owner", "repo", "job_id")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_create_pull_request",
                    description = "Создает Pull Request из рабочей ветки в базовую.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Репозиторий."),
                            "title" to ParameterPropertyDto(type = "STRING", description = "Заголовок PR."),
                            "body" to ParameterPropertyDto(type = "STRING", description = "Описание PR."),
                            "head_branch" to ParameterPropertyDto(type = "STRING", description = "Рабочая ветка."),
                            "base_branch" to ParameterPropertyDto(type = "STRING", description = "Базовая ветка (по умолчанию main).")
                        ),
                        required = listOf("owner", "repo", "title", "body", "head_branch")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_merge_pull_request",
                    description = "Выполняет слияние Pull Request.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Репозиторий."),
                            "pull_number" to ParameterPropertyDto(type = "INTEGER", description = "Номер PR."),
                            "commit_title" to ParameterPropertyDto(type = "STRING", description = "Заголовок коммита."),
                            "merge_method" to ParameterPropertyDto(type = "STRING", description = "squash, merge или rebase.")
                        ),
                        required = listOf("owner", "repo", "pull_number")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_execute_raw_rest",
                    description = "Произвольный REST-запрос к GitHub API.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "method" to ParameterPropertyDto(type = "STRING", description = "GET, POST, PUT, PATCH, DELETE"),
                            "endpoint_path" to ParameterPropertyDto(type = "STRING", description = "Путь эндпоинта."),
                            "query_params" to ParameterPropertyDto(type = "OBJECT", description = "Параметры.", properties = emptyMap()),
                            "json_body" to ParameterPropertyDto(type = "STRING", description = "JSON тело.")
                        ),
                        required = listOf("method", "endpoint_path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_execute_graphql",
                    description = "Выполняет GraphQL запрос к GitHub API v4.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "query" to ParameterPropertyDto(type = "STRING", description = "GraphQL запрос."),
                            "variables" to ParameterPropertyDto(type = "OBJECT", description = "Переменные.", properties = emptyMap())
                        ),
                        required = listOf("query")
                    )
                )
            )
        )
    }

    override suspend fun dispatchToolCall(
        call: FunctionCallDto,
        thoughtSignature: String?
    ): FunctionResponsePartDto = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        AppLogger.i(AppLogger.TAG_ENGINE, "ToolBridge: Запуск '${call.name}' (id=${call.id})...")

        val resultPayload: JsonObject = try {
            withTimeout(TOOL_EXECUTION_TIMEOUT_MS) {
                routeCallInternal(call.name, call.args)
            }
        } catch (e: Exception) {
            AppLogger.e(AppLogger.TAG_ENGINE, "ToolBridge: Ошибка исполнения '${call.name}': ${e.message}", e)
            buildErrorPayload(call.name, e)
        }

        val duration = System.currentTimeMillis() - startTime
        AppLogger.i(AppLogger.TAG_ENGINE, "ToolBridge: Инструмент '${call.name}' выполнен за $duration мс")

        FunctionResponsePartDto(
            functionResponse = FunctionResponseDto(
                name = call.name,
                response = buildJsonObject {
                    put("output", resultPayload)
                },
                id = call.id
            )
        )
    }

    private suspend fun routeCallInternal(name: String, args: JsonObject): JsonObject {
        return when (name) {
            "workspace_get_tree" -> {
                val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 8
                val treeDto = workspaceManager.getProjectTree(maxDepth = maxDepth)
                buildJsonObject {
                    put("status", "success")
                    put("total_files", treeDto.totalFiles)
                    put("total_directories", treeDto.totalDirectories)
                    putJsonArray("nodes") {
                        treeDto.nodes.take(300).forEach { node ->
                            addJsonObject {
                                put("path", node.path)
                                put("is_dir", node.isDirectory)
                                put("size_bytes", node.sizeBytes)
                            }
                        }
                    }
                    if (treeDto.nodes.size > 300) {
                        put("warning", "Показаны первые 300 элементов дерева.")
                    }
                }
            }

            "workspace_read_file" -> {
                val path = args.getRequiredString("path")
                val startLine = args["start_line"]?.jsonPrimitive?.intOrNull
                val endLine = args["end_line"]?.jsonPrimitive?.intOrNull

                val readResult = workspaceManager.readFile(path, startLine, endLine)
                buildJsonObject {
                    put("status", "success")
                    put("path", readResult.relativePath)
                    put("start_line", readResult.startLine)
                    put("end_line", readResult.endLine)
                    put("total_lines", readResult.totalLines)
                    put("is_binary", readResult.isBinary)
                    put("is_truncated", readResult.isTruncated)
                    put("content", safeTruncate(readResult.content))
                }
            }

            "workspace_write_file" -> {
                val path = args.getRequiredString("path")
                val content = args.getRequiredString("content")
                val isExecutable = args["is_executable"]?.jsonPrimitive?.booleanOrNull

                val cleanContent = content.trim()
                    .replace(Regex("^```[a-zA-Z0-9_-]*\\r?\\n"), "")
                    .replace(Regex("\\r?\\n```$"), "")

                val writtenFile = workspaceManager.writeTextFileAtomic(
                    relativePath = path,
                    content = cleanContent,
                    isExecutable = isExecutable
                )

                buildJsonObject {
                    put("status", "success")
                    put("path", path)
                    put("bytes_written", writtenFile.length())
                    put("message", "Файл '$path' успешно записан на диск.")
                }
            }

            "workspace_batch_write" -> {
                val filesObj = args["files"]?.jsonObject
                    ?: throw IllegalArgumentException("Обязательный параметр 'files' отсутствует.")

                val writtenPaths = mutableListOf<String>()
                filesObj.forEach { (path, contentElem) ->
                    val clean = contentElem.jsonPrimitive.content.trim()
                        .replace(Regex("^```[a-zA-Z0-9_-]*\\r?\\n"), "")
                        .replace(Regex("\\r?\\n```$"), "")
                    workspaceManager.writeTextFileAtomic(path, clean)
                    writtenPaths.add(path)
                }

                buildJsonObject {
                    put("status", "success")
                    put("files_written_count", writtenPaths.size)
                    putJsonArray("written_paths") {
                        writtenPaths.forEach { add(JsonPrimitive(it)) }
                    }
                    put("message", "Пакет из ${writtenPaths.size} файлов записан за 1 шаг.")
                }
            }

            "workspace_delete_file" -> {
                val path = args.getRequiredString("path")
                val deleted = workspaceManager.deleteFile(path)
                buildJsonObject {
                    put("status", if (deleted) "success" else "not_found")
                    put("path", path)
                }
            }

            "workspace_search_symbol" -> {
                val query = args.getRequiredString("query")
                val exts = args["file_extensions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
                val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 50

                val matches = workspaceManager.searchSymbol(query, exts, maxResults)
                buildJsonObject {
                    put("status", "success")
                    put("query", query)
                    put("matches_count", matches.size)
                    putJsonArray("matches") {
                        matches.forEach { match ->
                            addJsonObject {
                                put("file", match.relativePath)
                                put("line", match.lineNumber)
                                put("content", safeTruncate(match.lineContent, 400))
                            }
                        }
                    }
                }
            }

            "workspace_read_diff" -> {
                val diffOutput = workspaceManager.computeAggregatedDiff()
                buildJsonObject {
                    put("status", "success")
                    put("has_changes", diffOutput != "No changes detected in workspace.")
                    put("unified_diff", safeTruncate(diffOutput))
                }
            }

            "github_push_atomic_commit" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val branch = args.getRequiredString("branch")
                val message = args.getRequiredString("commit_message")

                val delta = workspaceManager.computeChangedFiles()
                if (delta.modifiedFiles.isEmpty() && delta.deletedFiles.isEmpty()) {
                    return buildJsonObject {
                        put("status", "nothing_to_commit")
                        put("message", "На диске нет изменений для фиксации.")
                    }
                }

                val pushResult = gitHubEngine.pushAtomicCommit(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    commitMessage = message,
                    modifiedFiles = delta.modifiedFiles,
                    deletedFiles = delta.deletedFiles
                )

                buildJsonObject {
                    put("status", "success")
                    put("commit_sha", pushResult.commitSha)
                    put("tree_sha", pushResult.treeSha)
                    put("branch", pushResult.branch)
                    put("files_committed_count", pushResult.updatedFilesCount)
                    put("added_count", delta.addedCount)
                    put("modified_count", delta.modifiedCount)
                    put("deleted_count", delta.deletedCount)
                }
            }

            // МГНОВЕННЫЙ FAIL-FAST (0 МС) ЕСЛИ В РЕПОЗИТОРИИ НЕТ WORKFLOWS:
            "github_trigger_ci_build" -> {
                if (!workspaceManager.hasConfiguredCiWorkflows()) {
                    return buildJsonObject {
                        put("status", "skipped")
                        put("message", "В репозитории нет каталога .github/workflows/*.yml. Запуск CI не требуется.")
                    }
                }

                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val workflowId = args.getRequiredString("workflow_id")
                val ref = args.getRequiredString("ref")

                val dispatched = gitHubEngine.dispatchWorkflow(owner, repo, workflowId, ref)
                buildJsonObject {
                    put("status", if (dispatched) "dispatched" else "failed")
                    put("workflow", workflowId)
                    put("ref", ref)
                }
            }

            "github_get_ci_status" -> {
                if (!workspaceManager.hasConfiguredCiWorkflows()) {
                    return buildJsonObject {
                        put("status", "skipped")
                        put("conclusion", "success")
                        put("message", "В репозитории нет воркфлоу CI. Проверка пропущена (локальный коммит валиден).")
                    }
                }

                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val runId = args.getRequiredLong("run_id")

                val finishedRun = gitHubEngine.pollWorkflowRunConclusion(
                    owner = owner,
                    repo = repo,
                    runId = runId,
                    pollIntervalMs = 5000L,
                    timeoutMs = 60_000L // Сокращено до 60 секунд
                )

                buildJsonObject {
                    put("status", "success")
                    put("run_id", runId)
                    put("run_status", finishedRun.status)
                    put("conclusion", finishedRun.conclusion ?: "pending")
                    put("html_url", finishedRun.htmlUrl)
                }
            }

            "github_get_ci_logs" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val jobId = args.getRequiredLong("job_id")

                val failureLog = gitHubEngine.downloadJobFailureLog(owner, repo, jobId)
                buildJsonObject {
                    put("status", "success")
                    put("job_id", jobId)
                    put("compiler_errors_extracted", safeTruncate(failureLog))
                }
            }

            "github_create_pull_request" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val title = args.getRequiredString("title")
                val body = args.getRequiredString("body")
                val head = args.getRequiredString("head_branch")
                val base = args["base_branch"]?.jsonPrimitive?.content ?: "main"

                val pr = gitHubEngine.createPullRequest(owner, repo, title, body, head, base)
                buildJsonObject {
                    put("status", "success")
                    put("pr_number", pr.number)
                    put("html_url", pr.htmlUrl)
                    put("state", pr.state)
                }
            }

            "github_merge_pull_request" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val pullNumber = args.getRequiredInt("pull_number")
                val title = args["commit_title"]?.jsonPrimitive?.contentOrNull
                val method = args["merge_method"]?.jsonPrimitive?.contentOrNull ?: "squash"

                val mergeRes = gitHubEngine.mergePullRequest(owner, repo, pullNumber, title, null, method)
                buildJsonObject {
                    put("status", if (mergeRes.merged) "merged" else "failed")
                    put("merged", mergeRes.merged)
                    put("sha", mergeRes.sha ?: "")
                }
            }

            "github_execute_raw_rest" -> {
                val method = args.getRequiredString("method")
                val path = args.getRequiredString("endpoint_path")
                val params = args["query_params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val body = args["json_body"]?.jsonPrimitive?.contentOrNull

                val rawResult = gitHubEngine.executeRawRest(method, path, params, body)
                buildJsonObject {
                    put("status", "success")
                    put("raw_response", safeTruncate(rawResult))
                }
            }

            "github_execute_graphql" -> {
                val query = args.getRequiredString("query")
                val vars = args["variables"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

                val rawResult = gitHubEngine.executeGraphQL(query, vars)
                buildJsonObject {
                    put("status", "success")
                    put("graphql_response", safeTruncate(rawResult))
                }
            }

            else -> throw IllegalArgumentException("Неизвестный инструмент: '$name'.")
        }
    }

    private fun buildErrorPayload(toolName: String, e: Exception): JsonObject {
        val hint = when (e) {
            is SecurityException -> "Ошибка песочницы: используйте относительные пути без '../'."
            is NoSuchFileException -> "Файл не найден на диске."
            is GitHubRateLimitException -> "Лимит запросов GitHub. Ожидание ${e.retryAfterSeconds} с."
            is GitHubApiException -> "Сбой GitHub API (${e.statusCode}): ${e.githubMessage}."
            is IllegalArgumentException -> "Некорректные аргументы инструмента: ${e.message}."
            else -> "Сбой инструмента: ${e.localizedMessage}."
        }

        return buildJsonObject {
            put("status", "error")
            put("tool", toolName)
            put("error_type", e::class.simpleName ?: "Exception")
            put("error_message", e.message ?: "Неизвестная ошибка")
            put("resolution_hint", hint)
        }
    }

    private fun safeTruncate(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n... [TRUNCATED]."
    }

    private fun JsonObject.getRequiredString(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Обязательный строковый аргумент '$key' не передан.")
    }

    private fun JsonObject.getRequiredInt(key: String): Int {
        return this[key]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Обязательный целочисленный аргумент '$key' отсутствует.")
    }

    private fun JsonObject.getRequiredLong(key: String): Long {
        return this[key]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Обязательный аргумент '$key' отсутствует.")
    }
}