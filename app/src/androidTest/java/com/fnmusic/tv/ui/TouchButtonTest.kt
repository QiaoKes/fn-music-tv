package com.fnmusic.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TouchButtonTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun physicalTapInvokesCallbackExactlyOnce() {
        val clicks = AtomicInteger(0)
        composeRule.setContent {
            FnMusicTheme {
                Button(
                    onClick = { clicks.incrementAndGet() },
                    modifier = Modifier
                        .size(width = 240.dp, height = 120.dp)
                        .semantics { contentDescription = "触摸按钮" },
                ) {
                    androidx.tv.material3.Text("触摸按钮")
                }
            }
        }

        composeRule.onNodeWithContentDescription("触摸按钮")
            .performTouchInput { click() }

        composeRule.runOnIdle { assertEquals(1, clicks.get()) }
    }

    @Test fun horizontalDragScrollsLazyRowWithoutClickingCard() {
        val clicks = AtomicInteger(0)
        lateinit var rowState: LazyListState
        composeRule.setContent {
            FnMusicTheme {
                rowState = rememberLazyListState()
                Box(Modifier.width(420.dp).height(140.dp)) {
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items((1..5).toList()) { index ->
                            Button(
                                onClick = { clicks.incrementAndGet() },
                                modifier = Modifier
                                    .size(width = 240.dp, height = 120.dp)
                                    .semantics { contentDescription = "卡片 $index" },
                            ) {
                                androidx.tv.material3.Text("卡片 $index")
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("卡片 1")
            .performTouchInput { swipeLeft(durationMillis = 500) }

        composeRule.runOnIdle {
            assertTrue(rowState.firstVisibleItemIndex > 0 || rowState.firstVisibleItemScrollOffset > 0)
            assertEquals(0, clicks.get())
        }
    }

    @Test fun verticalDragScrollsLazyColumnWithoutClickingCard() {
        val clicks = AtomicInteger(0)
        lateinit var columnState: LazyListState
        composeRule.setContent {
            FnMusicTheme {
                columnState = rememberLazyListState()
                Box(Modifier.width(280.dp).height(240.dp)) {
                    LazyColumn(
                        state = columnState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items((1..5).toList()) { index ->
                            Button(
                                onClick = { clicks.incrementAndGet() },
                                modifier = Modifier
                                    .size(width = 240.dp, height = 140.dp)
                                    .semantics { contentDescription = "纵向卡片 $index" },
                            ) {
                                androidx.tv.material3.Text("纵向卡片 $index")
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("纵向卡片 1")
            .performTouchInput { swipeUp(durationMillis = 500) }

        composeRule.runOnIdle {
            assertTrue(columnState.firstVisibleItemIndex > 0 || columnState.firstVisibleItemScrollOffset > 0)
            assertEquals(0, clicks.get())
        }
    }
}
