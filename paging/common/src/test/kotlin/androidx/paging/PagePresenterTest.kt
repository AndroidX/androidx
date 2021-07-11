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

import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.PageEvent.Insert.Companion.Refresh
import androidx.paging.PagePresenter.ProcessPageEventCallback
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

@Suppress("TestFunctionName")
internal fun <T : Any> PagePresenter(
    pages: List<List<T>>,
    leadingNullCount: Int = COUNT_UNDEFINED,
    trailingNullCount: Int = COUNT_UNDEFINED,
    indexOfInitialPage: Int = 0
) = PagePresenter(
    Refresh(
        pages = pages.mapIndexed { index, list ->
            TransformablePage(
                originalPageOffset = index - indexOfInitialPage,
                data = list
            )
        },
        placeholdersBefore = leadingNullCount,
        placeholdersAfter = trailingNullCount,
        combinedLoadStates = CombinedLoadStates.IDLE_SOURCE
    )
)

internal fun <T : Any> PagePresenter<T>.insertPage(
    isPrepend: Boolean,
    page: List<T>,
    placeholdersRemaining: Int,
    callback: ProcessPageEventCallback
) = processEvent(
    adjacentInsertEvent(
        isPrepend = isPrepend,
        page = page,
        originalPageOffset = 0,
        placeholdersRemaining = placeholdersRemaining
    ),
    callback
)

internal fun <T : Any> PagePresenter<T>.dropPages(
    isPrepend: Boolean,
    minPageOffset: Int,
    maxPageOffset: Int,
    placeholdersRemaining: Int,
    callback: ProcessPageEventCallback
) = processEvent(
    PageEvent.Drop(
        loadType = if (isPrepend) PREPEND else APPEND,
        minPageOffset = minPageOffset,
        maxPageOffset = maxPageOffset,
        placeholdersRemaining = placeholdersRemaining
    ),
    callback
)

internal fun <T : Any> PagePresenter<T>.asList() = List(size) { get(it) }

@Suppress("SameParameterValue")
@RunWith(JUnit4::class)
class PagePresenterTest {
    private fun verifyAppend(
        initialItems: Int,
        initialNulls: Int,
        newItems: Int,
        newNulls: Int = COUNT_UNDEFINED,
        events: List<PresenterEvent>
    ) {
        val data = PagePresenter(
            pages = mutableListOf(List(initialItems) { 'a' + it }),
            leadingNullCount = 0,
            trailingNullCount = initialNulls,
            indexOfInitialPage = 0
        )

        val callback = ProcessPageEventCallbackCapture()
        val page: List<Char> = List(newItems) { 'a' + it + initialItems }
        data.insertPage(
            isPrepend = false,
            page = page,
            placeholdersRemaining = newNulls,
            callback = callback
        )

        // Assert list contents first (since this shows more obvious errors)...
        val expectedNulls = if (newNulls != COUNT_UNDEFINED) {
            newNulls
        } else {
            maxOf(initialNulls - newItems, 0)
        }
        val expectedData =
            List(initialItems + newItems) { 'a' + it } + List(expectedNulls) { null }
        assertEquals(expectedData, data.asList())

        // ... then assert events
        assertEquals(events + IDLE_EVENTS, callback.getAllAndClear())
    }

    private fun verifyPrepend(
        initialItems: Int,
        initialNulls: Int,
        newItems: Int,
        newNulls: Int,
        events: List<PresenterEvent>
    ) {
        val data = PagePresenter(
            pages = mutableListOf(List(initialItems) { 'z' + it - initialItems - 1 }),
            leadingNullCount = initialNulls,
            trailingNullCount = 0,
            indexOfInitialPage = 0
        )

        val endItemCount = newItems + initialItems
        val callback = ProcessPageEventCallbackCapture()
        data.insertPage(
            isPrepend = true,
            page = List(newItems) { 'z' + it - endItemCount - 1 },
            placeholdersRemaining = newNulls,
            callback = callback
        )

        // Assert list contents first (since this shows more obvious errors)...
        val expectedNulls = if (newNulls != COUNT_UNDEFINED) {
            newNulls
        } else {
            maxOf(initialNulls - newItems, 0)
        }
        val expectedData =
            List(expectedNulls) { null } + List(endItemCount) { 'z' + it - endItemCount - 1 }
        assertEquals(expectedData, data.asList())

        // ... then assert events
        assertEquals(events + IDLE_EVENTS, callback.getAllAndClear())
    }

    private fun verifyPrependAppend(
        initialItems: Int,
        initialNulls: Int,
        newItems: Int,
        newNulls: Int,
        prependEvents: List<PresenterEvent>,
        appendEvents: List<PresenterEvent>
    ) {
        verifyPrepend(initialItems, initialNulls, newItems, newNulls, prependEvents)
        verifyAppend(initialItems, initialNulls, newItems, newNulls, appendEvents)
    }

    @Test
    fun insertPageEmpty() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 0,
        newItems = 0,
        newNulls = 0,
        prependEvents = emptyList(),
        appendEvents = emptyList()
    )

    @Test
    fun insertPageSimple() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 0,
        newItems = 2,
        newNulls = 0,
        prependEvents = listOf(
            InsertEvent(0, 2)
        ),
        appendEvents = listOf(
            InsertEvent(2, 2)
        )
    )

    @Test
    fun insertPageSimplePlaceholders() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 4,
        newItems = 2,
        newNulls = 2,
        prependEvents = listOf(
            ChangeEvent(2, 2)
        ),
        appendEvents = listOf(
            ChangeEvent(2, 2)
        )
    )

    @Test
    fun insertPageInitPlaceholders() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 0,
        newItems = 2,
        newNulls = 3,
        prependEvents = listOf(
            InsertEvent(0, 2),
            InsertEvent(0, 3)
        ),
        appendEvents = listOf(
            // NOTE: theoretically these could be combined
            InsertEvent(2, 2),
            InsertEvent(4, 3)
        )
    )

    @Test
    fun insertPageInitJustPlaceholders() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 0,
        newItems = 0,
        newNulls = 3,
        prependEvents = listOf(
            InsertEvent(0, 3)
        ),
        appendEvents = listOf(
            InsertEvent(2, 3)
        )
    )

    @Test
    fun insertPageInsertNulls() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 3,
        newItems = 2,
        newNulls = 2,
        prependEvents = listOf(
            ChangeEvent(1, 2),
            InsertEvent(0, 1)
        ),
        appendEvents = listOf(
            ChangeEvent(2, 2),
            InsertEvent(5, 1)
        )
    )

    @Test
    fun insertPageRemoveNulls() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 7,
        newItems = 2,
        newNulls = 0,
        prependEvents = listOf(
            ChangeEvent(5, 2),
            RemoveEvent(0, 5)
        ),
        appendEvents = listOf(
            ChangeEvent(2, 2),
            RemoveEvent(4, 5)
        )
    )

    @Test
    fun insertPageReduceNulls() = verifyPrependAppend(
        initialItems = 2,
        initialNulls = 10,
        newItems = 3,
        newNulls = 4,
        prependEvents = listOf(
            ChangeEvent(7, 3),
            RemoveEvent(0, 3)
        ),
        appendEvents = listOf(
            ChangeEvent(2, 3),
            RemoveEvent(9, 3)
        )
    )

    private fun verifyDropEnd(
        initialPages: List<List<Char>>,
        initialNulls: Int = 0,
        newNulls: Int,
        pagesToDrop: Int,
        events: List<PresenterEvent>
    ) {
        if (initialPages.size < 2) {
            fail("require at least 2 pages")
        }

        val data = PagePresenter(
            pages = initialPages.toMutableList(),
            leadingNullCount = 0,
            trailingNullCount = initialNulls,
            indexOfInitialPage = 0
        )

        assertEquals(initialPages.flatten() + List<Char?>(initialNulls) { null }, data.asList())

        val callback = ProcessPageEventCallbackCapture()
        data.dropPages(
            isPrepend = false,
            minPageOffset = initialPages.lastIndex - (pagesToDrop - 1),
            maxPageOffset = initialPages.lastIndex,
            placeholdersRemaining = newNulls,
            callback = callback
        )

        assertThat(callback.getAllAndClear()).isEqualTo(
            events + listOf(StateEvent(CombinedLoadStates.IDLE_SOURCE))
        )

        // assert final list state
        val finalData = initialPages.subList(0, initialPages.size - pagesToDrop).flatten()
        assertEquals(finalData + List<Char?>(newNulls) { null }, data.asList())
    }

    private fun verifyDropStart(
        initialPages: List<List<Char>>,
        initialNulls: Int = 0,
        newNulls: Int,
        pagesToDrop: Int,
        events: List<PresenterEvent>
    ) {
        if (initialPages.size < 2) {
            fail("require at least 2 pages")
        }

        val data = PagePresenter(
            pages = initialPages.reversed().toMutableList(),
            leadingNullCount = initialNulls,
            trailingNullCount = 0,
            indexOfInitialPage = 0
        )

        assertEquals(
            List<Char?>(initialNulls) { null } + initialPages.reversed().flatten(),
            data.asList()
        )

        val callback = ProcessPageEventCallbackCapture()
        data.dropPages(
            isPrepend = true,
            minPageOffset = 0,
            maxPageOffset = pagesToDrop - 1,
            placeholdersRemaining = newNulls,
            callback = callback
        )

        assertThat(callback.getAllAndClear()).isEqualTo(
            events + listOf(StateEvent(CombinedLoadStates.IDLE_SOURCE))
        )

        // assert final list state
        val finalData = initialPages.take(initialPages.size - pagesToDrop).reversed().flatten()
        assertEquals(List<Char?>(newNulls) { null } + finalData, data.asList())
    }

    private fun verifyDrop(
        initialPages: List<List<Char>>,
        initialNulls: Int = 0,
        newNulls: Int,
        pagesToDrop: Int,
        startEvents: List<PresenterEvent>,
        endEvents: List<PresenterEvent>
    ) {
        verifyDropStart(initialPages, initialNulls, newNulls, pagesToDrop, startEvents)
        verifyDropEnd(initialPages, initialNulls, newNulls, pagesToDrop, endEvents)
    }

    @Test
    fun dropPageMulti() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 0,
        newNulls = 0,
        pagesToDrop = 2,
        startEvents = listOf(RemoveEvent(0, 3)),
        endEvents = listOf(RemoveEvent(2, 3))
    )

    @Test
    fun dropPageReturnNulls() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 1,
        newNulls = 4,
        pagesToDrop = 2,
        startEvents = listOf(ChangeEvent(1, 3)),
        endEvents = listOf(ChangeEvent(2, 3))
    )

    @Test
    fun dropPageFromNoNullsToHavingNulls() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 0,
        newNulls = 3,
        pagesToDrop = 2,
        startEvents = listOf(
            // [null, null, null, 'a', 'b']
            ChangeEvent(0, 3)
        ),
        endEvents = listOf(
            // ['a', 'b', null, null, null]
            ChangeEvent(2, 3)
        )
    )

    @Test
    fun dropPageChangeRemovePlaceholders() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 2,
        newNulls = 4,
        pagesToDrop = 2,
        startEvents = listOf(
            // [null, 'e', 'c', 'd', 'a', 'b']
            RemoveEvent(0, 1),
            // [null, null, null, null, 'a', 'b']
            ChangeEvent(1, 3)
        ),
        endEvents = listOf(
            // ['a', 'b', 'c', 'd', 'e', null]
            RemoveEvent(6, 1),
            // ['a', 'b', null, null, null, null]
            ChangeEvent(2, 3)
        )
    )

    @Test
    fun dropPageChangeRemoveItems() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 0,
        newNulls = 1,
        pagesToDrop = 2,
        startEvents = listOf(
            // ['d', 'a', 'b']
            RemoveEvent(0, 2),
            // [null, 'a', 'b']
            ChangeEvent(0, 1)
        ),
        endEvents = listOf(
            // ['a', 'b', 'c']
            RemoveEvent(3, 2),
            // ['a', 'b', null]
            ChangeEvent(2, 1)
        )
    )

    @Test
    fun dropPageChangeDoubleRemove() = verifyDrop(
        initialPages = listOf(
            listOf('a', 'b'),
            listOf('c', 'd'),
            listOf('e')
        ),
        initialNulls = 3,
        newNulls = 1,
        pagesToDrop = 2,
        startEvents = listOf(
            // ['d', 'a', 'b']
            RemoveEvent(0, 5),
            // [null, 'a', 'b']
            ChangeEvent(0, 1)
        ),
        endEvents = listOf(
            // ['a', 'b', 'c']
            RemoveEvent(3, 5),
            // ['a', 'b', null]
            ChangeEvent(2, 1)
        )
    )

    @Test
    fun getOutOfBounds() {
        val presenter = PagePresenter(
            pages = mutableListOf(listOf('a')),
            leadingNullCount = 1,
            trailingNullCount = 1,
            indexOfInitialPage = 0
        )
        assertFailsWith<IndexOutOfBoundsException> {
            presenter.get(-1)
        }
        assertFailsWith<IndexOutOfBoundsException> {
            presenter.get(4)
        }
    }

    // TODO storageCount test

    @Test
    fun snapshot_uncounted() {
        val pagePresenter = PagePresenter(
            insertEvent = Refresh(
                pages = listOf(TransformablePage(listOf('a'))),
                placeholdersBefore = 0,
                placeholdersAfter = 0,
                combinedLoadStates = CombinedLoadStates.IDLE_SOURCE
            )
        )

        assertEquals<List<Char?>>(listOf('a'), pagePresenter.snapshot())
    }

    @Test
    fun snapshot_counted() {
        val pagePresenter = PagePresenter(
            insertEvent = Refresh(
                pages = listOf(TransformablePage(listOf('a'))),
                placeholdersBefore = 1,
                placeholdersAfter = 3,
                combinedLoadStates = CombinedLoadStates.IDLE_SOURCE
            )
        )

        assertEquals(listOf(null, 'a', null, null, null), pagePresenter.snapshot())
    }

    companion object {
        val IDLE_EVENTS = listOf<PresenterEvent>(
            StateEvent(CombinedLoadStates.IDLE_SOURCE)
        )
    }
}