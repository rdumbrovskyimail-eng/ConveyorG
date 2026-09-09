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
    }

    override fun getToolDeclarations(): GeminiToolDto {
        return GeminiToolDto(
            functionDeclarations = listOf(
                FunctionDeclarationDto(
                    name = "workspace_get_tree",
                    description = "Возвращает структуру дерева локального репозитория.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf("max_depth" to ParameterPropertyDto("INTEGER", "Глубина сканирования (по умолчанию 6)."))
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_read_file",
                    description = "Читает содержимое локального файла из рабочей области.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto("STRING", "Путь к файлу."),
                            "start_line" to ParameterPropertyDto("INTEGER", "Начальная строка."),
                            "end_line" to ParameterPropertyDto("INTEGER", "Конечная строка.")
                        ),
                        required = listOf("path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_write_file",
                    description = "Атомарно записывает файл на диск UFS 4.0. Для конфигов (.gitignore, gradle.properties), скриптов и правок без вызова роя.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "path" to ParameterPropertyDto("STRING", "Путь к файлу."),
                            "content" to ParameterPropertyDto("STRING", "Полный исходный код файла."),
                            "is_executable" to ParameterPropertyDto("BOOLEAN", "Установить chmod +x.")
                        ),
                        required = listOf("path", "content")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_batch_write",
                    description = "Атомарно создает группу файлов за 1 шаг.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf("files" to ParameterPropertyDto("OBJECT", "Словарь { 'путь': 'содержимое' }.", properties = emptyMap())),
                        required = listOf("files")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_delete_file",
                    description = "Удаляет локальный файл из рабочей области.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf("path" to ParameterPropertyDto("STRING", "Путь к файлу.")),
                        required = listOf("path")
                    )
                ),
                FunctionDeclarationDto(
                    name = "workspace_search_symbol",
                    description = "Выполняет быстрый поиск символа (Grep) по проекту.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "query" to ParameterPropertyDto("STRING", "Строка поиска."),
                            "max_results" to ParameterPropertyDto("INTEGER", "Лимит результатов.")
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
                    description = "Формирует дельту, создает блобы, дерево и отправляет в ветку GitHub ОДНИМ атомарным коммитом.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto("STRING", "Владелец."),
                            "repo" to ParameterPropertyDto("STRING", "Репозиторий."),
                            "branch" to ParameterPropertyDto("STRING", "Ветка."),
                            "commit_message" to ParameterPropertyDto("STRING", "Сообщение.")
                        ),
                        required = listOf("owner", "repo", "branch", "commit_message")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_trigger_ci_build",
                    description = "Запускает GitHub Actions. Вызывайте ТОЛЬКО если в репозитории есть .github/workflows/*.yml.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto("STRING", "Владелец."),
                            "repo" to ParameterPropertyDto("STRING", "Репозиторий."),
                            "workflow_id" to ParameterPropertyDto("STRING", "Файл воркфлоу."),
                            "ref" to ParameterPropertyDto("STRING", "Ветка.")
                        ),
                        required = listOf("owner", "repo", "workflow_id", "ref")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_status",
                    description = "Опрашивает статус Actions. Вызывайте ТОЛЬКО если CI запущен и есть реальный run_id.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto("STRING", "Владелец."),
                            "repo" to ParameterPropertyDto("STRING", "Репозиторий."),
                            "run_id" to ParameterPropertyDto("INTEGER", "Идентификатор запуска.")
                        ),
                        required = listOf("owner", "repo", "run_id")
                    )
                ),
                FunctionDeclarationDto(
                    name = "github_get_ci_logs",
                    description = "Скачивает лог задачи Actions для извлечения ошибок компилятора.",
                    parameters = FunctionParametersSchemaDto(
                        properties = mapOf(
                            "owner" to ParameterPropertyDto("STRING", "Владелец."),
                            "repo" to ParameterPropertyDto("STRING", "Репозиторий."),
                            "job_id" to ParameterPropertyDto("INTEGER", "Идентификатор задачи.")
                        ),
                        required = listOf("owner", "repo", "job_id")
                    )
                )
            )
        )
    }

    override suspend fun dispatchToolCall(call: FunctionCallDto, thoughtSignature: String?): FunctionResponsePartDto = withContext(Dispatchers.IO) {
        val resultPayload: JsonObject = try {
            withTimeout(TOOL_EXECUTION_TIMEOUT_MS) { routeCallInternal(call.name, call.args) }
        } catch (e: Exception) {
            AppLogger.e(AppLogger.TAG_ENGINE, "ToolBridge: Сбой '${call.name}': ${e.message}", e)
            buildErrorPayload(call.name, e)
        }
        FunctionResponsePartDto(FunctionResponseDto(call.name, buildJsonObject { put("output", resultPayload) }, call.id))
    }

    private suspend fun routeCallInternal(name: String, args: JsonObject): JsonObject {
        return when (name) {
            "workspace_get_tree" -> {
                val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 6
                val treeDto = workspaceManager.getProjectTree(maxDepth)
                buildJsonObject {
                    put("status", "success")
                    put("total_files", treeDto.totalFiles)
                    put("total_directories", treeDto.totalDirectories)
                    putJsonArray("nodes") {
                        treeDto.nodes.take(250).forEach { node ->
                            addJsonObject {
                                put("path", node.path)
                                put("is_dir", node.isDirectory)
                                put("size_bytes", node.sizeBytes)
                            }
                        }
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
                    put("total_lines", readResult.totalLines)
                    put("content", safeTruncate(readResult.content))
                }
            }

            "workspace_write_file" -> {
                val path = args.getRequiredString("path")
                val content = args.getRequiredString("content")
                val isExec = args["is_executable"]?.jsonPrimitive?.booleanOrNull
                val clean = content.trim().replace(Regex("^```[a-zA-Z0-9_-]*\\r?\\n"), "").replace(Regex("\\r?\\n```$"), "")
                val written = workspaceManager.writeTextFileAtomic(path, clean, isExec)
                buildJsonObject {
                    put("status", "success")
                    put("path", path)
                    put("bytes_written", written.length())
                }
            }

            "workspace_batch_write" -> {
                val filesObj = args["files"]?.jsonObject ?: throw IllegalArgumentException("Параметр 'files' отсутствует.")
                filesObj.forEach { (path, elem) ->
                    val clean = elem.jsonPrimitive.content.trim().replace(Regex("^```[a-zA-Z0-9_-]*\\r?\\n"), "").replace(Regex("\\r?\\n```$"), "")
                    workspaceManager.writeTextFileAtomic(path, clean)
                }
                buildJsonObject {
                    put("status", "success")
                    put("files_written_count", filesObj.size)
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
                val matches = workspaceManager.searchSymbol(query)
                buildJsonObject {
                    put("status", "success")
                    put("matches_count", matches.size)
                    putJsonArray("matches") {
                        matches.take(30).forEach { match ->
                            addJsonObject {
                                put("file", match.relativePath)
                                put("line", match.lineNumber)
                                put("content", safeTruncate(match.lineContent, 200))
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
                        put("message", "На диске нет изменений.")
                    }
                }

                val pushResult = gitHubEngine.pushAtomicCommit(owner, repo, branch, message, delta.modifiedFiles, delta.deletedFiles)
                buildJsonObject {
                    put("status", "success")
                    put("commit_sha", pushResult.commitSha)
                    put("branch", pushResult.branch)
                    put("files_committed_count", pushResult.updatedFilesCount)
                }
            }

            // МГНОВЕННЫЙ FAIL-FAST (0 МС) ЕСЛИ В РЕПОЗИТОРИИ НЕТ WORKFLOWS:
            "github_trigger_ci_build" -> {
                if (!workspaceManager.hasConfiguredCiWorkflows()) {
                    return buildJsonObject {
                        put("status", "skipped")
                        put("message", "В репозитории нет .github/workflows/*.yml. Запуск CI пропущен.")
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
                }
            }

            "github_get_ci_status" -> {
                if (!workspaceManager.hasConfiguredCiWorkflows()) {
                    return buildJsonObject {
                        put("status", "skipped")
                        put("conclusion", "success")
                        put("message", "В репозитории нет воркфлоу CI. Опрос пропущен.")
                    }
                }
                val owner = args.getRequiredString("owner")
                val repo = args.getRequiredString("repo")
                val runId = args.getRequiredLong("run_id")
                val finishedRun = gitHubEngine.pollWorkflowRunConclusion(owner, repo, runId, 5000L, 60_000L)
                buildJsonObject {
                    put("status", "success")
                    put("run_id", runId)
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

            else -> throw IllegalArgumentException("Неизвестный инструмент: '$name'.")
        }
    }

    private fun buildErrorPayload(toolName: String, e: Exception): JsonObject {
        return buildJsonObject {
            put("status", "error")
            put("tool", toolName)
            put("error_message", e.message ?: "Сбой")
        }
    }

    private fun safeTruncate(text: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n\n... [TRUNCATED]."
    }

    private fun JsonObject.getRequiredString(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Аргумент '$key' не передан.")
    }

    private fun JsonObject.getRequiredLong(key: String): Long {
        return this[key]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Аргумент '$key' отсутствует.")
    }
}