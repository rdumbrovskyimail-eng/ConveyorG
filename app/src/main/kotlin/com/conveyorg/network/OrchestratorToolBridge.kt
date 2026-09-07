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

// ====================================================================
// 1. DTO Спецификации Function Calling (OpenAPI 3.03 / Gemini REST v1beta)
// ====================================================================

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
    val type: String, // "STRING", "INTEGER", "BOOLEAN", "ARRAY", "OBJECT"
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

// ====================================================================
// 2. Эталонный Мост Инструментов: OrchestratorToolBridge
// ====================================================================

class OrchestratorToolBridge(
    private val workspaceManager: LocalWorkspaceManager,
    private val gitHubEngine: GitHubEngine
) {

    companion object {
        private const val TOOL_EXECUTION_TIMEOUT_MS = 90_000L // 90 сек таймаут на выполнение инструмента
        private const val MAX_OUTPUT_CHARS = 40_000          // Лимит размера ответа для защиты контекста

        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    // ====================================================================
    // 3. Реестр Деклараций Схем Инструментов для Gemini 3.8 Flash
    // ====================================================================

    fun getToolDeclarations(): GeminiToolDto {
        return GeminiToolDto(
            functionDeclarations = listOf(
                // --- ГРУППА 1: Локальная инспекция репозитория (на телефоне) ---
                FunctionDeclarationDto(
                    name = "workspace_get_tree",
                    description = "Возвращает структуру дерева локального репозитория. Используйте для понимания структуры папок и пакетов перед работой.",
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
                    description = "Читает содержимое локального файла из рабочей области. Поддерживает постраничное построчное чтение для экономии токенов.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Относительный POSIX-путь к файлу (например, app/src/main/AndroidManifest.xml)."
                            ),
                            "start_line" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Начальный номер строки для чтения (1-based, опционально)."
                            ),
                            "end_line" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Конечный номер строки для чтения (включительно, опционально)."
                            )
                        ),
                        required = listOf("path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_search_symbol",
                    description = "Выполняет быстрый полнотекстовый поиск (Grep) строки, имени класса или функции по файлам проекта на диске телефона.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "query" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Поисковая строка или сигнатура символа."
                            ),
                            "file_extensions" to ParameterPropertyDto(
                                type = "ARRAY",
                                description = "Фильтр по расширениям файлов (например, ['kt', 'kts', 'xml']).",
                                items = ParameterPropertyDto(type = "STRING", description = "Расширение файла без точки")
                            ),
                            "max_results" to ParameterPropertyDto(
                                type = "INTEGER",
                                description = "Лимит количества совпадений (по умолчанию 50)."
                            )
                        ),
                        required = listOf("query")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_read_diff",
                    description = "Генерирует сводный Unified Diff всех измененных, добавленных и удаленных файлов относительно исходного снимка репозитория.",
                    parameters = FunctionParametersSchemaDto(
                        properties = emptyMap()
                    )
                ),

                // --- ГРУППА 2: Пуш, Компиляция и CI/CD в GitHub ---
                FunctionDeclarationDto(
                    name = "github_push_atomic_commit",
                    description = "Вычисляет дельту измененных файлов на диске телефона, создает блобы, дерево и отправляет в ветку GitHub ОДНИМ атомарным коммитом.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория (пользователь или организация)."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "branch" to ParameterPropertyDto(type = "STRING", description = "Целевая ветка (например, main или refactor/logic)."),
                            "commit_message" to ParameterPropertyDto(type = "STRING", description = "Сообщение коммита по стандарту Conventional Commits.")
                        ),
                        required = listOf("owner", "repo", "branch", "commit_message")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_trigger_ci_build",
                    description = "Принудительно запускает сборку проекта (Workflow Dispatch) в GitHub Actions для аппаратной верификации компилятором.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "workflow_id" to ParameterPropertyDto(type = "STRING", description = "Имя файла воркфлоу (например, ci.yml)."),
                            "ref" to ParameterPropertyDto(type = "STRING", description = "Имя ветки, для которой запускается CI.")
                        ),
                        required = listOf("owner", "repo", "workflow_id", "ref")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_status",
                    description = "Опрашивает текущий статус выполнения сборщика Actions по ID запуска (status: queued, in_progress, completed; conclusion: success, failure).",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "run_id" to ParameterPropertyDto(type = "INTEGER", description = "Числовой идентификатор запуска сборки (run_id).")
                        ),
                        required = listOf("owner", "repo", "run_id")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_logs",
                    description = "Скачивает лог компилятора упавшей задачи GitHub Actions с извлечением стектрейсов ошибок kotlinc / javac.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "job_id" to ParameterPropertyDto(type = "INTEGER", description = "Идентификатор задачи сборщика (job_id).")
                        ),
                        required = listOf("owner", "repo", "job_id")
                    )
                ),

                // --- ГРУППА 3: Pull Requests и Коллаборация ---
                FunctionDeclarationDto(
                    name = "github_create_pull_request",
                    description = "Открывает Pull Request из рабочей ветки в базовую с подробным описанием проделанной работы.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "title" to ParameterPropertyDto(type = "STRING", description = "Заголовок Pull Request."),
                            "body" to ParameterPropertyDto(type = "STRING", description = "Markdown-описание изменений и архитектуры."),
                            "head_branch" to ParameterPropertyDto(type = "STRING", description = "Имя ветки с изменениями."),
                            "base_branch" to ParameterPropertyDto(type = "STRING", description = "Базовая ветка для слияния (по умолчанию main).")
                        ),
                        required = listOf("owner", "repo", "title", "body", "head_branch")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_merge_pull_request",
                    description = "Выполняет автоматическое слияние Pull Request после успешной компиляции и прохождения всех проверок.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto(type = "STRING", description = "Владелец репозитория."),
                            "repo" to ParameterPropertyDto(type = "STRING", description = "Имя репозитория."),
                            "pull_number" to ParameterPropertyDto(type = "INTEGER", description = "Номер Pull Request."),
                            "commit_title" to ParameterPropertyDto(type = "STRING", description = "Заголовок коммита слияния (опционально)."),
                            "merge_method" to ParameterPropertyDto(
                                type = "STRING",
                                description = "Метод слияния: squash, merge или rebase (по умолчанию squash).",
                                enum = listOf("squash", "merge", "rebase")
                            )
                        ),
                        required = listOf("owner", "repo", "pull_number")
                    )
                ),

                // --- ГРУППА 4: Универсальный Шлюз (100% Escape Hatch) ---
                FunctionDeclarationDto(
                    name = "github_execute_raw_rest",
                    description = "Универсальный шлюз: выполняет произвольный запрос к абсолютно любому эндпоинту GitHub REST API (100% покрытие любых редких методов).",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "method" to ParameterPropertyDto(type = "STRING", description = "HTTP-метод (GET, POST, PUT, PATCH, DELETE).", enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE")),
                            "endpoint_path" to ParameterPropertyDto(type = "STRING", description = "Путь эндпоинта (например, repos/owner/repo/releases)."),
                            "query_params" to ParameterPropertyDto(type = "OBJECT", description = "Query параметры в формате ключ-значение.", properties = emptyMap()),
                            "json_body" to ParameterPropertyDto(type = "STRING", description = "Тело запроса в виде валидной JSON-строки (опционально).")
                        ),
                        required = listOf("method", "endpoint_path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_execute_graphql",
                    description = "Универсальный GraphQL шлюз к GitHub API v4 для выполнения сложных графовых запросов.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "query" to ParameterPropertyDto(type = "STRING", description = "Тело GraphQL-запроса."),
                            "variables" to ParameterPropertyDto(type = "OBJECT", description = "Переменные запроса в формате ключ-значение.", properties = emptyMap())
                        ),
                        required = listOf("query")
                    )
                )
            )
        )
    }

    // ====================================================================
    // 4. Диспетчеризация и Выполнение Инструментов (Execution Router)
    // ====================================================================

    suspend fun dispatchToolCall(
        call: FunctionCallDto,
        thoughtSignature: String? = null
    ): FunctionResponsePartDto = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        AppLogger.i(AppLogger.TAG_ENGINE, "ToolBridge: Запуск вызова инструмента '${call.name}' (id=${call.id})...")

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
            // 1. Дерево файлов
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

            // 2. Чтение файла с окном строк
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

            // 3. Полнотекстовый поиск по проекту
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

            // 4. Сводный Myers Diff
            "workspace_read_diff" -> {
                val diffOutput = workspaceManager.computeAggregatedDiff()
                buildJsonObject {
                    put("status", "success")
                    put("has_changes", diffOutput != "No changes detected in workspace.")
                    put("unified_diff", safeTruncate(diffOutput))
                }
            }

            // 5. Атомарный коммит и пуш дельты
            "github_push_atomic_commit" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val branch = args.getRequiredString("branch")
                val message = args.getRequiredString("commit_message")

                val delta = workspaceManager.computeChangedFiles()
                if (delta.modifiedFiles.isEmpty()) {
                    return buildJsonObject {
                        put("status", "nothing_to_commit")
                        put("message", "На диске нет измененных файлов для отправки в репозиторий.")
                    }
                }

                val pushResult = gitHubEngine.pushAtomicCommit(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    commitMessage = message,
                    modifiedFiles = delta.modifiedFiles
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

            // 6. Запуск сборщика Actions
            "github_trigger_ci_build" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val workflowId = args.getRequiredString("workflow_id")
                val ref = args.getRequiredString("ref")

                val dispatched = gitHubEngine.dispatchWorkflow(owner, repo, workflowId, ref)
                buildJsonObject {
                    put("status", if (dispatched) "dispatched" else "failed")
                    put("workflow", workflowId)
                    put("ref", ref)
                    put("message", "Сборка поставлена в очередь выполнения GitHub Actions.")
                }
            }

            // 7. Проверка статуса сборщика Actions
            "github_get_ci_status" -> {
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val runId = args.getRequiredLong("run_id")

                val rawRunJson = gitHubEngine.executeRawRest("GET", "repos/$owner/$repo/actions/runs/$runId")
                val runObj = json.parseToJsonElement(rawRunJson).jsonObject

                buildJsonObject {
                    put("status", "success")
                    put("run_id", runId)
                    put("run_status", runObj["status"]?.jsonPrimitive?.content ?: "unknown")
                    put("conclusion", runObj["conclusion"]?.jsonPrimitive?.content ?: "pending")
                    put("html_url", runObj["html_url"]?.jsonPrimitive?.content ?: "")
                }
            }

            // 8. Выкачка лога ошибок компилятора
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

            // 9. Создание Pull Request
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

            // 10. Слияние Pull Request
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
                    put("message", mergeRes.message)
                }
            }

            // 11. Универсальный REST шлюз
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

            // 12. Универсальный GraphQL шлюз
            "github_execute_graphql" -> {
                val query = args.getRequiredString("query")
                val vars = args["variables"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

                val rawResult = gitHubEngine.executeGraphQL(query, vars)
                buildJsonObject {
                    put("status", "success")
                    put("graphql_response", safeTruncate(rawResult))
                }
            }

            else -> {
                throw IllegalArgumentException("Неизвестный инструмент: '$name'. Проверьте декларации схем.")
            }
        }
    }

    // ====================================================================
    // 5. Изоляция Сбоев и Помощь в Самоисцелении (Tool Error Boundary)
    // ====================================================================

    private fun buildErrorPayload(toolName: String, e: Exception): JsonObject {
        val hint = when (e) {
            is SecurityException -> "Ошибка доступа: путь вышел за пределы локальной папки. Используйте относительные пути без '../'."
            is NoSuchFileException -> "Файл не найден. Сначала вызовите 'workspace_get_tree', чтобы увидеть точные пути проекта."
            is GitHubRateLimitException -> "Превышен лимит запросов к GitHub. Повторите попытку через ${e.retryAfterSeconds} секунд."
            is GitHubApiException -> "Сбой GitHub API (${e.statusCode}): ${e.githubMessage}. Проверьте параметры и права токена."
            is IllegalArgumentException -> "Некорректные аргументы инструмента: ${e.message}."
            else -> "Внутренний сбой выполнения инструмента: ${e.localizedMessage}."
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
        return text.take(maxChars) + "\n\n... [TRUNCATED: Вывод превысил лимит $maxChars символов для защиты контекста модели]."
    }

    private fun JsonObject.getRequiredString(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Обязательный строковый аргумент '$key' не передан или пуст.")
    }

    private fun JsonObject.getRequiredInt(key: String): Int {
        return this[key]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Обязательный целочисленный аргумент '$key' отсутствует.")
    }

    private fun JsonObject.getRequiredLong(key: String): Long {
        return this[key]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Обязательный длинный целочисленный аргумент '$key' отсутствует.")
    }
}