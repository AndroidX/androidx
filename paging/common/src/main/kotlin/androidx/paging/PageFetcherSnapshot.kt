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

import androidx.annotation.VisibleForTesting
import androidx.paging.LoadState.Error
import androidx.paging.LoadState.Loading
import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.LoadType.REFRESH
import androidx.paging.PageEvent.LoadStateUpdate
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingSource.LoadResult.Page
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds a generation of pageable data, a snapshot of data loaded by [PagingSource]. An instance
 * of [PageFetcherSnapshot] and its corresponding [PageFetcherSnapshotState] should be launched
 * within a scope that is cancelled when [PagingSource.invalidate] is called.
 */
internal class PageFetcherSnapshot<Key : Any, Value : Any>(
    internal val initialKey: Key?,
    internal val pagingSource: PagingSource<Key, Value>,
    private val config: PagingConfig,
    private val retryFlow: Flow<Unit>,
    private val triggerRemoteRefresh: Boolean = false,
    val remoteMediatorConnection: RemoteMediatorConnection<Key, Value>? = null,
    private val previousPagingState: PagingState<Key, Value>? = null,
    private val invalidate: () -> Unit = {},
) {
    init {
        require(config.jumpThreshold == COUNT_UNDEFINED || pagingSource.jumpingSupported) {
            "PagingConfig.jumpThreshold was set, but the associated PagingSource has not marked " +
                "support for jumps by overriding PagingSource.jumpingSupported to true."
        }
    }

    private val hintSharedFlow = MutableSharedFlow<ViewportHint>(replay = 1)
    private var lastHint: ViewportHint.Access? = null

    private val pageEventChCollected = AtomicBoolean(false)
    private val pageEventCh = Channel<PageEvent<Value>>(BUFFERED)
    private val stateHolder = PageFetcherSnapshotState.Holder<Key, Value>(config = config)

    private val pageEventChannelFlowJob = Job()

    val pageEventFlow: Flow<PageEvent<Value>> = cancelableChannelFlow(pageEventChannelFlowJob) {
        check(pageEventChCollected.compareAndSet(false, true)) {
            "Attempt to collect twice from pageEventFlow, which is an illegal operation. Did you " +
                "forget to call Flow<PagingData<*>>.cachedIn(coroutineScope)?"
        }

        // Start collection on pageEventCh, which the rest of this class uses to send PageEvents
        // to this flow.
        launch {
            pageEventCh.consumeAsFlow().collect {
                // Protect against races where a subsequent call to submitData invoked close(),
                // but a pageEvent arrives after closing causing ClosedSendChannelException.
                try {
                    send(it)
                } catch (e: ClosedSendChannelException) {
                    // Safe to drop PageEvent here, since collection has been cancelled.
                }
            }
        }

        // Wrap collection behind a RendezvousChannel to prevent the RetryChannel from buffering
        // retry signals.
        val retryChannel = Channel<Unit>(Channel.RENDEZVOUS)
        launch { retryFlow.collect { retryChannel.trySend(it) } }

        // Start collection on retry signals.
        launch {
            retryChannel.consumeAsFlow()
                .collect {
                    val (sourceLoadStates, remotePagingState) = stateHolder.withLock { state ->
                        state.sourceLoadStates to state.currentPagingState(lastHint)
                    }
                    // tell remote mediator to retry and it will trigger necessary work / change
                    // its state as necessary.
                    remoteMediatorConnection?.retryFailed(remotePagingState)
                    // change source (local) states
                    sourceLoadStates.forEach { loadType, loadState ->
                        if (loadState !is Error) return@forEach

                        // Reset error state before sending hint.
                        if (loadType != REFRESH) {
                            stateHolder.withLock { state -> state.setLoading(loadType) }
                        }

                        retryLoadError(
                            loadType = loadType,
                            viewportHint = when (loadType) {
                                // ViewportHint is only used when retrying source PREPEND / APPEND.
                                REFRESH -> null
                                else -> stateHolder.withLock { state ->
                                    state.failedHintsByLoadType[loadType]
                                }
                            }
                        )

                        // If retrying REFRESH from PagingSource succeeds, start collection on
                        // ViewportHints for PREPEND / APPEND loads.
                        if (loadType == REFRESH) {
                            val newRefreshState = stateHolder.withLock { state ->
                                state.sourceLoadStates.get(REFRESH)
                            }

                            if (newRefreshState !is Error) {
                                startConsumingHints()
                            }
                        }
                    }
                }
        }

        if (triggerRemoteRefresh) {
            remoteMediatorConnection?.let {
                val pagingState = previousPagingState ?: stateHolder.withLock { state ->
                    state.currentPagingState(null)
                }
                it.requestLoad(REFRESH, pagingState)
            }
        }

        // Setup finished, start the initial load even if RemoteMediator throws an error.
        doInitialLoad()

        // Only start collection on ViewportHints if the initial load succeeded.
        if (stateHolder.withLock { state -> state.sourceLoadStates.get(REFRESH) } !is Error) {
            startConsumingHints()
        }
    }

    @Suppress("SuspendFunctionOnCoroutineScope")
    private suspend fun retryLoadError(
        loadType: LoadType,
        viewportHint: ViewportHint?
    ) {
        when (loadType) {
            REFRESH -> {
                doInitialLoad()
            }
            else -> {
                check(viewportHint != null) {
                    "Cannot retry APPEND / PREPEND load on PagingSource without ViewportHint"
                }

                hintSharedFlow.tryEmit(viewportHint)
            }
        }
    }

    fun accessHint(viewportHint: ViewportHint) {
        if (viewportHint is ViewportHint.Access) {
            lastHint = viewportHint
        }

        hintSharedFlow.tryEmit(viewportHint)
    }

    fun close() {
        pageEventChannelFlowJob.cancel()
    }

    suspend fun currentPagingState(): PagingState<Key, Value> {
        return stateHolder.withLock { state -> state.currentPagingState(lastHint) }
    }

    private fun CoroutineScope.startConsumingHints() {
        // Pseudo-tiling via invalidation on jumps.
        if (config.jumpThreshold != COUNT_UNDEFINED) {
            launch {
                hintSharedFlow
                    .filter { hint ->
                        hint.presentedItemsBefore * -1 > config.jumpThreshold ||
                            hint.presentedItemsAfter * -1 > config.jumpThreshold
                    }
                    .collectLatest { invalidate() }
            }
        }

        launch {
            stateHolder.withLock { state -> state.consumePrependGenerationIdAsFlow() }
                .collectAsGenerationalViewportHints(PREPEND)
        }

        launch {
            stateHolder.withLock { state -> state.consumeAppendGenerationIdAsFlow() }
                .collectAsGenerationalViewportHints(APPEND)
        }
    }

    /**
     * Maps a [Flow] of generation ids from [PageFetcherSnapshotState] to [ViewportHint]s from
     * [hintSharedFlow] with back-pressure handling via conflation by prioritizing hints which
     * either update presenter state or those that would load the most items.
     *
     * @param loadType [PREPEND] or [APPEND]
     */
    private suspend fun Flow<Int>.collectAsGenerationalViewportHints(
        loadType: LoadType
    ) = simpleFlatMapLatest { generationId ->
        // Reset state to Idle and setup a new flow for consuming incoming load hints.
        // Subsequent generationIds are normally triggered by cancellation.
        stateHolder.withLock { state ->
            // Skip this generationId of loads if there is no more to load in this
            // direction. In the case of the terminal page getting dropped, a new
            // generationId will be sent after load state is updated to Idle.
            if (state.sourceLoadStates.get(loadType) == NotLoading.Complete) {
                return@simpleFlatMapLatest flowOf()
            } else if (state.sourceLoadStates.get(loadType) !is Error) {
                state.setSourceLoadState(loadType, NotLoading.Incomplete)
            }
        }

        hintSharedFlow
            // Prevent infinite loop when competing PREPEND / APPEND cancel each other
            .drop(if (generationId == 0) 0 else 1)
            .map { hint -> GenerationalViewportHint(generationId, hint) }
    }
        // Prioritize new hints that would load the maximum number of items.
        .simpleRunningReduce { previous, next ->
            if (next.shouldPrioritizeOver(previous, loadType)) next else previous
        }
        .conflate()
        .collect { generationalHint ->
            doLoad(loadType, generationalHint)
        }

    private fun loadParams(loadType: LoadType, key: Key?) = LoadParams.create(
        loadType = loadType,
        key = key,
        loadSize = if (loadType == REFRESH) config.initialLoadSize else config.pageSize,
        placeholdersEnabled = config.enablePlaceholders,
    )

    private suspend fun doInitialLoad() {
        stateHolder.withLock { state -> state.setLoading(REFRESH) }

        val params = loadParams(REFRESH, initialKey)
        when (val result = pagingSource.load(params)) {
            is Page<Key, Value> -> {
                val insertApplied = stateHolder.withLock { state ->
                    state.insert(0, REFRESH, result)
                }

                // Update loadStates which are sent along with this load's Insert PageEvent.
                stateHolder.withLock { state ->
                    state.setSourceLoadState(REFRESH, NotLoading.Incomplete)
                    if (result.prevKey == null) {
                        state.setSourceLoadState(
                            type = PREPEND,
                            newState = NotLoading.Complete
                        )
                    }
                    if (result.nextKey == null) {
                        state.setSourceLoadState(
                            type = APPEND,
                            newState = NotLoading.Complete
                        )
                    }
                }

                // Send insert event after load state updates, so that endOfPaginationReached is
                // correctly reflected in the insert event. Note that we only send the event if the
                // insert was successfully applied in the case of cancellation due to page dropping.
                if (insertApplied) {
                    stateHolder.withLock { state ->
                        with(state) {
                            pageEventCh.send(result.toPageEvent(REFRESH))
                        }
                    }
                }

                // Launch any RemoteMediator boundary calls after applying initial insert.
                if (remoteMediatorConnection != null) {
                    if (result.prevKey == null || result.nextKey == null) {
                        val pagingState = stateHolder.withLock { state ->
                            state.currentPagingState(lastHint)
                        }

                        if (result.prevKey == null) {
                            remoteMediatorConnection.requestLoad(PREPEND, pagingState)
                        }

                        if (result.nextKey == null) {
                            remoteMediatorConnection.requestLoad(APPEND, pagingState)
                        }
                    }
                }
            }
            is LoadResult.Error -> stateHolder.withLock { state ->
                val loadState = Error(result.throwable)
                if (state.setSourceLoadState(REFRESH, loadState)) {
                    pageEventCh.send(
                        LoadStateUpdate(state.combinedLoadStates)
                    )
                }
            }
        }
    }

    // TODO: Consider making this a transform operation which emits PageEvents
    private suspend fun doLoad(
        loadType: LoadType,
        generationalHint: GenerationalViewportHint
    ) {
        require(loadType != REFRESH) { "Use doInitialLoad for LoadType == REFRESH" }

        // If placeholder counts differ between the hint and PageFetcherSnapshotState, then
        // assume fetcher is ahead of presenter and account for the difference in itemsLoaded.
        var itemsLoaded = 0
        stateHolder.withLock { state ->
            when (loadType) {
                PREPEND -> {
                    var firstPageIndex =
                        state.initialPageIndex + generationalHint.hint.originalPageOffsetFirst - 1

                    // If the pages before the first page in presenter have been dropped in
                    // fetcher, then we cannot count them towards loadedItems.
                    if (firstPageIndex > state.pages.lastIndex) {
                        itemsLoaded += config.pageSize * (firstPageIndex - state.pages.lastIndex)
                        firstPageIndex = state.pages.lastIndex
                    }

                    for (pageIndex in 0..firstPageIndex) {
                        itemsLoaded += state.pages[pageIndex].data.size
                    }
                }
                APPEND -> {
                    var lastPageIndex =
                        state.initialPageIndex + generationalHint.hint.originalPageOffsetLast + 1

                    // If the pages after the last page in presenter have been dropped in
                    // fetcher, then we cannot count them towards loadedItems.
                    if (lastPageIndex < 0) {
                        itemsLoaded += config.pageSize * -lastPageIndex
                        lastPageIndex = 0
                    }

                    for (pageIndex in lastPageIndex..state.pages.lastIndex) {
                        itemsLoaded += state.pages[pageIndex].data.size
                    }
                }
                REFRESH -> throw IllegalStateException("Use doInitialLoad for LoadType == REFRESH")
            }
        }

        var loadKey: Key? = stateHolder.withLock { state ->
            state.nextLoadKeyOrNull(
                loadType,
                generationalHint.generationId,
                generationalHint.presentedItemsBeyondAnchor(loadType) + itemsLoaded,
            )?.also { state.setLoading(loadType) }
        }

        // Keep track of whether endOfPaginationReached so we can update LoadState accordingly when
        // this load loop terminates due to fulfilling prefetchDistance.
        var endOfPaginationReached = false
        loop@ while (loadKey != null) {
            val params = loadParams(loadType, loadKey)
            val result: LoadResult<Key, Value> = pagingSource.load(params)
            when (result) {
                is Page<Key, Value> -> {
                    // First, check for common error case where the same key is re-used to load
                    // new pages, often resulting in infinite loops.
                    val nextKey = when (loadType) {
                        PREPEND -> result.prevKey
                        APPEND -> result.nextKey
                        else -> throw IllegalArgumentException(
                            "Use doInitialLoad for LoadType == REFRESH"
                        )
                    }

                    check(pagingSource.keyReuseSupported || nextKey != loadKey) {
                        val keyFieldName = if (loadType == PREPEND) "prevKey" else "nextKey"
                        """The same value, $loadKey, was passed as the $keyFieldName in two
                            | sequential Pages loaded from a PagingSource. Re-using load keys in
                            | PagingSource is often an error, and must be explicitly enabled by
                            | overriding PagingSource.keyReuseSupported.
                            """.trimMargin()
                    }

                    val insertApplied = stateHolder.withLock { state ->
                        state.insert(generationalHint.generationId, loadType, result)
                    }

                    // Break if insert was skipped due to cancellation
                    if (!insertApplied) break@loop

                    itemsLoaded += result.data.size

                    // Set endOfPaginationReached to false if no more data to load in current
                    // direction.
                    if ((loadType == PREPEND && result.prevKey == null) ||
                        (loadType == APPEND && result.nextKey == null)
                    ) {
                        endOfPaginationReached = true
                    }
                }
                is LoadResult.Error -> {
                    stateHolder.withLock { state ->
                        val loadState = Error(result.throwable)
                        if (state.setSourceLoadState(loadType, loadState)) {
                            pageEventCh.send(LoadStateUpdate(state.combinedLoadStates))
                        }

                        // Save the hint for retry on incoming retry signal, typically sent from
                        // user interaction.
                        state.failedHintsByLoadType[loadType] = generationalHint.hint
                    }
                    return
                }
            }

            val dropType = when (loadType) {
                PREPEND -> APPEND
                else -> PREPEND
            }

            stateHolder.withLock { state ->
                state.dropEventOrNull(dropType, generationalHint.hint)?.let { event ->
                    state.drop(event)
                    pageEventCh.send(event)
                }

                loadKey = state.nextLoadKeyOrNull(
                    loadType,
                    generationalHint.generationId,
                    generationalHint.presentedItemsBeyondAnchor(loadType) + itemsLoaded,
                )

                // Update load state to success if this is the final load result for this
                // load hint, and only if we didn't error out.
                if (loadKey == null && state.sourceLoadStates.get(loadType) !is Error) {
                    state.setSourceLoadState(
                        type = loadType,
                        newState = when {
                            endOfPaginationReached -> NotLoading.Complete
                            else -> NotLoading.Incomplete
                        }
                    )
                }

                // Send page event for successful insert, now that PagerState has been updated.
                val pageEvent = with(state) {
                    result.toPageEvent(loadType)
                }

                pageEventCh.send(pageEvent)
            }

            val endsPrepend = params is LoadParams.Prepend && result.prevKey == null
            val endsAppend = params is LoadParams.Append && result.nextKey == null
            if (remoteMediatorConnection != null && (endsPrepend || endsAppend)) {
                val pagingState = stateHolder.withLock { state ->
                    state.currentPagingState(lastHint)
                }

                if (endsPrepend) {
                    remoteMediatorConnection.requestLoad(PREPEND, pagingState)
                }

                if (endsAppend) {
                    remoteMediatorConnection.requestLoad(APPEND, pagingState)
                }
            }
        }
    }

    private suspend fun PageFetcherSnapshotState<Key, Value>.setLoading(loadType: LoadType) {
        if (setSourceLoadState(loadType, Loading)) {
            pageEventCh.send(
                LoadStateUpdate(combinedLoadStates)
            )
        }
    }

    /**
     * The next load key for a [loadType] or `null` if we should stop loading in that direction.
     */
    private fun PageFetcherSnapshotState<Key, Value>.nextLoadKeyOrNull(
        loadType: LoadType,
        generationId: Int,
        presentedItemsBeyondAnchor: Int
    ): Key? {
        if (generationId != generationId(loadType)) return null
        // Skip load if in error state, unless retrying.
        if (sourceLoadStates.get(loadType) is Error) return null

        // Skip loading if prefetchDistance has been fulfilled.
        if (presentedItemsBeyondAnchor >= config.prefetchDistance) return null

        return if (loadType == PREPEND) {
            pages.first().prevKey
        } else {
            pages.last().nextKey
        }
    }
}

/**
 * Generation of cancel token not [PageFetcherSnapshot]. [generationId] is used to differentiate
 * between loads from jobs that have been cancelled, but continued to run to completion.
 */
@VisibleForTesting
internal data class GenerationalViewportHint(val generationId: Int, val hint: ViewportHint) {
    /**
     * @return Count of presented items between [hint], and either:
     *  * the beginning of the list if [loadType] == PREPEND
     *  * the end of the list if loadType == APPEND
     */
    internal fun presentedItemsBeyondAnchor(loadType: LoadType): Int = when (loadType) {
        REFRESH -> throw IllegalArgumentException(
            "Cannot get presentedItems for loadType: REFRESH"
        )
        PREPEND -> hint.presentedItemsBefore
        APPEND -> hint.presentedItemsAfter
    }
}

/**
 * Helper for [GenerationalViewportHint] prioritization in cases where item accesses are being sent
 * to PageFetcherSnapshot] faster than they can be processed. A [GenerationalViewportHint] is
 * prioritized if it represents an update to presenter state or if it would cause
 * [PageFetcherSnapshot] to load more items.
 *
 * @param previous [GenerationalViewportHint] that would normally be processed next if [this]
 * [GenerationalViewportHint] was not sent.
 *
 * @return `true` if [this] [GenerationalViewportHint] should be prioritized over [previous].
 */
internal fun GenerationalViewportHint.shouldPrioritizeOver(
    previous: GenerationalViewportHint,
    loadType: LoadType
): Boolean {
    return when {
        // Prioritize hints from new generations, which increments after dropping.
        generationId > previous.generationId -> true
        // Prioritize Access hints over Initialize hints
        previous.hint is ViewportHint.Initial && hint is ViewportHint.Access -> true
        hint is ViewportHint.Initial && previous.hint is ViewportHint.Access -> false
        // Prioritize hints from most recent presenter state
        hint.originalPageOffsetFirst != previous.hint.originalPageOffsetFirst -> true
        hint.originalPageOffsetLast != previous.hint.originalPageOffsetLast -> true
        // Prioritize hints that would load the most items in PREPEND direction.
        loadType == PREPEND && previous.hint.presentedItemsBefore < hint.presentedItemsBefore -> {
            false
        }
        // Prioritize hints that would load the most items in APPEND direction.
        loadType == APPEND && previous.hint.presentedItemsAfter < hint.presentedItemsAfter -> {
            false
        }
        else -> true
    }
}