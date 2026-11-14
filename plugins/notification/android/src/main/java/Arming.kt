// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import android.os.Build
import java.util.Date

// The decisions behind scheduling, restoring and delivering a notification, kept apart from
// the Android calls that carry them out so that they can be tested on a plain JVM. Each one
// was wrong, or missing, in a way that cost somebody a notification.

/** What arming a notification does, in order. */
internal enum class ArmStep {
  /** Take the notification with this id off the tray. */
  DismissVisible,
  /** Cancel the alarm this id already has. */
  CancelTimer,
  /** Build the notification and show it, or put its alarm on the queue. */
  Build,
}

/**
 * The steps for arming one notification.
 *
 * Scheduling from the app clears what that id is showing first: somebody just asked for it
 * again, and the old one would be stale. Restoring after a restart or an update does not.
 * Nobody asked for anything then, and an evening reminder still unread in the tray at
 * 23:30 would vanish because an update was installed.
 */
internal fun armSteps(restoring: Boolean): List<ArmStep> =
  if (restoring) {
    listOf(ArmStep.CancelTimer, ArmStep.Build)
  } else {
    listOf(ArmStep.DismissVisible, ArmStep.CancelTimer, ArmStep.Build)
  }

/**
 * Whether this schedule can be put on the alarm queue at all. A one-off whose time has
 * passed cannot: arming refuses it, and nothing else would ever fire it.
 */
internal fun isArmable(schedule: NotificationSchedule, now: Date): Boolean =
  when (schedule) {
    is NotificationSchedule.At -> !schedule.date.before(now)
    else -> true
  }

/**
 * Whether a notification just scheduled belongs in the store, which is what the restore
 * receiver puts back after a restart. Only one that was armed and whose source is known. A
 * refused one kept there would come back fifteen seconds after every restart, as a
 * notification nobody scheduled.
 */
internal fun keepsInStore(notification: Notification, now: Date): Boolean {
  val schedule = notification.schedule ?: return false
  return notification.sourceJson != null && isArmable(schedule, now)
}

/**
 * Arm every restored notification, each on its own. One that throws, an invalid icon
 * colour for instance, is reported and the rest are armed anyway; otherwise a single bad
 * entry would take every other notification with it, on every restart. Returns how many
 * were armed.
 */
internal fun restoreEach(
  notifications: List<Notification>,
  arm: (Notification) -> Unit,
  failed: (Notification, Exception) -> Unit
): Int {
  var armed = 0
  for (notification in notifications) {
    try {
      arm(notification)
      armed++
    } catch (ex: Exception) {
      failed(notification, ex)
    }
  }
  return armed
}

/**
 * Deliver a notification whose alarm went off, and set up the next one.
 *
 * The notification is shown first, and telling a running app about it comes after and
 * cannot stop anything: it used to come first, and when it threw, the notification was
 * neither shown nor re-armed for the next day. Re-arming happens whatever the first two did,
 * because a repeating notification that misses one re-arming is gone for good.
 */
internal fun deliver(
  show: () -> Unit,
  tellApp: () -> Unit,
  rearm: () -> Boolean,
  forget: () -> Unit,
  removable: Boolean,
  failed: (String, Exception) -> Unit
) {
  try {
    show()
  } catch (ex: Exception) {
    failed("show", ex)
  }
  try {
    tellApp()
  } catch (ex: Exception) {
    failed("tell the app", ex)
  }
  val rearmed = try {
    rearm()
  } catch (ex: Exception) {
    failed("re-arm", ex)
    false
  }
  if (forgetsAfterShowing(rearmed, removable)) {
    forget()
  }
}

/**
 * Whether the store forgets a notification once it has been shown: only a one-off. A
 * repeating one stays stored even when this receiver does not re-arm it itself, as with
 * `Every` and a repeating `At`, whose alarms AlarmManager repeats on its own; forgetting
 * them would leave nothing for the restore receiver after the next restart.
 */
internal fun forgetsAfterShowing(rearmed: Boolean, removable: Boolean): Boolean =
  !rearmed && removable

/**
 * Whether to ask for an exact alarm.
 *
 * Exact only when the app's own manifest asks for it and Android grants it. The manifest
 * part is not redundant. On the update that stops declaring SCHEDULE_EXACT_ALARM, the
 * restore receiver runs on MY_PACKAGE_REPLACED, and nothing guarantees that
 * `canScheduleExactAlarms()` already reflects the new manifest by then; an exact alarm
 * armed in that gap is one AlarmManagerService removes a moment later, without telling
 * anybody. The installed package's requested permissions are already the new ones.
 */
internal fun armsExact(sdk: Int, declaresExactAlarms: Boolean, canScheduleExact: () -> Boolean): Boolean =
  sdk < Build.VERSION_CODES.S || (declaresExactAlarms && canScheduleExact())
