package io.legado.app.help.storage

import io.legado.app.data.entities.HttpTTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTtsBackupMergeTest {

    @Test
    fun restoreKeepsLocalEnginesAndOnlyAddsMissingOnes() {
        val local = listOf(HttpTTS(id = 1, name = "本地引擎", url = "local"))
        val restored = listOf(
            HttpTTS(id = 1, name = "本地引擎", url = "backup"),
            HttpTTS(id = 2, name = "备份引擎", url = "backup2"),
        )

        val merged = mergeRestoredHttpTts(restored, local)

        assertEquals(listOf(2L), merged.map { it.id })
        assertEquals(listOf("备份引擎"), merged.map { it.name })
    }

    @Test
    fun restoreKeepsAllEnginesWhenLocalListIsEmpty() {
        val restored = listOf(
            HttpTTS(id = 10, name = "引擎A"),
            HttpTTS(id = 11, name = "引擎B"),
        )

        assertEquals(restored, mergeRestoredHttpTts(restored, emptyList()))
    }

    @Test
    fun restoreSkipsDuplicateNamesAndRenewsConflictingIds() {
        val local = listOf(HttpTTS(id = 5, name = "已有引擎"))
        val restored = listOf(
            HttpTTS(id = 5, name = "新增引擎", url = "u1"),
            HttpTTS(id = 5, name = "新增引擎", url = "u2"),
            HttpTTS(id = 6, name = " ", url = "u3"),
        )

        val merged = mergeRestoredHttpTts(restored, local)

        assertEquals(1, merged.size)
        assertEquals("新增引擎", merged.first().name)
        assertEquals("u1", merged.first().url)
        assertTrue(merged.first().id != 5L)
    }
}
