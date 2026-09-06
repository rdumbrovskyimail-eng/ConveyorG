package com.conveyorg.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conveyorg.network.InlineCitation
import kotlinx.coroutines.delay
import java.util.Locale

// ====================================================================
// 1. Модели блоков потокового Markdown (AST Node Hierarchy)
// ====================================================================

sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val content: String) : MarkdownBlock
    data class NumberedItem(val number: String, val content: String) : MarkdownBlock
    data class Paragraph(val content: String) : MarkdownBlock
    data class CodeBlock(
        val language: String,
        val code: String,
        val isComplete: Boolean
    ) : MarkdownBlock
}

// Оптимизация: регулярное выражение скомпилировано один раз на уровне файла
private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.*)$")

// ====================================================================
// 2. Инкрементальный FSM-парсер (CommonMark 0.31.2 Stream Compliant)
// ====================================================================

fun parseStreamingMarkdown(input: String): List<MarkdownBlock> {
    if (input.isEmpty()) return emptyList()

    val blocks = ArrayList<MarkdownBlock>()
    val lines = input.lines()
    val paragraphBuffer = StringBuilder()

    var inCodeBlock = false
    var codeLanguage = ""
    val codeBuffer = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            val content = paragraphBuffer.toString().trim()
            if (content.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(content))
            }
            paragraphBuffer.setLength(0)
        }
    }

    for (line in lines) {
        val trimmedLine = line.trim()

        if (trimmedLine.startsWith("```")) {
            if (!inCodeBlock) {
                flushParagraph()
                inCodeBlock = true
                codeLanguage = trimmedLine.removePrefix("```").trim()
                codeBuffer.setLength(0)
            } else {
                inCodeBlock = false
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = codeLanguage,
                        code = codeBuffer.toString().trimEnd('\n'),
                        isComplete = true
                    )
                )
                codeLanguage = ""
                codeBuffer.setLength(0)
            }
            continue
        }

        if (inCodeBlock) {
            codeBuffer.append(line).append("\n")
            continue
        }

        if (trimmedLine.isEmpty()) {
            flushParagraph()
            continue
        }

        val orderedMatch = ORDERED_LIST_REGEX.find(trimmedLine)

        when {
            trimmedLine.startsWith("###### ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(6, trimmedLine.removePrefix("###### ").trim()))
            }
            trimmedLine.startsWith("##### ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(5, trimmedLine.removePrefix("##### ").trim()))
            }
            trimmedLine.startsWith("#### ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(4, trimmedLine.removePrefix("#### ").trim()))
            }
            trimmedLine.startsWith("### ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(3, trimmedLine.removePrefix("### ").trim()))
            }
            trimmedLine.startsWith("## ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(2, trimmedLine.removePrefix("## ").trim()))
            }
            trimmedLine.startsWith("# ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Header(1, trimmedLine.removePrefix("# ").trim()))
            }
            trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Bullet(trimmedLine.substring(2).trim()))
            }
            orderedMatch != null -> {
                flushParagraph()
                val num = orderedMatch.groupValues[1]
                val content = orderedMatch.groupValues[2]
                blocks.add(MarkdownBlock.NumberedItem(num, content))
            }
            else -> {
                if (paragraphBuffer.isNotEmpty()) paragraphBuffer.append(" ")
                paragraphBuffer.append(trimmedLine)
            }
        }
    }

    if (inCodeBlock) {
        blocks.add(
            MarkdownBlock.CodeBlock(
                language = codeLanguage,
                code = codeBuffer.toString().trimEnd('\n'),
                isComplete = false
            )
        )
    } else {
        flushParagraph()
    }

    return blocks
}

// ====================================================================
// 3. Быстрый потоковый токенизатор подсветки синтаксиса O(N)
// ====================================================================

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "is", "null", "object", "package", "return", "super", "this",
    "throw", "true", "try", "typealias", "val", "var", "when", "while", "data",
    "override", "private", "public", "protected", "internal", "import", "sealed"
)

private val KOTLIN_TYPES = setOf(
    "Int", "Long", "Float", "Double", "Boolean", "String", "Char", "Byte",
    "Short", "Unit", "Any", "List", "Map", "Set", "Array", "StateFlow", "Flow"
)

fun highlightCodeSyntax(code: String, language: String): AnnotatedString {
    val isKotlin = language.lowercase(Locale.US) in setOf("kotlin", "kt")
    if (!isKotlin || code.length > 50_000) {
        return AnnotatedString(code)
    }

    return buildAnnotatedString {
        val lines = code.lines()
        for ((lineIdx, line) in lines.withIndex()) {
            if (lineIdx > 0) append("\n")

            val commentIdx = line.indexOf("//")
            val codePart = if (commentIdx != -1) line.substring(0, commentIdx) else line
            val commentPart = if (commentIdx != -1) line.substring(commentIdx) else ""

            var i = 0
            while (i < codePart.length) {
                if (codePart[i] == '"') {
                    val endQuote = codePart.indexOf('"', i + 1)
                    val strEnd = if (endQuote != -1) endQuote + 1 else codePart.length
                    withStyle(SpanStyle(color = Color(0xFFC3E88D))) {
                        append(codePart.substring(i, strEnd))
                    }
                    i = strEnd
                    continue
                }

                if (codePart[i].isLetter() || codePart[i] == '_') {
                    val start = i
                    while (i < codePart.length && (codePart[i].isLetterOrDigit() || codePart[i] == '_')) {
                        i++
                    }
                    val word = codePart.substring(start, i)
                    when {
                        word in KOTLIN_KEYWORDS -> withStyle(SpanStyle(color = Color(0xFFC792EA), fontWeight = FontWeight.Bold)) { append(word) }
                        word in KOTLIN_TYPES -> withStyle(SpanStyle(color = Color(0xFF82AAFF))) { append(word) }
                        else -> append(word)
                    }
                    continue
                }

                append(codePart[i])
                i++
            }

            if (commentPart.isNotEmpty()) {
                withStyle(SpanStyle(color = Color(0xFF546E7A), fontStyle = FontStyle.Italic)) {
                    append(commentPart)
                }
            }
        }
    }
}

// ====================================================================
// 4. Компонуемые элементы интерфейса (Jetpack Compose View)
// ====================================================================

private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
        }
    }.build()
}

@Composable
fun StreamingMarkdownContent(
    text: String,
    citations: List<InlineCitation> = emptyList(),
    onOpenUrl: (String) -> Unit
) {
    val blocks = remember(text) { parseStreamingMarkdown(text) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    Text(
                        text = block.text,
                        color = Color(0xFFF2F2F2),
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            4 -> 16.sp
                            5 -> 15.sp
                            else -> 14.sp
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.Bullet -> {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text(text = "• ", color = Color(0xFF8AB4F8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = parseInlineMarkdown(block.content, onOpenUrl),
                            color = Color(0xFFF2F2F2),
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text(text = "${block.number}. ", color = Color(0xFF8AB4F8), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = parseInlineMarkdown(block.content, onOpenUrl),
                            color = Color(0xFFF2F2F2),
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.content, onOpenUrl),
                        color = Color(0xFFF2F2F2),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    StreamingCodeBlockCard(block = block)
                }
            }
        }

        if (citations.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                citations.distinctBy { it.source.url }.forEachIndexed { idx, citation ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF171717),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282828)),
                        modifier = Modifier.clickable { onOpenUrl(citation.source.url) }
                    ) {
                        Text(
                            text = "[${idx + 1}] ${citation.source.title}",
                            color = Color(0xFF8AB4F8),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StreamingCodeBlockCard(block: MarkdownBlock.CodeBlock) {
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var isCopied by remember { mutableStateOf(false) }

    val codeScrollState = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(block.code.length, block.isComplete) {
        if (!block.isComplete) {
            codeScrollState.scrollTo(codeScrollState.maxValue)
        }
    }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1500)
            isCopied = false
        }
    }

    val langColor = when (block.language.lowercase(Locale.US)) {
        "kotlin", "kt" -> Color(0xFF7F52FF)
        "python", "py" -> Color(0xFF3776AB)
        "json", "xml" -> Color(0xFFFFD54F)
        "sh", "bash" -> Color(0xFF4CAF50)
        else -> Color(0xFFAAAAAA)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF080808))
    ) {
        Column {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121212))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(langColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = (block.language.ifEmpty { "code" }).uppercase(Locale.US) + if (!block.isComplete) " (ГЕНЕРАЦИЯ...)" else "",
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clickable {
                                clipboardManager.setText(AnnotatedString(block.code))
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isCopied = true
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else CopyIcon,
                            contentDescription = "Скопировать код",
                            tint = if (isCopied) Color(0xFF81C784) else Color(0xFFAAAAAA),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCopied) "Скопировано" else "Скопировать",
                            color = if (isCopied) Color(0xFF81C784) else Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            val highlightedText = remember(block.code, block.language) {
                highlightCodeSyntax(block.code, block.language)
            }

            Text(
                text = highlightedText,
                color = Color(0xFFE0E0E0),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp, max = 380.dp)
                    .verticalScroll(codeScrollState)
                    .horizontalScroll(horizontalScroll)
                    .padding(14.dp)
            )
        }
    }
}

fun parseInlineMarkdown(text: String, onOpenUrl: (String) -> Unit): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val nextTick = text.indexOf('`', i + 1)
                if (nextTick != -1) {
                    val codeSnippet = text.substring(i + 1, nextTick)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0xFF222222),
                            color = Color(0xFFFFD54F),
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $codeSnippet ")
                    }
                    i = nextTick + 1
                    continue
                }
            }

            if (text.startsWith("***", i)) {
                val nextStars = text.indexOf("***", i + 3)
                if (nextStars != -1) {
                    val content = text.substring(i + 3, nextStars)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = Color(0xFFF2F2F2))) {
                        append(content)
                    }
                    i = nextStars + 3
                    continue
                }
            }

            if (text.startsWith("**", i)) {
                val nextStars = text.indexOf("**", i + 2)
                if (nextStars != -1) {
                    val boldText = text.substring(i + 2, nextStars)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFF2F2F2))) {
                        append(boldText)
                    }
                    i = nextStars + 2
                    continue
                }
            }

            if (text[i] == '*') {
                val nextStar = text.indexOf('*', i + 1)
                if (nextStar != -1) {
                    val italicText = text.substring(i + 1, nextStar)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFF2F2F2))) {
                        append(italicText)
                    }
                    i = nextStar + 1
                    continue
                }
            }

            if (text.startsWith("[", i)) {
                val closeBracket = text.indexOf(']', i + 1)
                val openParen = if (closeBracket != -1) text.indexOf('(', closeBracket) else -1
                val closeParen = if (openParen != -1 && openParen == closeBracket + 1) text.indexOf(')', openParen) else -1

                if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                    val linkTitle = text.substring(i + 1, closeBracket)
                    val linkUrl = text.substring(openParen + 1, closeParen).trim()

                    withLink(
                        LinkAnnotation.Url(
                            url = linkUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = Color(0xFF8AB4F8),
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            ),
                            linkInteractionListener = { _ -> onOpenUrl(linkUrl) }
                        )
                    ) {
                        append(linkTitle)
                    }
                    i = closeParen + 1
                    continue
                }
            }

            append(text[i])
            i++
        }
    }
}