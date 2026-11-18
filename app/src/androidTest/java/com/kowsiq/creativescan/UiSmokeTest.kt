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
import androidx.lifecycle.ViewModelProvider
import com.kowsiq.creativescan.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
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

    /** The real screens: after the front side the app must stop, explain, and wait for the user's tap. */
    @Test
    fun idCardStopsAfterFrontUntilUserAllowsBackSide() {
        var vmRef: AppViewModel? = null
        compose.activityRule.scenario.onActivity { vmRef = ViewModelProvider(it)[AppViewModel::class.java] }
        val vm = vmRef!!
        compose.runOnUiThread { vm.updateSettings(vm.settings.copy(autoCapture = false)) }
        compose.onNodeWithText("ID Card Scan").performClick()
        compose.waitUntil(15_000) { vm.idCardCaptureStage == 1 }
        Thread.sleep(2000)
        shot("17_ui_id_front_stage")

        val front = File(TestSupport.ctx.cacheDir, "ui_front.jpg")
        TestSupport.saveJpegFile(TestSupport.cardScene(0).bitmap, front, 95)
        compose.runOnUiThread { vm.onPhotoCaptured(front) }
        compose.waitUntil(90_000) { vm.idCardAwaitingBack }
        compose.waitForIdle()
        compose.onNodeWithText("Front side captured", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Scan back side").assertIsDisplayed()
        shot("18_ui_id_after_front_waiting")

        // nothing may happen on its own while the user is flipping the card
        Thread.sleep(8000)
        assertTrue("must still be waiting", vm.idCardAwaitingBack)
        assertEquals("no page may be created yet", 0, vm.pages.size)
        assertEquals(2, vm.idCardCaptureStage)

        compose.onNodeWithText("Scan back side").performClick()
        compose.waitForIdle()
        assertTrue("waiting flag must clear after the tap", !vm.idCardAwaitingBack)
        compose.onNodeWithText("BACK SIDE (2 OF 2)", substring = true).assertIsDisplayed()
        shot("19_ui_id_back_stage")

        val back = File(TestSupport.ctx.cacheDir, "ui_back.jpg")
        TestSupport.saveJpegFile(TestSupport.cardScene(1).bitmap, back, 95)
        compose.runOnUiThread { vm.onPhotoCaptured(back) }
        compose.waitUntil(90_000) { vm.pages.size == 1 && vm.busy == null }
        compose.waitForIdle()
        shot("20_ui_id_result_edit")
        log("ID flow through the real screens: stopped after front, resumed on tap, A4 page created (pages=${vm.pages.size})")
    }
}
