package app.omymoney.core.recognition

object EvidencePrivacy {
    private val longNumber = Regex("(?<![0-9])(?:[0-9][ -]?){11,}[0-9](?![0-9])")

    fun text(raw: String): String {
        val normalized = cleanText(raw)
        if (!longNumber.containsMatchIn(normalized)) return raw
        return longNumber.replace(normalized) { match ->
            "****" + match.value.filter(Char::isDigit).takeLast(4)
        }
    }

    fun accountHint(evidence: FieldEvidence): FieldEvidence = redact(evidence)

    fun redact(evidence: FieldEvidence): FieldEvidence {
        val safe = text(evidence.rawText)
        return evidence.copy(rawText = safe, redacted = evidence.redacted || safe != evidence.rawText)
    }
}
