package app.omymoney.core.recognition

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class RuleRegistryTest {
    private val apps = linkedMapOf("wechat" to "com.tencent.mm", "alipay" to "com.eg.android.AlipayGphone",
        "pdd" to "com.xunmeng.pinduoduo", "unionpay" to "com.unionpay", "jd" to "com.jingdong.app.mall",
        "meituan" to "com.sankuai.meituan", "douyin" to "com.ss.android.ugc.aweme")
    private val context = RecognitionContext(bookId = "book", zoneId = ZoneId.of("Asia/Shanghai"))
    private val all = RuleRegistry.enabledRules(apps.values.toSet(), context)

    @Test fun registryExactlyMatchesStaticConnectedInventoryAndHasUniqueIds() {
        val inventory = Json.parseToJsonElement(File(System.getProperty("omymoney.repoRoot"), "docs/qianji-static-inventory.json").readText()).jsonObject
        val connected = inventory.getValue("extractors").jsonArray.map { it.jsonObject }
            .filter { it.getValue("status").jsonPrimitive.content == "statically_connected" }
        assertEquals(56, connected.size)
        assertEquals(connected.map { it.getValue("page_type").jsonPrimitive.content }.toSet(), all.map { it.id }.toSet())
        assertEquals(56, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { rule ->
            val original = connected.single { it.getValue("page_type").jsonPrimitive.content == rule.id }.getValue("matcher").jsonObject
            assertEquals(rule.id, original.getValue("package_name").jsonPrimitive.content, rule.packageName)
            assertEquals(rule.id, original.getValue("priority_in_app").jsonPrimitive.int, rule.priority)
        }
    }

    @Test fun everyEnabledRuleHasPassingIndependentPositiveAndNegativeEvidence() {
        assertEquals(apps.values.toSet() + MeituanTakeoutRulesTest.PACKAGE, RuleRegistry.supportedPackages)
        val fixtureIds = apps.keys.flatMap(::fixtures).map { it.ruleId }.toSet()
        assertEquals(fixtureIds, all.map { it.id }.toSet())
        apps.forEach { (name, pkg) ->
            val rules = RuleRegistry.enabledRules(setOf(pkg), context)
            val count = fixtures(name).map { it.ruleId }.toSet().size
            RuleFixtureAssertions(name, pkg, rules, count).run { coverage(); positives(); negatives() }
        }
    }

    @Test fun packageConsentIsExplicitAndDisconnectedOrPasswordRulesStayAbsent() {
        assertTrue(RuleRegistry.enabledRules(emptySet()).isEmpty())
        assertTrue(RuleRegistry.enabledRules(setOf("com.taobao.taobao", "me.ele", "cn.gov.pbc.dcep")).isEmpty())
        assertFalse(all.any { it.id.contains("password", ignoreCase = true) || it.id.contains("taobao", ignoreCase = true) })
        assertEquals(setOf("com.tencent.mm"), RuleRegistry.enabledRules(setOf("com.tencent.mm", "unregistered")).map { it.packageName }.toSet())
    }

    @Test fun registryUsesFreshBookContextInsteadOfCapturingAStaleSelection() {
        val snapshot = fixtures("wechat").first { it.ruleId == "WeChatPaySuccess" && it.positive }.snapshot("com.tencent.mm")
        fun book(id: String) = RuleEngine(RuleRegistry.enabledRules(setOf("com.tencent.mm"), context.copy(bookId = id))).recognize(snapshot).draft().bookId
        assertEquals("book-one", book("book-one"))
        assertEquals("book-two", book("book-two"))
    }

    @Test fun coverageReportKeepsEveryScenarioAndDoesNotClaimLiveDeviceSupport() {
        val file = File(System.getProperty("omymoney.repoRoot"), "docs/recognition-coverage.json")
        assertTrue("Stage B must publish per-scenario evidence", file.isFile)
        val report = Json.parseToJsonElement(file.readText()).jsonObject
        val scenarios = report.getValue("scenarios").jsonArray.map { it.jsonObject }
        assertEquals(all.map { it.id }.toSet(), scenarios.map { it.getValue("pageType").jsonPrimitive.content }.toSet())
        assertEquals(56, scenarios.size)
        scenarios.forEach { scenario ->
            val id = scenario.getValue("pageType").jsonPrimitive.content
            val fixtureFile = File(System.getProperty("omymoney.repoRoot"), scenario.getValue("fixture").jsonPrimitive.content)
            val fixtures = Json.parseToJsonElement(fixtureFile.readText()).jsonArray.map { it.jsonObject }
                .filter { it.getValue("ruleId").jsonPrimitive.content == id }
            assertEquals(fixtures.count { it.getValue("positive").jsonPrimitive.boolean }, scenario.getValue("positiveCases").jsonPrimitive.int)
            assertEquals(fixtures.count { !it.getValue("positive").jsonPrimitive.boolean }, scenario.getValue("negativeCases").jsonPrimitive.int)
            assertEquals("not_tested", scenario.getValue("livePaymentApp").jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals("synthetic_fixtures", scenario.getValue("evidenceLevel").jsonPrimitive.content)
        }
    }
}
