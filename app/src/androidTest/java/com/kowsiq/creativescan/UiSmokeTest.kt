package com.kowsiq.creativescan

import android.Manifest
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.kowsiq.creativescan.TestSupport.log
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Launches the real activity and walks through every screen reachable without a document. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    private val perms = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*perms)).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle()
        TestSupport.save(name, compose.onRoot().captureToImage().asAndroidBitmap(), 900)
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun homeAndInfoScreens() {
        compose.onNodeWithText("Scan Document").assertIsDisplayed()
        compose.onNodeWithText("ID Card Scan").assertIsDisplayed()
        shot("10_ui_home")

        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("PDF quality").assertIsDisplayed()
        shot("11_ui_settings")
        back()

        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.onNodeWithText("Version", substring = true).assertIsDisplayed()
        shot("12_ui_about")
        back()

        compose.onNodeWithText("Privacy").performScrollTo().performClick()
        compose.onNodeWithText("100% on-device").assertIsDisplayed()
        shot("13_ui_privacy")
        back()

        compose.onNodeWithText("My Documents").performScrollTo().performClick()
        compose.waitForIdle()
        shot("14_ui_documents")
        back()
        compose.onNodeWithText("Scan Document").assertIsDisplayed()
    }

    @Test
    fun cameraScreensOpenWithoutCrashing() {
        compose.onNodeWithText("ID Card Scan").performClick()
        Thread.sleep(4000)
        compose.waitForIdle()
        shot("15_ui_camera_id")
        log("camera (ID mode) activity state=${compose.activityRule.scenario.state}")
        assertTrue(compose.activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED))
        back()

        compose.onNodeWithText("Scan Document").performClick()
        Thread.sleep(4000)
        compose.waitForIdle()
        shot("16_ui_camera_doc")
        log("camera (document mode) activity state=${compose.activityRule.scenario.state}")
        assertTrue(compose.activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED))
        back()
        compose.onAllNodesWithText("Scan Document")
    }
}
