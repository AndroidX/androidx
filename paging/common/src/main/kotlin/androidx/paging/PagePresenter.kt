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
import androidx.paging.LoadType.REFRESH

/**
 * Presents post-transform paging data as a list, with list update notifications when
 * PageEvents are dispatched.
 */
internal class PagePresenter<T : Any>(
    insertEvent: PageEvent.Insert<T>
) : NullPaddedList<T> {
    private val pages: MutableList<TransformablePage<T>> = insertEvent.pages.toMutableList()
    override var storageCount: Int = insertEvent.pages.fullCount()
        private set
    private val originalPageOffsetFirst: Int
        get() = pages.first().originalPageOffsets.minOrNull()!!
    private val originalPageOffsetLast: Int
        get() = pages.last().originalPageOffsets.maxOrNull()!!
    override var placeholdersBefore: Int = insertEvent.placeholdersBefore
        private set
    override var placeholdersAfter: Int = insertEvent.placeholdersAfter
        private set

    private fun checkIndex(index: Int) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }
    }

    override fun toString(): String {
        val items = List(storageCount) { getFromStorage(it) }.joinToString()
        return "[($placeholdersBefore placeholders), $items, ($placeholdersAfter placeholders)]"
    }

    fun get(index: Int): T? {
        checkIndex(index)

        val localIndex = index - placeholdersBefore
        if (localIndex < 0 || localIndex >= storageCount) {
            return null
        }
        return getFromStorage(localIndex)
    }

    fun snapshot(): ItemSnapshotList<T> {
        return ItemSnapshotList(
            placeholdersBefore,
            placeholdersAfter,
            pages.flatMap { it.data }
        )
    }

    override fun getFromStorage(localIndex: Int): T {
        var pageIndex = 0
        var indexInPage = localIndex

        // Since we don't know if page sizes are regular, we walk to correct page.
        val localPageCount = pages.size
        while (pageIndex < localPageCount) {
            val pageSize = pages[pageIndex].data.size
            if (pageSize > indexInPage) {
                // stop, found the page
                break
            }
            indexInPage -= pageSize
            pageIndex++
        }
        return pages[pageIndex].data[indexInPage]
    }

    override val size: Int
        get() = placeholdersBefore + storageCount + placeholdersAfter

    private fun List<TransformablePage<T>>.fullCount() = sumOf { it.data.size }

    fun processEvent(pageEvent: PageEvent<T>, callback: ProcessPageEventCallback) {
        when (pageEvent) {
            is PageEvent.Insert -> insertPage(pageEvent, callback)
            is PageEvent.Drop -> dropPages(pageEvent, callback)
            is PageEvent.LoadStateUpdate -> {
                callback.onStateUpdate(pageEvent.combinedLoadStates)
            }
        }
    }

    fun initializeHint(): ViewportHint.Initial {
        val presentedItems = storageCount
        return ViewportHint.Initial(
            presentedItemsBefore = presentedItems / 2,
            presentedItemsAfter = presentedItems / 2,
            originalPageOffsetFirst = originalPageOffsetFirst,
            originalPageOffsetLast = originalPageOffsetLast
        )
    }

    fun accessHintForPresenterIndex(index: Int): ViewportHint.Access {
        var pageIndex = 0
        var indexInPage = index - placeholdersBefore
        while (indexInPage >= pages[pageIndex].data.size && pageIndex < pages.lastIndex) {
            // index doesn't appear in current page, keep looking!
            indexInPage -= pages[pageIndex].data.size
            pageIndex++
        }

        return pages[pageIndex].viewportHintFor(
            index = indexInPage,
            presentedItemsBefore = index - placeholdersBefore,
            presentedItemsAfter = size - index - placeholdersAfter - 1,
            originalPageOffsetFirst = originalPageOffsetFirst,
            originalPageOffsetLast = originalPageOffsetLast
        )
    }

    /**
     * Insert the event's page to the presentation list, and dispatch associated callbacks for
     * change (placeholder becomes real item) or insert (real item is appended).
     *
     * For each insert (or removal) there are three potential events:
     *
     * 1) change
     *     this covers any placeholder/item conversions, and is done first
     *
     * 2) item insert/remove
     *     this covers any remaining items that are inserted/removed, but aren't swapping with
     *     placeholders
     *
     * 3) placeholder insert/remove
     *     after the above, placeholder count can be wrong for a number of reasons - approximate
     *     counting or filtering are the most common. In either case, we adjust placeholders at
     *     the far end of the list, so that they don't trigger animations near the user.
     */
    private fun insertPage(insert: PageEvent.Insert<T>, callback: ProcessPageEventCallback) {
        val count = insert.pages.fullCount()
        val oldSize = size
        when (insert.loadType) {
            REFRESH -> throw IllegalArgumentException()
            PREPEND -> {
                val placeholdersChangedCount = minOf(placeholdersBefore, count)
                val placeholdersChangedPos = placeholdersBefore - placeholdersChangedCount

                val itemsInsertedCount = count - placeholdersChangedCount
                val itemsInsertedPos = 0

                // first update all state...
                pages.addAll(0, insert.pages)
                storageCount += count
                placeholdersBefore = insert.placeholdersBefore

                // ... then trigger callbacks, so callbacks won't see inconsistent state
                callback.onChanged(placeholdersChangedPos, placeholdersChangedCount)
                callback.onInserted(itemsInsertedPos, itemsInsertedCount)
                val placeholderInsertedCount = size - oldSize - itemsInsertedCount
                if (placeholderInsertedCount > 0) {
                    callback.onInserted(0, placeholderInsertedCount)
                } else if (placeholderInsertedCount < 0) {
                    callback.onRemoved(0, -placeholderInsertedCount)
                }
            }
            APPEND -> {
                val placeholdersChangedCount = minOf(placeholdersAfter, count)
                val placeholdersChangedPos = placeholdersBefore + storageCount

                val itemsInsertedCount = count - placeholdersChangedCount
                val itemsInsertedPos = placeholdersChangedPos + placeholdersChangedCount

                // first update all state...
                pages.addAll(pages.size, insert.pages)
                storageCount += count
                placeholdersAfter = insert.placeholdersAfter

                // ... then trigger callbacks, so callbacks won't see inconsistent state
                callback.onChanged(placeholdersChangedPos, placeholdersChangedCount)
                callback.onInserted(itemsInsertedPos, itemsInsertedCount)
                val placeholderInsertedCount = size - oldSize - itemsInsertedCount
                if (placeholderInsertedCount > 0) {
                    callback.onInserted(
                        position = size - placeholderInsertedCount,
                        count = placeholderInsertedCount
                    )
                } else if (placeholderInsertedCount < 0) {
                    callback.onRemoved(size, -placeholderInsertedCount)
                }
            }
        }

        callback.onStateUpdate(insert.combinedLoadStates)
    }

    /**
     * @param pageOffsetsToDrop originalPageOffset of pages that were dropped
     * @return The number of items dropped
     */
    private fun dropPagesWithOffsets(pageOffsetsToDrop: IntRange): Int {
        var removeCount = 0
        val pageIterator = pages.iterator()
        while (pageIterator.hasNext()) {
            val page = pageIterator.next()
            if (page.originalPageOffsets.any { pageOffsetsToDrop.contains(it) }) {
                removeCount += page.data.size
                pageIterator.remove()
            }
        }

        return removeCount
    }

    /**
     * Helper which converts a [PageEvent.Drop] to a set of [ProcessPageEventCallback] events by
     * dropping all pages that depend on the n-lowest or n-highest originalPageOffsets.
     *
     * Note: We never run DiffUtil here because it is safe to assume that empty pages can never
     * become non-empty no matter what transformations they go through. [ProcessPageEventCallback]
     * events generated by this helper always drop contiguous sets of items because pages that
     * depend on multiple originalPageOffsets will always be the next closest page that's non-empty.
     */
    private fun dropPages(drop: PageEvent.Drop<T>, callback: ProcessPageEventCallback) {
        val oldSize = size

        if (drop.loadType == PREPEND) {
            val oldPlaceholdersBefore = placeholdersBefore

            // first update all state...
            val itemDropCount = dropPagesWithOffsets(drop.minPageOffset..drop.maxPageOffset)
            storageCount -= itemDropCount
            placeholdersBefore = drop.placeholdersRemaining

            // ... then trigger callbacks, so callbacks won't see inconsistent state
            // Trim or insert to expected size.
            val expectedSize = size
            val placeholdersToInsert = expectedSize - oldSize
            if (placeholdersToInsert > 0) {
                callback.onInserted(0, placeholdersToInsert)
            } else if (placeholdersToInsert < 0) {
                callback.onRemoved(0, -placeholdersToInsert)
            }

            // Compute the index of the first item that must be rebound as a placeholder.
            // If any placeholders were inserted above, we only need to send onChanged for the next
            // n = (drop.placeholdersRemaining - placeholdersToInsert) items. E.g., if two nulls
            // were inserted above, then the onChanged event can start from index = 2.
            // Note: In cases where more items were dropped than there were previously placeholders,
            // we can simply rebind n = drop.placeholdersRemaining items starting from position = 0.
            val firstItemIndex = maxOf(0, oldPlaceholdersBefore + placeholdersToInsert)
            // Compute the number of previously loaded items that were dropped and now need to be
            // updated to null. This computes the distance between firstItemIndex (inclusive),
            // and index of the last leading placeholder (inclusive) in the final list.
            val changeCount = drop.placeholdersRemaining - firstItemIndex
            if (changeCount > 0) {
                callback.onChanged(firstItemIndex, changeCount)
            }

            // Dropping from prepend direction implies NotLoading(endOfPaginationReached = false).
            callback.onStateUpdate(CombinedLoadStates.IDLE_SOURCE)
        } else {
            val oldPlaceholdersAfter = placeholdersAfter

            // first update all state...
            val itemDropCount = dropPagesWithOffsets(drop.minPageOffset..drop.maxPageOffset)
            storageCount -= itemDropCount
            placeholdersAfter = drop.placeholdersRemaining

            // ... then trigger callbacks, so callbacks won't see inconsistent state
            // Trim or insert to expected size.
            val expectedSize = size
            val placeholdersToInsert = expectedSize - oldSize
            if (placeholdersToInsert > 0) {
                callback.onInserted(oldSize, placeholdersToInsert)
            } else if (placeholdersToInsert < 0) {
                callback.onRemoved(oldSize + placeholdersToInsert, -placeholdersToInsert)
            }

            // Number of trailing placeholders in the list, before dropping, that were removed
            // above during size adjustment.
            val oldPlaceholdersRemoved = when {
                placeholdersToInsert < 0 -> minOf(oldPlaceholdersAfter, -placeholdersToInsert)
                else -> 0
            }
            // Compute the number of previously loaded items that were dropped and now need to be
            // updated to null. This subtracts the total number of existing placeholders in the
            // list, before dropping, that were not removed above during size adjustment, from
            // the total number of expected placeholders.
            val changeCount =
                drop.placeholdersRemaining - (oldPlaceholdersAfter - oldPlaceholdersRemoved)
            if (changeCount > 0) {
                callback.onChanged(
                    position = size - drop.placeholdersRemaining,
                    count = changeCount
                )
            }

            // Dropping from append direction implies NotLoading(endOfPaginationReached = false).
            callback.onStateUpdate(CombinedLoadStates.IDLE_SOURCE)
        }
    }

    internal companion object {
        private val INITIAL = PagePresenter<Any>(PageEvent.Insert.EMPTY_REFRESH_LOCAL)

        @Suppress("UNCHECKED_CAST", "SyntheticAccessor")
        internal fun <T : Any> initial(): PagePresenter<T> = INITIAL as PagePresenter<T>
    }

    /**
     * Callback to communicate events from [PagePresenter] to [PagingDataDiffer]
     */
    internal interface ProcessPageEventCallback {
        fun onChanged(position: Int, count: Int)
        fun onInserted(position: Int, count: Int)
        fun onRemoved(position: Int, count: Int)
        fun onStateUpdate(combinedLoadStates: CombinedLoadStates)
    }
}
