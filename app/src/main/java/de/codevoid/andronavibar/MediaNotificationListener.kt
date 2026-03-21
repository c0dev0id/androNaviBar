package de.codevoid.andronavibar

import android.service.notification.NotificationListenerService

/**
 * Minimal NotificationListenerService.
 *
 * The service itself does nothing — its sole purpose is to allow
 * MediaSessionManager.getActiveSessions() to work. That API requires
 * either the MEDIA_CONTENT_CONTROL (signature-level) permission or
 * an active notification listener. The user must enable this service
 * once in Settings → Notification access.
 */
class MediaNotificationListener : NotificationListenerService()
