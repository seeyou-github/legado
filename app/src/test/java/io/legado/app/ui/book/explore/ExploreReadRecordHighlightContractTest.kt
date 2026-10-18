package io.legado.app.ui.book.explore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 发现列表把读过(阅读时长超过1分钟)的书籍书名标成强调色,这里锁定实现方式:
 * 阅读记录整体索引 + 流监听,避免列表每本书都去查一次数据库
 */
class ExploreReadRecordHighlightContractTest {

    @Test
    fun `read records over one minute are aggregated by book name`() {
        val dao = projectText("src/main/java/io/legado/app/data/dao/ReadRecordDao.kt")

        assertTrue(dao.contains("fun flowBooksReadOver(minReadTime: Long): Flow<List<ReadRecordBook>>"))
        assertTrue(dao.contains("group by bookName having sum(readTime) > :minReadTime"))
    }

    @Test
    fun `the index is rebuilt from a flow and only on real changes`() {
        val viewModel = projectText("src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt")

        assertTrue(viewModel.contains("appDb.readRecordDao.flowBooksReadOver(ReadRecordIndex.MIN_READ_TIME_MS)"))
        assertTrue(viewModel.contains("distinctUntilChanged()"))
        assertTrue(viewModel.contains("readRecordIndex = ReadRecordIndex.of(it)"))
        assertTrue(viewModel.contains("""upAdapterLiveData.postValue("hasReadRecord")"""))
    }

    @Test
    fun `matching is done in memory without per item database access`() {
        val viewModel = projectText("src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt")

        assertTrue(viewModel.contains("readRecordIndex.contains(book.name, book.author)"))

        listOf(
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowAdapter.kt",
            "src/main/java/io/legado/app/ui/book/explore/ExploreShowGridAdapter.kt",
        ).forEach { path ->
            val adapter = projectText(path)
            assertFalse(path, adapter.contains("appDb"))
            assertTrue(path, adapter.contains("callBack.hasReadRecordOverMinute(item)"))
            assertTrue(path, adapter.contains(""" "hasReadRecord" ->"""))
            //主题色只解析一次,不能放在每次 bind 里
            assertTrue(path, adapter.contains("by lazy { context.accentColor }"))
        }
    }

    @Test
    fun `highlight uses the theme accent color and resets to the default color`() {
        val adapter = projectText("src/main/java/io/legado/app/ui/book/explore/ExploreShowAdapter.kt")

        assertTrue(
            adapter.contains(
                "if (callBack.hasReadRecordOverMinute(item)) readRecordNameColor else normalNameColor"
            )
        )

        val activity = projectText("src/main/java/io/legado/app/ui/book/explore/ExploreShowActivity.kt")
        assertTrue(activity.contains("override fun hasReadRecordOverMinute(book: SearchBook): Boolean"))
        assertTrue(activity.contains("return viewModel.hasReadRecordOverMinute(book)"))
    }

    private fun projectText(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace(Regex("\\s+"), " ")
            ?: error("Missing project file: $pathInApp")
}
