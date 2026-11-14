// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.notification

import android.content.Context
import android.content.SharedPreferences
import app.tauri.Logger
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.Exception
import java.util.Date

// Key for private preferences
private const val NOTIFICATION_STORE_ID = "NOTIFICATION_STORE"
// Key used to save action types
private const val ACTION_TYPES_ID = "ACTION_TYPE_STORE"

class NotificationStorage(private val context: Context, private val jsonMapper: ObjectMapper) {
  /**
   * Keep each notification just scheduled as the JSON it arrived in, under its id, when it
   * belongs in the store (see keepsInStore), and take its id out when it does not.
   *
   * It used to write `sourceJson.toString()` with nothing ever assigning `sourceJson`, so
   * what it would have kept was the string "null". And the id comes out, rather than being
   * left alone, because scheduling has just cancelled whatever alarm that id had: an entry
   * left behind would be put back on the next restart.
   */
  fun appendNotifications(localNotifications: List<Notification>, now: Date = Date()) {
    val storage = getStorage(NOTIFICATION_STORE_ID)
    val editor = storage.edit()
    for (request in localNotifications) {
      val key: String = request.id.toString()
      val source = request.sourceJson
      if (source != null && keepsInStore(request, now)) {
        editor.putString(key, source)
      } else {
        editor.remove(key)
      }
    }
    editor.apply()
  }

  fun getSavedNotificationIds(): List<String> {
    val storage = getStorage(NOTIFICATION_STORE_ID)
    val all = storage.all
    return if (all != null) {
      ArrayList(all.keys)
    } else ArrayList()
  }

  fun getSavedNotifications(): List<Notification> {
    val storage = getStorage(NOTIFICATION_STORE_ID)
    val all = storage.all
    if (all != null) {
      val notifications = ArrayList<Notification>()
      for (key in all.keys) {
        val notificationString = all[key] as String? ?: continue
        try {
          decode(jsonMapper, notificationString)?.let { notifications.add(it) }
        } catch (ex: Exception) {
          Logger.error(Logger.tags("Notification"), "Stored notification $key cannot be read", ex)
        }
      }
      return notifications
    }
    return ArrayList()
  }

  fun getSavedNotification(key: String): Notification? {
    val storage = getStorage(NOTIFICATION_STORE_ID)
    val notificationString = try {
      storage.getString(key, null)
    } catch (ex: ClassCastException) {
      return null
    } ?: return null

    // Any exception, not only org.json's: Jackson throws its own, and this runs inside
    // broadcast receivers, where one escaping takes the delivery of the notification with it.
    return try {
      decode(jsonMapper, notificationString)
    } catch (ex: Exception) {
      Logger.error(Logger.tags("Notification"), "Stored notification $key cannot be read", ex)
      null
    }
  }

  fun deleteNotification(id: String?) {
    val editor = getStorage(NOTIFICATION_STORE_ID).edit()
    editor.remove(id)
    editor.apply()
  }

  fun deleteAllNotifications() {
    getStorage(NOTIFICATION_STORE_ID).edit().clear().apply()
  }

  private fun getStorage(key: String): SharedPreferences {
    return context.getSharedPreferences(key, Context.MODE_PRIVATE)
  }

  fun writeActionGroup(actions: List<ActionType>) {
    for (type in actions) {
      val i = type.id
      val editor = getStorage(ACTION_TYPES_ID + type.id).edit()
      editor.clear()
      editor.putInt("count", type.actions.size)
      for (action in type.actions) {
        editor.putString("id$i", action.id)
        editor.putString("title$i", action.title)
        editor.putBoolean("input$i", action.input ?: false)
      }
      editor.apply()
    }
  }

  fun getActionGroup(forId: String): Array<NotificationAction?> {
    val storage = getStorage(ACTION_TYPES_ID + forId)
    val count = storage.getInt("count", 0)
    val actions: Array<NotificationAction?> = arrayOfNulls(count)
    for (i in 0 until count) {
      val id = storage.getString("id$i", "")
      val title = storage.getString("title$i", "")
      val input = storage.getBoolean("input$i", false)

      val action = NotificationAction()
      action.id = id ?: ""
      action.title = title
      action.input = input
      actions[i] = action
    }
    return actions
  }

  companion object {
    /**
     * A mapper configured like the one Tauri hands the plugin, for the receivers, which run
     * without the plugin and so without its mapper.
     *
     * They used to build a bare `ObjectMapper()`, which was harmless while the store was
     * always empty and would not be now: what `show` stores is the JSON the Rust side sent,
     * and that carries fields this class does not have, such as `silent`. A bare mapper fails
     * on the first one, so every stored notification would read as unreadable, and in
     * TimedNotificationPublisher the exception would end the delivery before the
     * notification was shown. These are the three settings of tauri's PluginManager, so a
     * stored notification is read back exactly as `show` parsed it.
     */
    fun receiverJsonMapper(): ObjectMapper = ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

    /**
     * A stored notification, read back with the JSON it was stored as kept in `sourceJson`,
     * which is what a tap on it hands the app. Null for a stored JSON null.
     */
    fun decode(jsonMapper: ObjectMapper, json: String): Notification? {
      val notification = jsonMapper.readValue(json, Notification::class.java) ?: return null
      notification.sourceJson = json
      return notification
    }

    /** The JSON of each notification in a `batch` call, in the order they arrived. */
    fun sourcesOfBatch(jsonMapper: ObjectMapper, rawArgs: String): List<String> {
      val notifications = jsonMapper.readTree(rawArgs)?.get("notifications") ?: return listOf()
      return notifications.map { it.toString() }
    }
  }
}
