package com.example.whiz.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.commonmark.node.*
import org.commonmark.parser.Parser

/**
 * Renders markdown text using CommonMark parser and Compose AnnotatedString.
 * Pure Compose implementation - no AndroidView.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val baseFontSize = style.fontSize
    val annotatedString = parseMarkdownToAnnotatedString(markdown, color, baseFontSize)

    Text(
        text = annotatedString,
        modifier = modifier,
        style = style
    )
}

/**
 * Parses markdown string to AnnotatedString by walking the CommonMark AST.
 */
private fun parseMarkdownToAnnotatedString(
    markdown: String,
    color: Color,
    baseFontSize: androidx.compose.ui.unit.TextUnit
): AnnotatedString {
    val parser = Parser.builder().build()
    val document = parser.parse(markdown)

    return buildAnnotatedString {
        processNode(document, color, baseFontSize)
    }
}

/**
 * Extension function for AnnotatedString.Builder to process CommonMark nodes.
 */
private fun AnnotatedString.Builder.processNode(
    node: Node,
    color: Color,
    baseFontSize: androidx.compose.ui.unit.TextUnit
) {
    var child = node.firstChild

    while (child != null) {
        when (child) {
            is Text -> append(child.literal)

            is Emphasis -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                processNode(child, color, baseFontSize)
                pop()
            }

            is StrongEmphasis -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                processNode(child, color, baseFontSize)
                pop()
            }

            is Code -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Gray.copy(alpha = 0.2f),
                        color = if (color != Color.Unspecified) color else Color.Unspecified
                    )
                )
                append(child.literal)
                pop()
            }

            is Link -> {
                val url = child.destination
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(
                    style = SpanStyle(
                        color = Color.Blue,
                        textDecoration = TextDecoration.Underline
                    )
                )))
                processNode(child, color, baseFontSize)
                pop()
            }

            is Paragraph -> {
                processNode(child, color, baseFontSize)
                // Add line break after paragraph
                if (child.next != null) {
                    append("\n\n")
                }
            }

            is Heading -> {
                val level = child.level
                val fontWeight = when (level) {
                    1, 2 -> FontWeight.Bold
                    else -> FontWeight.SemiBold
                }
                val scale = when (level) {
                    1 -> 1.5f
                    2 -> 1.3f
                    3 -> 1.2f
                    4 -> 1.1f
                    else -> 1.0f
                }

                pushStyle(
                    SpanStyle(
                        fontWeight = fontWeight,
                        fontSize = baseFontSize * scale
                    )
                )
                processNode(child, color, baseFontSize)
                pop()

                if (child.next != null) {
                    append("\n\n")
                }
            }

            is BulletList -> {
                processListNode(child, color, baseFontSize, isOrdered = false)
                if (child.next != null) {
                    append("\n")
                }
            }

            is OrderedList -> {
                processListNode(child, color, baseFontSize, isOrdered = true, startNumber = child.startNumber)
                if (child.next != null) {
                    append("\n")
                }
            }

            is ListItem -> {
                // ListItem processing is handled in processListNode
                processNode(child, color, baseFontSize)
            }

            is BlockQuote -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append("❝ ")
                processNode(child, color, baseFontSize)
                pop()
                if (child.next != null) {
                    append("\n\n")
                }
            }

            is FencedCodeBlock, is IndentedCodeBlock -> {
                val codeContent = when (child) {
                    is FencedCodeBlock -> child.literal
                    is IndentedCodeBlock -> child.literal
                    else -> ""
                }

                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color.Gray.copy(alpha = 0.2f),
                        color = if (color != Color.Unspecified) color else Color.Unspecified
                    )
                )
                append(codeContent)
                pop()

                if (child.next != null) {
                    append("\n\n")
                }
            }

            is HardLineBreak -> {
                append("\n")
            }

            is SoftLineBreak -> {
                append(" ")
            }

            is ThematicBreak -> {
                // Compact horizontal rule for chat - trailing newline only so following content starts on new line
                append("───────────\n")
            }

            else -> {
                // For any unhandled node types, recursively process children
                processNode(child, color, baseFontSize)
            }
        }

        child = child.next
    }
}

/**
 * Helper function to process list nodes (bullet or ordered).
 */
private fun AnnotatedString.Builder.processListNode(
    listNode: Node,
    color: Color,
    baseFontSize: androidx.compose.ui.unit.TextUnit,
    isOrdered: Boolean,
    startNumber: Int = 1
) {
    var itemNumber = startNumber
    var item = listNode.firstChild

    while (item != null) {
        if (item is ListItem) {
            val bullet = if (isOrdered) {
                "$itemNumber. "
            } else {
                "• "
            }

            append(bullet)
            processNode(item, color, baseFontSize)

            if (item.next != null) {
                append("\n")
            }

            if (isOrdered) {
                itemNumber++
            }
        }

        item = item.next
    }
}
