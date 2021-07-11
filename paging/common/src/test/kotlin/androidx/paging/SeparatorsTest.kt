/*
 * Copyright 2020 The Android Open Source Project
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

import androidx.paging.LoadState.Loading
import androidx.paging.LoadState.NotLoading
import androidx.paging.LoadType.APPEND
import androidx.paging.LoadType.PREPEND
import androidx.paging.PageEvent.Drop
import androidx.paging.PageEvent.Insert.Companion.Append
import androidx.paging.PageEvent.Insert.Companion.Prepend
import androidx.paging.PageEvent.Insert.Companion.Refresh
import androidx.paging.PageEvent.LoadStateUpdate
import androidx.paging.TerminalSeparatorType.FULLY_COMPLETE
import androidx.paging.TerminalSeparatorType.SOURCE_COMPLETE
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals

private fun <T : Any> List<PageEvent<T>>.getItems() = mapNotNull { event ->
    when (event) {
        is PageEvent.Insert<T> -> event.pages.map { it.data }
        else -> null
    }
}

/**
 * Simpler assert which only concerns itself with page data, all other event types are ignored.
 */
private fun <T : Any> assertInsertData(
    expected: List<PageEvent<T>>,
    actual: List<PageEvent<T>>
) {
    @Suppress("UNCHECKED_CAST")
    assertEquals(
        expected.getItems(),
        actual.getItems()
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class SeparatorsTest {
    @Test
    fun refreshFull() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(
                        listOf("a2", "b1"),
                        listOf("c1", "c2")
                    ).toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("a2", "B", "b1"),
                            hintOriginalPageOffset = 0,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1", "c2")
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun refreshStartFull() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("c1")
            ).toTransformablePages(),
            placeholdersBefore = 0,
            placeholdersAfter = 1,
            combinedLoadStates = localLoadStatesOf()
        )

        assertThat(
            flowOf(
                refresh,
                Prepend(
                    pages = listOf(
                        listOf("a1", "b1"),
                        listOf("b2", "b3")
                    ).toTransformablePages(2),
                    placeholdersBefore = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-2),
                            data = listOf("a1", "B", "b1"),
                            hintOriginalPageOffset = -2,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("b2", "b3")
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1, 0),
                            data = listOf("C"),
                            hintOriginalPageOffset = -1,
                            hintOriginalIndices = listOf(1)
                        )
                    ),
                    placeholdersBefore = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun refreshEndFull() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("a1", "a2")
            ).toTransformablePages(),
            placeholdersBefore = 0,
            placeholdersAfter = 1,
            combinedLoadStates = localLoadStatesOf()
        )
        assertThat(
            flowOf(
                refresh,
                Append(
                    pages = listOf(
                        listOf("c1", "d1"),
                        listOf("d2", "d3")
                    ).toTransformablePages(-1),
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("c1", "D", "d1"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = 2,
                            data = listOf("d2", "d3")
                        )
                    ),
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun refreshDropFull() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(
                        listOf("a1"),
                        listOf("a2")
                    ).toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                ),
                Drop(APPEND, 1, 1, 4)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1")
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("a2")
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                ),
                Drop<String>(APPEND, 1, 1, 4)
            )
        )
    }

    private fun refresh(
        pages: List<String?>,
        prepend: LoadState = NotLoading.Incomplete,
        append: LoadState = NotLoading.Incomplete
    ) = Refresh(
        pages = when {
            pages.isEmpty() -> listOf(TransformablePage.empty())
            else -> pages.map {
                if (it != null) listOf(it)
                else listOf()
            }.toTransformablePages()
        },
        placeholdersBefore = 0,
        placeholdersAfter = 1,
        combinedLoadStates = localLoadStatesOf(prependLocal = prepend, appendLocal = append)
    )

    private fun prepend(
        pages: List<String?>,
        prepend: LoadState = NotLoading.Incomplete
    ) = Prepend(
        pages = pages.map {
            if (it != null) listOf(it) else listOf()
        }.toTransformablePages(),
        placeholdersBefore = 0,
        combinedLoadStates = localLoadStatesOf(prependLocal = prepend)
    )

    private fun append(
        pages: List<String?>,
        append: LoadState = NotLoading.Incomplete
    ) = Append(
        pages = pages.map {
            if (it != null) listOf(it) else listOf()
        }.toTransformablePages(),
        placeholdersAfter = 0,
        combinedLoadStates = localLoadStatesOf(appendLocal = append)
    )

    private fun drop(loadType: LoadType, minPageOffset: Int, maxPageOffset: Int) = Drop<String>(
        loadType = loadType,
        minPageOffset = minPageOffset,
        maxPageOffset = maxPageOffset,
        placeholdersRemaining = 0
    )

    @Test
    fun refreshNoop() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf("a1"))
            ),
            flowOf(
                refresh(pages = listOf("a1"))
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshStartDone() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf("A", "a1"))
            ),
            flowOf(
                refresh(pages = listOf("a1"), prepend = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEndDone() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf("a1", "END"))
            ),
            flowOf(
                refresh(pages = listOf("a1"), append = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshBothDone() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf("A", "a1", "END"))
            ),
            flowOf(
                refresh(
                    pages = listOf("a1"),
                    prepend = NotLoading.Complete,
                    append = NotLoading.Complete
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyNoop() = runBlockingTest {
        assertInsertData(
            listOf(
                // should only get one item when initial page is endOfPaginationReached is false on
                // both sides.
                refresh(pages = listOf<String>())
            ),
            flowOf(
                refresh(pages = listOf())
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyStartDone() = runBlockingTest {
        assertInsertData(
            listOf(
                // not enough data to create separators yet
                refresh(pages = listOf()),
                // now we can, since we know what's on the other side
                append(pages = listOf("A", "a1")),
                // empty should be noop, since it's not append = NotLoading(true)
                append(pages = listOf()),
                // append = NotLoading(true), so resolve final separator
                append(pages = listOf("END"))
            ),
            flowOf(
                refresh(pages = listOf(), prepend = NotLoading.Complete),
                append(pages = listOf("a1")),
                append(pages = listOf()),
                append(pages = listOf(), append = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyEndDone() = runBlockingTest {
        assertInsertData(
            listOf(
                // not enough data to create separators yet
                refresh(pages = listOf()),
                // now we can, since we know what's on the other side
                prepend(pages = listOf("a1", "END")),
                // empty should be noop, since it's not prepend = NotLoading(true)
                prepend(pages = listOf()),
                // prepend = NotLoading(true), so resolve final separator
                prepend(pages = listOf("A"))
            ),
            flowOf(
                refresh(pages = listOf(), append = NotLoading.Complete),
                prepend(pages = listOf("a1")),
                prepend(pages = listOf()),
                prepend(pages = listOf(), prepend = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun emptyDropResetsDeferredFrontSeparator() = runBlockingTest {
        assertInsertData(
            listOf(
                // not enough data to create separators yet
                refresh(pages = listOf()),
                // don't insert a separator, since a drop occurred
                append(pages = listOf("a1")),
                // but now add the separator, since start is done again
                prepend(pages = listOf("A"))
            ),
            flowOf(
                refresh(pages = listOf(), prepend = NotLoading.Complete),
                drop(loadType = PREPEND, minPageOffset = 0, maxPageOffset = 0),
                append(pages = listOf("a1")),
                prepend(pages = listOf(), prepend = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun emptyDropResetsDeferredEndSeparator() = runBlockingTest {
        assertInsertData(
            listOf(
                // not enough data to create separators yet
                refresh(pages = listOf()),
                // don't insert a separator, since a drop occurred
                prepend(pages = listOf("a1")),
                // but now add the separator, since end is done again
                append(pages = listOf("END"))
            ),
            flowOf(
                refresh(pages = listOf(), append = NotLoading.Complete),
                drop(loadType = APPEND, minPageOffset = 0, maxPageOffset = 0),
                prepend(pages = listOf("a1")),
                append(pages = listOf(), append = NotLoading.Complete)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyStart() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf()),
                // not enough data to create separators yet
                prepend(pages = listOf("a1"))
            ),
            flowOf(
                refresh(pages = listOf()),
                prepend(pages = listOf("a1"))
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyEnd() = runBlockingTest {
        assertInsertData(
            listOf(
                refresh(pages = listOf()),
                // not enough data to create separators yet
                append(pages = listOf("a1"))
            ),
            flowOf(
                refresh(pages = listOf()),
                append(pages = listOf("a1"))
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        )
    }

    @Test
    fun refreshEmptyStartDropFull() = runBlockingTest {
        // when start terminal separator is inserted, we need to drop count*2 + 1
        assertThat(
            flowOf(
                refresh(
                    pages = listOf("a1", "b1"),
                    prepend = NotLoading.Complete
                ),
                drop(loadType = PREPEND, minPageOffset = 0, maxPageOffset = 0)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalPageOffset = 0,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1")
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("B"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("b1")
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf(prependLocal = NotLoading.Complete)
                ),
                drop(loadType = PREPEND, minPageOffset = 0, maxPageOffset = 0)
            )
        )
    }

    @Test
    fun refreshEmptyEndDropFull() = runBlockingTest {
        // when end terminal separator is inserted, we need to drop count*2 + 1
        assertThat(
            flowOf(
                refresh(
                    pages = listOf("a1", "b1"),
                    append = NotLoading.Complete
                ),
                drop(loadType = APPEND, minPageOffset = 0, maxPageOffset = 0)
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1")
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("B"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("b1")
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("END"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0)
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf(appendLocal = NotLoading.Complete)
                ),
                drop(loadType = APPEND, minPageOffset = 0, maxPageOffset = 0)
            )
        )
    }

    @Test
    fun types() = runBlockingTest {
        open class Base
        data class PrimaryType(val string: String) : Base()
        data class SeparatorType(val string: String) : Base()

        assertInsertData(
            listOf(
                Refresh(
                    pages = listOf(
                        listOf(
                            PrimaryType("a1"),
                            SeparatorType("B"),
                            PrimaryType("b1")
                        )
                    ).toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ),
            flowOf(
                Refresh(
                    pages = listOf(listOf(PrimaryType("a1"), PrimaryType("b1")))
                        .toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(terminalSeparatorType = FULLY_COMPLETE) { before, after ->
                return@insertEventSeparators (
                    if (before != null && after != null) {
                        SeparatorType("B")
                    } else null
                    )
            }.toList()
        )
    }

    @Test
    fun refreshEmptyPagesExceptOne() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(
                        listOf(),
                        listOf(),
                        listOf("a2", "b1"),
                        listOf(),
                        listOf()
                    ).toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(2),
                            data = listOf("a2", "B", "b1"),
                            hintOriginalPageOffset = 2,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = 3,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = 4,
                            data = listOf()
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun refreshSparsePages() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(
                        listOf(),
                        listOf("a2", "b1"),
                        listOf(),
                        listOf("c1", "c2"),
                        listOf()
                    ).toTransformablePages(),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("a2", "B", "b1"),
                            hintOriginalPageOffset = 1,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = 2,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1, 3),
                            data = listOf("C"),
                            hintOriginalPageOffset = 3,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 3,
                            data = listOf("c1", "c2")
                        ),
                        TransformablePage(
                            originalPageOffset = 4,
                            data = listOf()
                        )
                    ),
                    placeholdersBefore = 0,
                    placeholdersAfter = 1,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun prependEmptyPagesExceptOne() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("c1", "c2")
            ).toTransformablePages(),
            placeholdersBefore = 2,
            placeholdersAfter = 0,
            combinedLoadStates = localLoadStatesOf()
        )

        assertThat(
            flowOf(
                refresh,
                Prepend(
                    pages = listOf(
                        listOf(),
                        listOf(),
                        listOf("a1", "b1"),
                        listOf(),
                        listOf()
                    ).toTransformablePages(5),
                    placeholdersBefore = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -5,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = -4,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-3),
                            data = listOf("a1", "B", "b1"),
                            hintOriginalPageOffset = -3,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-3, 0),
                            data = listOf("C"),
                            hintOriginalPageOffset = -3,
                            hintOriginalIndices = listOf(1)
                        ),
                        TransformablePage(
                            originalPageOffset = -2,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf()
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun prependSparsePages() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("d1", "d2")
            ).toTransformablePages(),
            placeholdersBefore = 0,
            placeholdersAfter = 4,
            combinedLoadStates = localLoadStatesOf()
        )

        assertThat(
            flowOf(
                refresh,
                Prepend(
                    pages = listOf(
                        listOf(),
                        listOf("a1", "b1"),
                        listOf(),
                        listOf("c1", "c2"),
                        listOf()
                    ).toTransformablePages(5),
                    placeholdersBefore = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -5,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-4),
                            data = listOf("a1", "B", "b1"),
                            hintOriginalPageOffset = -4,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = -3,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-4, -2),
                            data = listOf("C"),
                            hintOriginalPageOffset = -4,
                            hintOriginalIndices = listOf(1)
                        ),
                        TransformablePage(
                            originalPageOffset = -2,
                            data = listOf("c1", "c2")
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-2, 0),
                            data = listOf("D"),
                            hintOriginalPageOffset = -2,
                            hintOriginalIndices = listOf(1)
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf()
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun appendEmptyPagesExceptOne() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("a1", "a2")
            ).toTransformablePages(),
            placeholdersBefore = 0,
            placeholdersAfter = 2,
            combinedLoadStates = localLoadStatesOf()
        )

        assertThat(
            flowOf(
                refresh,
                Append(
                    pages = listOf(
                        listOf(),
                        listOf(),
                        listOf("b1", "c1"),
                        listOf(),
                        listOf()
                    ).toTransformablePages(-1),
                    placeholdersAfter = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = 2,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 3),
                            data = listOf("B"),
                            hintOriginalPageOffset = 3,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(3),
                            data = listOf("b1", "C", "c1"),
                            hintOriginalPageOffset = 3,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = 4,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffset = 5,
                            data = listOf()
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun appendSparsePages() = runBlockingTest {
        val refresh = Refresh(
            pages = listOf(
                listOf("a1", "a2")
            ).toTransformablePages(),
            placeholdersBefore = 0,
            placeholdersAfter = 4,
            combinedLoadStates = localLoadStatesOf()
        )

        assertThat(
            flowOf(
                refresh,
                Append(
                    pages = listOf(
                        listOf(),
                        listOf("b1", "c1"),
                        listOf(),
                        listOf("d1", "d2"),
                        listOf()
                    ).toTransformablePages(-1),
                    placeholdersAfter = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                refresh,
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 2),
                            data = listOf("B"),
                            hintOriginalPageOffset = 2,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(2),
                            data = listOf("b1", "C", "c1"),
                            hintOriginalPageOffset = 2,
                            hintOriginalIndices = listOf(0, 1, 1)
                        ),
                        TransformablePage(
                            originalPageOffset = 3,
                            data = listOf()
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(2, 4),
                            data = listOf("D"),
                            hintOriginalPageOffset = 4,
                            hintOriginalIndices = listOf(0)
                        ),
                        TransformablePage(
                            originalPageOffset = 4,
                            data = listOf("d1", "d2")
                        ),
                        TransformablePage(
                            originalPageOffset = 5,
                            data = listOf()
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = localLoadStatesOf()
                )
            )
        )
    }

    @Test
    fun remoteRefreshEndOfPaginationReached_fullyComplete() = runBlockingTest {
        assertThat(
            flowOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshLocal = Loading,
                        refreshRemote = Loading
                    )
                ),
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        append = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete
                    )
                )
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remoteRefreshEndOfPaginationReached_sourceComplete() = runBlockingTest {
        assertThat(
            flowOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                )
            ).insertEventSeparators(
                terminalSeparatorType = SOURCE_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = Loading,
                        refreshRemote = Loading,
                        refreshLocal = Loading
                    )
                ),
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(),
                    placeholdersBefore = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
                Append(
                    pages = listOf(),
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        refresh = NotLoading.Complete,
                        prepend = NotLoading.Complete,
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        refreshRemote = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remotePrependEndOfPaginationReached_fullyComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote prepend is done triggers the header to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remotePrependEndOfPaginationReached_sourceComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote prepend is done triggers the header to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = SOURCE_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(),
                    placeholdersBefore = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remotePrependEndOfPaginationReachedWithDrops_fullyComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Incomplete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote prepend is done triggers the header to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete
                    )
                ),
                // Drop the first page, header and separator between "b1" and "a1"
                Drop(
                    loadType = PREPEND,
                    minPageOffset = -1,
                    maxPageOffset = -1,
                    placeholdersRemaining = 1
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Incomplete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1, 0),
                            data = listOf("B"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1
                        ),
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1,
                        ),
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
                Drop(
                    loadType = PREPEND,
                    minPageOffset = -1,
                    maxPageOffset = -1,
                    placeholdersRemaining = 1
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1,
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1, 0),
                            data = listOf("B"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1
                        ),
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    )
                ),
            )
        )
    }

    @Test
    fun remotePrependEndOfPaginationReachedWithDrops_sourceComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Incomplete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote prepend is done triggers the header to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete
                    )
                ),
                // Drop the first page, header and separator between "b1" and "a1"
                Drop(
                    loadType = PREPEND,
                    minPageOffset = -1,
                    maxPageOffset = -1,
                    placeholdersRemaining = 1
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        )
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = SOURCE_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("b1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Incomplete,
                    )
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1,
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1, 0),
                            data = listOf("B"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1
                        ),
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Prepend(
                    pages = listOf(),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    ),
                ),
                Drop(
                    loadType = PREPEND,
                    minPageOffset = -1,
                    maxPageOffset = -1,
                    placeholdersRemaining = 1
                ),
                Prepend(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1,
                        ),
                        TransformablePage(
                            originalPageOffset = -1,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(-1, 0),
                            data = listOf("B"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = -1
                        ),
                    ),
                    placeholdersBefore = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prepend = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        prependRemote = NotLoading.Complete,
                    )
                ),
            )
        )
    }

    @Test
    fun remoteAppendEndOfPaginationReached_fullyComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote append is done triggers the footer to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remoteAppendEndOfPaginationReached_sourceComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("a1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote append is done triggers the footer to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    )
                )
            ).insertEventSeparators(
                terminalSeparatorType = SOURCE_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("A"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("a1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0,
                        ),
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                    )
                ),
                Append(
                    pages = listOf(),
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
            )
        )
    }

    @Test
    fun remoteAppendEndOfPaginationReachedWithDrops_fullyComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Incomplete,
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote append is done triggers the footer to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete
                    )
                ),
                // Drop the last page, footer and separator between "b1" and "c1"
                Drop(
                    loadType = APPEND,
                    minPageOffset = 1,
                    maxPageOffset = 1,
                    placeholdersRemaining = 1
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = FULLY_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Incomplete,
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        ),
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1,
                        ),
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
                Drop(
                    loadType = APPEND,
                    minPageOffset = 1,
                    maxPageOffset = 1,
                    placeholdersRemaining = 1
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    )
                ),
            )
        )
    }

    @Test
    fun remoteAppendEndOfPaginationReachedWithDrops_sourceComplete() = runBlockingTest {
        assertThat(
            flowOf(
                Refresh(
                    pages = listOf(listOf("b1")).toTransformablePages(),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Incomplete,
                    )
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                    )
                ),
                // Signalling that remote append is done triggers the footer to resolve.
                LoadStateUpdate(
                    remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete
                    )
                ),
                // Drop the last page, footer and separator between "b1" and "c1"
                Drop(
                    loadType = APPEND,
                    minPageOffset = 1,
                    maxPageOffset = 1,
                    placeholdersRemaining = 1
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        )
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    )
                ),
            ).insertEventSeparators(
                terminalSeparatorType = SOURCE_COMPLETE,
                generator = LETTER_SEPARATOR_GENERATOR
            ).toList()
        ).isEqualTo(
            listOf(
                Refresh(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0),
                            data = listOf("B"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 0
                        ),
                        TransformablePage(
                            originalPageOffset = 0,
                            data = listOf("b1"),
                        ),
                    ),
                    placeholdersBefore = 1,
                    placeholdersAfter = 1,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Incomplete,
                    ),
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1,
                        ),
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                    ),
                ),
                Append(
                    pages = listOf(),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    ),
                ),
                Drop(
                    loadType = APPEND,
                    minPageOffset = 1,
                    maxPageOffset = 1,
                    placeholdersRemaining = 1
                ),
                Append(
                    pages = listOf(
                        TransformablePage(
                            originalPageOffsets = intArrayOf(0, 1),
                            data = listOf("C"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                        TransformablePage(
                            originalPageOffset = 1,
                            data = listOf("c1"),
                        ),
                        TransformablePage(
                            originalPageOffsets = intArrayOf(1),
                            data = listOf("END"),
                            hintOriginalIndices = listOf(0),
                            hintOriginalPageOffset = 1
                        ),
                    ),
                    placeholdersAfter = 0,
                    combinedLoadStates = remoteLoadStatesOf(
                        append = NotLoading.Complete,
                        prependLocal = NotLoading.Complete,
                        appendLocal = NotLoading.Complete,
                        appendRemote = NotLoading.Complete,
                    )
                ),
            )
        )
    }

    companion object {
        /**
         * Creates an upper-case letter at the beginning of each section of strings that start
         * with the same letter, and the string "END" at the very end.
         */
        val LETTER_SEPARATOR_GENERATOR: suspend (String?, String?) -> String? = { before, after ->
            if (after == null) {
                "END"
            } else if (before == null || before.first() != after.first()) {
                after.first().uppercaseChar().toString()
            } else null
        }
    }
}

@Suppress("TestFunctionName")
internal fun <T : Any> TransformablePage(data: List<T>) = TransformablePage(
    data = data,
    originalPageOffset = 0
)

internal fun <T : Any> List<List<T>>.toTransformablePages(
    indexOfInitialPage: Int = 0
) = mapIndexed { index, list ->
    TransformablePage(
        data = list,
        originalPageOffset = index - indexOfInitialPage
    )
}.toMutableList()
