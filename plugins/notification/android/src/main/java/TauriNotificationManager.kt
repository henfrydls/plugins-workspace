// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.tauri.Logger
import app.tauri.plugin.JSObject
import app.tauri.plugin.PluginManager
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

// Action constants
const val NOTIFICATION_INTENT_KEY = "NotificationId"
const val NOTIFICATION_OBJ_INTENT_KEY = "LocalNotficationObject"
const val ACTION_INTENT_KEY = "NotificationUserAction"
const val NOTIFICATION_IS_REMOVABLE_KEY = "NotificationRepeating"
const val REMOTE_INPUT_KEY = "NotificationRemoteInput"
const val DEFAULT_NOTIFICATION_CHANNEL_ID = "default"
const val DEFAULT_PRESS_ACTION = "tap"

class TauriNotificationManager(
  private val storage: NotificationStorage,
  private val activity: Activity?,
  private val context: Context,
  private val config: PluginConfig?
) {
  private var defaultSoundID: Int = AssetUtils.RESOURCE_ID_ZERO_VALUE
  private var defaultSmallIconID: Int = AssetUtils.RESOURCE_ID_ZERO_VALUE

  fun handleNotificationActionPerformed(
    data: Intent,
    notificationStorage: NotificationStorage
  ): JSObject? {
    Logger.debug(Logger.tags("Notification"), "Notification received: " + data.dataString)
    val notificationId =
      data.getIntExtra(NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
    if (notificationId == Int.MIN_VALUE) {
      Logger.debug(Logger.tags("Notification"), "Activity started without notification attached")
      return null
    }
    val isRemovable =
      data.getBooleanExtra(NOTIFICATION_IS_REMOVABLE_KEY, true)
    if (isRemovable) {
      notificationStorage.deleteNotification(notificationId.toString())
    }
    val dataJson = JSObject()
    val results = RemoteInput.getResultsFromIntent(data)
    val input = results?.getCharSequence(REMOTE_INPUT_KEY)
    dataJson.put("inputValue", input?.toString())
    val menuAction = data.getStringExtra(ACTION_INTENT_KEY)
    dismissVisibleNotification(notificationId)
    dataJson.put("actionId", menuAction)
    var request: JSONObject? = null
    try {
      val notificationJsonString =
        data.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)
      if (notificationJsonString != null) {
        request = JSObject(notificationJsonString)
      }
    } catch (_: JSONException) {
    }
    dataJson.put("notification", request)
    return dataJson
  }

  /**
   * Create notification channel
   */
  fun createNotificationChannel() {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (SDK_INT >= Build.VERSION_CODES.O) {
      val name: CharSequence = "Default"
      val description = "Default"
      val importance = NotificationManager.IMPORTANCE_DEFAULT
      val channel = NotificationChannel(DEFAULT_NOTIFICATION_CHANNEL_ID, name, importance)
      channel.description = description
      val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()
      val soundUri = getDefaultSoundUrl(context)
      if (soundUri != null) {
        channel.setSound(soundUri, audioAttributes)
      }
      // Register the channel with the system; you can't change the importance
      // or other notification behaviors after this
      val notificationManager = context.getSystemService(
        NotificationManager::class.java
      )
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun trigger(
    notificationManager: NotificationManagerCompat,
    notification: Notification,
    restoring: Boolean = false
  ): Int {
    for (step in armSteps(restoring)) {
      when (step) {
        ArmStep.DismissVisible -> dismissVisibleNotification(notification.id)
        ArmStep.CancelTimer -> cancelTimerForNotification(notification.id)
        ArmStep.Build -> buildNotification(notificationManager, notification)
      }
    }

    return notification.id
  }

  fun schedule(notification: Notification): Int {
    val notificationManager = NotificationManagerCompat.from(context)
    return trigger(notificationManager, notification)
  }

  /**
   * Put stored notifications back on the alarm queue after a restart or an update, leaving
   * the tray alone (see armSteps), each one on its own (see restoreEach).
   */
  fun restore(notifications: List<Notification>) {
    val notificationManager = NotificationManagerCompat.from(context)
    restoreEach(
      notifications,
      { trigger(notificationManager, it, restoring = true) },
      { notification, ex ->
        Logger.error(Logger.tags("Notification"), "Notification ${notification.id} could not be restored", ex)
      }
    )
  }

  fun schedule(notifications: List<Notification>): List<Int> {
    val ids = mutableListOf<Int>()
    val notificationManager = NotificationManagerCompat.from(context)

    for (notification in notifications) {
      val id = trigger(notificationManager, notification)
      ids.add(id)
    }

    return ids
  }

  // TODO Progressbar support
  // TODO System categories (DO_NOT_DISTURB etc.)
  // TODO use NotificationCompat.MessagingStyle for latest API
  // TODO expandable notification NotificationCompat.MessagingStyle
  // TODO media style notification support NotificationCompat.MediaStyle
  @SuppressLint("MissingPermission")
  private fun buildNotification(
    notificationManager: NotificationManagerCompat,
    notification: Notification,
  ) {
    val channelId = notification.channelId ?: DEFAULT_NOTIFICATION_CHANNEL_ID
    val mBuilder = NotificationCompat.Builder(
      context, channelId
    )
      .setContentTitle(notification.title)
      .setContentText(notification.body)
      .setAutoCancel(notification.isAutoCancel)
      .setOngoing(notification.isOngoing)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setGroupSummary(notification.isGroupSummary)
    if (notification.largeBody != null) {
      // support multiline text
      mBuilder.setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(notification.largeBody)
          .setSummaryText(notification.summary)
      )
    } else if (notification.inboxLines != null) {
      val inboxStyle = NotificationCompat.InboxStyle()
      for (line in notification.inboxLines ?: listOf()) {
        inboxStyle.addLine(line)
      }
      inboxStyle.setBigContentTitle(notification.title)
      inboxStyle.setSummaryText(notification.summary)
      mBuilder.setStyle(inboxStyle)
    }
    val sound = notification.getSound(context, getDefaultSound(context))
    if (sound != null) {
      val soundUri = Uri.parse(sound)
      // Grant permission to use sound
      context.grantUriPermission(
        "com.android.systemui",
        soundUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
      )
      mBuilder.setSound(soundUri)
      mBuilder.setDefaults(android.app.Notification.DEFAULT_VIBRATE or android.app.Notification.DEFAULT_LIGHTS)
    } else {
      mBuilder.setDefaults(android.app.Notification.DEFAULT_ALL)
    }
    val group = notification.group
    if (group != null) {
      mBuilder.setGroup(group)
      if (notification.isGroupSummary) {
        mBuilder.setSubText(notification.summary)
      }
    }
    mBuilder.setVisibility(notification.visibility ?: NotificationCompat.VISIBILITY_PRIVATE)
    mBuilder.setOnlyAlertOnce(true)
    mBuilder.setSmallIcon(notification.getSmallIcon(context, getDefaultSmallIcon(context)))
    mBuilder.setLargeIcon(notification.getLargeIcon(context))
    val iconColor = notification.getIconColor(config?.iconColor ?: "")
    if (iconColor.isNotEmpty()) {
      try {
        mBuilder.color = Color.parseColor(iconColor)
      } catch (ex: IllegalArgumentException) {
        throw Exception("Invalid color provided. Must be a hex string (ex: #ff0000")
      }
    }
    createActionIntents(notification, mBuilder)
    // notificationId is a unique int for each notification that you must define
    val buildNotification = mBuilder.build()
    if (notification.schedule != null) {
      triggerScheduledNotification(buildNotification, notification)
    } else {
      notificationManager.notify(notification.id, buildNotification)
      try {
        NotificationPlugin.triggerNotification(notification)
      } catch (_: JSONException) {
      }
    }
  }

  // Create intents for open/dismiss actions
  private fun createActionIntents(
    notification: Notification,
    mBuilder: NotificationCompat.Builder
  ) {
    // Open intent
    val intent = buildIntent(notification, DEFAULT_PRESS_ACTION)
    var flags = PendingIntent.FLAG_CANCEL_CURRENT
    if (SDK_INT >= Build.VERSION_CODES.S) {
      flags = flags or PendingIntent.FLAG_MUTABLE
    }
    val pendingIntent = PendingIntent.getActivity(context, notification.id, intent, flags)
    mBuilder.setContentIntent(pendingIntent)

    // Build action types
    val actionTypeId = notification.actionTypeId
    if (actionTypeId != null) {
      val actionGroup = storage.getActionGroup(actionTypeId)
      for (notificationAction in actionGroup) {
        // TODO Add custom icons to actions
        val actionIntent = buildIntent(notification, notificationAction!!.id)
        val actionPendingIntent = PendingIntent.getActivity(
          context,
          (notification.id) + notificationAction.id.hashCode(),
          actionIntent,
          flags
        )
        val actionBuilder: NotificationCompat.Action.Builder = NotificationCompat.Action.Builder(
          R.drawable.ic_transparent,
          notificationAction.title,
          actionPendingIntent
        )
        if (notificationAction.input == true) {
          val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(
            notificationAction.title
          ).build()
          actionBuilder.addRemoteInput(remoteInput)
        }
        mBuilder.addAction(actionBuilder.build())
      }
    }

    // Dismiss intent
    val dissmissIntent = Intent(
      context,
      NotificationDismissReceiver::class.java
    )
    dissmissIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    dissmissIntent.putExtra(NOTIFICATION_INTENT_KEY, notification.id)
    dissmissIntent.putExtra(ACTION_INTENT_KEY, "dismiss")
    val schedule = notification.schedule
    dissmissIntent.putExtra(
      NOTIFICATION_IS_REMOVABLE_KEY,
      schedule == null || schedule.isRemovable()
    )
    flags = 0
    if (SDK_INT >= Build.VERSION_CODES.S) {
      flags = PendingIntent.FLAG_MUTABLE
    }
    val deleteIntent =
      PendingIntent.getBroadcast(context, notification.id, dissmissIntent, flags)
    mBuilder.setDeleteIntent(deleteIntent)
  }

  private fun buildIntent(notification: Notification, action: String?): Intent {
    val intent = if (activity != null) {
      Intent(context, activity.javaClass)
    } else {
      val packageName = context.packageName
      context.packageManager.getLaunchIntentForPackage(packageName)!!
    }
    intent.action = Intent.ACTION_MAIN
    intent.addCategory(Intent.CATEGORY_LAUNCHER)
    intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    intent.putExtra(NOTIFICATION_INTENT_KEY, notification.id)
    intent.putExtra(ACTION_INTENT_KEY, action)
    intent.putExtra(NOTIFICATION_OBJ_INTENT_KEY, notification.sourceJson)
    val schedule = notification.schedule
    intent.putExtra(NOTIFICATION_IS_REMOVABLE_KEY, schedule == null || schedule.isRemovable())
    return intent
  }

  /**
   * Build a notification trigger, such as triggering each N seconds, or
   * on a certain date "shape" (such as every first of the month)
   */
  // TODO support different AlarmManager.RTC modes depending on priority
  @SuppressLint("SimpleDateFormat")
  private fun triggerScheduledNotification(notification: android.app.Notification, request: Notification) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val schedule = request.schedule
    val notificationIntent = Intent(
      context,
      TimedNotificationPublisher::class.java
    )
    notificationIntent.putExtra(NOTIFICATION_INTENT_KEY, request.id)
    notificationIntent.putExtra(TimedNotificationPublisher.NOTIFICATION_KEY, notification)
    var flags = PendingIntent.FLAG_CANCEL_CURRENT
    if (SDK_INT >= Build.VERSION_CODES.S) {
      flags = flags or PendingIntent.FLAG_MUTABLE
    }
    var pendingIntent =
      PendingIntent.getBroadcast(context, request.id, notificationIntent, flags)

    when (schedule) {
      is NotificationSchedule.At -> {
        if (!isArmable(schedule, Date())) {
          Logger.error(Logger.tags("Notification"), "Scheduled time must be *after* current time", null)
          return
        }
        if (schedule.repeating) {
          val interval: Long = schedule.date.time - Date().time
          alarmManager.setRepeating(AlarmManager.RTC, schedule.date.time, interval, pendingIntent)
        } else {
          setAlarm(context, alarmManager, schedule.date.time, schedule.allowWhileIdle(), pendingIntent)
        }
      }
      is NotificationSchedule.Interval -> {
        val trigger = schedule.interval.nextTrigger(Date())
        notificationIntent.putExtra(TimedNotificationPublisher.CRON_KEY, schedule.interval.toMatchString())
        notificationIntent.putExtra(
          TimedNotificationPublisher.ALLOW_WHILE_IDLE_KEY,
          schedule.allowWhileIdle()
        )
        pendingIntent =
          PendingIntent.getBroadcast(context, request.id, notificationIntent, flags)
        setAlarm(context, alarmManager, trigger, schedule.allowWhileIdle(), pendingIntent)
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
        Logger.debug(
          Logger.tags("Notification"),
          "notification " + request.id + " will next fire at " + sdf.format(Date(trigger))
        )
      }
      is NotificationSchedule.Every -> {
        val everyInterval = getIntervalTime(schedule.interval, schedule.count)
        val startTime: Long = Date().time + everyInterval
        alarmManager.setRepeating(AlarmManager.RTC, startTime, everyInterval, pendingIntent)
      }
      else -> {}
    }
  }

  fun cancel(notifications: List<Int>) {
    for (id in notifications) {
      dismissVisibleNotification(id)
      cancelTimerForNotification(id)
      storage.deleteNotification(id.toString())
    }
  }

  /**
   * Cancel everything the store knows about, and empty it. Since `show` writes the store,
   * an entry left there comes back on every restart, so "cancel all" has to reach it. An
   * alarm armed by a build that did not write the store is not listed anywhere and is
   * beyond its reach, as it always was.
   */
  fun cancelAll() {
    cancel(storage.getSavedNotificationIds().mapNotNull { it.toIntOrNull() })
    storage.deleteAllNotifications()
  }

  private fun cancelTimerForNotification(notificationId: Int) {
    val intent = Intent(context, TimedNotificationPublisher::class.java)
    var flags = 0
    if (SDK_INT >= Build.VERSION_CODES.S) {
      flags = PendingIntent.FLAG_MUTABLE
    }
    val pi = PendingIntent.getBroadcast(context, notificationId, intent, flags)
    if (pi != null) {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarmManager.cancel(pi)
    }
  }

  private fun dismissVisibleNotification(notificationId: Int) {
    val notificationManager = NotificationManagerCompat.from(
      context
    )
    notificationManager.cancel(notificationId)
  }

  fun areNotificationsEnabled(): Boolean {
    val notificationManager = NotificationManagerCompat.from(context)
    return notificationManager.areNotificationsEnabled()
  }

  private fun getDefaultSoundUrl(context: Context): Uri? {
    val soundId = getDefaultSound(context)
    return if (soundId != AssetUtils.RESOURCE_ID_ZERO_VALUE) {
      Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + soundId)
    } else null
  }

  private fun getDefaultSound(context: Context): Int {
    if (defaultSoundID != AssetUtils.RESOURCE_ID_ZERO_VALUE) return defaultSoundID
    var resId: Int = AssetUtils.RESOURCE_ID_ZERO_VALUE
    val soundConfigResourceName = AssetUtils.getResourceBaseName(config?.sound)
    if (soundConfigResourceName != null) {
      resId = AssetUtils.getResourceID(context, soundConfigResourceName, "raw")
    }
    defaultSoundID = resId
    return resId
  }

  private fun getDefaultSmallIcon(context: Context): Int {
    if (defaultSmallIconID != AssetUtils.RESOURCE_ID_ZERO_VALUE) return defaultSmallIconID
    var resId: Int = AssetUtils.RESOURCE_ID_ZERO_VALUE
    val smallIconConfigResourceName = AssetUtils.getResourceBaseName(config?.icon)
    if (smallIconConfigResourceName != null) {
      resId = AssetUtils.getResourceID(context, smallIconConfigResourceName, "drawable")
    }
    if (resId == AssetUtils.RESOURCE_ID_ZERO_VALUE) {
      resId = android.R.drawable.ic_dialog_info
    }
    defaultSmallIconID = resId
    return resId
  }
}

class NotificationDismissReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val intExtra =
      intent.getIntExtra(NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
    if (intExtra == Int.MIN_VALUE) {
      Logger.error(Logger.tags("Notification"), "Invalid notification dismiss operation", null)
      return
    }
    val isRemovable =
      intent.getBooleanExtra(NOTIFICATION_IS_REMOVABLE_KEY, true)
    if (isRemovable) {
      val notificationStorage = NotificationStorage(context, NotificationStorage.receiverJsonMapper())
      notificationStorage.deleteNotification(intExtra.toString())
    }
  }
}

class TimedNotificationPublisher : BroadcastReceiver() {
  /**
   * Restore and present notification
   */
  override fun onReceive(context: Context, intent: Intent) {
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(
        NOTIFICATION_KEY,
        android.app.Notification::class.java
      )
    } else {
      getParcelableExtraLegacy(intent, NOTIFICATION_KEY)
    }
    notification?.`when` = System.currentTimeMillis()
    val id = intent.getIntExtra(NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
    if (id == Int.MIN_VALUE) {
      Logger.error(Logger.tags("Notification"), "No valid id supplied", null)
    }
    val storage = NotificationStorage(context, NotificationStorage.receiverJsonMapper())

    val savedNotification = storage.getSavedNotification(id.toString())
    // What the schedule asked for, carried on the intent beside the cron string, which is
    // how the receiver already learns everything else it knows. The intent is cloned on
    // each re-arming, so the answer travels with the alarm for as long as the alarm exists.
    //
    // It was put there when the storage was always empty, and it stays there now that
    // `show` writes the storage: an alarm armed by a build from before that change has an
    // intent with the flag and no stored notification at all.
    val allowWhileIdle = intent.getBooleanExtra(ALLOW_WHILE_IDLE_KEY, false)
    // Shown first, the app told after, re-armed whatever happened: see deliver.
    deliver(
      show = { notificationManager.notify(id, notification) },
      tellApp = { savedNotification?.let { NotificationPlugin.triggerNotification(it) } },
      rearm = { rescheduleNotificationIfNeeded(context, intent, id, allowWhileIdle) },
      forget = { storage.deleteNotification(id.toString()) },
      removable = savedNotification?.schedule?.isRemovable() ?: true,
      failed = { what, ex ->
        Logger.error(Logger.tags("Notification"), "Notification $id: could not $what", ex)
      }
    )
  }

  @Suppress("DEPRECATION")
  private fun getParcelableExtraLegacy(intent: Intent, string: String): android.app.Notification? {
    return intent.getParcelableExtra(string)
  }

  @SuppressLint("MissingPermission", "SimpleDateFormat")
  private fun rescheduleNotificationIfNeeded(
    context: Context,
    intent: Intent,
    id: Int,
    allowWhileIdle: Boolean
  ): Boolean {
    val dateString = intent.getStringExtra(CRON_KEY)
    if (dateString != null) {
      val date = DateMatch.fromMatchString(dateString)
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      val trigger = date.nextTrigger(Date())
      val clone = intent.clone() as Intent
      var flags = PendingIntent.FLAG_CANCEL_CURRENT
      if (SDK_INT >= Build.VERSION_CODES.S) {
        flags = flags or PendingIntent.FLAG_MUTABLE
      }
      val pendingIntent = PendingIntent.getBroadcast(context, id, clone, flags)
      setAlarm(context, alarmManager, trigger, allowWhileIdle, pendingIntent)
      val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
      Logger.debug(
        Logger.tags("Notification"),
        "notification " + id + " will next fire at " + sdf.format(Date(trigger))
      )
      return true
    }
    return false
  }

  companion object {
    var NOTIFICATION_KEY = "NotificationPublisher.notification"
    var CRON_KEY = "NotificationPublisher.cron"
    var ALLOW_WHILE_IDLE_KEY = "NotificationPublisher.allowWhileIdle"
  }
}

/**
 * Put every stored notification back on the alarm queue.
 *
 * After a restart, because Android empties the queue when it shuts down. After an update
 * of the app too (MY_PACKAGE_REPLACED), because an update can take the alarms with it: an
 * app whose new version no longer declares SCHEDULE_EXACT_ALARM has its exact alarms
 * removed by AlarmManagerService on install, without the app being run or told. Either
 * way, without this a repeating notification stays gone until somebody opens the app.
 *
 * Re-arming one that is still on the queue does not double it. `restore` cancels the
 * timer for that id and arms the new one under the same id with FLAG_CANCEL_CURRENT, so
 * an update that kept the alarm, or a second boot broadcast, ends with one alarm. It
 * leaves the tray alone: a notification still unread there stays.
 *
 * Whether the alarm is exact is decided by setAlarm, from the installed manifest as well as
 * from `canScheduleExactAlarms()`, which may still answer for the old version on
 * MY_PACKAGE_REPLACED (see armsExact).
 *
 * A repeating schedule is armed for its next match from now, so nothing fires at once.
 * Only a one-off whose time passed while the phone was off is shown, fifteen seconds
 * later, which is what the original restore was written to do.
 */
class LocalNotificationRestoreReceiver : BroadcastReceiver() {
  @SuppressLint("ObsoleteSdkInt")
  override fun onReceive(context: Context, intent: Intent) {
    // The receiver is direct boot aware, so LOCKED_BOOT_COMPLETED reaches it before the
    // user has unlocked, when the store, which lives in credential protected storage,
    // cannot be read. It leaves; BOOT_COMPLETED follows the unlock and does the work. An
    // update is only ever installed for a running user, so MY_PACKAGE_REPLACED passes.
    if (SDK_INT >= Build.VERSION_CODES.N) {
      val um = context.getSystemService(
        UserManager::class.java
      )
      if (um == null || !um.isUserUnlocked) return
    }
    val storage = NotificationStorage(context, NotificationStorage.receiverJsonMapper())
    val ids = storage.getSavedNotificationIds()
    val notifications = mutableListOf<Notification>()
    for (id in ids) {
      val notification = storage.getSavedNotification(id) ?: continue
      val schedule = notification.schedule
      if (schedule != null && schedule is NotificationSchedule.At) {
        val at: Date = schedule.date
        if (at.before(Date())) {
          // modify the scheduled date in order to show notifications that would have been delivered while device was off.
          //
          // Known and left alone: a *repeating* At takes its repeat gap from the time left
          // until its date (triggerScheduledNotification), so one restored here repeats
          // every fifteen seconds. Its real gap is not stored anywhere to put it back.
          val newDateTime = Date().time + 15 * 1000
          schedule.date = Date(newDateTime)
        }
      }
      notifications.add(notification)
    }
    // The moved date is not stored back. That write used to store the string "null" over
    // the notification, and storing the source JSON again would keep the old date anyway.
    // Nothing is lost: the stored date stays in the past, so a restart before the fifteen
    // seconds are up moves it again, and once it is shown the publisher forgets it.

    var config: PluginConfig? = null
    try {
      config = PluginManager.loadConfig(context, "notification", PluginConfig::class.java)
    } catch (ex: Exception) {
      ex.printStackTrace()
    }
    val notificationManager = TauriNotificationManager(storage, null, context, config)
    notificationManager.restore(notifications)
  }
}

/**
 * Put one alarm on the queue, as exactly and as insistently as this device allows.
 *
 * Shared by the first firing and by every re-arming after it. They used to decide this
 * separately and had already drifted apart: a scheduled notification would wake a dozing
 * phone on the day it was created and then, from the second day on, wait for the phone to
 * be picked up, because the receiver re-armed with RTC and no idle allowance. Nothing in
 * the schedule said to stop allowing it; the second copy of the decision simply never
 * read it.
 *
 * `allowWhileIdle` is what the caller asked for, and it only ever relaxes Doze for this
 * one alarm. Exactness is not asked for: it is whatever the device will give, so an app
 * without SCHEDULE_EXACT_ALARM still gets its notification, just moved. See armsExact for
 * why the manifest is read as well as the grant.
 */
// NewApi: canScheduleExactAlarms is only called from S on; armsExact checks the version
// before calling it, which lint cannot see through the lambda.
@SuppressLint("ObsoleteSdkInt", "MissingPermission", "NewApi")
internal fun setAlarm(
  context: Context,
  alarmManager: AlarmManager,
  trigger: Long,
  allowWhileIdle: Boolean,
  pendingIntent: PendingIntent
) {
  val exact = armsExact(SDK_INT, declaresExactAlarms(context)) {
    alarmManager.canScheduleExactAlarms()
  }
  val whileIdle = SDK_INT >= Build.VERSION_CODES.M && allowWhileIdle
  when {
    exact && whileIdle ->
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
    exact -> alarmManager.setExact(AlarmManager.RTC, trigger, pendingIntent)
    whileIdle -> alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
    else -> alarmManager[AlarmManager.RTC, trigger] = pendingIntent
  }
}

/**
 * Whether the installed package asks for exact alarms in its manifest. Read from the
 * package, which on MY_PACKAGE_REPLACED is already the new version. If it cannot be read,
 * the answer is no, and the alarm is inexact rather than one that may be removed.
 */
@SuppressLint("InlinedApi")
internal fun declaresExactAlarms(context: Context): Boolean {
  return try {
    val pm = context.packageManager
    val info = if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
      )
    } else {
      @Suppress("DEPRECATION")
      pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    val requested = info.requestedPermissions ?: return false
    requested.any {
      it == Manifest.permission.SCHEDULE_EXACT_ALARM || it == Manifest.permission.USE_EXACT_ALARM
    }
  } catch (ex: Exception) {
    Logger.error(Logger.tags("Notification"), "Could not read the app's own permissions", ex)
    false
  }
}
