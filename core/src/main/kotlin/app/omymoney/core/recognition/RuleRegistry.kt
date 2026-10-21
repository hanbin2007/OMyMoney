package app.omymoney.core.recognition

import app.omymoney.core.recognition.rules.*
import java.util.Collections

object RuleRegistry {
    private val factories: Map<String, (RecognitionContext) -> List<PageRule>> = linkedMapOf(
        "com.tencent.mm" to WeChatRules::create,
        "com.eg.android.AlipayGphone" to { context -> AlipayRules.create(context) },
        "com.xunmeng.pinduoduo" to PddRules::create,
        "com.unionpay" to UnionPayRules::create,
        "com.jingdong.app.mall" to JdRules::create,
        "com.sankuai.meituan" to MeituanRules::create,
        MeituanTakeoutRules.PACKAGE to MeituanTakeoutRules::create,
        "com.ss.android.ugc.aweme" to DouyinRules::create,
    )
    val supportedPackages: Set<String> = Collections.unmodifiableSet(factories.keys.toSet())

    fun enabledRules(packages: Set<String>, context: RecognitionContext = RecognitionContext()): List<PageRule> =
        factories.filterKeys { it in packages }.values.flatMap { it(context) }
}
