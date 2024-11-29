/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.notifications.ui

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.ColorUtils
import com.android.systemui.util.NotificationUtils

class PeekDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), 
    NotificationListener.NotificationHandler, 
    ConfigurationController.ConfigurationListener {

    private var notificationShelf: RecyclerView? = null
    private var notificationCard: CardView? = null
    private var notificationIcon: ImageView? = null
    private var notificationTitle: TextView? = null
    private var notificationSummary: TextView? = null
    private var notificationHeader: TextView? = null
    private var overflowText: TextView? = null
    private var clearAllButton: ImageView? = null
    private var minimizeButton: ImageView? = null
    private var dismissButton: ImageView? = null
    private var clearAllHandler: Handler = Handler()
    private var currentDisplayedNotification: StatusBarNotification? = null

    private val notificationAdapter: NotificationAdapter = NotificationAdapter()

    private val activityStarter: ActivityStarter = Dependency.get(ActivityStarter::class.java)
    private val configurationController: ConfigurationController = Dependency.get(ConfigurationController::class.java)
    private val notificationListener: NotificationListener = Dependency.get(NotificationListener::class.java)

    private var isPeekDisplayEnabled = false
    private var showOverflow = false
    private var mDozing = false
    private var mPulsing = false

    private val settingsObserver: ContentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            updatePeekDisplayState()
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.peek_display, this, true)
        notificationShelf = findViewById(R.id.notificationShelf)
        notificationCard = findViewById(R.id.notificationCard)
        notificationIcon = findViewById(R.id.notificationIcon)
        notificationTitle = findViewById(R.id.notificationTitle)
        notificationSummary = findViewById(R.id.notificationSummary)
        notificationHeader = findViewById(R.id.notificationHeader)
        minimizeButton = findViewById(R.id.minimizeButton)
        dismissButton = findViewById(R.id.dismissButton)
        overflowText = findViewById(R.id.overflowText)
        clearAllButton = findViewById(R.id.clearAllButton)
        notificationShelf?.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        notificationShelf?.adapter = notificationAdapter
        notificationShelf?.setOnClickListener {
            if (notificationCard?.visibility == View.VISIBLE) {
                hideNotificationCard()
            }
        }
        notificationListener.addNotificationHandler(this)
        minimizeButton?.setOnClickListener { hideNotificationCard() }
        dismissButton?.setOnClickListener { removeCurrentNotification() }
        overflowText?.setOnClickListener { showClearAllButton() }
        clearAllButton?.setOnClickListener { clearAllNotifications() }
        notificationShelf?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                showClearAllButton()
            }
        })
    }
    
    private fun showClearAllButton() {
        overflowText?.visibility = View.GONE
        clearAllButton?.visibility = View.VISIBLE
        clearAllHandler.removeCallbacksAndMessages(null)
        clearAllHandler.postDelayed({
            if (clearAllButton?.visibility == View.VISIBLE) {
                hideClearAllButton()
            }
        }, 3000)
    }
    
    private fun hideClearAllButton() {
        clearAllButton?.visibility = View.GONE
        overflowText?.visibility = if (showOverflow) View.VISIBLE else View.GONE
    }

    fun clearAllNotifications() {
        try {
            notificationListener.cancelAllNotifications()
        } catch (e: Exception) {}
        updateNotificationShelf(emptyList())
        overflowText?.visibility = View.GONE
        clearAllButton?.visibility = View.GONE
    }

    fun removeCurrentNotification() {
        currentDisplayedNotification?.let { sbn ->
            val sbnKey = sbn.key
            notificationListener?.let { listener ->
                listener.cancelNotification(sbnKey)
                updateNotificationShelf(listener.getActiveNotifications().toList())
                hideNotificationCard()
            }
        }
    }

    fun updateNotificationShelf(notificationList: List<StatusBarNotification>) {
        val sortedNotifications = notificationList.sortedByDescending { it.postTime }
        val filteredNotifications = sortedNotifications.filter { sbn ->
            val (title, content) = NotificationUtils.resolveNotificationContent(sbn)
            !sbn.isOngoing() && (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0) && 
            (title.isNotBlank() || content.isNotBlank())
        }
        if (filteredNotifications.isNotEmpty()) {
            notificationAdapter.submitList(filteredNotifications)
            notificationShelf?.visibility = View.VISIBLE
        } else {
            notificationShelf?.visibility = View.GONE
        }
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.peek_display_notification_icon_size)
        val iconMarginEnd = context.resources.getDimensionPixelSize(R.dimen.peek_display_notification_icon_margin_end)
        val layoutParams = notificationShelf?.layoutParams as? ViewGroup.LayoutParams
        if (filteredNotifications.size <= 4) {
            layoutParams?.width = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            layoutParams?.width = (4 * iconSize) + (4 * iconMarginEnd)
        }
        notificationShelf?.layoutParams = layoutParams
        showOverflow = (filteredNotifications.size > 4)
        overflowText?.visibility = if (showOverflow) View.VISIBLE else View.GONE
        clearAllButton?.visibility = View.GONE
    }

    private fun toggleNotificationDetails(sbn: StatusBarNotification) {
        val (title, content) = NotificationUtils.resolveNotificationContent(sbn)
        if ((title.isBlank() && content.isBlank()) 
            || currentDisplayedNotification == sbn && notificationCard?.visibility == View.VISIBLE) {
            hideNotificationCard()
        } else {
            currentDisplayedNotification = sbn
            val subText = sbn.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val packageName = sbn.packageName
            val timeSinceArrival = android.text.format.DateUtils.getRelativeTimeSpanString(
                sbn.postTime, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
            val appLabel = try {
                context.packageManager.getApplicationLabel(NotificationUtils.getApplicationInfo(sbn, context)).toString()
            } catch (e: Exception) {
                packageName
            }
            val headerText = if (subText.isNotBlank()) {
                "$subText • $appLabel • $timeSinceArrival"
            } else {
                "$appLabel • $timeSinceArrival"
            }
            notificationHeader?.text = headerText
            notificationTitle?.text = title
            notificationSummary?.text = content
            notificationIcon?.setImageDrawable(NotificationUtils.resolveNotificationIcon(sbn, context))
            setClickListener(notificationCard, notificationSummary, notificationIcon, notificationHeader) {
                launchNotificationIntent()
            }
            notificationCard?.visibility = View.VISIBLE
            notificationCard?.scaleX = 0.8f
            notificationCard?.scaleY = 0.8f
            notificationCard?.alpha = 0f
            notificationCard?.animate()
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.alpha(1f)
                ?.setDuration(300L)
                ?.setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                ?.start()
        }
    }
    
    private fun launchNotificationIntent() {
        val currentNotification = currentDisplayedNotification ?: return
        val pkgName = currentNotification.packageName ?: return
        val pendingIntent = currentNotification.notification?.contentIntent
        pendingIntent?.let {
            try {
                activityStarter.postStartActivityDismissingKeyguard(it)
            } catch (e: Exception) {
                launchAppFromPackageName(pkgName)
            }
        } ?: launchAppFromPackageName(pkgName)
        removeCurrentNotification()
    }

    private fun launchAppFromPackageName(pkgName: String) {
        val appIntent = context.packageManager.getLaunchIntentForPackage(pkgName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        appIntent?.let {
            activityStarter.startActivity(it, true)
        }
    }

    private fun hideNotificationCard() {
        notificationCard?.animate()
            ?.scaleX(0.8f)
            ?.scaleY(0.8f)
            ?.alpha(0f)
            ?.setDuration(200L)
            ?.setInterpolator(android.view.animation.AccelerateInterpolator())
            ?.withEndAction {
                notificationCard?.visibility = View.GONE
                currentDisplayedNotification = null
            }
            ?.start()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap
    ) {
        if (sbn.isOngoing() || (sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) {
            return
        }
        updateNotificationShelf(notificationListener.getActiveNotifications().toList())
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap,
        reason: Int
    ) {
        updateNotificationShelf(notificationListener.getActiveNotifications().toList())
    }
    
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap
    ) {
        updateNotificationShelf(notificationListener.getActiveNotifications().toList())
    }

    override fun onNotificationRankingUpdate(rankingMap: NotificationListenerService.RankingMap) {}
    override fun onNotificationsInitialized() {}

    override fun onUiModeChanged() {
        updateViewColors()
    }
    
    override fun onThemeChanged() {
        updateViewColors()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configurationController.addCallback(this)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("peek_display_notifications"), false, settingsObserver)
        updatePeekDisplayState()
        updateViewColors()
        updateNotificationShelf(notificationListener.getActiveNotifications().toList())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        configurationController.removeCallback(this)
        context.contentResolver.unregisterContentObserver(settingsObserver)
    }

    private fun updatePeekDisplayState() {
        isPeekDisplayEnabled = Settings.Secure.getIntForUser(context.contentResolver,
            "peek_display_notifications", 0, UserHandle.USER_CURRENT) == 1
        visibility = if (isPeekDisplayEnabled) View.VISIBLE else View.GONE
    }

    private fun updateViewColors() {
        val surfaceColor = ColorUtils.getSurfaceColor(context)
        val primaryColor = ColorUtils.getPrimaryColor(context)
        notificationCard?.setCardBackgroundColor(surfaceColor)
        notificationTitle?.setTextColor(primaryColor)
        notificationSummary?.setTextColor(ColorUtils.getSecondaryColor(context))
        notificationHeader?.setTextColor(primaryColor)
        minimizeButton?.imageTintList = ColorStateList.valueOf(primaryColor)
        dismissButton?.imageTintList = ColorStateList.valueOf(primaryColor)
        notificationAdapter.notifyDataSetChanged()
        notificationShelf?.invalidate()
    }
    
    private fun setClickListener(vararg views: View?, action: () -> Unit) {
        views.forEach { view ->
            view?.setOnClickListener { action() }
        }
    }

    inner class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

        private val notifications: MutableList<StatusBarNotification> = mutableListOf()
        private var selectedPosition: Int = -1
        private var isHighlighted = false 

        fun submitList(list: List<StatusBarNotification>) {
            notifications.clear()
            notifications.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
            val iconView = ImageView(parent.context).apply {
                val iconSize = parent.context.resources.getDimensionPixelSize(R.dimen.peek_display_notification_icon_size)
                layoutParams = RecyclerView.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = parent.context.resources.getDimensionPixelSize(R.dimen.peek_display_notification_icon_margin_end)
                }
                isClickable = true
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            return NotificationViewHolder(iconView)
        }

        override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
            val notification = notifications[position]
            val context = holder.iconView.context
            val iconDrawable = NotificationUtils.resolveSmallIcon(notification, context)
            holder.iconView.setImageDrawable(iconDrawable)
            holder.iconView.setOnClickListener {
                toggleNotificationDetails(notification)
            }
        }

        override fun getItemCount(): Int = notifications.size

        inner class NotificationViewHolder(val iconView: ImageView) : RecyclerView.ViewHolder(iconView)
    }
}
