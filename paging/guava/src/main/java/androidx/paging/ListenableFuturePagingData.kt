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

@file:JvmName("PagingDataFutures")

package androidx.paging

import androidx.annotation.CheckResult
import androidx.concurrent.futures.await
import com.google.common.util.concurrent.AsyncFunction

/**
 * Returns a [PagingData] containing the result of applying the given [transform] to each
 * element, as it is loaded.
 */
@CheckResult
fun <T : Any, R : Any> PagingData<T>.mapAsync(
    transform: AsyncFunction<T, R>
): PagingData<R> = map { transform.apply(it).await() }

/**
 * Returns a [PagingData] of all elements returned from applying the given [transform] to each
 * element, as it is loaded.
 */
@CheckResult
fun <T : Any, R : Any> PagingData<T>.flatMapAsync(
    transform: AsyncFunction<T, Iterable<R>>
): PagingData<R> = flatMap { transform.apply(it).await() }

/**
 * Returns a [PagingData] containing only elements matching the given [predicate].
 */
@CheckResult
fun <T : Any> PagingData<T>.filterAsync(
    predicate: AsyncFunction<T, Boolean>
): PagingData<T> = filter { predicate.apply(it).await() }

/**
 * Returns a [PagingData] containing each original element, with an optional separator generated
 * by [generator], given the elements before and after (or null, in boundary conditions).
 *
 * Note that this transform is applied asynchronously, as pages are loaded. Potential separators
 * between pages are only computed once both pages are loaded.
 *
 * @sample androidx.paging.samples.insertSeparatorsFutureSample
 * @sample androidx.paging.samples.insertSeparatorsUiModelFutureSample
 */
@CheckResult
fun <T : R, R : Any> PagingData<T>.insertSeparatorsAsync(
    generator: AsyncFunction<AdjacentItems<T>, R?>
): PagingData<R> = insertSeparators { before, after ->
    generator.apply(AdjacentItems(before, after)).await()
}

/**
 * Represents a pair of adjacent items, null values are used to signal boundary conditions.
 */
data class AdjacentItems<T>(val before: T?, val after: T?)
