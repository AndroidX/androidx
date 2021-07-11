/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.paging

import androidx.paging.ContiguousPagedListTest.Companion.EXCEPTION
import androidx.paging.LoadState.Error
import androidx.paging.LoadState.Loading
import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.LoadType.REFRESH
import androidx.paging.PageEvent.Drop
import androidx.paging.PageEvent.Insert.Companion.Append
import androidx.paging.PageEvent.Insert.Companion.Prepend
import androidx.paging.PageEvent.Insert.Companion.Refresh
import androidx.paging.PageEvent.LoadStateUpdate
import androidx.paging.PagingSource.LoadResult.Page
import androidx.paging.RemoteMediatorMock.LoadEvent
import androidx.paging.TestPagingSource.Companion.LOAD_ERROR
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PageFetcherSnapshotTest {
    private val testScope = TestCoroutineScope()
    private val retryBus = ConflatedEventBus<Unit>()
    private val pagingSourceFactory = suspend {
        TestPagingSource(loadDelay = 1000).also {
            currentPagingSource = it
        }
    }

    private var currentPagingSource: TestPagingSource? = null
    private val config = PagingConfig(
        pageSize = 1,
        prefetchDistance = 1,
        enablePlaceholders = true,
        initialLoadSize = 2,
        maxSize = 3
    )

    @Test
    fun loadStates_prependDone() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 1, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(1..2)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(
                pageOffset = -1,
                range = 0..0,
                startState = NotLoading.Complete
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun loadStates_prependDoneThenDrop() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 1, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(1..2)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(
                pageOffset = -1,
                range = 0..0,
                startState = NotLoading.Complete
            )
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 2,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = -1,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = NotLoading.Complete,
                    appendLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = PREPEND,
                minPageOffset = -1,
                maxPageOffset = -1,
                placeholdersRemaining = 1
            ),
            createAppend(
                pageOffset = 1,
                range = 3..3,
                startState = NotLoading.Incomplete,
                endState = NotLoading.Incomplete
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun loadStates_appendDone() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 97, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 97..98)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(pageOffset = 1, range = 99..99, endState = NotLoading.Complete)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun loadStates_appendDoneThenDrop() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 97, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 97..98)

        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(
                pageOffset = 1,
                range = 99..99,
                startState = NotLoading.Incomplete,
                endState = NotLoading.Complete
            )
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 1
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = NotLoading.Complete,
                    prependLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = APPEND,
                minPageOffset = 1,
                maxPageOffset = 1,
                placeholdersRemaining = 1
            ),
            createPrepend(
                pageOffset = -1,
                range = 96..96,
                startState = NotLoading.Incomplete,
                endState = NotLoading.Incomplete
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun loadStates_refreshStart() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 0, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertThat(fetcherState.pageEventLists[0]).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(
                range = 0..1,
                startState = NotLoading.Complete,
                endState = NotLoading.Incomplete
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun loadStates_refreshEnd() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 98, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertThat(fetcherState.pageEventLists[0]).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(
                range = 98..99,
                startState = NotLoading.Incomplete,
                endState = NotLoading.Complete
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun initialize() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertThat(fetcherState.pageEventLists[0]).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun initialize_bufferedHint() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()

        assertThat(fetcherState.pageEventLists[0]).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51),
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(pageOffset = -1, range = 49..49)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun prepend() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(pageOffset = -1, range = 49..49)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun prependAndDrop() = testScope.runBlockingTest {
        pauseDispatcher {
            val config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 4
            )
            val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            // Make sure the job didn't complete exceptionally
            assertFalse { fetcherState.job.isCancelled }
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..51)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertFalse { fetcherState.job.isCancelled }
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                createPrepend(pageOffset = -1, range = 48..49)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = -1,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 3,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertFalse { fetcherState.job.isCancelled }
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                Drop<Int>(
                    loadType = APPEND,
                    minPageOffset = 0,
                    maxPageOffset = 0,
                    placeholdersRemaining = 50
                ),
                createPrepend(pageOffset = -2, range = 46..47)
            )

            fetcherState.job.cancel()
        }
    }

    @Test
    fun prependAndSkipDrop_prefetchWindow() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageFetcher = PageFetcher(
                pagingSourceFactory = pagingSourceFactory,
                initialKey = 50,
                config = PagingConfig(
                    pageSize = 1,
                    prefetchDistance = 2,
                    enablePlaceholders = true,
                    initialLoadSize = 5,
                    maxSize = 5
                )
            )
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..54)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 4,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                createPrepend(
                    pageOffset = -1,
                    range = 49..49,
                    startState = Loading
                ),
                createPrepend(pageOffset = -2, range = 48..48)
            )

            // Make sure the job didn't complete exceptionally
            assertFalse { fetcherState.job.isCancelled }

            fetcherState.job.cancel()
        }
    }

    @Test
    fun prependAndDropWithCancellation() = testScope.runBlockingTest {
        pauseDispatcher {
            val config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 4
            )
            val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..51)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                createPrepend(pageOffset = -1, range = 48..49)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = -1,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 3,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            // Start hint processing until load starts, but hasn't finished.
            advanceTimeBy(500)
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 1,
                    presentedItemsBefore = 3,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading,
                        appendLocal = Loading
                    )
                ),
                Drop<Int>(
                    loadType = APPEND,
                    minPageOffset = 0,
                    maxPageOffset = 0,
                    placeholdersRemaining = 50
                ),
                createPrepend(pageOffset = -2, range = 46..47)
            )

            fetcherState.job.cancel()
        }
    }

    @Test
    fun prependMultiplePages() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..52)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(pageOffset = -1, range = 49..49, startState = Loading),
            createPrepend(pageOffset = -2, range = 48..48)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun prepend_viewportHintPrioritizesGenerationId() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..52)
        )

        // PREPEND a few pages.
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(pageOffset = -1, range = 49..49, startState = Loading),
            createPrepend(pageOffset = -2, range = 48..48)
        )

        // APPEND a few pages causing PREPEND pages to drop
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 2,
                presentedItemsBefore = 4,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = -2,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = PREPEND,
                minPageOffset = -2,
                maxPageOffset = -2,
                placeholdersRemaining = 49
            ),
            createAppend(pageOffset = 1, range = 53..53, endState = Loading),
            Drop<Int>(
                loadType = PREPEND,
                minPageOffset = -1,
                maxPageOffset = -1,
                placeholdersRemaining = 50
            ),
            createAppend(pageOffset = 2, range = 54..54)
        )

        // PREPEND a page, this hint would normally be ignored, but has a newer generationId.
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 3,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 2
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = APPEND,
                minPageOffset = 2,
                maxPageOffset = 2,
                placeholdersRemaining = 46
            ),
            createPrepend(pageOffset = -1, range = 49..49)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun append() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(1, 52..52)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun appendMultiplePages() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..52)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 2,
                presentedItemsBefore = 2,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(
                pageOffset = 1,
                range = 53..53,
                startState = NotLoading.Incomplete,
                endState = Loading
            ),
            createAppend(2, 54..54)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun appendAndDrop() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 2,
            prefetchDistance = 1,
            enablePlaceholders = true,
            initialLoadSize = 2,
            maxSize = 4
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(pageOffset = 1, range = 52..53)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 1,
                indexInPage = 1,
                presentedItemsBefore = 3,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 1
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = PREPEND,
                minPageOffset = 0,
                maxPageOffset = 0,
                placeholdersRemaining = 52
            ),
            createAppend(pageOffset = 2, range = 54..55)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun appendAndSkipDrop_prefetchWindow() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageFetcher = PageFetcher(
                pagingSourceFactory = pagingSourceFactory,
                initialKey = 50,
                config = PagingConfig(
                    pageSize = 1,
                    prefetchDistance = 2,
                    enablePlaceholders = true,
                    initialLoadSize = 5,
                    maxSize = 5
                )
            )
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..54)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 4,
                    presentedItemsBefore = 4,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        appendLocal = Loading
                    )
                ),
                createAppend(
                    pageOffset = 1,
                    range = 55..55,
                    endState = Loading
                ),
                createAppend(pageOffset = 2, range = 56..56)
            )

            fetcherState.job.cancel()
        }
    }

    @Test
    fun appendAndDropWithCancellation() = testScope.runBlockingTest {
        pauseDispatcher {
            val config = PagingConfig(
                pageSize = 2,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 4
            )
            val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
            val fetcherState = collectFetcherState(pageFetcher)

            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..51)
            )

            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 1,
                    presentedItemsBefore = 1,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        appendLocal = Loading
                    )
                ),
                createAppend(pageOffset = 1, range = 52..53)
            )

            // Start hint processing until load starts, but hasn't finished.
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 1,
                    indexInPage = 1,
                    presentedItemsBefore = 3,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 1
                )
            )
            advanceTimeBy(500)
            fetcherState.pagingDataList[0].receiver.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 3,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 1
                )
            )
            advanceUntilIdle()
            assertThat(fetcherState.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        appendLocal = Loading
                    )
                ),
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        appendLocal = Loading,
                        prependLocal = Loading
                    )
                ),
                Drop<Int>(
                    loadType = PREPEND,
                    minPageOffset = 0,
                    maxPageOffset = 0,
                    placeholdersRemaining = 52
                ),
                createAppend(
                    pageOffset = 2,
                    range = 54..55,
                    startState = NotLoading.Incomplete,
                    endState = NotLoading.Incomplete
                )
            )

            fetcherState.job.cancel()
        }
    }

    @Test
    fun append_viewportHintPrioritizesGenerationId() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..52)
        )

        // APPEND a few pages.
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 2,
                presentedItemsBefore = 2,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(pageOffset = 1, range = 53..53, endState = Loading),
            createAppend(pageOffset = 2, range = 54..54)
        )

        // PREPEND a few pages causing APPEND pages to drop
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 4,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 2
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = APPEND,
                minPageOffset = 2,
                maxPageOffset = 2,
                placeholdersRemaining = 46
            ),
            createPrepend(pageOffset = -1, range = 49..49, startState = Loading),
            Drop<Int>(
                loadType = APPEND,
                minPageOffset = 1,
                maxPageOffset = 1,
                placeholdersRemaining = 47
            ),
            createPrepend(pageOffset = -2, range = 48..48)
        )

        // APPEND a page, this hint would normally be ignored, but has a newer generationId.
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 3,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = -2,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            Drop<Int>(
                loadType = PREPEND,
                minPageOffset = -2,
                maxPageOffset = -2,
                placeholdersRemaining = 49
            ),
            createAppend(pageOffset = 1, range = 53..53)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun invalidateNoScroll() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        pageFetcher.refresh()
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun invalidateAfterScroll() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(1, 52..52)
        )

        pageFetcher.refresh()
        advanceUntilIdle()

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(51..52)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun close_cancelsCollectionBeforeInitialLoad() = testScope.runBlockingTest {
        // Infinitely suspending PagingSource which never finishes loading anything.
        val pagingSource = object : PagingSource<Int, Int>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                delay(2000)
                fail("Should never get here")
            }

            override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
        }
        val pager = PageFetcherSnapshot(50, pagingSource, config, retryFlow = retryBus.flow)

        collectSnapshotData(pager) { _, job ->

            // Start the initial load, but do not let it finish.
            advanceTimeBy(500)

            // Close pager, then advance time by enough to allow initial load to finish.
            pager.close()
            advanceTimeBy(1500)

            assertTrue { !job.isActive }
        }
    }

    @Test
    fun retry() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { state, _ ->
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(range = 50..51)
                )

                pageSource.errorNextLoad = true
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR)
                        )
                    ),
                )

                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    createAppend(pageOffset = 1, range = 52..52)
                )
            }
        }
    }

    @Test
    fun retryNothing() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { state, _ ->

                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(range = 50..51)
                )

                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    createAppend(pageOffset = 1, range = 52..52)
                )
                retryBus.send(Unit)
                advanceUntilIdle()
                assertTrue { state.newEvents().isEmpty() }
            }
        }
    }

    @Test
    fun retryTwice() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { state, _ ->

                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(range = 50..51)
                )
                pageSource.errorNextLoad = true
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR)
                        )
                    )
                )
                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    createAppend(pageOffset = 1, range = 52..52)
                )
                retryBus.send(Unit)
                advanceUntilIdle()
                assertTrue { state.newEvents().isEmpty() }
            }
        }
    }

    @Test
    fun retryBothDirections() = testScope.runBlockingTest {
        pauseDispatcher {
            val config = PagingConfig(
                pageSize = 1,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 4
            )
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { state, _ ->
                // Initial REFRESH
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(range = 50..51)
                )

                // Failed APPEND
                pageSource.errorNextLoad = true
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR)
                        )
                    )
                )

                // Failed PREPEND
                pageSource.errorNextLoad = true
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 0,
                        presentedItemsBefore = 0,
                        presentedItemsAfter = 1,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            prependLocal = Loading,
                            appendLocal = Error(LOAD_ERROR)
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            prependLocal = Error(LOAD_ERROR),
                            appendLocal = Error(LOAD_ERROR)
                        )
                    )
                )

                // Retry should trigger in both directions.
                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            prependLocal = Loading,
                            appendLocal = Error(LOAD_ERROR)
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            prependLocal = Loading,
                            appendLocal = Loading
                        )
                    ),
                    createPrepend(
                        pageOffset = -1,
                        range = 49..49,
                        startState = NotLoading.Incomplete,
                        endState = Loading
                    ),
                    createAppend(pageOffset = 1, range = 52..52)
                )
            }
        }
    }

    @Test
    fun retry_errorDoesNotEnableHints() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = object : PagingSource<Int, Int>() {
                var nextResult: LoadResult<Int, Int>? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
                    val result = nextResult
                    nextResult = null
                    return result ?: LoadResult.Error(LOAD_ERROR)
                }

                override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
            }
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { pageEvents, _ ->
                // Successful REFRESH
                pageSource.nextResult = Page(
                    data = listOf(0, 1),
                    prevKey = -1,
                    nextKey = 1,
                    itemsBefore = 50,
                    itemsAfter = 48
                )
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    Refresh(
                        pages = listOf(TransformablePage(listOf(0, 1))),
                        placeholdersBefore = 50,
                        placeholdersAfter = 48,
                        combinedLoadStates = CombinedLoadStates.IDLE_SOURCE
                    )
                )

                // Hint to trigger APPEND
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading,
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR)
                        )
                    ),
                )

                // Retry failed APPEND
                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR)
                        )
                    ),
                )

                // This hint should be ignored even though in the non-error state it would
                // re-emit for APPEND due to greater presenterIndex value.
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 2,
                        presentedItemsBefore = 2,
                        presentedItemsAfter = -1,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).isEmpty()

                // Hint to trigger PREPEND
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 0,
                        presentedItemsBefore = 0,
                        presentedItemsAfter = 1,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR),
                            prependLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR),
                            prependLocal = Error(LOAD_ERROR)
                        )
                    )
                )

                // Retry failed hints, both PREPEND and APPEND should trigger.
                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR),
                            prependLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading,
                            prependLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Loading,
                            prependLocal = Error(LOAD_ERROR)
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            appendLocal = Error(LOAD_ERROR),
                            prependLocal = Error(LOAD_ERROR)
                        )
                    )
                )

                // This hint should be ignored even though in the non-error state it would
                // re-emit for PREPEND due to smaller presenterIndex value.
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = -1,
                        presentedItemsBefore = 0,
                        presentedItemsAfter = 2,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertThat(pageEvents.newEvents()).isEmpty()
            }
        }
    }

    @Test
    fun retryRefresh() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { state, _ ->

                pageSource.errorNextLoad = true
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Error(LOAD_ERROR)
                        )
                    )
                )

                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(50..51)
                )
            }
        }
    }

    @Test
    fun retryRefreshWithBufferedHint() = testScope.runBlockingTest {
        pauseDispatcher {
            val pageSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pageSource, config, retryFlow = retryBus.flow)
            collectSnapshotData(pager) { state, _ ->
                pageSource.errorNextLoad = true
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Error(LOAD_ERROR)
                        )
                    )
                )
                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 0,
                        presentedItemsBefore = 0,
                        presentedItemsAfter = 1,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )
                advanceUntilIdle()
                assertTrue { state.newEvents().isEmpty() }

                retryBus.send(Unit)
                advanceUntilIdle()
                assertThat(state.newEvents()).containsExactly(
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    createRefresh(range = 50..51),
                    LoadStateUpdate<Int>(
                        localLoadStatesOf(
                            prependLocal = Loading
                        )
                    ),
                    createPrepend(pageOffset = -1, range = 49..49)
                )
            }
        }
    }

    @Test
    fun retry_remotePrepend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)

                return if (loadType == PREPEND) {
                    MediatorResult.Error(EXCEPTION)
                } else {
                    MediatorResult.Success(endOfPaginationReached = true)
                }
            }
        }

        var createdPagingSource = false
        val factory = suspend {
            check(!createdPagingSource)
            createdPagingSource = true
            TestPagingSource(items = List(2) { it })
        }
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = factory,
            config = config,
            remoteMediator = remoteMediator
        )

        pager.collectEvents {
            awaitIdle()
            retry()
            awaitIdle()
            retry()
            awaitIdle()
            assertThat(
                remoteMediator.loadEventCounts()
            ).containsExactlyEntriesIn(
                mapOf(
                    PREPEND to 3,
                    APPEND to 1,
                    REFRESH to 0
                )
            )
        }
    }

    @Test
    fun retry_remoteAppend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)

                return if (loadType == APPEND) {
                    MediatorResult.Error(EXCEPTION)
                } else {
                    MediatorResult.Success(endOfPaginationReached = true)
                }
            }
        }

        var createdPagingSource = false
        val factory = suspend {
            check(!createdPagingSource)
            createdPagingSource = true
            TestPagingSource(items = List(2) { it })
        }
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = factory,
            config = config,
            remoteMediator = remoteMediator
        )

        pager.collectEvents {
            // Resolve initial load.
            awaitIdle()
            retry()
            awaitIdle()
            retry()
            awaitIdle()
            assertThat(
                remoteMediator.loadEventCounts()
            ).containsExactlyEntriesIn(
                mapOf(
                    PREPEND to 1,
                    APPEND to 3,
                    REFRESH to 0
                )
            )
        }
    }

    @Test
    fun disablePlaceholders_refresh() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = false,
            initialLoadSize = 2,
            maxSize = 3
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()

        assertThat(fetcherState.pageEventLists[0]).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51).let { Refresh(it.pages, 0, 0, it.combinedLoadStates) }
        )

        fetcherState.job.cancel()
    }

    @Test
    fun disablePlaceholders_prepend() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = false,
            initialLoadSize = 2,
            maxSize = 3
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51).let { Refresh(it.pages, 0, 0, it.combinedLoadStates) }
        )
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(-1, 49..49).let { Prepend(it.pages, 0, it.combinedLoadStates) }
        )

        fetcherState.job.cancel()
    }

    @Test
    fun disablePlaceholders_append() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = false,
            initialLoadSize = 2,
            maxSize = 3
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..51).let { Refresh(it.pages, 0, 0, it.combinedLoadStates) }
        )

        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 1,
                presentedItemsBefore = 1,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(1, 52..52).let { Append(it.pages, 0, it.combinedLoadStates) }
        )

        fetcherState.job.cancel()
    }

    @Test
    fun neverDropBelowTwoPages() = testScope.runBlockingTest {
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 1,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 3
        )
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(range = 50..52)
        )
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 2,
                presentedItemsBefore = 2,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(pageOffset = 1, range = 53..53)
        )

        fetcherState.job.cancel()
    }

    @Test
    fun currentPagingState_pagesEmptyWithHint() = testScope.runBlockingTest {
        pauseDispatcher {
            val pagingSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pagingSource, config, retryFlow = retryBus.flow)
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 1,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            assertThat(pager.currentPagingState()).isEqualTo(
                PagingState<Int, Int>(
                    pages = listOf(),
                    anchorPosition = 0,
                    config = config,
                    leadingPlaceholderCount = 0
                )
            )
        }
    }

    /**
     * Verify we re-use previous PagingState for remote refresh if there are no pages loaded.
     */
    @Test
    fun currentPagingState_ignoredOnEmptyPages() = testScope.runBlockingTest {
        val remoteMediator = RemoteMediatorMock()
        val pagingSource = pagingSourceFactory()
        val pager = PageFetcherSnapshot(
            initialKey = 50,
            pagingSource = pagingSource,
            config = config,
            retryFlow = retryBus.flow,
            remoteMediatorConnection = RemoteMediatorAccessor(testScope, remoteMediator)
        )
        pager.accessHint(
            ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 1,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        assertThat(pager.currentPagingState()).isEqualTo(
            PagingState<Int, Int>(
                pages = listOf(),
                anchorPosition = 0,
                config = config,
                leadingPlaceholderCount = 0
            )
        )
    }

    @Test
    fun currentPagingState_loadedIndex() = testScope.runBlockingTest {
        pauseDispatcher {
            val pagingSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pagingSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { _, _ ->
                advanceUntilIdle()

                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = 1,
                        presentedItemsBefore = 1,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )

                val pagingState = pager.currentPagingState()
                assertNotNull(pagingState)
                assertEquals(51, pagingState.anchorPosition)

                // Assert from anchorPosition in placeholdersBefore
                assertEquals(50, pagingState.closestItemToPosition(10))
                // Assert from anchorPosition in loaded indices
                assertEquals(50, pagingState.closestItemToPosition(50))
                assertEquals(51, pagingState.closestItemToPosition(51))
                // Assert from anchorPosition in placeholdersAfter
                assertEquals(51, pagingState.closestItemToPosition(90))

                val loadedPage = Page(
                    data = listOf(50, 51),
                    prevKey = 49,
                    nextKey = 52,
                    itemsBefore = 50,
                    itemsAfter = 48
                )
                assertEquals(listOf(loadedPage), pagingState.pages)
                // Assert from anchorPosition in placeholdersBefore
                assertEquals(loadedPage, pagingState.closestPageToPosition(10))
                // Assert from anchorPosition in loaded indices
                assertEquals(loadedPage, pagingState.closestPageToPosition(50))
                assertEquals(loadedPage, pagingState.closestPageToPosition(51))
                // Assert from anchorPosition in placeholdersAfter
                assertEquals(loadedPage, pagingState.closestPageToPosition(90))
            }
        }
    }

    @Test
    fun currentPagingState_placeholdersBefore() = testScope.runBlockingTest {
        pauseDispatcher {
            val pagingSource = pagingSourceFactory()
            val pager = PageFetcherSnapshot(50, pagingSource, config, retryFlow = retryBus.flow)

            collectSnapshotData(pager) { _, _ ->
                advanceUntilIdle()

                pager.accessHint(
                    ViewportHint.Access(
                        pageOffset = 0,
                        indexInPage = -40,
                        presentedItemsBefore = -40,
                        presentedItemsAfter = 0,
                        originalPageOffsetFirst = 0,
                        originalPageOffsetLast = 0
                    )
                )

                val pagingState = pager.currentPagingState()
                assertNotNull(pagingState)
                assertEquals(10, pagingState.anchorPosition)
                assertEquals(
                    listOf(
                        Page(
                            data = listOf(50, 51),
                            prevKey = 49,
                            nextKey = 52,
                            itemsBefore = 50,
                            itemsAfter = 48
                        )
                    ),
                    pagingState.pages
                )

                // Assert from anchorPosition in placeholdersBefore
                assertEquals(50, pagingState.closestItemToPosition(10))
                // Assert from anchorPosition in loaded indices
                assertEquals(50, pagingState.closestItemToPosition(50))
                assertEquals(51, pagingState.closestItemToPosition(51))
                // Assert from anchorPosition in placeholdersAfter
                assertEquals(51, pagingState.closestItemToPosition(90))

                val loadedPage = Page(
                    data = listOf(50, 51),
                    prevKey = 49,
                    nextKey = 52,
                    itemsBefore = 50,
                    itemsAfter = 48
                )
                // Assert from anchorPosition in placeholdersBefore
                assertEquals(loadedPage, pagingState.closestPageToPosition(10))
                // Assert from anchorPosition in loaded indices
                assertEquals(loadedPage, pagingState.closestPageToPosition(50))
                assertEquals(loadedPage, pagingState.closestPageToPosition(51))
                // Assert from anchorPosition in placeholdersAfter
                assertEquals(loadedPage, pagingState.closestPageToPosition(90))
            }
        }
    }

    @Test
    fun currentPagingState_noHint() = testScope.runBlockingTest {
        val pager = PageFetcherSnapshot(
            initialKey = 50,
            pagingSource = TestPagingSource(loadDelay = 100),
            config = config,
            retryFlow = retryBus.flow
        )

        assertThat(pager.currentPagingState()).isEqualTo(
            PagingState<Int, Int>(
                pages = listOf(),
                anchorPosition = null,
                config = config,
                leadingPlaceholderCount = 0,
            )
        )
    }

    @Test
    fun retry_ignoresNewSignalsWhileProcessing() = testScope.runBlockingTest {
        val pagingSource = pagingSourceFactory()
        val pager = PageFetcherSnapshot(50, pagingSource, config, retryFlow = retryBus.flow)
        collectSnapshotData(pager) { state, _ ->
            pagingSource.errorNextLoad = true
            advanceUntilIdle()
            assertThat(state.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Error(LOAD_ERROR)
                    )
                )
            )

            pagingSource.errorNextLoad = true
            retryBus.send(Unit)
            // Should be ignored by pager as it's still processing previous retry.
            retryBus.send(Unit)
            advanceUntilIdle()
            assertThat(state.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Error(LOAD_ERROR)
                    )
                )
            )
        }
    }

    /**
     * The case where all pages from presenter have been dropped in fetcher, so instead of
     * counting dropped pages against prefetchDistance, we should clamp that logic to only count
     * pages that have been loaded.
     */
    @Test
    fun doLoad_prependPresenterPagesDropped() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        // Send a hint from a presenter state that only sees pages well after the pages loaded in
        // fetcher state:
        // [hint], [50, 51], [52], [53], [54], [55]
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = 4,
                indexInPage = -6,
                presentedItemsBefore = -6,
                presentedItemsAfter = 2,
                originalPageOffsetFirst = 4,
                originalPageOffsetLast = 6
            )
        )
        advanceUntilIdle()

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    prependLocal = Loading
                )
            ),
            createPrepend(pageOffset = -1, range = 49..49, startState = Loading),
            createPrepend(pageOffset = -2, range = 48..48, startState = NotLoading.Incomplete),
        )

        fetcherState.job.cancel()
    }

    /**
     * The case where all pages from presenter have been dropped in fetcher, so instead of
     * counting dropped pages against prefetchDistance, we should clamp that logic to only count
     * pages that have been loaded.
     */
    @Test
    fun doLoad_appendPresenterPagesDropped() = testScope.runBlockingTest {
        val pageFetcher = PageFetcher(pagingSourceFactory, 50, config)
        val fetcherState = collectFetcherState(pageFetcher)

        advanceUntilIdle()
        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    refreshLocal = Loading
                )
            ),
            createRefresh(50..51)
        )

        // Send a hint from a presenter state that only sees pages well before the pages loaded in
        // fetcher state:
        // [46], [47], [48], [49], [50, 51], [hint]
        fetcherState.pagingDataList[0].receiver.accessHint(
            ViewportHint.Access(
                pageOffset = -4,
                indexInPage = 6,
                presentedItemsBefore = 2,
                presentedItemsAfter = -6,
                originalPageOffsetFirst = -6,
                originalPageOffsetLast = -4
            )
        )
        advanceUntilIdle()

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                localLoadStatesOf(
                    appendLocal = Loading
                )
            ),
            createAppend(pageOffset = 1, range = 52..52, endState = Loading),
            createAppend(pageOffset = 2, range = 53..53, endState = NotLoading.Incomplete),
        )

        fetcherState.job.cancel()
    }

    @Test
    fun remoteMediator_initialLoadErrorTriggersLocal() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun initialize(): InitializeAction {
                return InitializeAction.LAUNCH_INITIAL_REFRESH
            }

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                return MediatorResult.Error(EXCEPTION)
            }
        }

        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = pagingSourceFactory,
            config = PagingConfig(1),
            remoteMediator = remoteMediator
        )

        val expected = listOf(
            listOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshLocal = Loading,
                        refreshRemote = Loading
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Error(EXCEPTION),
                        refreshLocal = Loading,
                        refreshRemote = Error(EXCEPTION)
                    )
                ),
                createRefresh(
                    range = 0..2,
                    remoteLoadStatesOf(
                        refresh = Error(EXCEPTION),
                        prependLocal = NotLoading.Complete,
                        refreshRemote = Error(EXCEPTION),
                    ),
                ),
                // since remote refresh failed and launch initial refresh is requested,
                // we won't receive any append/prepend events
            )
        )

        pager.assertEventByGeneration(expected)
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun remoteMediator_initialLoadTriggersPrepend() = testScope.runBlockingTest {
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                currentPagingSource!!.invalidate()
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )

        pager.pageEvents().take(4).toList()
        assertEquals(1, remoteMediator.loadEvents.size)
        assertEquals(PREPEND, remoteMediator.loadEvents[0].loadType)
        assertNotNull(remoteMediator.loadEvents[0].state)
    }

    @OptIn(ExperimentalPagingApi::class)
    @Test
    fun remoteMediator_initialLoadTriggersAppend() = testScope.runBlockingTest {
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                currentPagingSource!!.invalidate()
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }
        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val fetcher = PageFetcher(
            initialKey = 99,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )
        // taking 4 events:
        // local load, local insert, append state change to loading, local load w/ new append result
        // 4th one is necessary as the Loading state change is done optimistically before the
        // remote mediator is invoked
        fetcher.pageEvents().take(4).toList()
        assertEquals(1, remoteMediator.loadEvents.size)
        assertEquals(APPEND, remoteMediator.loadEvents[0].loadType)
        assertNotNull(remoteMediator.loadEvents[0].state)
    }

    @Test
    fun remoteMediator_remoteRefreshCachesPreviousPagingState() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ -> RemoteMediator.MediatorResult.Success(true) }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = { TestPagingSource(items = listOf(0)) },
            config = config,
            remoteMediator = remoteMediator
        )

        val state = collectFetcherState(pager)

        // Let the initial page load; loaded data should be [0]
        advanceUntilIdle()
        assertThat(remoteMediator.newLoadEvents).containsExactly(
            LoadEvent<Int, Int>(
                loadType = REFRESH,
                state = PagingState<Int, Int>(
                    pages = listOf(),
                    anchorPosition = null,
                    config = config,
                    leadingPlaceholderCount = 0,
                ),
            )
        )

        // Explicit call to refresh, which should trigger remote refresh with cached PagingState.
        pager.refresh()
        advanceUntilIdle()

        assertThat(remoteMediator.newLoadEvents).containsExactly(
            LoadEvent<Int, Int>(
                loadType = REFRESH,
                state = PagingState<Int, Int>(
                    pages = listOf(
                        Page(
                            data = listOf(0),
                            prevKey = null,
                            nextKey = null,
                            itemsBefore = 0,
                            itemsAfter = 0,
                        ),
                    ),
                    anchorPosition = null,
                    config = config,
                    leadingPlaceholderCount = 0,
                ),
            )
        )

        state.job.cancel()
    }

    @Test
    fun remoteMediator_remoteRefreshEndOfPaginationReached() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = RemoteMediatorMock().apply {
            initializeResult = RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH
            loadCallback = { _, _ -> RemoteMediator.MediatorResult.Success(true) }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = { TestPagingSource(items = listOf(0)) },
            config = config,
            remoteMediator = remoteMediator
        )

        val state = collectFetcherState(pager)

        advanceUntilIdle()

        assertThat(state.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshRemote = Loading
                )
            ),
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshLocal = Loading,
                    refreshRemote = Loading
                )
            ),
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    refresh = Loading,
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete,
                    refreshLocal = Loading,
                    refreshRemote = NotLoading.Complete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete
                )
            ),
            Refresh(
                pages = listOf(
                    TransformablePage(
                        originalPageOffsets = intArrayOf(0),
                        data = listOf(0),
                        hintOriginalPageOffset = 0,
                        hintOriginalIndices = null
                    )
                ),
                placeholdersBefore = 0,
                placeholdersAfter = 0,
                combinedLoadStates = remoteLoadStatesOf(
                    refresh = NotLoading.Complete,
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete,
                    refreshLocal = NotLoading.Incomplete,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    refreshRemote = NotLoading.Complete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                )
            )
        )

        state.job.cancel()
    }

    @Test
    fun remoteMediator_endOfPaginationNotReachedLoadStatePrepend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                currentPagingSource!!.invalidate()
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )

        val expected: List<List<PageEvent<Int>>> = listOf(
            listOf(
                LoadStateUpdate(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf(0)
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 99,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = Loading,
                        prependLocal = NotLoading.Complete,
                        prependRemote = Loading,
                    )
                )
            ),
            listOf(
                LoadStateUpdate(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf(0)
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 99,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete
                    )
                )
            )

        )

        pager.assertEventByGeneration(expected)
    }

    @Test
    fun remoteMediator_endOfPaginationReachedLoadStatePrepend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val fetcher = PageFetcher(
            initialKey = 0,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )

        fetcher.assertEventByGeneration(
            listOf(
                listOf(
                    LoadStateUpdate(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    Refresh(
                        pages = listOf(
                            TransformablePage(
                                originalPageOffset = 0,
                                data = listOf(0)
                            )
                        ),
                        placeholdersBefore = 0,
                        placeholdersAfter = 99,
                        combinedLoadStates = remoteLoadStatesOf(
                            prependLocal = NotLoading.Complete,
                        )
                    ),
                    LoadStateUpdate(
                        remoteLoadStatesOf(
                            prepend = Loading,
                            prependLocal = NotLoading.Complete,
                            prependRemote = Loading
                        )
                    ),
                    LoadStateUpdate(
                        remoteLoadStatesOf(
                            prepend = NotLoading.Complete,
                            prependLocal = NotLoading.Complete,
                            prependRemote = NotLoading.Complete
                        )
                    )
                )
            )
        )
    }

    @Test
    fun remoteMediator_prependEndOfPaginationReachedLocalThenRemote() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val fetcher = PageFetcher(
            initialKey = 1,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )

        fetcher.collectEvents {
            awaitEventCount(2)
            val refreshEvents = listOf(
                LoadStateUpdate(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf(1, 2, 3)
                        )
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 96,
                    combinedLoadStates = remoteLoadStatesOf()
                )
            )
            assertThat(eventsByGeneration[0]).isEqualTo(refreshEvents)
            accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 2,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            val postHintEvents = listOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf(0)
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = Loading,
                        prependLocal = NotLoading.Complete,
                        prependRemote = Loading,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete
                    )
                )
            )
            awaitEventCount(refreshEvents.size + postHintEvents.size)
            assertEquals(
                eventsByGeneration[0],
                refreshEvents + postHintEvents
            )
        }
    }

    @Test
    fun remoteMediator_endOfPaginationNotReachedLoadStateAppend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                currentPagingSource!!.invalidate()
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val fetcher = PageFetcher(
            initialKey = 99,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )
        fetcher.assertEventByGeneration(
            listOf(
                listOf(
                    LoadStateUpdate(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    Refresh(
                        pages = listOf(
                            TransformablePage(
                                originalPageOffset = 0,
                                data = listOf(99)
                            )
                        ),
                        placeholdersBefore = 99,
                        placeholdersAfter = 0,
                        combinedLoadStates = remoteLoadStatesOf(
                            appendLocal = NotLoading.Complete,
                        )
                    ),
                    LoadStateUpdate(
                        remoteLoadStatesOf(
                            append = Loading,
                            appendLocal = NotLoading.Complete,
                            appendRemote = Loading
                        )
                    )
                ),
                listOf(
                    LoadStateUpdate(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    Refresh(
                        pages = listOf(
                            TransformablePage(
                                originalPageOffset = 0,
                                data = listOf(99)
                            )
                        ),
                        placeholdersBefore = 99,
                        placeholdersAfter = 0,
                        combinedLoadStates = remoteLoadStatesOf(
                            appendLocal = NotLoading.Complete,
                        )
                    ),
                )
            )
        )
    }

    @Test
    fun remoteMediator_endOfPaginationReachedLoadStateAppend() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 99,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )

        val expected: List<List<PageEvent<Int>>> = listOf(
            listOf(
                LoadStateUpdate(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf(99)
                        )
                    ),
                    placeholdersBefore = 99,
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = Loading,
                        appendLocal = NotLoading.Complete,
                        appendRemote = Loading
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete
                    )
                )
            )
        )
        pager.assertEventByGeneration(expected)
    }

    @Test
    fun remoteMediator_appendEndOfPaginationReachedLocalThenRemote() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 3,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 96,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )
        pager.collectEvents {
            val initialEvents = listOf(
                LoadStateUpdate(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf(96, 97, 98)
                        )
                    ),
                    placeholdersBefore = 96,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf()
                )
            )
            awaitEventCount(initialEvents.size)
            assertEvents(initialEvents, eventsByGeneration[0])
            accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 48,
                    presentedItemsBefore = 48,
                    presentedItemsAfter = -46,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            val postHintEvents = listOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        appendLocal = Loading
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf(99)
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = Loading,
                        appendLocal = NotLoading.Complete,
                        appendRemote = Loading
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete
                    )
                )
            )
            awaitEventCount(initialEvents.size + postHintEvents.size)
            assertThat(eventsByGeneration[0]).isEqualTo(initialEvents + postHintEvents)
        }
    }

    @Test
    fun remoteMediator_initialRefreshSuccess() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock() {
            override suspend fun initialize(): InitializeAction {
                super.initialize()
                return InitializeAction.LAUNCH_INITIAL_REFRESH
            }

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                currentPagingSource!!.invalidate()
                return MediatorResult.Success(endOfPaginationReached = false)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 50,
            pagingSourceFactory = pagingSourceFactory,
            config = config,
            remoteMediator = remoteMediator
        )
        pager.assertEventByGeneration(
            listOf(
                listOf<PageEvent<Int>>(
                    LoadStateUpdate(
                        remoteLoadStatesOf(
                            refresh = Loading,
                            refreshRemote = Loading
                        )
                    ),
                    LoadStateUpdate(
                        remoteLoadStatesOf(
                            refresh = NotLoading.Incomplete,
                            refreshRemote = NotLoading.Incomplete
                        )
                    )
                ),
                listOf(
                    LoadStateUpdate(
                        localLoadStatesOf(
                            refreshLocal = Loading
                        )
                    ),
                    Refresh(
                        pages = listOf(
                            TransformablePage(
                                originalPageOffset = 0,
                                data = listOf(50)
                            )
                        ),
                        placeholdersBefore = 50,
                        placeholdersAfter = 49,
                        combinedLoadStates = remoteLoadStatesOf()
                    ),
                )
            )
        )
    }

    @Test
    fun remoteMediator_initialRefreshSuccessEndOfPagination() = testScope.runBlockingTest {
        @OptIn(ExperimentalPagingApi::class)
        val remoteMediator = object : RemoteMediatorMock(loadDelay = 2000) {
            override suspend fun initialize(): InitializeAction {
                super.initialize()
                return InitializeAction.LAUNCH_INITIAL_REFRESH
            }

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, Int>
            ): MediatorResult {
                super.load(loadType, state)
                return MediatorResult.Success(endOfPaginationReached = true)
            }
        }

        val config = PagingConfig(
            pageSize = 1,
            prefetchDistance = 2,
            enablePlaceholders = true,
            initialLoadSize = 1,
            maxSize = 5
        )
        val pager = PageFetcher(
            initialKey = 50,
            pagingSourceFactory = {
                TestPagingSource().apply {
                    nextLoadResult = Page(
                        data = listOf(50),
                        prevKey = null,
                        nextKey = null,
                        itemsBefore = 50,
                        itemsAfter = 49
                    )
                }
            },
            config = config,
            remoteMediator = remoteMediator
        )

        val fetcherState = collectFetcherState(pager)

        advanceTimeBy(1000)

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshRemote = Loading
                )
            ),
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    refresh = Loading,
                    refreshLocal = Loading,
                    refreshRemote = Loading
                )
            ),
            Refresh(
                pages = listOf(
                    TransformablePage(
                        originalPageOffset = 0,
                        data = listOf(50)
                    )
                ),
                placeholdersBefore = 50,
                placeholdersAfter = 49,
                combinedLoadStates = remoteLoadStatesOf(
                    refresh = Loading,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete,
                    refreshRemote = Loading,
                )
            ),
        )

        advanceUntilIdle()

        assertThat(fetcherState.newEvents()).containsExactly(
            LoadStateUpdate<Int>(
                remoteLoadStatesOf(
                    append = NotLoading.Complete,
                    refresh = NotLoading.Complete,
                    prepend = NotLoading.Complete,
                    refreshRemote = NotLoading.Complete,
                    prependRemote = NotLoading.Complete,
                    appendRemote = NotLoading.Complete,
                    prependLocal = NotLoading.Complete,
                    appendLocal = NotLoading.Complete
                )
            )
        )

        fetcherState.job.cancel()
    }

    @Test
    fun jump() = testScope.runBlockingTest {
        pauseDispatcher {
            val config = PagingConfig(
                pageSize = 1,
                prefetchDistance = 1,
                enablePlaceholders = true,
                initialLoadSize = 2,
                maxSize = 3,
                jumpThreshold = 10
            )
            var didJump = false
            val pager = PageFetcherSnapshot<Int, Int>(
                initialKey = 50,
                pagingSource = pagingSourceFactory(),
                config = config,
                retryFlow = retryBus.flow,
                previousPagingState = null,
            ) {
                didJump = true
            }
            // Trigger collection on flow to init jump detection job.
            val job = launch { pager.pageEventFlow.collect { } }

            advanceUntilIdle()

            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = -50,
                    presentedItemsBefore = -50,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            assertTrue { didJump }

            job.cancel()
        }
    }

    @Test
    fun jump_requiresPagingSourceOptIn() {
        assertFailsWith<IllegalArgumentException> {
            PageFetcherSnapshot(
                initialKey = 50,
                pagingSource = TestPagingSource(jumpingSupported = false),
                config = PagingConfig(pageSize = 1, prefetchDistance = 1, jumpThreshold = 1),
                retryFlow = retryBus.flow
            )
        }
    }

    @Test
    fun keyReuse_unsupported_success() = testScope.runBlockingTest {
        pauseDispatcher {
            val pager = PageFetcherSnapshot(
                initialKey = 50,
                pagingSource = object : PagingSource<Int, Int>() {
                    var loads = 0

                    override val keyReuseSupported: Boolean
                        get() = true

                    override suspend fun load(params: LoadParams<Int>) = when (params) {
                        is LoadParams.Refresh -> Page(listOf(0), 0, 0)
                        else -> Page<Int, Int>(
                            listOf(),
                            if (loads < 3) loads else null,
                            if (loads < 3) loads else null
                        )
                    }.also {
                        loads++
                    }

                    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
                },
                config = config,
                retryFlow = retryBus.flow
            )

            // Trigger collection on flow.
            val job = launch {
                pager.pageEventFlow.collect { }
            }

            advanceUntilIdle()

            // Trigger first prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            // Trigger second prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            job.cancel()
        }
    }

    @Test
    fun keyReuse_unsupported_failure() = testScope.runBlockingTest {
        pauseDispatcher {
            val pager = PageFetcherSnapshot(
                initialKey = 50,
                pagingSource = object : PagingSource<Int, Int>() {
                    override val keyReuseSupported = false

                    override suspend fun load(params: LoadParams<Int>) = when (params) {
                        is LoadParams.Refresh -> Page(listOf(0, 0), 0, 0)
                        else -> Page(listOf(0), 0, 0)
                    }

                    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
                },
                config = config,
                retryFlow = retryBus.flow
            )

            // Trigger collection on flow.
            launch {
                // Assert second prepend re-using key = 0 leads to IllegalStateException
                assertFailsWith<IllegalStateException> {
                    pager.pageEventFlow.collect { }
                }
            }

            advanceUntilIdle()

            // Trigger first prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            // Trigger second prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()
        }
    }

    @Test
    fun keyReuse_supported() = testScope.runBlockingTest {
        pauseDispatcher {
            val pager = PageFetcherSnapshot(
                initialKey = 50,
                pagingSource = object : PagingSource<Int, Int>() {
                    var loads = 0

                    override val keyReuseSupported: Boolean
                        get() = true

                    override suspend fun load(params: LoadParams<Int>) = when (params) {
                        is LoadParams.Refresh -> Page(listOf(0), 0, 0)
                        else -> Page<Int, Int>(
                            listOf(),
                            if (loads < 3) 0 else null,
                            if (loads < 3) 0 else null
                        )
                    }.also {
                        loads++
                    }

                    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
                },
                config = config,
                retryFlow = retryBus.flow
            )

            // Trigger collection on flow.
            val job = launch {
                pager.pageEventFlow.collect { }
            }

            advanceUntilIdle()

            // Trigger first prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = 0,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            // Trigger second prepend with key = 0
            pager.accessHint(
                ViewportHint.Access(
                    pageOffset = 0,
                    indexInPage = 0,
                    presentedItemsBefore = 0,
                    presentedItemsAfter = 0,
                    originalPageOffsetFirst = -1,
                    originalPageOffsetLast = 0
                )
            )
            advanceUntilIdle()

            job.cancel()
        }
    }

    @Test
    fun initializeHintAfterEmpty() = testScope.runBlockingTest {
        val pageFetcherSnapshot = PageFetcherSnapshot(
            initialKey = 50,
            pagingSource = TestPagingSource(),
            config = config,
            retryFlow = emptyFlow(),
        )
        collectSnapshotData(pageFetcherSnapshot) { state, _ ->
            advanceUntilIdle()
            assertThat(state.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        refreshLocal = Loading
                    )
                ),
                createRefresh(range = 50..51),
            )

            pageFetcherSnapshot.accessHint(ViewportHint.Initial(0, 0, 0, 0))
            advanceUntilIdle()
            assertThat(state.newEvents()).containsExactly(
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading
                    )
                ),
                LoadStateUpdate<Int>(
                    localLoadStatesOf(
                        prependLocal = Loading,
                        appendLocal = Loading
                    )
                ),
                createPrepend(pageOffset = -1, range = 49..49, endState = Loading),
                createAppend(pageOffset = 1, range = 52..52),
            )
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun pageEventSentAfterChannelClosed() {
        val pager = PageFetcherSnapshot(
            initialKey = 50,
            pagingSource = TestPagingSource(loadDelay = 100),
            config = config,
            retryFlow = retryBus.flow
        )

        val deferred = GlobalScope.async {
            pager.pageEventFlow.collect { }
        }
        pager.close()

        runBlocking { deferred.await() }
    }

    @Test
    fun generationalViewportHint_shouldPrioritizeOver_presenterUpdates() {
        val prependHint = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = -10,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        val prependHintWithPresenterUpdate = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Access(
                pageOffset = -10,
                indexInPage = 0,
                presentedItemsBefore = -5,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = -10,
                originalPageOffsetLast = 0
            )
        )
        assertTrue { prependHintWithPresenterUpdate.shouldPrioritizeOver(prependHint, PREPEND) }

        val appendHint = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = -10,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        val appendHintWithPresenterUpdate = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Access(
                pageOffset = 10,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = -5,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 10
            )
        )
        assertTrue { appendHintWithPresenterUpdate.shouldPrioritizeOver(appendHint, APPEND) }
    }

    @Test
    fun generationalViewportHint_shouldPrioritizeAccessOverInitial() {
        val accessHint = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Access(
                pageOffset = 0,
                indexInPage = 0,
                presentedItemsBefore = 0,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )
        val initialHint = GenerationalViewportHint(
            generationId = 0,
            hint = ViewportHint.Initial(
                presentedItemsBefore = 0,
                presentedItemsAfter = 0,
                originalPageOffsetFirst = 0,
                originalPageOffsetLast = 0
            )
        )

        assertTrue { accessHint.shouldPrioritizeOver(initialHint, PREPEND) }
        assertFalse { initialHint.shouldPrioritizeOver(accessHint, PREPEND) }
        assertTrue { accessHint.shouldPrioritizeOver(accessHint, APPEND) }
        assertFalse { initialHint.shouldPrioritizeOver(accessHint, APPEND) }
    }

    internal class CollectedPageEvents<T : Any>(val pageEvents: ArrayList<PageEvent<T>>) {
        var lastIndex = 0
        fun newEvents(): List<PageEvent<T>> = when {
            pageEvents.isEmpty() -> pageEvents.toList()
            lastIndex > pageEvents.lastIndex -> listOf()
            else -> pageEvents.lastIndex.let {
                val result = pageEvents.slice(lastIndex..it)
                lastIndex = it + 1
                result
            }
        }
    }

    @Suppress("SuspendFunctionOnCoroutineScope")
    internal suspend fun <T : Any> CoroutineScope.collectSnapshotData(
        pageFetcherSnapshot: PageFetcherSnapshot<*, T>,
        block: suspend (state: CollectedPageEvents<T>, job: Job) -> Unit
    ) {
        if (pageFetcherSnapshot.remoteMediatorConnection != null) {
            throw IllegalArgumentException("cannot test fetcher with remote mediator here")
        }
        val pageEvents = ArrayList<PageEvent<T>>()
        val job: Job = launch { pageFetcherSnapshot.pageEventFlow.collect { pageEvents.add(it) } }
        block(CollectedPageEvents(pageEvents), job)
        job.cancel()
    }

    internal fun <T : Any> PageFetcher<*, T>.pageEvents(): Flow<PageEvent<T>> {
        return flow.flatMapLatest {
            it.flow
        }
    }

    internal suspend fun <T : Any> PageFetcher<*, T>.collectEvents(
        block: (suspend MultiGenerationCollectionScope<T>.() -> Unit)
    ): List<List<PageEvent<T>>> {
        val collectionScope = MultiGenerationCollectionScopeImpl<T>()
        val eventsByGeneration = collectionScope.eventsByGeneration
        coroutineScope {
            val collectionJob = launch(start = CoroutineStart.LAZY) {
                flow.flatMapLatest { data ->
                    collectionScope.uiReceiver = data.receiver
                    val generationEvents = mutableListOf<PageEvent<T>>().also {
                        eventsByGeneration.add(it)
                    }
                    collectionScope.generationCount.value = eventsByGeneration.size
                    data.flow.onEach {
                        generationEvents.add(it)
                        collectionScope.eventCount.value += 1
                    }
                }.collect() // just keep collecting, block will cancel eventually
            }
            launch {
                collectionScope.stopped.await()
                collectionJob.cancel()
            }
            launch {
                collectionScope.block()
                collectionScope.stop()
            }
            collectionJob.join()
        }

        return eventsByGeneration
    }

    internal suspend fun <T : Any> PageFetcher<*, T>.assertEventByGeneration(
        expected: List<List<PageEvent<T>>>
    ) {
        val total = expected.sumOf { it.size }
        val actual = collectEvents {
            awaitEventCount(total)
            stop()
        }
        expected.forEachIndexed { index, list ->
            assertThat(actual.getOrNull(index) ?: emptyList<PageEvent<T>>()).isEqualTo(list)
        }
        assertThat(actual.size).isEqualTo(expected.size)
    }

    internal interface MultiGenerationCollectionScope<T : Any> {
        val eventCount: StateFlow<Int>
        val generationCount: StateFlow<Int>
        val eventsByGeneration: List<List<PageEvent<T>>>
        val uiReceiver: UiReceiver?
        suspend fun stop()
        fun accessHint(viewportHint: ViewportHint) {
            uiReceiver!!.accessHint(viewportHint)
        }

        fun retry() {
            uiReceiver!!.retry()
        }

        suspend fun TestCoroutineScope.awaitIdle() {
            yield()
            advanceUntilIdle()
        }

        suspend fun awaitEventCount(limit: Int) {
            eventCount.takeWhile {
                it < limit
            }.collect()
        }
    }

    internal class MultiGenerationCollectionScopeImpl<T : Any>(
        override val eventCount: MutableStateFlow<Int> = MutableStateFlow(0),
        override val generationCount: MutableStateFlow<Int> = MutableStateFlow(0),
        override val eventsByGeneration: MutableList<List<PageEvent<T>>> = mutableListOf(),
        override var uiReceiver: UiReceiver? = null
    ) : MultiGenerationCollectionScope<T> {
        val stopped = CompletableDeferred<Unit>()
        override suspend fun stop() {
            stopped.complete(Unit)
        }
    }
}
