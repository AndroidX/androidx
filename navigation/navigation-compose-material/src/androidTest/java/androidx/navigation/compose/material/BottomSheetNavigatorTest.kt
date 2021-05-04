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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetState
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.testing.TestNavigatorState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterialApi::class)
class BottomSheetNavigatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testNavigateAddsDestinationToBackStack(): Unit = runBlocking {
        val sheetState = ModalBottomSheetState(ModalBottomSheetValue.Hidden)
        val navigator = BottomSheetNavigator(sheetState)
        val navigatorState = TestNavigatorState()

        navigator.onAttach(navigatorState)
        val entry = navigatorState.createBackStackEntry(navigator.createDestination(), null)
        navigator.navigate(listOf(entry), null, null)

        assertWithMessage("The back stack entry has been added to the back stack")
            .that(navigatorState.backStack.value)
            .containsExactly(entry)
    }

    @Test
    fun testNavigateAddsDestinationToBackStackAndKeepsPrevious(): Unit = runBlocking {
        val sheetState = ModalBottomSheetState(ModalBottomSheetValue.Hidden)
        val navigator = BottomSheetNavigator(sheetState)
        val navigatorState = TestNavigatorState()

        navigator.onAttach(navigatorState)
        val firstEntry = navigatorState.createBackStackEntry(navigator.createDestination(), null)
        val secondEntry = navigatorState.createBackStackEntry(navigator.createDestination(), null)

        navigator.navigate(listOf(firstEntry), null, null)
        assertWithMessage("The first entry has been added to the back stack")
            .that(navigatorState.backStack.value)
            .containsExactly(firstEntry)

        navigator.navigate(listOf(secondEntry), null, null)
        assertWithMessage("The second entry has been added to the back stack and it still " +
            "contains the first entry")
            .that(navigatorState.backStack.value)
            .containsExactly(firstEntry, secondEntry)
            .inOrder()
    }

    @Test
    fun testNavigateComposesDestinationAndDisposesPreviousDestination(): Unit = runBlocking {
        val sheetState = ModalBottomSheetState(ModalBottomSheetValue.Hidden)
        val navigator = BottomSheetNavigator(sheetState)
        val navigatorState = TestNavigatorState()

        composeTestRule.setContent {
            Column { navigator.sheetContent(this) }
        }

        navigator.onAttach(navigatorState)

        var firstDestinationCompositions = 0
        val firstDestinationContentTag = "firstSheetContentTest"
        val firstDestination = BottomSheetNavigator.Destination(navigator) {
            DisposableEffect(Unit) {
                firstDestinationCompositions++
                onDispose { firstDestinationCompositions = 0 }
            }
            Text("Fake Sheet Content", Modifier.testTag(firstDestinationContentTag))
        }
        val firstEntry = navigatorState.createActiveBackStackEntry(firstDestination, null)

        var secondDestinationCompositions = 0
        val secondDestinationContentTag = "secondSheetContentTest"
        val secondDestination = BottomSheetNavigator.Destination(navigator) {
            DisposableEffect(Unit) {
                secondDestinationCompositions++
                onDispose { secondDestinationCompositions = 0 }
            }
            Box(Modifier.size(64.dp).testTag(secondDestinationContentTag))
        }
        val secondEntry = navigatorState.createActiveBackStackEntry(secondDestination, null)

        navigator.navigate(listOf(firstEntry), null, null)
        composeTestRule.awaitIdle()

        composeTestRule.onNodeWithTag(firstDestinationContentTag).assertExists()
        composeTestRule.onNodeWithTag(secondDestinationContentTag).assertDoesNotExist()
        assertWithMessage("First destination should have been composed exactly once")
            .that(firstDestinationCompositions).isEqualTo(1)
        assertWithMessage("Second destination should not have been composed yet")
            .that(secondDestinationCompositions).isEqualTo(0)

        navigator.navigate(listOf(secondEntry), null, null)
        composeTestRule.awaitIdle()

        composeTestRule.onNodeWithTag(firstDestinationContentTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(secondDestinationContentTag).assertExists()
        assertWithMessage("First destination has not been disposed")
            .that(firstDestinationCompositions).isEqualTo(0)
        assertWithMessage("Second destination should have been composed exactly once")
            .that(secondDestinationCompositions).isEqualTo(1)
    }

    @Test
    fun testBackStackEntryPoppedAfterManualSheetDismiss(): Unit = runBlocking {
        val navigatorState = TestNavigatorState()
        val sheetState = ModalBottomSheetState(ModalBottomSheetValue.Hidden)
        val navigator = BottomSheetNavigator(sheetState)
        navigator.onAttach(navigatorState)

        val bodyContentTag = "testBodyContent"

        composeTestRule.setContent {
            ModalBottomSheetLayout(
                bottomSheetNavigator = navigator,
                content = { Box(Modifier.fillMaxSize().testTag(bodyContentTag)) }
            )
        }

        val destination = BottomSheetNavigator.Destination(navigator) {
            Text("Fake Sheet Content")
        }
        val backStackEntry = navigatorState.createActiveBackStackEntry(destination, null)
        navigator.navigate(listOf(backStackEntry), null, null)
        composeTestRule.awaitIdle()

        assertWithMessage("Navigated to destination")
            .that(navigatorState.backStack.value)
            .containsExactly(backStackEntry)
        assertWithMessage("Bottom sheet shown")
            .that(sheetState.isVisible).isTrue()

        composeTestRule.onNodeWithTag(bodyContentTag).performClick()
        composeTestRule.awaitIdle()
        assertWithMessage("Sheet should be hidden")
            .that(sheetState.isVisible).isFalse()
        assertWithMessage("Back stack entry should be popped off the back stack")
            .that(navigatorState.backStack.value)
            .isEmpty()
    }

}
