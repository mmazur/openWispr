package com.voicerewriter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InsertionDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun report(index: Int, pkg: String = "com.openai.chatgpt", events: List<String> = listOf("PASTE rejected")) =
        InsertionReport("request-$index", index.toLong(), pkg, "clipboard_fallback", "Dev test", "focused=false", events)

    @Test fun `global byte cap includes failures from every app`() {
        val dir = temporary.newFolder()
        val log = RollingInsertionLog(dir, maxBytes = 2048)
        repeat(30) { index ->
            log.append(report(index, "app.${index % 4}", listOf("x".repeat(600))))
            assertTrue("storage exceeded global limit", dir.listFiles()!!.sumOf { it.length() } <= 2048)
        }
        assertTrue(log.reports().any { it.id == "request-29" })
        assertFalse(log.reports().any { it.id == "request-0" })
    }

    @Test fun `oldest reports are evicted at the report count cap`() {
        val dir = temporary.newFolder()
        val log = RollingInsertionLog(dir, maxBytes = 100_000, maxReports = 3)
        repeat(5) { index ->
            log.append(report(index))
            dir.listFiles()!!.first { it.name.startsWith("$index-") }.setLastModified(index.toLong() + 1)
        }
        assertEquals(listOf("request-4", "request-3", "request-2"), log.reports().map { it.id })
    }

    @Test fun `oversized unicode event data is trimmed to the byte limit`() {
        val dir = temporary.newFolder()
        val log = RollingInsertionLog(dir, maxBytes = 2048)
        log.append(report(1, events = List(100) { "\u20ac".repeat(300) }))
        assertEquals(1, log.reports().size)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2048)
        assertTrue(log.reports().single().events.size < 100)
    }

    @Test fun `clearing one app retains reports for other apps`() {
        val log = RollingInsertionLog(temporary.newFolder())
        log.append(report(1, "com.openai.chatgpt"))
        log.append(report(2, "other.app"))
        log.clear("com.openai.chatgpt")
        assertEquals(listOf("other.app"), log.reports().map { it.appPackage })
        log.clear()
        assertTrue(log.reports().isEmpty())
    }

    @Test fun `incomplete or corrupt reports do not break inspection`() {
        val dir = temporary.newFolder()
        File(dir, "broken.json").writeText("{incomplete")
        val log = RollingInsertionLog(dir)
        log.append(report(1))
        assertEquals(listOf("request-1"), log.reports().map { it.id })
    }

    @Test fun `verified successes are discarded even after failed intermediate attempts`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("com.openai.chatgpt", "focused=true", "Dev test", reports::add)
        trace.add("no input field found on first attempt")
        trace.add("PASTE accepted, insertion observed")
        trace.finish(null)
        assertTrue(reports.isEmpty())
    }

    @Test fun `request event ring is bounded and report is saved only once`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("com.openai.chatgpt", "focused=true", "Dev test", reports::add)
        trace.add("queued: preserve this initial decision")
        repeat(1000) { trace.add("attempt=$it " + "x".repeat(1000)) }
        trace.finish("clipboard_fallback")
        trace.finish("clipboard_fallback")
        assertEquals(1, reports.size)
        assertTrue(reports.single().events.sumOf { it.length } < 16000)
        assertTrue(reports.single().events.any { it.contains("preserve this initial decision") })
        assertEquals("com.openai.chatgpt", reports.single().appPackage)
        assertTrue(reports.single().events.first().contains("dropped"))
    }

    @Test fun `clipboard restoration problems are retained even if field insertion succeeded`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("com.openai.chatgpt", "focused=true", "Dev test", reports::add)
        trace.problem = "clipboard_restore_failed"
        trace.finish(null)
        assertEquals("clipboard_restore_failed", reports.single().outcome)
    }

    @Test fun `repeated unchanged snapshots are suppressed and counted`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("app", "focused=true", "test", reports::add)
        repeat(1000) { trace.state("lookup", "same missing input") }
        trace.state("lookup", "input became available")
        trace.finish("clipboard_fallback")
        assertEquals(3, reports.single().events.size)
        assertTrue(reports.single().events.first().contains("suppressed: 999"))
    }

    @Test fun `direct failure is retained when paste successfully inserts text`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("app", "focused=true", "test", reports::add)
        trace.directFailed("set_text_action_rejected")
        trace.finish("direct_entry_failed_paste_succeeded")
        val report = reports.single()
        assertEquals("direct_entry_failed_paste_succeeded", report.outcome)
        assertEquals("set_text_action_rejected", report.primaryFailure)
        assertEquals(report, InsertionReport.parse(report.json()))
    }

    @Test fun `transient direct failure remains inspectable after a later direct success`() {
        val reports = mutableListOf<InsertionReport>()
        val trace = InsertionTrace("app", "focused=true", "test", reports::add)
        trace.directFailed("unknown_cursor_in_nonempty_field")
        trace.finish(null)
        assertEquals("direct_entry_failed_then_recovered", reports.single().outcome)
    }

    @Test fun `reports saved by earlier Dev builds remain readable`() {
        val old = org.json.JSONObject(report(1).json()).apply { remove("primaryFailure") }
        val parsed = InsertionReport.parse(old.toString())
        assertEquals("com.openai.chatgpt", parsed.appPackage)
        assertEquals("clipboard_fallback", parsed.outcome)
        assertNull(parsed.primaryFailure)
    }
}
