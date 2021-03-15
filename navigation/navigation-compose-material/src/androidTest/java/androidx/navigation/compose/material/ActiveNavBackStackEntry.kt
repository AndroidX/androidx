/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.navigation.compose.material

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination
import androidx.navigation.testing.TestNavigatorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// By default, NavBackStackEntrys are in the INITIALIZED state and then get moved to the next
// appropriate state by the NavController. In case we aren't testing with a NavController,
// this sets the entry's lifecycle state to the passed state so that the entry is active.
internal suspend fun TestNavigatorState.createActiveBackStackEntry(
    destination: NavDestination,
    arguments: Bundle? = null,
    lifecycleState: Lifecycle.State = Lifecycle.State.RESUMED
) = createBackStackEntry(destination, arguments).apply {
    withContext(Dispatchers.Main) { maxLifecycle = lifecycleState }
}