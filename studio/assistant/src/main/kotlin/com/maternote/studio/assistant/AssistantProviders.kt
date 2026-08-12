package com.maternote.studio.assistant

data class ExtractedTextDraft(val text: String, val confidence: Float, val uncertainSpans: List<IntRange>)
fun interface TextExtractionProvider { suspend fun extract(bytes: ByteArray): ExtractedTextDraft }
fun interface ExplanationGenerationProvider { suspend fun explain(text: String): String }
fun interface ImageGenerationProvider { suspend fun generate(prompt: String): ByteArray }
