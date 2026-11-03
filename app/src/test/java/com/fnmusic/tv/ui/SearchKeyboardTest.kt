package com.fnmusic.tv.ui

import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.TrackGuid
import com.fnmusic.tv.core.model.playback.QueuePageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyboardTest {
    @Test fun `t9 grid exposes letters digits and the command columns`() {
        val rows = searchKeyboardRows()

        assertEquals(4, rows.size)
        assertEquals(listOf(4, 4, 4, 3), rows.map { it.keys.size })

        // Rightmost command column
        assertEquals(SearchKey.Erase, rows[0].keys.last())
        assertEquals(SearchKey.Clear, rows[1].keys.last())
        assertEquals(SearchKey.Submit, rows[2].keys.last())
        // Bottom control row
        assertEquals(listOf(SearchKey.Ime, SearchKey.SpaceZero, SearchKey.ClearHistory), rows[3].keys)

        val grid = rows.take(3).flatMap { it.keys }.filterIsInstance<SearchKey.NineGrid>()
        assertEquals("23456789", grid.joinToString("") { it.digit.toString() })
        assertEquals(
            "ABC DEF GHI JKL MNO PQRS TUV WXYZ",
            grid.joinToString(" ") { it.letters.joinToString("") { letter -> letter.uppercaseChar().toString() } },
        )

        assertEquals("1", searchKeyLabel(SearchKey.Direct('1')))
        assertEquals("输入法", searchKeyLabel(SearchKey.Ime))
        assertEquals("清空历史", searchKeyLabel(SearchKey.ClearHistory))
        assertEquals("空格/0", searchKeyLabel(SearchKey.SpaceZero))
    }

    @Test fun `space zero key types a space immediately and offers digit 0 downward`() {
        val slots = searchSecondarySlots(SearchKey.SpaceZero).associate { it.position to it.char }

        assertEquals(' ', slots[SearchKeySlotPosition.Up])
        assertEquals('0', slots[SearchKeySlotPosition.Down])
        assertEquals('0', searchSlotForDirection(SearchKey.SpaceZero, SearchKeySlotPosition.Down))
        assertNull(searchSlotForDirection(SearchKey.SpaceZero, SearchKeySlotPosition.Left))
    }

    @Test fun `three letter keys put the digit on Left and letters on three directions`() {
        val key = searchKeyboardRows()[0].keys[1] as SearchKey.NineGrid // key 2 ABC

        val labeled = searchSecondarySlots(key).associate { it.position to it.char }

        assertEquals('a', labeled[SearchKeySlotPosition.Up])
        assertEquals('b', labeled[SearchKeySlotPosition.Right])
        assertEquals('c', labeled[SearchKeySlotPosition.Down])
        assertEquals('2', labeled[SearchKeySlotPosition.Left])
        assertNull(labeled[SearchKeySlotPosition.Center])
    }

    @Test fun `four letter keys cover the four directions and reserve a digit for the second OK`() {
        val key = searchKeyboardRows()[2].keys[0] as SearchKey.NineGrid // key 7 PQRS

        val labeled = searchSecondarySlots(key).associate { it.position to it.char }

        assertEquals('p', labeled[SearchKeySlotPosition.Up])
        assertEquals('q', labeled[SearchKeySlotPosition.Right])
        assertEquals('r', labeled[SearchKeySlotPosition.Down])
        assertEquals('s', labeled[SearchKeySlotPosition.Left])
        assertEquals('7', labeled[SearchKeySlotPosition.Center])
        assertEquals('7', searchSlotForDirection(key, SearchKeySlotPosition.Center))
    }

    @Test fun `directional slot lookup never returns null for an open grid key`() {
        searchKeyboardRows()
            .flatMap { it.keys }
            .filterIsInstance<SearchKey.NineGrid>()
            .forEach { key ->
                val directional = SearchKeySlotPosition.entries - SearchKeySlotPosition.Center
                directional.forEach { position ->
                    assertTrue(searchSlotForDirection(key, position) != null)
                }
                // The center slot only exists on four-letter keys (7/9).
                assertEquals(
                    key.letters.size == 4,
                    searchSlotForDirection(key, SearchKeySlotPosition.Center) != null,
                )
            }
    }

    @Test fun `character appends and space and clear edit the query`() {
        assertEquals("ab", appendSearchQueryCharacter("a", 'b'))
        assertEquals("a ", appendSearchQueryCharacter("a", ' '))
        assertEquals("a ", appendSearchQueryCharacter("a ", ' '))
        assertEquals("", eraseKeyboardChar("a"))
        assertEquals("", eraseKeyboardChar(""))
    }

    @Test fun `submit requires a non blank trimmed keyword`() {
        assertFalse(isSearchQuerySubmittable("   "))
        assertTrue(isSearchQuerySubmittable(" 那英 "))
        assertEquals("那英", submittedSearchKeyword("  那英 "))
    }

    @Test fun `pending keyword promotes only after the debounce boundary`() {
        assertNull(pendingSearchKeyword("那英", "那英", debounceElapsed = true))
        assertNull(pendingSearchKeyword("那英 ", "那英", debounceElapsed = true))
        assertNull(pendingSearchKeyword("  ", "那英", debounceElapsed = true))
        assertNull(pendingSearchKeyword("周杰", "那英", debounceElapsed = false))
        assertEquals("周杰", pendingSearchKeyword("周杰", "那英", debounceElapsed = true))
        assertEquals("周杰", pendingSearchKeyword("周杰 ", " 周杰伦", debounceElapsed = true))
    }

    @Test fun `search tab keys round trip to their section`() {
        SearchSection.entries.forEach { section ->
            assertEquals(section, searchSectionForTabKey(searchTabKey(section)))
        }
        assertNull(searchSectionForTabKey("primary-action"))
    }

    @Test fun `playable search rows keep their absolute index in the queue window`() {
        val first = track("t1")
        val blocked = track("t2", accessStatus = 1)
        val third = track("t3")
        val data = SearchSectionData(
            keyword = "那",
            loaded = true,
            items = listOf(first, blocked, third),
            total = 3,
            sort = "createdAt,desc",
        )

        val (playable, segment) = searchResultQueueWindow(data, ::isTrackPlayable)!!

        assertEquals(listOf(first, third), playable)
        assertEquals(listOf("t1", "t3"), segment.mediaIds)
        assertEquals(listOf(0, 2), segment.playableItems.map(QueuePageItem::sourceAbsoluteIndex))
        assertEquals(3, segment.rawRowCount)
        assertEquals("createdAt,desc", segment.sort)

        val empty = searchResultQueueWindow(data.copy(items = listOf(blocked)), ::isTrackPlayable)
        assertEquals(null, empty)
    }

    private fun track(guid: String, accessStatus: Int? = null) = Track(
        guid = TrackGuid(guid),
        title = "title-$guid",
        artistName = "artist",
        albumName = null,
        coverId = null,
        durationMs = 1_000,
        isCue = false,
        accessStatus = accessStatus,
        audioFormat = null,
        isFavorite = false,
    )
}
