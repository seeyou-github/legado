package io.legado.app.ui.book.read.page.entities

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TextChapterByPageReadAloudTest {

    private fun line(
        text: String,
        paragraphNum: Int,
        chapterPosition: Int,
        isParagraphEnd: Boolean,
    ) = TextLine(
        text = text,
        paragraphNum = paragraphNum,
        chapterPosition = chapterPosition,
        isParagraphEnd = isParagraphEnd,
    )

    private fun TextPage.setLines(vararg lines: TextLine) = apply {
        lines.forEach { addLine(it) }
    }

    private fun chapterOf(vararg pages: TextPage): TextChapter {
        val chapter = TextChapter(
            chapter = BookChapter(bookUrl = "book-url"),
            position = 0,
            title = "chapter",
            chaptersSize = pages.size,
            sameTitleRemoved = false,
            isVip = false,
            isPay = false,
            effectiveReplaceRules = null,
        )
        val field = TextChapter::class.java.getDeclaredField("textPages")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val textPages = field.get(chapter) as CopyOnWriteArrayList<TextPage>
        pages.forEachIndexed { index, page ->
            page.index = index
            page.textChapter = chapter
            textPages.add(page)
        }
        return chapter
    }

    private fun splitPage1() = TextPage(text = "第一段文字。\n第二段文字。\n第三段文字-部分1", title = "").setLines(
        line("第一段文字。", paragraphNum = 1, chapterPosition = 0, isParagraphEnd = true),
        line("第二段文字。", paragraphNum = 2, chapterPosition = 7, isParagraphEnd = true),
        line("第三段文字-部分1", paragraphNum = 3, chapterPosition = 14, isParagraphEnd = false),
    )

    private fun splitPage2() = TextPage(
        text = "第三段文字-部分2。\n第四段文字。\n第五段文字。\n",
        title = ""
    ).setLines(
        line("第三段文字-部分2。", paragraphNum = 3, chapterPosition = 23, isParagraphEnd = true),
        line("第四段文字。", paragraphNum = 4, chapterPosition = 34, isParagraphEnd = true),
        line("第五段文字。", paragraphNum = 5, chapterPosition = 41, isParagraphEnd = true),
    )

    @Test
    fun `by page speech does not split a paragraph across the page boundary`() {
        val chapter = chapterOf(splitPage1(), splitPage2())

        val contentList = chapter.getNeedReadAloud(0, pageSplit = true, startPos = 0)
            .split("\n")
            .filter { it.isNotEmpty() }

        assertEquals(
            listOf(
                "第一段文字。",
                "第二段文字。",
                "第三段文字-部分1第三段文字-部分2。",
                "第四段文字。",
                "第五段文字。",
            ),
            contentList
        )
        assertFalse(contentList.contains("第三段文字-部分1"))
    }

    @Test
    fun `continuous speech already merges the split paragraph`() {
        val chapter = chapterOf(splitPage1(), splitPage2())

        assertEquals(
            "第一段文字。\n第二段文字。\n第三段文字-部分1第三段文字-部分2。\n第四段文字。\n第五段文字。\n",
            chapter.getNeedReadAloud(0, pageSplit = false, startPos = 0)
        )
    }

    @Test
    fun `page boundary at a paragraph end keeps its separator`() {
        val page1 = TextPage(text = "第一段文字。\n", title = "").setLines(
            line("第一段文字。", paragraphNum = 1, chapterPosition = 0, isParagraphEnd = true),
        )
        val page2 = TextPage(text = "第三段文字。\n", title = "").setLines(
            line("第三段文字。", paragraphNum = 2, chapterPosition = 7, isParagraphEnd = true),
        )
        val chapter = chapterOf(page1, page2)

        assertEquals(
            "第一段文字。\n第三段文字。\n",
            chapter.getNeedReadAloud(0, pageSplit = true, startPos = 0)
        )
    }

    @Test
    fun `mid paragraph page that does not continue injects the separator as before`() {
        val page1 = TextPage(text = "第三段文字-部分1", title = "").setLines(
            line("第三段文字-部分1", paragraphNum = 3, chapterPosition = 0, isParagraphEnd = false),
        )
        val page2 = TextPage(text = "第四段文字。\n", title = "").setLines(
            line("第四段文字。", paragraphNum = 4, chapterPosition = 9, isParagraphEnd = true),
        )
        val chapter = chapterOf(page1, page2)

        assertEquals(
            "第三段文字-部分1\n第四段文字。\n",
            chapter.getNeedReadAloud(0, pageSplit = true, startPos = 0)
        )
    }
}