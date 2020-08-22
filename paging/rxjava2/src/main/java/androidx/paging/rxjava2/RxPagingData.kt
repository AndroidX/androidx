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

package androidx.paging.rxjava2

import androidx.annotation.CheckResult
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import io.reactivex.Maybe
import io.reactivex.Single
import kotlinx.coroutines.rx2.await

/**
 * Returns a [PagingData] containing the result of applying the given [transform] to each
 * element, as it is loaded.
 */
@CheckResult
fun <T : Any, R : Any> PagingData<T>.mapRx(
    transform: (T) -> Single<R>
): PagingData<R> = map { transform(it).await() }

/**
 * Returns a [PagingData] of all elements returned from applying the given [transform] to each
 * element, as it is loaded.
 */
@CheckResult
fun <T : Any, R : Any> PagingData<T>.flatMapRx(
    transform: (T) -> Single<Iterable<R>>
): PagingData<R> = flatMap { transform(it).await() }

/**
 * Returns a [PagingData] containing only elements matching the given [predicate].
 */
@CheckResult
fun <T : Any> PagingData<T>.filterRx(
    predicate: (T) -> Single<Boolean>
): PagingData<T> = filter { predicate(it).await() }

/**
 * Returns a [PagingData] containing each original element, with an optional separator generated
 * by [generator], given the elements before and after (or null, in boundary conditions).
 *
 * Note that this transform is applied asynchronously, as pages are loaded. Potential separators
 * between pages are only computed once both pages are loaded.
 *
 * @sample androidx.paging.samples.insertSeparatorsSample
 * @sample androidx.paging.samples.insertSeparatorsUiModelSample
 */
@CheckResult
fun <T : R, R : Any> PagingData<T>.insertSeparatorsRx(
    generator: (T?, T?) -> Maybe<R>
): PagingData<R> = insertSeparators { left, right -> generator(left, right).await() }
