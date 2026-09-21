package com.aicodemax.tools.tester

import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestEngineTest {
    @Test
    fun aggregatesSuitesAndStopsOnError() = runBlocking {
        val ok = object : TestSuiteAdapter {
            override val suiteId = "unit"
            override suspend fun run(): Outcome<TestSuiteResult> = Outcome.Success(
                TestSuiteResult(
                    "unit",
                    listOf(
                        TestCaseResult("unit:a", "a", TestStatus.PASS),
                        TestCaseResult("unit:b", "b", TestStatus.FAIL, "boom"),
                    ),
                ),
            )
        }
        val engine = AggregatingTestEngine(listOf(ok))
        val results = (engine.runAll() as Outcome.Success<List<TestSuiteResult>>).value
        assertEquals("Passed 1/2 • Failed 1 • Skipped 0 • Blocked 0", engine.summarize(results))

        val failing = object : TestSuiteAdapter {
            override val suiteId = "device"
            override suspend fun run(): Outcome<TestSuiteResult> =
                Outcome.Failure(com.aicodemax.core.common.AppError("TEST_RUN", "no device"))
        }
        assertTrue(AggregatingTestEngine(listOf(failing)).runAll() is Outcome.Failure)
    }

    @Test
    fun parsesJUnitXml() {
        val xml = """
        <testsuite name="Demo" tests="3">
          <testcase classname="Demo" name="a" time="0.01"/>
          <testcase classname="Demo" name="b" time="0.02"><failure message="boom">trace</failure></testcase>
          <testcase classname="Demo" name="c" time="0.0"><skipped/></testcase>
        </testsuite>
        """.trimIndent()
        val suite = (JUnitXmlParser.parse(xml, "demo") as Outcome.Success<TestSuiteResult>).value
        assertEquals(3, suite.total)
        assertEquals(1, suite.passed)
        assertEquals(1, suite.failed)
        assertEquals(1, suite.skipped)
        assertTrue(JUnitXmlParser.parse("<nope/>") is Outcome.Failure)
    }
}
