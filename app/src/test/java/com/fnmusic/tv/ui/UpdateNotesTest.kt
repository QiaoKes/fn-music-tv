package com.fnmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNotesTest {
    @Test fun `plain release sections and markdown bullets become structured blocks`() {
        val blocks = parseUpdateNotes(
            """
            安装提示

            - 覆盖安装前请确认签名一致
            - 安装后需要重新登录

            ## 新增

            * 支持多行歌词
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                UpdateNoteBlock.Heading("安装提示"),
                UpdateNoteBlock.Item("覆盖安装前请确认签名一致"),
                UpdateNoteBlock.Item("安装后需要重新登录"),
                UpdateNoteBlock.Heading("新增"),
                UpdateNoteBlock.Item("支持多行歌词"),
            ),
            blocks,
        )
    }

    @Test fun `plain paragraphs remain readable without an artificial bullet`() {
        assertEquals(
            listOf(UpdateNoteBlock.Paragraph("这是一个普通更新说明。")),
            parseUpdateNotes("这是一个普通更新说明。"),
        )
    }
}
