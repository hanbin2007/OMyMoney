package app.omymoney.core.recognition

interface PageRule {
    val id: String
    val packageName: String
    val priority: Int
    fun matches(snapshot: UiSnapshot): Boolean
    fun extract(snapshot: UiSnapshot): RecognitionResult
}
