package com.ipcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.regex.Pattern

class InMemoryLogBufferTest {

    @Before
    fun clearBuffer() {
        InMemoryLogBuffer.clear()
    }

    // A single log entry must match the "MM-dd HH:mm:ss.SSS LEVEL/Tag: message" format.
    @Test
    fun getTextUsesExpectedFormat() {
        InMemoryLogBuffer.add("I", "TestTag", "hello world")
        val line = InMemoryLogBuffer.getText().trim()
        val p = Pattern.compile("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I/TestTag: hello world$""")
        assertTrue(p.matcher(line).matches())
    }

    // Multiple entries must be returned in FIFO insertion order via getEntries().
    @Test
    fun getEntriesPreserveInsertionOrder() {
        InMemoryLogBuffer.add("I", "A", "first")
        InMemoryLogBuffer.add("W", "B", "second")
        val entries = InMemoryLogBuffer.getEntries()
        assertEquals(2, entries.size)
        assertTrue(entries[0].endsWith("I/A: first"))
        assertTrue(entries[1].contains("W/B: second"))
    }

    // clear() must remove all entries; both getText() and getEntries() must return empty results.
    @Test
    fun clearResetsToEmpty() {
        InMemoryLogBuffer.add("E", "T", "boom")
        InMemoryLogBuffer.clear()
        assertEquals("", InMemoryLogBuffer.getText().trim())
        assertTrue(InMemoryLogBuffer.getEntries().isEmpty())
    }

    // When the buffer reaches capacity, the oldest entry must be evicted (FIFO); the newest
    // entry must appear last and the first remaining entry must be the second-oldest.
    @Test
    fun capacityEvictsOldestEntry() {
        repeat(500) { i ->
            InMemoryLogBuffer.add("I", "Cap", "msg$i")
        }
        assertEquals(500, InMemoryLogBuffer.getEntries().size)
        InMemoryLogBuffer.add("I", "Cap", "newest")
        val entries = InMemoryLogBuffer.getEntries()
        assertEquals(500, entries.size)
        assertTrue(entries.none { it.contains("msg0") })
        assertTrue("Oldest remaining entry should be msg1", entries.first().contains("msg1"))
        assertTrue("Newest entry should be at the end", entries.last().contains("newest"))
    }
}
