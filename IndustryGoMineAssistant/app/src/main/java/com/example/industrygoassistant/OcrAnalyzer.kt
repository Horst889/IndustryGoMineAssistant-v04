package com.example.industrygoassistant

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object OcrAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val percentRegex = Regex("(100|[1-9]?[0-9])\\s*%")
    private val meterRegex = Regex("(?:Minengr[oö]ße|Mine size)\\s*:?\\s*(\\d{1,3})\\s*m", RegexOption.IGNORE_CASE)
    private val qualityLabelRegex = Regex("(?:Qualit[aä]t|Quality)\\s*:?\\s*(100|[1-9]?[0-9])\\s*%", RegexOption.IGNORE_CASE)

    suspend fun recognize(bitmap: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /**
     * Selects a valid alternative using the user's strict resource priority.
     * Example from the supplied screenshot: Lehm 99 %, Kohle 93 %, Eisen 92 %
     * -> Kohle 93 % wins because Kohle outranks Eisen and Lehm.
     */
    fun chooseBest(text: Text?): ResourceCandidate? {
        if (text == null) return null
        val candidates = mutableListOf<ResourceCandidate>()
        val lines = text.textBlocks.flatMap { it.lines }

        for (line in lines) {
            val resource = ResourceType.fromText(line.text) ?: continue
            val q = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: nearestPercent(line.boundingBox, lines)
                ?: continue
            val bounds = line.boundingBox ?: continue
            if (q >= resource.minQuality) candidates += ResourceCandidate(resource, q, bounds)
        }

        return candidates
            .distinctBy { Triple(it.resource, it.quality, it.bounds.centerY()) }
            .sortedWith(compareBy<ResourceCandidate> { it.resource.priority }.thenByDescending { it.quality })
            .firstOrNull()
    }

    fun verifyBuildDialog(text: Text?, expected: ResourceCandidate): BuildVerification {
        if (text == null) return BuildVerification(null, null, null, false, false, "Kein Text erkannt")
        val raw = text.text
        val resource = ResourceType.fromText(raw)
        val quality = qualityLabelRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val mineSize = meterRegex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val noNexus = raw.contains("kein Nexus-Verbrauch", ignoreCase = true) ||
            raw.contains("no nexus", ignoreCase = true) ||
            raw.contains("without nexus", ignoreCase = true)
        val titleLooksLikeBuild = raw.contains("BAUEN", ignoreCase = true) || raw.contains("BUILD", ignoreCase = true)
        val expectedResource = resource == expected.resource
        val qualityMatches = quality == null || quality == expected.quality
        val safe = titleLooksLikeBuild && expectedResource && qualityMatches && noNexus
        val reason = when {
            !titleLooksLikeBuild -> "Baudialog nicht sicher erkannt"
            !expectedResource -> "Rohstoff im Baudialog stimmt nicht"
            !qualityMatches -> "Qualität im Baudialog stimmt nicht"
            !noNexus -> "'kein Nexus-Verbrauch' nicht erkannt"
            else -> "OK"
        }
        return BuildVerification(resource, quality, mineSize, noNexus, safe, reason)
    }

    fun isBuildSuccess(text: Text?): Boolean {
        val t = text?.text ?: return false
        return t.contains("Mine gebaut", ignoreCase = true) ||
            t.contains("erfolgreich gebaut", ignoreCase = true) ||
            t.contains("mine built", ignoreCase = true)
    }

    fun containsNoSource(text: Text?): Boolean {
        val t = text?.text ?: return false
        return t.contains("Keine Quelle", ignoreCase = true) || t.contains("No source", ignoreCase = true)
    }

    fun findButtonCenter(text: Text?, vararg labels: String): Pair<Float, Float>? {
        if (text == null) return null
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (labels.any { line.text.contains(it, ignoreCase = true) }) {
                    val b = line.boundingBox ?: continue
                    return b.exactCenterX() to b.exactCenterY()
                }
            }
        }
        return null
    }

    private fun nearestPercent(target: Rect?, lines: List<Text.Line>): Int? {
        if (target == null) return null
        return lines.mapNotNull { line ->
            val b = line.boundingBox ?: return@mapNotNull null
            val q = percentRegex.find(line.text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val dx = b.centerX() - target.centerX()
            val dy = b.centerY() - target.centerY()
            val d2 = dx * dx + dy * dy
            Triple(d2, q, b)
        }.minByOrNull { it.first }?.second
    }
}
