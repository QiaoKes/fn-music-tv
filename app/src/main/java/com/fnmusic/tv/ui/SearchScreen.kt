package com.fnmusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt
import com.fnmusic.tv.AuthenticatedAppDependencies
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.QueuePageItem
import com.fnmusic.tv.core.model.playback.QueuePageSegment
import com.fnmusic.tv.core.model.playback.QueueSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SEARCH_DEBOUNCE_MS = 300L
internal const val SEARCH_TRACKS_PAGE_SIZE = 50

/** Which result group is displayed on the right pane; the song group owns the play pipeline. */
internal enum class SearchSection(val label: String) {
    Tracks("歌曲"),
    Artists("歌手"),
    Albums("专辑"),
    Playlists("歌单"),
}

internal fun searchTabKey(section: SearchSection): String = "search-tab:${section.name}"

internal fun searchSectionForTabKey(key: String): SearchSection? =
    SearchSection.entries.firstOrNull { searchTabKey(it) == key }

/** T9 grid: letters and digits share one key; no symbol panel is offered. */
internal enum class SearchKeySlotPosition {
    Up,
    Right,
    Down,
    Left,
    Center,
}

/** Pure keyboard surface: stable layout plus query editing, fully unit-testable without composition. */
internal sealed interface SearchKey {
    val focusKey: String

    /** Secondary-panel activation id; keys without a panel use null. */
    val activationId: Char?
        get() = null

    /** T9 key: one digit plus its letters; the digit is reachable from its secondary panel. */
    data class NineGrid(val digit: Char, val letters: List<Char>) : SearchKey {
        override val focusKey: String = "grid:$digit"
        override val activationId: Char = digit
        val label: String get() = letters.map(Char::uppercaseChar).joinToString("") + digit
    }

    /** Key without a secondary panel: pressing OK types its character directly. */
    data class Direct(val char: Char, val label: String = char.uppercaseChar().toString()) : SearchKey {
        override val focusKey: String = "direct:$char"
    }

    /** Bottom-row combined key: OK types a space right away, Down then types the digit 0. */
    data object SpaceZero : SearchKey {
        override val focusKey: String = "space-zero"
        override val activationId: Char = '0'
        val label: String = "空格/0"
    }

    data object Ime : SearchKey {
        override val focusKey: String = "key-ime"
    }

    data object Erase : SearchKey {
        override val focusKey: String = "key-erase"
    }

    data object Clear : SearchKey {
        override val focusKey: String = "key-clear"
    }

    data object Submit : SearchKey {
        override val focusKey: String = "key-submit"
    }

    data object ClearHistory : SearchKey {
        override val focusKey: String = "key-clear-history"
    }
}

internal data class SearchKeyboardRow(val keys: List<SearchKey>)

/**
 * Four-column layout: the three T9 rows share their rightmost column with the commands
 * (删除/清空/搜索), and the bottom row holds 输入法 / 空格·0 / 清空历史.
 */
internal fun searchKeyboardRows(): List<SearchKeyboardRow> = listOf(
    SearchKeyboardRow(
        listOf(
            SearchKey.Direct('1'),
            SearchKey.NineGrid('2', listOf('a', 'b', 'c')),
            SearchKey.NineGrid('3', listOf('d', 'e', 'f')),
            SearchKey.Erase,
        ),
    ),
    SearchKeyboardRow(
        listOf(
            SearchKey.NineGrid('4', listOf('g', 'h', 'i')),
            SearchKey.NineGrid('5', listOf('j', 'k', 'l')),
            SearchKey.NineGrid('6', listOf('m', 'n', 'o')),
            SearchKey.Clear,
        ),
    ),
    SearchKeyboardRow(
        listOf(
            SearchKey.NineGrid('7', listOf('p', 'q', 'r', 's')),
            SearchKey.NineGrid('8', listOf('t', 'u', 'v')),
            SearchKey.NineGrid('9', listOf('w', 'x', 'y', 'z')),
            SearchKey.Submit,
        ),
    ),
    SearchKeyboardRow(
        listOf(
            SearchKey.Ime,
            SearchKey.SpaceZero,
            SearchKey.ClearHistory,
        ),
    ),
)

internal fun searchKeyLabel(key: SearchKey): String = when (key) {
    is SearchKey.NineGrid -> key.label
    is SearchKey.Direct -> key.label
    is SearchKey.SpaceZero -> key.label
    SearchKey.Ime -> "输入法"
    SearchKey.Erase -> "删除"
    SearchKey.Clear -> "清空"
    SearchKey.Submit -> "搜索"
    SearchKey.ClearHistory -> "清空历史"
}

internal data class SearchSecondarySlot(
    val label: String,
    val char: Char,
    val position: SearchKeySlotPosition,
)

/**
 * Secondary selection slots around one key, in stable order.
 * - Three-letter keys: up/right/down carry the letters, left carries the digit.
 * - Four-letter keys (7/9): the four directions carry the letters and a second OK press (center)
 *   selects the digit, so no letter is lost.
 * - The space/0 key: down carries the digit 0 while the immediate OK press already typed a space.
 */
internal fun searchSecondarySlots(key: SearchKey): List<SearchSecondarySlot> = when (key) {
    is SearchKey.NineGrid -> when (key.letters.size) {
        3 -> listOf(
            SearchSecondarySlot(key.letters[0].uppercaseChar().toString(), key.letters[0], SearchKeySlotPosition.Up),
            SearchSecondarySlot(key.letters[1].uppercaseChar().toString(), key.letters[1], SearchKeySlotPosition.Right),
            SearchSecondarySlot(key.letters[2].uppercaseChar().toString(), key.letters[2], SearchKeySlotPosition.Down),
            SearchSecondarySlot(key.digit.toString(), key.digit, SearchKeySlotPosition.Left),
        )
        else -> key.letters.mapIndexed { index, letter ->
            SearchSecondarySlot(
                letter.uppercaseChar().toString(),
                letter,
                SearchKeySlotPosition.entries[index],
            )
        } + SearchSecondarySlot(key.digit.toString(), key.digit, SearchKeySlotPosition.Center)
    }
    is SearchKey.SpaceZero -> listOf(
        SearchSecondarySlot("空格", ' ', SearchKeySlotPosition.Up),
        SearchSecondarySlot("0", '0', SearchKeySlotPosition.Down),
    )
    else -> emptyList()
}

/** Maps one directional key (or a repeated center press) to the character it selects. */
internal fun searchSlotForDirection(
    key: SearchKey,
    position: SearchKeySlotPosition,
): Char? = searchSecondarySlots(key).firstOrNull { it.position == position }?.char

/** Applies one pressed key to the current query; Submit is a no-op on the text itself. */
internal fun applyKeyboardKey(query: String, key: SearchKey): String = when (key) {
    SearchKey.Erase, SearchKey.Submit -> query
    else -> query
}

internal fun appendSearchQueryCharacter(query: String, char: Char): String = when (char) {
    ' ' -> if (query.isEmpty() || query.endsWith(' ')) query else "$query "
    else -> query + char
}

internal fun eraseKeyboardChar(query: String): String = if (query.isEmpty()) query else query.dropLast(1)

internal fun isSearchQuerySubmittable(query: String): Boolean = query.isNotBlank()

internal fun submittedSearchKeyword(query: String): String = query.trim()

/**
 * Debounced promotion of a query to a refresh keyword.
 * - Not submittable or equal (ignoring surrounding spaces) to the loaded keyword: no refresh (null).
 * - Submittable and different: the keyword, committed by callers once the debounce window completes.
 */
internal fun pendingSearchKeyword(query: String, loadedKeyword: String, debounceElapsed: Boolean): String? {
    val keyword = submittedSearchKeyword(query)
    if (!isSearchQuerySubmittable(keyword) || keyword == submittedSearchKeyword(loadedKeyword)) return null
    return keyword.takeIf { debounceElapsed }
}

/** Search page: left keyboard panel, right live results; focus stays in the panel while typing. */
@Composable
internal fun SearchScreen(
    container: AuthenticatedAppDependencies,
    onOpenArtist: (Artist) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayer: (Track) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var loadedKeyword by rememberSaveable { mutableStateOf("") }
    var editingSignal by rememberSaveable { mutableStateOf(0) }
    var refreshRevision by remember { mutableStateOf(0) }
    var activeKeyDigit by rememberSaveable { mutableStateOf<Char?>(null) }
    var section by rememberSaveable { mutableStateOf(SearchSection.Tracks) }
    val history = container.searchHistory.state.collectAsStateWithLifecycle().value
    val keyboardEntryFocus = remember { FocusRequester() }
    val results = remember { SearchResultsHolder() }

    fun recordHistory(keyword: String) {
        if (isSearchQuerySubmittable(keyword)) container.searchHistory.record(keyword)
    }

    fun submit() {
        val keyword = submittedSearchKeyword(query)
        if (!isSearchQuerySubmittable(keyword)) return
        recordHistory(keyword)
        // Explicit submit refreshes immediately, bypassing the debounce window.
        loadedKeyword = keyword
        refreshRevision++
    }

    // Live search: each stable query after the debounce window refreshes the right pane. Results
    // loading never moves focus; it stays wherever the keyboard or field currently has it.
    LaunchedEffect(query, loadedKeyword) {
        if (submittedSearchKeyword(query) == submittedSearchKeyword(loadedKeyword)) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        val pending = pendingSearchKeyword(query, loadedKeyword, debounceElapsed = true) ?: return@LaunchedEffect
        loadedKeyword = pending
        refreshRevision++
    }

    // Refetch guard: a submit revision, a restored saveable state, or a stale holder all funnel
    // through here so the right pane always matches the committed keyword exactly once.
    LaunchedEffect(loadedKeyword, refreshRevision) {
        if (loadedKeyword.isNotBlank()) results.fetchIfStale(container, loadedKeyword)
    }

    // Saveable-safe initial focus: the first keyboard key owns entry into the panel, and the
    // keydown pipeline keeps focus there while results load.
    LaunchedEffect(Unit) {
        runCatching { keyboardEntryFocus.requestFocus() }
    }

    // Back closes the open secondary panel before it can leave the route.
    BackHandler(enabled = activeKeyDigit != null) {
        activeKeyDigit = null
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp)) {
        SearchPanel(
            modifier = Modifier.weight(1f),
            query = query,
            onQueryChange = { query = it },
            onOpenIme = { editingSignal++ },
            entryFocus = keyboardEntryFocus,
            history = history,
            onKeyboardCharacter = { char -> query = appendSearchQueryCharacter(query, char) },
            onErase = { query = eraseKeyboardChar(query) },
            onSubmit = ::submit,
            onClearHistory = { container.searchHistory.clear() },
            editingSignal = editingSignal,
            activeKeyDigit = activeKeyDigit,
            onKeyActivated = { activeKeyDigit = it },
            onKeyDeactivated = { activeKeyDigit = null },
        )
        Spacer(Modifier.width(28.dp))
        SearchResultsPane(
            modifier = Modifier.weight(2f),
            container = container,
            holder = results,
            loadedKeyword = loadedKeyword,
            section = section,
            onSection = { section = it },
            onRetry = ::submit,
            onPlayer = onPlayer,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenPlaylist = onOpenPlaylist,
        )
    }
}

@Composable
private fun SearchPanel(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    editingSignal: Int,
    onOpenIme: () -> Unit,
    entryFocus: FocusRequester,
    history: List<String>,
    onKeyboardCharacter: (Char) -> Unit,
    onErase: () -> Unit,
    onSubmit: () -> Unit,
    onClearHistory: () -> Unit,
    activeKeyDigit: Char?,
    onKeyActivated: (Char) -> Unit,
    onKeyDeactivated: () -> Unit,
) {
    Column(modifier) {
        Text("搜索", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        TvTextField(
            value = query,
            onValueChange = onQueryChange,
            label = "关键词",
            placeholder = "歌曲 / 歌手 / 专辑 / 歌单",
            editingSignal = editingSignal,
            onEditingChange = {},
            downFocus = entryFocus,
        )
        Spacer(Modifier.height(12.dp))
        KeyboardGrid(
            rows = searchKeyboardRows(),
            activeKeyDigit = activeKeyDigit,
            onKeyActivated = onKeyActivated,
            onKeyDeactivated = onKeyDeactivated,
            entryFocus = entryFocus,
            onCharacter = onKeyboardCharacter,
            onErase = onErase,
            onClear = { onQueryChange("") },
            onSubmit = onSubmit,
            onIme = onOpenIme,
            onClearHistory = onClearHistory,
        )
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("最近搜索", color = FnColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(84.dp),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(history) { keyword ->
                    SearchActionChip(keyword) {
                        onQueryChange(keyword)
                        onSubmit()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchActionChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Button(
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF171B1D),
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyboardGrid(
    rows: List<SearchKeyboardRow>,
    activeKeyDigit: Char?,
    onKeyActivated: (Char) -> Unit,
    onKeyDeactivated: () -> Unit,
    entryFocus: FocusRequester,
    onCharacter: (Char) -> Unit,
    onErase: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onIme: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    val entryTarget = rows.first().keys.first()
    val cellCoords = remember { mutableMapOf<Char, LayoutCoordinates>() }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // One overlay layer above every cell so the secondary bubbles can never be covered by a
    // neighbouring key button or by the row below them.
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { gridCoords = it },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.keys.forEach { key ->
                        Box(
                            Modifier
                                .onGloballyPositioned { coords ->
                                    val id = key.activationId
                                    if (id != null) cellCoords[id] = coords
                                }
                                .weight(if (key is SearchKey.SpaceZero) 2f else 1f)
                                .height(58.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            SearchKeyButton(
                                key = key,
                                entryTarget = key === entryTarget,
                                entryFocus = entryFocus,
                                activated = key.activationId != null && key.activationId == activeKeyDigit,
                                shape = shape,
                                onActivated = onKeyActivated,
                                onDeactivated = onKeyDeactivated,
                                onCharacter = onCharacter,
                                onErase = onErase,
                                onClear = onClear,
                                onSubmit = onSubmit,
                                onIme = onIme,
                                onClearHistory = onClearHistory,
                            )
                        }
                    }
                }
            }
        }
        val grid = gridCoords
        val cell = cellCoords[activeKeyDigit]
        if (grid != null && cell != null) {
            val activeKey = rows.flatMap { it.keys }.firstOrNull { it.activationId == activeKeyDigit }
            if (activeKey != null) {
                SecondarySlotBubbles(
                    slots = searchSecondarySlots(activeKey),
                    center = grid.localPositionOf(
                        cell,
                        Offset(cell.size.width / 2f, cell.size.height / 2f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SearchKeyButton(
    key: SearchKey,
    entryTarget: Boolean,
    entryFocus: FocusRequester,
    activated: Boolean,
    shape: RoundedCornerShape,
    onActivated: (Char) -> Unit,
    onDeactivated: () -> Unit,
    onCharacter: (Char) -> Unit,
    onErase: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onIme: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Button(
        onClick = {
            when (key) {
                SearchKey.Submit -> onSubmit()
                SearchKey.Erase -> onErase()
                SearchKey.Clear -> onClear()
                SearchKey.Ime -> onIme()
                SearchKey.ClearHistory -> onClearHistory()
                is SearchKey.Direct -> onCharacter(key.char)
                is SearchKey.SpaceZero -> {
                    if (activated) {
                        // Repeat OK on the open space/0 panel just closes it.
                        onDeactivated()
                    } else {
                        // The first press inputs a space; the open bubble offers the digit 0 down.
                        onCharacter(' ')
                        onActivated('0')
                    }
                }
                is SearchKey.NineGrid -> {
                    if (activated) {
                        // Second OK press on a four-letter key picks the digit; three-letter keys
                        // put the digit on Left, so a repeat press just closes the panel.
                        val digitSlot = searchSecondarySlots(key)
                            .firstOrNull { it.position == SearchKeySlotPosition.Center }
                        if (digitSlot != null) {
                            onCharacter(digitSlot.char)
                        }
                        onDeactivated()
                    } else {
                        onActivated(key.digit)
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .let { if (entryTarget) it.focusRequester(entryFocus) else it }
            .let {
                if (activated) {
                    it.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (key) {
                            is SearchKey.SpaceZero -> {
                                if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                onCharacter('0')
                                onDeactivated()
                                true
                            }
                            is SearchKey.NineGrid -> {
                                val position = when (event.key) {
                                    Key.DirectionUp -> SearchKeySlotPosition.Up
                                    Key.DirectionRight -> SearchKeySlotPosition.Right
                                    Key.DirectionDown -> SearchKeySlotPosition.Down
                                    Key.DirectionLeft -> SearchKeySlotPosition.Left
                                    else -> null
                                } ?: return@onPreviewKeyEvent false
                                val char = searchSlotForDirection(key, position) ?: return@onPreviewKeyEvent false
                                onCharacter(char)
                                onDeactivated()
                                true
                            }
                            else -> false
                        }
                    }
                } else {
                    it
                }
            }
            .semantics {
                contentDescription = when (key) {
                    SearchKey.Submit -> "搜索"
                    SearchKey.Erase -> "删除上一个字符"
                    SearchKey.Clear -> "清空输入"
                    SearchKey.Ime -> "调出系统输入法"
                    SearchKey.ClearHistory -> "清空搜索历史"
                    is SearchKey.Direct -> "输入 ${searchKeyLabel(key)}"
                    is SearchKey.SpaceZero -> if (activated) {
                        "已输入空格，按方向键下方输入数字 0"
                    } else {
                        "输入空格或数字 0"
                    }
                    is SearchKey.NineGrid -> if (activated) {
                        "已展开选择：按上下左右键选择字母，再按 OK 输入数字 ${key.digit}"
                    } else {
                        "输入 ${searchKeyLabel(key)}"
                    }
                }
            },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF1B201F),
            contentColor = if (key is SearchKey.Direct) FnColors.Text else FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (key) {
                is SearchKey.NineGrid -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(key.label.dropLast(1), fontSize = 12.sp, maxLines = 1)
                    Text(key.digit.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                else -> Text(
                    searchKeyLabel(key),
                    fontSize = if (searchKeyLabel(key).length > 2) 12.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Visual bubbles of the open secondary panel; they surround the pressed key and carry the slot chars. */
@Composable
private fun SecondarySlotBubbles(
    slots: List<SearchSecondarySlot>,
    center: Offset,
) {
    slots.forEach { slot ->
        val (dx, dy) = when (slot.position) {
            SearchKeySlotPosition.Up -> 0f to -58f
            SearchKeySlotPosition.Right -> 62f to 0f
            SearchKeySlotPosition.Down -> 0f to 58f
            SearchKeySlotPosition.Left -> -62f to 0f
            SearchKeySlotPosition.Center -> 0f to 0f
        }
        Box(
            Modifier
                .absoluteOffset {
                    IntOffset(
                        (center.x + dx.dp.toPx() - 19.dp.toPx()).roundToInt(),
                        (center.y + dy.dp.toPx() - 19.dp.toPx()).roundToInt(),
                    )
                }
                .size(38.dp)
                .background(FnColors.Surface, CircleShape)
                .border(2.dp, FnColors.Coral, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(slot.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * One committed keyword's result payload for every section; fetched once in parallel, reused
 * across tab flips until the next committed keyword replaces it.
 */
internal data class SearchSectionData<T>(
    val keyword: String = "",
    val error: AppError? = null,
    val loaded: Boolean = false,
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val sort: String = "",
)

internal class SearchResultsHolder {
    var tracks by mutableStateOf(SearchSectionData<Track>())
        private set
    var artists by mutableStateOf(SearchSectionData<Artist>())
        private set
    var albums by mutableStateOf(SearchSectionData<Album>())
        private set
    var playlists by mutableStateOf(SearchSectionData<Playlist>())
        private set

    fun isStale(keyword: String): Boolean =
        tracks.keyword != keyword ||
            !(tracks.loaded && artists.loaded && albums.loaded && playlists.loaded)

    suspend fun fetchIfStale(container: AuthenticatedAppDependencies, keyword: String) {
        if (!isStale(keyword)) return
        coroutineScope {
            val tracksJob = async { fetchSection(keyword) { container.musicRepository.searchTracks(keyword, it) } }
            val artistsJob = async { fetchSection(keyword) { container.musicRepository.searchArtists(keyword, it) } }
            val albumsJob = async { fetchSection(keyword) { container.musicRepository.searchAlbums(keyword, it) } }
            val playlistsJob = async { fetchSection(keyword) { container.musicRepository.searchPlaylists(keyword) } }
            tracks = tracksJob.await()
            artists = artistsJob.await()
            albums = albumsJob.await()
            playlists = playlistsJob.await()
        }
    }

    private suspend fun <T> fetchSection(
        keyword: String,
        loader: suspend (Int) -> Page<T>,
    ): SearchSectionData<T> =
        runCatching { loader(1) }.fold(
            onSuccess = { page ->
                SearchSectionData(
                    keyword = keyword,
                    loaded = true,
                    items = page.items,
                    total = page.total.takeIf { it > 0 },
                    sort = page.sort,
                )
            },
            onFailure = { cause ->
                if (cause is CancellationException) throw cause
                SearchSectionData(
                    keyword = keyword,
                    loaded = true,
                    error = (cause as? AppException)?.error ?: AppError.Unknown(),
                )
            },
        )
}

internal const val SEARCH_DEFAULT_SORT = "createdAt,desc"

/**
 * Play-queue window for one committed track search: one page of source rows, where playable rows
 * keep their absolute source index so the sliding queue can continue from the same keyword.
 */
internal fun searchResultQueueWindow(
    data: SearchSectionData<Track>,
    playable: (Track) -> Boolean,
): Pair<List<Track>, QueuePageSegment>? {
    if (data.items.isEmpty()) return null
    val playableTracks = data.items.filter(playable)
    if (playableTracks.isEmpty()) return null
    val segment = QueuePageSegment(
        page = 1,
        rawRowCount = data.items.size,
        playableItems = data.items
            .withIndex()
            .filter { (_, track) -> playable(track) }
            .map { (rawIndex, track) -> QueuePageItem(track.guid.value, rawIndex) },
        sort = data.sort.ifBlank { SEARCH_DEFAULT_SORT },
        knownTotal = data.total,
        pageSize = SEARCH_TRACKS_PAGE_SIZE,
        sourceStartIndex = 0,
    )
    return playableTracks to segment
}

@Composable
private fun SearchResultsPane(
    modifier: Modifier = Modifier,
    container: AuthenticatedAppDependencies,
    holder: SearchResultsHolder,
    loadedKeyword: String,
    section: SearchSection,
    onSection: (SearchSection) -> Unit,
    onRetry: () -> Unit,
    onPlayer: (Track) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    var playError by remember { mutableStateOf<AppError?>(null) }
    val recordOnActivation = {
        // Clicking any result confirms the current keyword's value: the keyword joins the
        // recent-search history when it is not already the newest entry.
        val keyword = submittedSearchKeyword(loadedKeyword)
        if (isSearchQuerySubmittable(keyword)) container.searchHistory.record(keyword)
    }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchSection.entries.forEach { candidate ->
                SearchSectionTab(
                    label = candidate.label,
                    selected = section == candidate,
                    onClick = { onSection(candidate) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val keyword = submittedSearchKeyword(loadedKeyword)
        val data: SearchSectionData<*> = when (section) {
            SearchSection.Tracks -> holder.tracks
            SearchSection.Artists -> holder.artists
            SearchSection.Albums -> holder.albums
            SearchSection.Playlists -> holder.playlists
        }
        when {
            keyword.isBlank() -> Text("输入关键词开始搜索", color = FnColors.Muted, fontSize = 16.sp)
            data.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineError(data.error)
                SearchResultRetryButton { onRetry() }
            }
            !data.loaded -> Text("正在搜索", color = FnColors.Muted, fontSize = 16.sp)
            data.items.isEmpty() -> Text("没有找到相关结果", color = FnColors.Muted, fontSize = 17.sp)
            else -> when (section) {
                SearchSection.Tracks -> SearchTrackResults(
                    container = container,
                    data = holder.tracks,
                    playError = playError,
                    onPlayError = { playError = it },
                    onPlayer = onPlayer,
                    onActivated = recordOnActivation,
                )
                SearchSection.Artists -> SearchEntryResults(
                    items = holder.artists.items,
                    columns = 4,
                ) { artist, modifier ->
                    ArtistLockup(artist.name, "${artist.trackCount ?: 0} 首歌曲", artist.coverId, modifier) {
                        recordOnActivation()
                        onOpenArtist(artist)
                    }
                }
                SearchSection.Albums -> SearchEntryResults(
                    items = holder.albums.items,
                    columns = 4,
                ) { album, modifier ->
                    AlbumLockup(album.name, album.artistName.orEmpty(), album.coverId, modifier) {
                        recordOnActivation()
                        onOpenAlbum(album)
                    }
                }
                SearchSection.Playlists -> SearchEntryResults(
                    items = holder.playlists.items,
                    columns = 5,
                ) { playlist, modifier ->
                    PlaylistTile(
                        title = playlist.name,
                        subtitle = "歌单",
                        coverId = playlist.coverId,
                        accent = FnColors.Coral,
                        modifier = modifier,
                        onClick = {
                            recordOnActivation()
                            onOpenPlaylist(playlist)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "$label 搜索结果" },
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) FnColors.Coral else Color(0xFF171B1D),
            contentColor = if (selected) FnColors.Background else FnColors.Muted,
            focusedContainerColor = if (selected) FnColors.Coral else FnColors.FocusFill,
            focusedContentColor = if (selected) FnColors.Background else FnColors.Text,
            pressedContainerColor = if (selected) FnColors.Coral else FnColors.FocusFill,
            pressedContentColor = if (selected) FnColors.Background else FnColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun <T> SearchEntryResults(
    items: List<T>,
    columns: Int,
    item: @Composable (T, Modifier) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // The first row keeps free space above it: the focused card scales slightly beyond its
        // bounds and its border must not be clipped by the grid viewport edge.
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        items(items) { entry ->
            item(entry, Modifier)
        }
    }
}

@Composable
private fun SearchTrackResults(
    container: AuthenticatedAppDependencies,
    data: SearchSectionData<Track>,
    playError: AppError?,
    onPlayError: (AppError?) -> Unit,
    onPlayer: (Track) -> Unit,
    onActivated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        playError?.let { InlineError(it) }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(data.items, key = { _, track -> track.guid.value }) { index, track ->
                DetailTrackRow(
                    index = index,
                    track = track,
                    enabled = isTrackPlayable(track),
                    onClick = {
                        onActivated()
                        scope.launch {
                            playSearchTrack(container, data, index, track, onPlayError, onPlayer)
                        }
                    },
                )
            }
        }
    }
}

private suspend fun playSearchTrack(
    container: AuthenticatedAppDependencies,
    data: SearchSectionData<Track>,
    index: Int,
    target: Track,
    onPlayError: (AppError?) -> Unit,
    onPlayer: (Track) -> Unit,
) {
    if (!isTrackPlayable(target)) return
    val window = searchResultQueueWindow(data, ::isTrackPlayable) ?: run {
        onPlayError(AppError.CollectionChanged)
        return
    }
    val (playableTracks, segment) = window
    val targetQueueIndex = playableTracks.indexOfFirst { it.guid == target.guid }
    val queue = container.musicRepository.prepareQueue(playableTracks)
    if (targetQueueIndex < 0 || queue.isEmpty()) {
        onPlayError(AppError.TranscodeUnavailable)
        return
    }
    val transition = runCatching {
        container.playbackController.playQueue(
            tracks = queue,
            startIndex = targetQueueIndex,
            source = QueueSource.SearchTracks(data.keyword, segment.sort),
            firstPage = 1,
            lastPage = 1,
            knownTotal = segment.knownTotal,
            segments = listOf(segment),
        )
    }.getOrElse {
        onPlayError(AppError.Unknown(it.message))
        return
    }
    if (transition == null) {
        onPlayError(AppError.TranscodeUnavailable)
        return
    }
    runCatching { transition.awaitCommitted() }
        .onSuccess {
            onPlayError(null)
            onPlayer(target)
        }
        .onFailure { onPlayError(AppError.Unknown(it.message)) }
}

@Composable
private fun SearchResultRetryButton(onRetry: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Button(
        onClick = onRetry,
        modifier = Modifier.height(38.dp),
        shape = ButtonDefaults.shape(shape, shape, shape, shape, shape),
        colors = ButtonDefaults.colors(
            containerColor = FnColors.Surface,
            contentColor = FnColors.Text,
            focusedContainerColor = FnColors.FocusFill,
            focusedContentColor = FnColors.Text,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    ) {
        Text("重试", fontSize = 13.sp)
    }
}

