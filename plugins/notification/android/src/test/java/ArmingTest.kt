// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
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

  // A repeating At restored after its date would fire every minute for good: its gap is the
  // time left until a date the receiver has just moved fifteen seconds ahead.
  @Test
  fun aRepeatingAtIsNotStored() {
    val repeating = at(Date(now.time + 60_000)).apply {
      (schedule as NotificationSchedule.At).repeating = true
    }
    assertFalse(keepsInStore(repeating, now))
  }

  @Test
  fun nothingWithoutASourceOrASchedule() {
    assertFalse(keepsInStore(interval().apply { sourceJson = null }, now))
    assertFalse(keepsInStore(interval().apply { schedule = null }, now))
  }

  // 3. On the update that drops the permission, no exact alarm for AlarmManager to remove.

  @Test
  fun notExactWhenTheManifestNoLongerAsksEvenIfTheGrantLooksAlive() {
    assertFalse(armsExact(sdk = 34, declaresExactAlarms = false, canScheduleExact = true))
  }

  @Test
  fun exactWhenTheManifestAsksAndAndroidGrants() {
    assertTrue(armsExact(sdk = 34, declaresExactAlarms = true, canScheduleExact = true))
    assertFalse(armsExact(sdk = 34, declaresExactAlarms = true, canScheduleExact = false))
  }

  @Test
  fun alwaysExactBeforeAndroid12() {
    assertTrue(armsExact(sdk = 30, declaresExactAlarms = false, canScheduleExact = false))
  }

  // The log line that says which input decided a restored alarm's exactness.

  @Test
  fun findsTheExactAlarmPermissionAmongTheOthers() {
    assertEquals(
      listOf("android.permission.SCHEDULE_EXACT_ALARM"),
      exactAlarmPermissionsIn(
        arrayOf("android.permission.INTERNET", "android.permission.SCHEDULE_EXACT_ALARM")
      )
    )
  }

  @Test
  fun aManifestReadAsNullDeclaresNothing() {
    assertEquals(listOf<String>(), exactAlarmPermissionsIn(null))
  }

  @Test
  fun theLogLineSaysEachInputApart() {
    val line = exactAlarmDecisionLine(
      caller = "restore",
      sdk = 34,
      requested = null,
      canScheduleExact = true,
      exact = false,
      whileIdle = true
    )

    assertTrue(line, line.contains("caller=restore"))
    assertTrue(line, line.contains("requestedPermissions=[null]"))
    assertTrue(line, line.contains("declaresExactAlarms=false"))
    assertTrue(line, line.contains("canScheduleExactAlarms=true"))
    assertTrue(line, line.contains("exact=false"))
  }

  // 7. A day already logged: the first firing waits for tomorrow, and only the first.

  private fun nov(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
    set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
    set(Calendar.MILLISECOND, 0)
  }.timeInMillis

  private fun daily(hour: Int, minute: Int) = DateMatch().apply {
    this.hour = hour; this.minute = minute; second = 0
  }

  @Test
  fun withoutAStartItIsTheNextMatchFromNow() {
    assertEquals(nov(16, 21, 0), firstTrigger(daily(21, 0), null, Date(nov(16, 19, 30))))
  }

  @Test
  fun aStartTomorrowSkipsTodaysTime() {
    assertEquals(
      nov(17, 21, 0),
      firstTrigger(daily(21, 0), nov(17, 0, 0), Date(nov(16, 19, 30)))
    )
  }

  @Test
  fun aMidnightReminderStartedAtMidnightIsNotADayLate() {
    assertEquals(
      nov(17, 0, 0),
      firstTrigger(daily(0, 0), nov(17, 0, 0), Date(nov(16, 19, 30)))
    )
  }

  @Test
  fun aStartAlreadyPastIsTheNextMatchFromNow() {
    assertEquals(
      nov(16, 21, 0),
      firstTrigger(daily(21, 0), nov(15, 0, 0), Date(nov(16, 19, 30)))
    )
  }

  // 8. A permission request is always replied to.

  @Test
  fun belowAndroid13TheRequestRepliesWithTheState() {
    assertEquals(PermissionRequest.AnswerNow, permissionRequest(sdk = 32, granted = false))
  }

  @Test
  fun fromAndroid13AnAlreadyGrantedRequestRepliesAtOnce() {
    assertEquals(PermissionRequest.AnswerNow, permissionRequest(sdk = 33, granted = true))
  }

  @Test
  fun fromAndroid13ANotGrantedRequestAsksThePerson() {
    assertEquals(PermissionRequest.AskThePerson, permissionRequest(sdk = 33, granted = false))
  }
}
