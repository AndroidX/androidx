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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/**
 * An intermediate flow producer that flattens previous page events and gives any new downstream
 * just those events instead of the full history.
 */
internal class CachedPageEventFlow<T : Any>(
    src: Flow<PageEvent<T>>,
    scope: CoroutineScope
) {
    private val pageController = FlattenedPageController<T>()

    /**
     * Flow<PageEvent> can be collected only once so this goes from active to passive only.
     */
    private val active = MutableStateFlow(true)

    /**
     * Shared flow for upstream.
     *
     * Note that shared flows cannot be completed, hence we use a custom wrapper type
     * [FlattenedPageEventStorage.UpstreamMessage] to specify end of stream.
     */
    private val sharedSrc = active.simpleFlatMapLatest { active ->
        if (active) {
            src.withIndex().onEach(pageController::record)
                .map {
                    FlattenedPageEventStorage.UpstreamMessage.Value(it)
                }
                .onCompletion {
                    // close it so that we can dispatch the completed message.
                    this@CachedPageEventFlow.close()
                }
        } else {
            // send a complete message to notify end of stream
            flowOf(FlattenedPageEventStorage.UpstreamMessage.Completed)
        }
    }.shareIn(
        scope = scope,
        started = SharingStarted.Lazily, // never stop, we'll be closed manually
        // Don't replay for events, we have our own condensed replay mechanism that merges events.
        // We only replay once to account for the Completed terminal event. If we don't, shared flow
        // would never emit any value hence the upstream would look as if it is open even though
        // it is not.
        // note that not replaying does not mean values are not buffered. Buffering still
        // happens as long as the subscriber has arrived in time before the value is emitted.
        // [downstreamFlow] takes care of ordering them behind a mutex.
        // https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/share-in.html
        replay = 1
    )

    fun close() {
        active.compareAndSet(expect = true, update = false)
    }

    val downstreamFlow = simpleChannelFlow<PageEvent<T>> {
        // get a new snapshot. this will immediately hook us to the upstream channel
        val snapshot = pageController.createTemporaryDownstream()
        var lastReceivedHistoryIndex = Int.MIN_VALUE
        // first, dispatch everything in the snapshot
        // these are events that sync us to the current state + other events that might be
        // emitted by the upstream between the time of snapshot and us connecting to the upstream
        val historyCollection = launch {
            snapshot.consumeHistory().collect {
                lastReceivedHistoryIndex = it.index
                send(it.value)
            }
        }
        val activeStreamCollection = launch {
            sharedSrc.takeWhile {
                it !== FlattenedPageEventStorage.UpstreamMessage.Completed
            }.onCompletion {
                // if main upstream finishes, make sure to close the history stream
                // otherwise it might stay open forever
                snapshot.close()
            }.collect {
                // we got a value from real stream, close history
                snapshot.close()
                // wait for it to be done so that all events there are dispatched
                historyCollection.join()
                // now if it is new enough, emit it, otherwise, skip it
                if (it is FlattenedPageEventStorage.UpstreamMessage.Value<*>) {
                    if (it.event.index > lastReceivedHistoryIndex) {
                        @Suppress("UNCHECKED_CAST")
                        send(it.event.value as PageEvent<T>)
                    }
                }
            }
        }
        joinAll(activeStreamCollection, historyCollection)
    }
}

/**
 * This intermediate class is used to ensure new downstream does not miss any events.
 *
 * There is a race condition where, while it is collecting the initial state, there might be more
 * events dispatched by the real source which will be missed because new downstream is not
 * subscribed to the real upstream yet.
 *
 * FlattenedPageController tracks these temporary channels and sends each event into them as well
 * so that they are not lost until the new downstream connects to the real upstream.
 * After that connection is established, this channel is closed.
 *
 * Each value is indexed to avoid sending the same event multiple times.
 */
private class TemporaryDownstream<T : Any> {
    /**
     * List of events that sync us to current state + any other events that might arrive while
     * we are doing the initial sync
     */
    private val historyChannel: Channel<IndexedValue<PageEvent<T>>> = Channel(Channel.UNLIMITED)

    fun consumeHistory() = historyChannel.consumeAsFlow()

    /**
     * Tries to send the value to the history channel.
     * Returns false if send fails (e.g. channel is already closed)
     */
    suspend fun send(event: IndexedValue<PageEvent<T>>): Boolean {
        return try {
            historyChannel.send(event)
            true
        } catch (closed: ClosedSendChannelException) {
            false
        }
    }

    fun close() {
        historyChannel.close()
    }
}

private class FlattenedPageController<T : Any> {
    private val list = FlattenedPageEventStorage<T>()
    private var snapshots = listOf<TemporaryDownstream<T>>()
    private val lock = Mutex()
    private var maxEventIndex = -1

    /**
     * Record the event.
     * This sends the event into storage but also into any other active TemporaryDownstream.
     */
    suspend fun record(event: IndexedValue<PageEvent<T>>) {
        lock.withLock {
            maxEventIndex = event.index
            list.add(event.value)
            snapshots = snapshots.filter {
                it.send(event)
            }
        }
    }

    /**
     * Creates a temporary downstream which will have events that are necessary to sync the
     * current state + any other events that might arrive while that sync is in progress.
     */
    suspend fun createTemporaryDownstream(): TemporaryDownstream<T> {
        return lock.withLock {
            // send the current state into the temporary downstream while we are inside the mutex
            // this ensure that we don't process any new events until we copy all initial events
            // into the channel of temporary downstream
            val snap = TemporaryDownstream<T>()
            // condensed events to bring downstream up to the current state
            val catchupEvents = list.getAsEvents()
            // make sure the indices here match the indices coming from upstream so that if the same
            // event shows up in both places, we won't dispatch it twice.
            // we want the last event's index to match the latest event index we've received so
            // that downstream can ignore that event if they receive it also from the original
            // upstream.
            val startEventIndex = maxEventIndex - catchupEvents.size + 1
            catchupEvents.forEachIndexed { index, pageEvent ->
                snap.send(
                    IndexedValue(
                        index = startEventIndex + index,
                        value = pageEvent
                    )
                )
            }
            snap
        }
    }
}

/**
 * Keeps a list of page events and can dispatch them at once as PageEvent instead of multiple
 * events.
 *
 * There is no synchronization in this code so it should be used with locks around if necessary.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal class FlattenedPageEventStorage<T : Any> {
    private var placeholdersBefore: Int = 0
    private var placeholdersAfter: Int = 0
    private val pages = ArrayDeque<TransformablePage<T>>()

    /**
     * Note - this is initialized without remote state, since we don't know if we have remote
     * data once we start getting events. This is fine, since downstream needs to handle this
     * anyway - remote state being added after initial, empty, PagingData.
     */
    private val loadStates = MutableLoadStateCollection()
    private var prevLoadStates: CombinedLoadStates = CombinedLoadStates.IDLE_SOURCE
    fun add(event: PageEvent<T>) {
        when (event) {
            is PageEvent.Insert<T> -> handleInsert(event)
            is PageEvent.Drop<T> -> handlePageDrop(event)
            is PageEvent.LoadStateUpdate<T> -> handleLoadStateUpdate(event)
        }
    }

    private fun handlePageDrop(event: PageEvent.Drop<T>) {
        // TODO: include state in drop event for simplicity, instead of reconstructing behavior.
        //  This allows upstream to control how drop affects states (e.g. letting drop affect both
        //  remote and local)
        prevLoadStates = loadStates.snapshot()
        loadStates.set(event.loadType, false, LoadState.NotLoading.Incomplete)

        when (event.loadType) {
            LoadType.PREPEND -> {
                placeholdersBefore = event.placeholdersRemaining
                repeat(event.pageCount) { pages.removeFirst() }
            }
            LoadType.APPEND -> {
                placeholdersAfter = event.placeholdersRemaining
                repeat(event.pageCount) { pages.removeLast() }
            }
            else -> throw IllegalArgumentException("Page drop type must be prepend or append")
        }
    }

    private fun handleInsert(event: PageEvent.Insert<T>) {
        prevLoadStates = loadStates.snapshot()
        loadStates.set(event.combinedLoadStates)
        when (event.loadType) {
            LoadType.REFRESH -> {
                pages.clear()
                placeholdersAfter = event.placeholdersAfter
                placeholdersBefore = event.placeholdersBefore
                pages.addAll(event.pages)
            }
            LoadType.PREPEND -> {
                placeholdersBefore = event.placeholdersBefore
                (event.pages.size - 1 downTo 0).forEach {
                    pages.addFirst(event.pages[it])
                }
            }
            LoadType.APPEND -> {
                placeholdersAfter = event.placeholdersAfter
                pages.addAll(event.pages)
            }
        }
    }

    private fun handleLoadStateUpdate(event: PageEvent.LoadStateUpdate<T>) {
        prevLoadStates = loadStates.snapshot()
        loadStates.set(event.combinedLoadStates)
    }

    fun getAsEvents(): List<PageEvent<T>> {
        val events = mutableListOf<PageEvent<T>>()
        if (pages.isNotEmpty()) {
            events.add(
                PageEvent.Insert.Refresh(
                    pages = pages.toList(),
                    placeholdersBefore = placeholdersBefore,
                    placeholdersAfter = placeholdersAfter,
                    combinedLoadStates = loadStates.snapshot()
                )
            )
        } else {
            if (PageEvent.LoadStateUpdate.canDispatchWithoutInsert(
                    prevLoadStates, loadStates.snapshot()
                )
            ) {
                events.add(PageEvent.LoadStateUpdate(loadStates.snapshot()))
            }
        }

        return events
    }

    /**
     * Message type for the shared stream
     */
    sealed class UpstreamMessage {
        object Completed : UpstreamMessage()
        class Value<T : Any>(val event: IndexedValue<PageEvent<T>>) : UpstreamMessage()
    }
}
