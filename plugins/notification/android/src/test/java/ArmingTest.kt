// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The decisions in Arming.kt. Each test names what a person would lose if the decision went
 * the other way.
 */
class ArmingTest {
  private val now = Date(1_800_000_000_000)

  private fun interval(): Notification = Notification().apply {
    id = 1
    sourceJson = "{\"id\":1}"
    schedule = NotificationSchedule.Interval().apply {
      interval = DateMatch().apply { hour = 21; minute = 0; second = 0 }
      allowWhileIdle = true
    }
  }

  private fun at(date: Date): Notification = Notification().apply {
    id = 2
    sourceJson = "{\"id\":2}"
    schedule = NotificationSchedule.At().apply { this.date = date }
  }

  // 1. An update must not take an unread notification off the tray.

  @Test
  fun restoringLeavesTheTrayAlone() {
    assertFalse(armSteps(restoring = true).contains(ArmStep.DismissVisible))
  }

  @Test
  fun restoringStillReplacesTheAlarmAndBuildsIt() {
    assertEquals(listOf(ArmStep.CancelTimer, ArmStep.Build), armSteps(restoring = true))
  }

  @Test
  fun schedulingFromTheAppClearsTheOldOneFirst() {
    assertEquals(
      listOf(ArmStep.DismissVisible, ArmStep.CancelTimer, ArmStep.Build),
      armSteps(restoring = false)
    )
  }

  // 4. One bad stored entry must not cost the others their alarms.

  @Test
  fun oneEntryThatThrowsDoesNotStopTheRest() {
    val armed = mutableListOf<Int>()
    val failed = mutableListOf<Int>()
    val entries = listOf(1, 2, 3).map { n -> Notification().apply { id = n } }

    val count = restoreEach(
      entries,
      { if (it.id == 2) throw IllegalArgumentException("bad colour") else armed.add(it.id) },
      { notification, _ -> failed.add(notification.id) }
    )

    assertEquals(listOf(1, 3), armed)
    assertEquals(listOf(2), failed)
    assertEquals(2, count)
  }

  // 5. The notification is shown and re-armed even when telling the app fails.

  @Test
  fun aFailureTellingTheAppStillShowsAndRearms() {
    val calls = mutableListOf<String>()

    deliver(
      show = { calls.add("show") },
      tellApp = { throw IllegalStateException("webview gone") },
      rearm = { calls.add("rearm"); true },
      forget = { calls.add("forget") },
      removable = false,
      failed = { what, _ -> calls.add("failed $what") }
    )

    assertEquals(listOf("show", "failed tell the app", "rearm"), calls)
  }

  @Test
  fun aFailureShowingStillRearmsTomorrow() {
    val calls = mutableListOf<String>()

    deliver(
      show = { throw NullPointerException("no notification on the intent") },
      tellApp = { calls.add("tell") },
      rearm = { calls.add("rearm"); true },
      forget = { calls.add("forget") },
      removable = false,
      failed = { what, _ -> calls.add("failed $what") }
    )

    assertEquals(listOf("failed show", "tell", "rearm"), calls)
  }

  // 6. A repeating notification stays stored after it is shown; a one-off does not.

  @Test
  fun aOneOffIsForgottenOnceShown() {
    assertTrue(forgetsAfterShowing(rearmed = false, removable = true))
  }

  @Test
  fun aRepeatingOneIsKeptEvenWhenAlarmManagerRepeatsIt() {
    assertFalse(forgetsAfterShowing(rearmed = false, removable = false))
    assertFalse(forgetsAfterShowing(rearmed = true, removable = false))
  }

  // 7. A refused notification must not come back after every restart.

  @Test
  fun aOneOffInThePastIsNotStored() {
    assertFalse(keepsInStore(at(Date(now.time - 60_000)), now))
  }

  @Test
  fun aOneOffInTheFutureIsStored() {
    assertTrue(keepsInStore(at(Date(now.time + 60_000)), now))
  }

  @Test
  fun aDailyReminderIsStored() {
    assertTrue(keepsInStore(interval(), now))
  }

  @Test
  fun nothingWithoutASourceOrASchedule() {
    assertFalse(keepsInStore(interval().apply { sourceJson = null }, now))
    assertFalse(keepsInStore(interval().apply { schedule = null }, now))
  }

  // 3. On the update that drops the permission, no exact alarm for AlarmManager to remove.

  @Test
  fun notExactWhenTheManifestNoLongerAsksEvenIfTheGrantLooksAlive() {
    assertFalse(armsExact(sdk = 34, declaresExactAlarms = false) { true })
  }

  @Test
  fun exactWhenTheManifestAsksAndAndroidGrants() {
    assertTrue(armsExact(sdk = 34, declaresExactAlarms = true) { true })
    assertFalse(armsExact(sdk = 34, declaresExactAlarms = true) { false })
  }

  @Test
  fun alwaysExactBeforeAndroid12() {
    assertTrue(armsExact(sdk = 30, declaresExactAlarms = false) { false })
  }
}
