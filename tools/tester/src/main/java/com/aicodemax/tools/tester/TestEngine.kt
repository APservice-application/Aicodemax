package com.aicodemax.tools.tester

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import javax.xml.parsers.DocumentBuilderFactory

/** CP-22: test engine (MASTER_ARCHITECTURE §22). */
enum class TestStatus { PASS, FAIL, SKIPPED, BLOCKED }

data class TestCaseResult(
    val id: String,
    val name: String,
    val status: TestStatus,
    val detail: String = "",
    val durationMs: Long = 0,
)

data class TestSuiteResult(
    val suite: String,
    val cases: List<TestCaseResult>,
) {
    val passed: Int get() = cases.count { it.status == TestStatus.PASS }
    val failed: Int get() = cases.count { it.status == TestStatus.FAIL }
    val skipped: Int get() = cases.count { it.status == TestStatus.SKIPPED }
    val blocked: Int get() = cases.count { it.status == TestStatus.BLOCKED }
    val total: Int get() = cases.size
}

/** A runnable suite (unit/integration/UI/device…). Device runners plug in here. */
interface TestSuiteAdapter {
    val suiteId: String
    suspend fun run(): Outcome<TestSuiteResult>
}

class AggregatingTestEngine(private val suites: List<TestSuiteAdapter>) {
    suspend fun runAll(): Outcome<List<TestSuiteResult>> {
        val results = mutableListOf<TestSuiteResult>()
        for (suite in suites) {
            when (val result = suite.run()) {
                is Outcome.Failure -> return result
                is Outcome.Success -> results.add(result.value)
            }
        }
        return Outcome.Success(results)
    }

    fun summarize(results: List<TestSuiteResult>): String {
        val total = results.sumOf { it.total }
        val failed = results.sumOf { it.failed }
        val skipped = results.sumOf { it.skipped }
        val blocked = results.sumOf { it.blocked }
        val passed = results.sumOf { it.passed }
        return "Passed $passed/$total • Failed $failed • Skipped $skipped • Blocked $blocked"
    }
}

/** Parses Gradle/JUnit TEST-*.xml reports into suite results. */
object JUnitXmlParser {
    fun parse(xml: String, suiteId: String = "junit"): Outcome<TestSuiteResult> =
        runOutcome("TEST_XML_PARSE") {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.byteInputStream())
            doc.documentElement.normalize()
            val nodes = doc.getElementsByTagName("testcase")
            val cases = mutableListOf<TestCaseResult>()
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val attrs = node.attributes
                fun attr(name: String): String =
                    attrs.getNamedItem(name)?.nodeValue.orEmpty()
                val name = "${attr("classname")}.${attr("name")}".trim('.')
                val timeMs = (attr("time").toDoubleOrNull() ?: 0.0) * 1000
                var status = TestStatus.PASS
                var detail = ""
                val children = node.childNodes
                for (j in 0 until children.length) {
                    val child = children.item(j)
                    when (child.nodeName) {
                        "failure", "error" -> {
                            status = TestStatus.FAIL
                            detail = (child.attributes.getNamedItem("message")?.nodeValue
                                ?: child.textContent).take(500)
                        }
                        "skipped" -> status = TestStatus.SKIPPED
                    }
                }
                cases.add(TestCaseResult("$suiteId:$name", name, status, detail, timeMs.toLong()))
            }
            if (nodes.length == 0) throw IllegalArgumentException("no <testcase> nodes found")
            TestSuiteResult(suiteId, cases)
        }
}
