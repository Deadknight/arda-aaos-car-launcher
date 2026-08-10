/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

/**
 * Forwards eligible notifications to the active CarLauncher home screen as temporary peeks.
 */
public final class ArdaNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "ArdaNotifListener";
    public static final String ACTION_NOTIFICATION_PEEK =
            "com.android.car.carlauncher.action.NOTIFICATION_PEEK";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null
                || (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        Ranking ranking = new Ranking();
        if (rankingMap != null
                && rankingMap.getRanking(sbn.getKey(), ranking)
                && ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
            return;
        }

        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(text)) {
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) {
            return;
        }

        String appName = sbn.getPackageName();
        try {
            appName = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Exception ignored) {
            // Keep the package name.
        }

        Intent intent = new Intent(ACTION_NOTIFICATION_PEEK);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_PACKAGE, sbn.getPackageName());
        intent.putExtra(EXTRA_APP_NAME, appName);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_TEXT, text);
        sendBroadcast(intent);
        Log.i(TAG, "ARDA_NOTIFICATION_PEEK_FORWARDED package=" + sbn.getPackageName());
    }
}
