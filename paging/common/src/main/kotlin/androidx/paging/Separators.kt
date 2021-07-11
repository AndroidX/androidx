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

import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.LoadType.REFRESH
import androidx.paging.PageEvent.Drop
import androidx.paging.PageEvent.Insert
import androidx.paging.PageEvent.LoadStateUpdate
import androidx.paging.TerminalSeparatorType.FULLY_COMPLETE
import androidx.paging.TerminalSeparatorType.SOURCE_COMPLETE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Mode for configuring when terminal separators (header and footer) would be displayed by the
 * [insertSeparators], [insertHeaderItem] or [insertFooterItem] operators on [PagingData].
 */
public enum class TerminalSeparatorType {
    /**
     * Show terminal separators (header and footer) when both [PagingSource] and [RemoteMediator]
     * reaches the end of pagination.
     *
     * End of paginations occurs when [CombinedLoadStates] has set
     * [LoadState.endOfPaginationReached] to `true` for both [CombinedLoadStates.source] and
     * [CombinedLoadStates.mediator] in the [PREPEND] direction for the header and in the
     * [APPEND] direction for the footer.
     *
     * In cases where [RemoteMediator] isn't used, only [CombinedLoadStates.source] will be
     * considered.
     */
    FULLY_COMPLETE,

    /**
     * Show terminal separators (header and footer) as soon as [PagingSource] reaches the end of
     * pagination, regardless of [RemoteMediator]'s state.
     *
     * End of paginations occurs when [CombinedLoadStates] has set
     * [LoadState.endOfPaginationReached] to `true` for [CombinedLoadStates.source] in the [PREPEND]
     * direction for the header and in the [APPEND] direction for the footer.
     */
    SOURCE_COMPLETE,
}

/**
 * Create a TransformablePage with separators inside (ignoring edges)
 *
 * Separators between pages are handled outside of the page, see `Flow<PageEvent>.insertSeparators`.
 */
internal suspend fun <R : Any, T : R> TransformablePage<T>.insertInternalSeparators(
    generator: suspend (T?, T?) -> R?
): TransformablePage<R> {
    if (data.isEmpty()) {
        @Suppress("UNCHECKED_CAST")
        return this as TransformablePage<R>
    }

    val initialCapacity = data.size + 4 // extra space to avoid bigger allocations
    val outputList = ArrayList<R>(initialCapacity)
    val outputIndices = ArrayList<Int>(initialCapacity)

    outputList.add(data.first())
    outputIndices.add(hintOriginalIndices?.first() ?: 0)
    for (i in 1 until data.size) {
        val item = data[i]
        val separator = generator(data[i - 1], item)
        if (separator != null) {
            outputList.add(separator)
            outputIndices.add(i)
        }
        outputList.add(item)
        outputIndices.add(i)
    }
    return if (outputList.size == data.size) {
        /*
         * If we inserted no separators, just use original page.
         *
         * This isn't a particularly important optimization, but it does make tests easier to
         * write, since Insert event coming in is unchanged
         */
        @Suppress("UNCHECKED_CAST")
        this as TransformablePage<R>
    } else {
        TransformablePage(
            originalPageOffsets = originalPageOffsets,
            data = outputList,
            hintOriginalPageOffset = hintOriginalPageOffset,
            hintOriginalIndices = outputIndices
        )
    }
}

/**
 * Create a [TransformablePage] with the given separator (or empty, if the separator is null)
 */
internal fun <T : Any> separatorPage(
    separator: T,
    originalPageOffsets: IntArray,
    hintOriginalPageOffset: Int,
    hintOriginalIndex: Int
): TransformablePage<T> = TransformablePage(
    originalPageOffsets = originalPageOffsets,
    data = listOf(separator),
    hintOriginalPageOffset = hintOriginalPageOffset,
    hintOriginalIndices = listOf(hintOriginalIndex)
)

/**
 * Create a [TransformablePage] with the given separator, and add it if [separator] is non-null
 *
 * This is a helper to create separator pages that contain a single separator to be used to join
 * pages provided from stream.
 */
internal fun <T : Any> MutableList<TransformablePage<T>>.addSeparatorPage(
    separator: T?,
    originalPageOffsets: IntArray,
    hintOriginalPageOffset: Int,
    hintOriginalIndex: Int
) {
    if (separator == null) return

    val separatorPage = separatorPage(
        separator = separator,
        originalPageOffsets = originalPageOffsets,
        hintOriginalPageOffset = hintOriginalPageOffset,
        hintOriginalIndex = hintOriginalIndex
    )
    add(separatorPage)
}

/**
 * Create a [TransformablePage] with the given separator, and add it if [separator] is non-null
 *
 * This is a helper to create separator pages that contain a single separator to be used to join
 * pages provided from stream.
 */
internal fun <R : Any, T : R> MutableList<TransformablePage<R>>.addSeparatorPage(
    separator: R?,
    adjacentPageBefore: TransformablePage<T>?,
    adjacentPageAfter: TransformablePage<T>?,
    hintOriginalPageOffset: Int,
    hintOriginalIndex: Int
) {
    val beforeOffsets = adjacentPageBefore?.originalPageOffsets
    val afterOffsets = adjacentPageAfter?.originalPageOffsets
    addSeparatorPage(
        separator = separator,
        originalPageOffsets = when {
            beforeOffsets != null && afterOffsets != null -> {
                (beforeOffsets + afterOffsets).distinct().sorted().toIntArray()
            }
            beforeOffsets == null && afterOffsets != null -> afterOffsets
            beforeOffsets != null && afterOffsets == null -> beforeOffsets
            else -> throw IllegalArgumentException(
                "Separator page expected adjacentPageBefore or adjacentPageAfter, but both were" +
                    " null."
            )
        },
        hintOriginalPageOffset = hintOriginalPageOffset,
        hintOriginalIndex = hintOriginalIndex
    )
}

private class SeparatorState<R : Any, T : R>(
    val terminalSeparatorType: TerminalSeparatorType,
    val generator: suspend (before: T?, after: T?) -> R?
) {
    /**
     * Lookup table of previously emitted pages, that skips empty pages.
     *
     * This table is used to keep track of originalPageOffsets for separators that would span
     * across empty pages. It includes a simplified version of loaded pages which only has the
     * first and last item in each page to reduce memory pressure.
     *
     * Note: [TransformablePage] added to this stash must always have
     * [TransformablePage.originalPageOffsets] defined, since it needs to keep track of the
     * originalPageOffset of the last item.
     */
    val pageStash = mutableListOf<TransformablePage<T>>()

    /**
     * True if next insert event should be treated as terminal, as a previous terminal event was
     * empty and no items has been loaded yet.
     */
    var endTerminalSeparatorDeferred = false
    var startTerminalSeparatorDeferred = false

    val loadStates = MutableLoadStateCollection()
    var placeholdersBefore = 0
    var placeholdersAfter = 0

    var footerAdded = false
    var headerAdded = false

    @Suppress("UNCHECKED_CAST")
    suspend fun onEvent(event: PageEvent<T>): PageEvent<R> = when (event) {
        is Insert<T> -> onInsert(event)
        is Drop -> onDrop(event)
        is LoadStateUpdate -> onLoadStateUpdate(event)
    }.also {
        // validate internal state after each modification
        if (endTerminalSeparatorDeferred) {
            check(pageStash.isEmpty()) { "deferred endTerm, page stash should be empty" }
        }
        if (startTerminalSeparatorDeferred) {
            check(pageStash.isEmpty()) { "deferred startTerm, page stash should be empty" }
        }
    }

    fun Insert<T>.asRType(): Insert<R> {
        @Suppress("UNCHECKED_CAST")
        return this as Insert<R>
    }

    fun <T : Any> Insert<T>.terminatesStart(terminalSeparatorType: TerminalSeparatorType): Boolean {
        if (loadType == APPEND) {
            return startTerminalSeparatorDeferred
        }

        return when (terminalSeparatorType) {
            FULLY_COMPLETE -> {
                combinedLoadStates.source.prepend.endOfPaginationReached &&
                    combinedLoadStates.mediator?.prepend?.endOfPaginationReached != false
            }
            SOURCE_COMPLETE -> combinedLoadStates.source.prepend.endOfPaginationReached
        }
    }

    fun <T : Any> Insert<T>.terminatesEnd(terminalSeparatorType: TerminalSeparatorType): Boolean {
        if (loadType == PREPEND) {
            return endTerminalSeparatorDeferred
        }

        return when (terminalSeparatorType) {
            FULLY_COMPLETE -> {
                combinedLoadStates.source.append.endOfPaginationReached &&
                    combinedLoadStates.mediator?.append?.endOfPaginationReached != false
            }
            SOURCE_COMPLETE -> combinedLoadStates.source.append.endOfPaginationReached
        }
    }

    suspend fun onInsert(event: Insert<T>): Insert<R> {
        val eventTerminatesStart = event.terminatesStart(terminalSeparatorType)
        val eventTerminatesEnd = event.terminatesEnd(terminalSeparatorType)
        val eventEmpty = event.pages.all { it.data.isEmpty() }

        require(!headerAdded || event.loadType != PREPEND || eventEmpty) {
            "Additional prepend event after prepend state is done"
        }
        require(!footerAdded || event.loadType != APPEND || eventEmpty) {
            "Additional append event after append state is done"
        }

        // Update SeparatorState before we do any real work.
        loadStates.set(event.combinedLoadStates)
        // Append insert has placeholdersBefore = -1 as a placeholder value.
        if (event.loadType != APPEND) {
            placeholdersBefore = event.placeholdersBefore
        }
        // Prepend insert has placeholdersAfter = -1 as a placeholder value.
        if (event.loadType != PREPEND) {
            placeholdersAfter = event.placeholdersAfter
        }

        // Special-case handling for empty events when the page stash is empty as the logic after
        // this assumes we'll have some loaded items to use when generating separators, especially
        // in the header / footer case.
        if (eventEmpty) {
            // If event is non terminal no transformation necessary, just return it directly.
            if (!eventTerminatesStart && !eventTerminatesEnd) {
                return event.asRType()
            }

            // We only need to transform empty insert events if they would cause terminal
            // separators to get added. If both terminal separators are already added we can just
            // skip this and return the event directly.
            if (headerAdded && footerAdded) {
                return event.asRType()
            }

            // Only resolve separators for empty events if page stash is also empty, otherwise we
            // can use the regular flow since we have loaded items to depend on.
            if (pageStash.isEmpty()) {
                if (eventTerminatesStart && eventTerminatesEnd && !headerAdded && !footerAdded) {
                    // If event is empty and fully terminal, resolve a single separator.
                    val separator = generator(null, null)
                    endTerminalSeparatorDeferred = false
                    startTerminalSeparatorDeferred = false
                    headerAdded = true
                    footerAdded = true
                    return if (separator == null) {
                        event.asRType()
                    } else {
                        event.transformPages {
                            listOf(separatorPage(separator, intArrayOf(0), 0, 0))
                        }
                    }
                } else {
                    // can't insert the appropriate separator yet - defer!
                    if (eventTerminatesEnd && !footerAdded) {
                        endTerminalSeparatorDeferred = true
                    }
                    if (eventTerminatesStart && !headerAdded) {
                        startTerminalSeparatorDeferred = true
                    }
                    return event.asRType()
                }
            }
        }

        // If we've gotten to this point, that means the outgoing insert will have data.
        // Either this event has data, or the pageStash does.
        val outList = ArrayList<TransformablePage<R>>(event.pages.size)
        val stashOutList = ArrayList<TransformablePage<T>>(event.pages.size)

        var firstNonEmptyPage: TransformablePage<T>? = null
        var firstNonEmptyPageIndex: Int? = null
        var lastNonEmptyPage: TransformablePage<T>? = null
        var lastNonEmptyPageIndex: Int? = null
        if (!eventEmpty) {
            // Compute the first non-empty page index to be used as adjacent pages for creating
            // separator pages.
            // Note: We're guaranteed to have at least one non-empty page at this point.
            var pageIndex = 0
            while (pageIndex < event.pages.lastIndex && event.pages[pageIndex].data.isEmpty()) {
                pageIndex++
            }
            firstNonEmptyPageIndex = pageIndex
            firstNonEmptyPage = event.pages[pageIndex]

            // Compute the last non-empty page index to be used as adjacent pages for creating
            // separator pages.
            // Note: We're guaranteed to have at least one non-empty page at this point.
            pageIndex = event.pages.lastIndex
            while (pageIndex > 0 && event.pages[pageIndex].data.isEmpty()) {
                pageIndex--
            }
            lastNonEmptyPageIndex = pageIndex
            lastNonEmptyPage = event.pages[pageIndex]
        }

        // Header separator
        if (eventTerminatesStart && !headerAdded) {
            headerAdded = true

            // Using data from previous generation if event is empty, adjacent page otherwise.
            val pageAfter = if (eventEmpty) pageStash.first() else firstNonEmptyPage!!
            outList.addSeparatorPage(
                separator = generator(null, pageAfter.data.first()),
                adjacentPageBefore = null,
                adjacentPageAfter = pageAfter,
                hintOriginalPageOffset = pageAfter.hintOriginalPageOffset,
                hintOriginalIndex = pageAfter.hintOriginalIndices?.first() ?: 0
            )
        }

        // Create pages based on data in the event
        if (!eventEmpty) {
            // Add empty pages before [firstNonEmptyPageIndex] from event directly.
            for (pageIndex in 0 until firstNonEmptyPageIndex!!) {
                outList.add(event.pages[pageIndex].insertInternalSeparators(generator))
            }

            // Insert separator page between last stash and first non-empty event page if APPEND.
            if (event.loadType == APPEND && pageStash.isNotEmpty()) {
                val lastStash = pageStash.last()
                val separator = generator(lastStash.data.last(), firstNonEmptyPage!!.data.first())
                outList.addSeparatorPage(
                    separator = separator,
                    adjacentPageBefore = lastStash,
                    adjacentPageAfter = firstNonEmptyPage,
                    hintOriginalPageOffset = firstNonEmptyPage.hintOriginalPageOffset,
                    hintOriginalIndex = firstNonEmptyPage.hintOriginalIndices?.first() ?: 0
                )
            }

            // Add the first non-empty insert event page with separators inserted.
            stashOutList.add(transformablePageToStash(firstNonEmptyPage!!))
            outList.add(firstNonEmptyPage.insertInternalSeparators(generator))

            // Handle event pages that may be sparsely populated by empty pages.
            event.pages
                .subList(firstNonEmptyPageIndex, lastNonEmptyPageIndex!! + 1)
                // Note: If we enter reduce loop, pageBefore is guaranteed to be non-null.
                .reduce { pageBefore, page ->
                    if (page.data.isNotEmpty()) {
                        // Insert separator pages in between insert event pages.
                        val separator = generator(pageBefore.data.last(), page.data.first())
                        outList.addSeparatorPage(
                            separator = separator,
                            adjacentPageBefore = pageBefore,
                            adjacentPageAfter = page,
                            hintOriginalPageOffset = if (event.loadType == PREPEND) {
                                pageBefore.hintOriginalPageOffset
                            } else {
                                page.hintOriginalPageOffset
                            },
                            hintOriginalIndex = if (event.loadType == PREPEND) {
                                pageBefore.hintOriginalIndices?.last() ?: pageBefore.data.lastIndex
                            } else {
                                page.hintOriginalIndices?.first() ?: 0
                            }
                        )
                    }

                    if (page.data.isNotEmpty()) {
                        stashOutList.add(transformablePageToStash(page))
                    }
                    // Add the insert event page with separators inserted.
                    outList.add(page.insertInternalSeparators(generator))

                    // Current page becomes the next pageBefore on next iteration unless empty.
                    if (page.data.isNotEmpty()) page else pageBefore
                }

            // Insert separator page between first stash and last non-empty event page if PREPEND.
            if (event.loadType == PREPEND && pageStash.isNotEmpty()) {
                val pageAfter = pageStash.first()
                val separator = generator(lastNonEmptyPage!!.data.last(), pageAfter.data.first())
                outList.addSeparatorPage(
                    separator = separator,
                    adjacentPageBefore = lastNonEmptyPage,
                    adjacentPageAfter = pageAfter,
                    hintOriginalPageOffset = lastNonEmptyPage.hintOriginalPageOffset,
                    hintOriginalIndex = lastNonEmptyPage.hintOriginalIndices?.last()
                        ?: lastNonEmptyPage.data.lastIndex
                )
            }

            // Add empty pages after [lastNonEmptyPageIndex] from event directly.
            for (pageIndex in (lastNonEmptyPageIndex + 1)..event.pages.lastIndex) {
                outList.add(event.pages[pageIndex].insertInternalSeparators(generator))
            }
        }

        // Footer separator
        if (eventTerminatesEnd && !footerAdded) {
            footerAdded = true

            // Using data from previous generation if event is empty, adjacent page otherwise.
            val pageBefore = if (eventEmpty) pageStash.last() else lastNonEmptyPage!!
            outList.addSeparatorPage(
                separator = generator(pageBefore.data.last(), null),
                adjacentPageBefore = pageBefore,
                adjacentPageAfter = null,
                hintOriginalPageOffset = pageBefore.hintOriginalPageOffset,
                hintOriginalIndex = pageBefore.hintOriginalIndices?.last()
                    ?: pageBefore.data.lastIndex
            )
        }

        endTerminalSeparatorDeferred = false
        startTerminalSeparatorDeferred = false

        if (event.loadType == APPEND) {
            pageStash.addAll(stashOutList)
        } else {
            pageStash.addAll(0, stashOutList)
        }
        return event.transformPages { outList }
    }

    /**
     * Process a [Drop] event to update [pageStash] stage.
     */
    fun onDrop(event: Drop<T>): Drop<R> {
        loadStates.set(type = event.loadType, remote = false, state = NotLoading.Incomplete)
        if (event.loadType == PREPEND) {
            placeholdersBefore = event.placeholdersRemaining
            headerAdded = false
        } else if (event.loadType == APPEND) {
            placeholdersAfter = event.placeholdersRemaining
            footerAdded = false
        }

        if (pageStash.isEmpty()) {
            if (event.loadType == PREPEND) {
                startTerminalSeparatorDeferred = false
            } else {
                endTerminalSeparatorDeferred = false
            }
        }

        // Drop all stashes that depend on pageOffset being dropped.
        val pageOffsetsToDrop = event.minPageOffset..event.maxPageOffset
        pageStash.removeAll { stash ->
            stash.originalPageOffsets.any { pageOffsetsToDrop.contains(it) }
        }

        @Suppress("UNCHECKED_CAST")
        return event as Drop<R>
    }

    suspend fun onLoadStateUpdate(event: LoadStateUpdate<T>): PageEvent<R> {
        // Check for redundant LoadStateUpdate events to avoid unnecessary mapping to empty inserts
        // that might cause terminal separators to get added out of place.
        if (loadStates.snapshot() == event.combinedLoadStates) {
            @Suppress("UNCHECKED_CAST")
            return event as PageEvent<R>
        }

        // Determine which LoadType has changed in the event from the current loadStates
        var fromMediator = false
        var diffType = REFRESH
        var diffState: LoadState = NotLoading.Incomplete

        event.combinedLoadStates.forEach { type, isRemote, state ->
            if (loadStates.get(type, isRemote) != state) {
                fromMediator = isRemote
                diffType = type
                diffState = state
            }
        }

        loadStates.set(event.combinedLoadStates)

        // Transform terminal load state updates into empty inserts for header + footer support
        // when used with RemoteMediator. In cases where we defer adding a terminal separator,
        // RemoteMediator can report endOfPaginationReached via LoadStateUpdate event, which
        // isn't possible to add a separator to. Note: Adding a separate insert event also
        // doesn't work in the case where .insertSeparators() is called multiple times on the
        // same page event stream - we have to transform the terminating LoadStateUpdate event.
        if (diffType != REFRESH && fromMediator && diffState.endOfPaginationReached) {
            val emptyTerminalInsert: Insert<T> = if (diffType == PREPEND) {
                Insert.Prepend(
                    pages = emptyList(),
                    placeholdersBefore = placeholdersBefore,
                    combinedLoadStates = loadStates.snapshot()
                )
            } else {
                Insert.Append(
                    pages = emptyList(),
                    placeholdersAfter = placeholdersAfter,
                    combinedLoadStates = loadStates.snapshot()
                )
            }

            return onInsert(emptyTerminalInsert)
        }

        @Suppress("UNCHECKED_CAST")
        return event as PageEvent<R>
    }

    private fun <T : Any> transformablePageToStash(
        originalPage: TransformablePage<T>
    ): TransformablePage<T> {
        return TransformablePage(
            originalPageOffsets = originalPage.originalPageOffsets,
            data = listOf(originalPage.data.first(), originalPage.data.last()),
            hintOriginalPageOffset = originalPage.hintOriginalPageOffset,
            hintOriginalIndices = listOf(
                originalPage.hintOriginalIndices?.first() ?: 0,
                originalPage.hintOriginalIndices?.last() ?: originalPage.data.lastIndex
            )
        )
    }
}

/**
 * This is intentionally not named insertSeparators to avoid creating a clashing import
 * with PagingData.insertSeparators, which is public
 */
internal fun <T : R, R : Any> Flow<PageEvent<T>>.insertEventSeparators(
    terminalSeparatorType: TerminalSeparatorType,
    generator: suspend (T?, T?) -> R?
): Flow<PageEvent<R>> {
    val separatorState = SeparatorState(terminalSeparatorType) { before: T?, after: T? ->
        generator(before, after)
    }
    return map { separatorState.onEvent(it) }
}