package com.example.whiz.accessibility

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaceUnlockSwiperTest {

    /** Simulated keyguard: `keyguardShowing` is isKeyguardLocked, `deviceLocked` is isDeviceLocked. */
    private class FakeKeyguard(var keyguardShowing: Boolean = true, var deviceLocked: Boolean = true)

    private fun swiper(
        kg: FakeKeyguard,
        swipe: suspend () -> Boolean,
        watchWindowMs: Long = 60_000,
        dismissWaitMs: Long = 1_500,
    ) = FaceUnlockSwiper(
        isKeyguardLocked = { kg.keyguardShowing },
        isDeviceLocked = { kg.deviceLocked },
        swipeUp = swipe,
        watchWindowMs = watchWindowMs,
        dismissWaitMs = dismissWaitMs,
        pollMs = 100,
    )

    @Test
    fun facePassesThenSwipeDismisses_returnsDismissed() = runTest {
        val kg = FakeKeyguard()
        var swipes = 0
        val s = swiper(kg, swipe = {
            swipes++
            kg.keyguardShowing = false // the swipe took
            true
        })
        // Face passes 800ms in.
        launchFaceAt(kg, 800)
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.DISMISSED)
        assertThat(swipes).isEqualTo(1)
    }

    @Test
    fun faceNeverPasses_returnsFaceNotPassed_withoutSwiping() = runTest {
        val kg = FakeKeyguard()
        var swipes = 0
        val s = swiper(kg, swipe = { swipes++; true })
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.FACE_NOT_PASSED)
        assertThat(swipes).isEqualTo(0)
        assertThat(currentTime).isAtLeast(60_000)
    }

    @Test
    fun systemFinishesUnlockItselfLateInWindow_returnsAlreadyUnlocked_withoutSwiping() = runTest {
        // The common case: the dismiss request triggers face, face passes, and the system
        // drops the keyguard on its own (device-unlocked and keyguard-gone flip together).
        val kg = FakeKeyguard()
        var swipes = 0
        val s = swiper(kg, swipe = { swipes++; true })
        launchAt(20_000) { kg.deviceLocked = false; kg.keyguardShowing = false }
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.ALREADY_UNLOCKED)
        assertThat(swipes).isEqualTo(0)
    }

    @Test
    fun alreadyUnlockedBeforeFaceWindow_returnsAlreadyUnlocked_withoutSwiping() = runTest {
        val kg = FakeKeyguard(keyguardShowing = false, deviceLocked = false)
        var swipes = 0
        val s = swiper(kg, swipe = { swipes++; true })
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.ALREADY_UNLOCKED)
        assertThat(swipes).isEqualTo(0)
    }

    @Test
    fun userUnlocksThemselvesDuringFaceWindow_returnsAlreadyUnlocked() = runTest {
        val kg = FakeKeyguard()
        var swipes = 0
        val s = swiper(kg, swipe = { swipes++; true })
        launchAt(500) { kg.keyguardShowing = false; kg.deviceLocked = false }
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.ALREADY_UNLOCKED)
        assertThat(swipes).isEqualTo(0)
    }

    @Test
    fun swipeDispatchFails_returnsSwipeFailed() = runTest {
        val kg = FakeKeyguard()
        val s = swiper(kg, swipe = { false })
        launchFaceAt(kg, 100)
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.SWIPE_FAILED)
    }

    @Test
    fun swipeDispatchedButKeyguardStays_returnsSwipeFailed_afterDismissWait() = runTest {
        val kg = FakeKeyguard()
        val s = swiper(kg, swipe = { true }) // dispatched, but keyguard never goes away
        launchFaceAt(kg, 100)
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.SWIPE_FAILED)
        assertThat(currentTime).isAtLeast(100 + 1_500)
    }

    @Test
    fun keyguardDismissesShortlyAfterSwipe_returnsDismissed() = runTest {
        val kg = FakeKeyguard()
        val s = swiper(kg, swipe = {
            launchAt(400) { kg.keyguardShowing = false } // animation delay
            true
        })
        launchFaceAt(kg, 100)
        assertThat(s.attempt()).isEqualTo(FaceUnlockSwiper.Result.DISMISSED)
    }

    @Test
    fun swipesOnlyOnce() = runTest {
        val kg = FakeKeyguard()
        var swipes = 0
        val s = swiper(kg, swipe = { swipes++; true })
        launchFaceAt(kg, 100)
        s.attempt()
        assertThat(swipes).isEqualTo(1)
    }

    private fun TestScope.launchFaceAt(kg: FakeKeyguard, atMs: Long) =
        launchAt(atMs) { kg.deviceLocked = false }

    private fun TestScope.launchAt(atMs: Long, block: () -> Unit) =
        launch { delay(atMs); block() }
}
