// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * What `show` stores has to come back, in a receiver and with the receivers' mapper, as the
 * same alarm it was: the same time of day and the same permission to wake the phone. The
 * restore receiver has nothing else to go on after a restart or an update.
 */
class NotificationStorageTest {
  // What the Rust side sends `show` for a daily reminder at 21:00, as serde_json writes
  // NotificationData: every field, in declaration order, including `silent`, which the
  // Kotlin class does not have.
  private val dailyAtNine = """{"id":1,"channelId":null,"title":"Daylo","body":"How did today go? Tap to log it.","schedule":{"interval":{"interval":{"year":null,"month":null,"day":null,"weekday":null,"hour":21,"minute":0,"second":0},"allowWhileIdle":true}},"largeBody":null,"summary":null,"actionTypeId":null,"group":null,"groupSummary":false,"sound":null,"inboxLines":[],"icon":"ic_notification","largeIcon":null,"iconColor":null,"attachments":[],"extra":{},"ongoing":false,"autoCancel":false,"silent":false}"""

  private fun readBack(json: String): Notification? =
    NotificationStorage.decode(NotificationStorage.receiverJsonMapper(), json)

  @Test
  fun keepsTheHourTheMinuteAndTheWakeUp() {
    val notification = readBack(dailyAtNine)!!

    assertEquals(1, notification.id)
    assertEquals("Daylo", notification.title)
    val schedule = notification.schedule as NotificationSchedule.Interval
    assertTrue("allowWhileIdle must survive the store", schedule.allowWhileIdle())
    assertEquals(21, schedule.interval.hour)
    assertEquals(0, schedule.interval.minute)
    assertEquals(0, schedule.interval.second)
  }

  @Test
  fun firesAgainAtTheSameTimeOfDay() {
    val schedule = readBack(dailyAtNine)!!.schedule as NotificationSchedule.Interval
    val evening = Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 14, 20, 15, 42)
      set(Calendar.MILLISECOND, 0)
    }
    val expected = Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 14, 21, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }

    assertEquals(expected.timeInMillis, schedule.interval.nextTrigger(evening.time))
  }

  @Test
  fun anUpdateAfterNineWaitsForTomorrow() {
    // MY_PACKAGE_REPLACED at night must not show the reminder again at once.
    val schedule = readBack(dailyAtNine)!!.schedule as NotificationSchedule.Interval
    val late = Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 14, 23, 5, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val expected = Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 15, 21, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }

    assertEquals(expected.timeInMillis, schedule.interval.nextTrigger(late.time))
  }

  @Test
  fun keepsItsSourceSoStoringItAgainWritesTheSameJson() {
    assertEquals(dailyAtNine, readBack(dailyAtNine)!!.sourceJson)
  }

  @Test
  fun aStoredNullIsNothingRatherThanACrash() {
    assertNull(readBack("null"))
  }

  @Test
  fun keepsEachNotificationOfABatchAsItArrived() {
    val other = dailyAtNine.replace("\"id\":1", "\"id\":2")
    val sources = NotificationStorage.sourcesOfBatch(
      NotificationStorage.receiverJsonMapper(),
      """{"notifications":[$dailyAtNine,$other]}"""
    )

    assertEquals(2, sources.size)
    assertEquals(1, readBack(sources[0])!!.id)
    assertEquals(2, readBack(sources[1])!!.id)
    assertTrue(
      (readBack(sources[1])!!.schedule as NotificationSchedule.Interval).allowWhileIdle()
    )
  }
}
