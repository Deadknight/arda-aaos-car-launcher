/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Controls the temporary notification card on the Launcher home screen. */
public final class ArdaNotificationPeekController {
    /** Notifies the activity when the peek must claim or release media-card space. */
    public interface VisibilityListener {
        void onVisibilityChanged(boolean visible);
    }

    private static final long PEEK_DURATION_MS = 7_000L;

    private final Activity mActivity;
    private final View mRoot;
    private final ImageView mIcon;
    private final TextView mAppName;
    private final TextView mTitle;
    private final TextView mText;
    private final VisibilityListener mVisibilityListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideRunnable = () -> hide(true);

    private boolean mVisible;

    public ArdaNotificationPeekController(
            Activity activity, View root, VisibilityListener visibilityListener) {
        mActivity = activity;
        mRoot = root;
        mVisibilityListener = visibilityListener;
        mIcon = root.findViewById(R.id.arda_notification_icon);
        mAppName = root.findViewById(R.id.arda_notification_app_name);
        mTitle = root.findViewById(R.id.arda_notification_title);
        mText = root.findViewById(R.id.arda_notification_text);
        mRoot.setOnClickListener(v -> hide(true));
    }

    /** Displays a peek from an {@link ArdaNotificationListenerService} broadcast. */
    public void show(Intent intent) {
        if (intent == null) {
            return;
        }

        String packageName = intent.getStringExtra(
                ArdaNotificationListenerService.EXTRA_PACKAGE);
        CharSequence appName = intent.getCharSequenceExtra(
                ArdaNotificationListenerService.EXTRA_APP_NAME);
        CharSequence title = intent.getCharSequenceExtra(
                ArdaNotificationListenerService.EXTRA_TITLE);
        CharSequence text = intent.getCharSequenceExtra(
                ArdaNotificationListenerService.EXTRA_TEXT);

        mAppName.setText(TextUtils.isEmpty(appName) ? packageName : appName);
        mTitle.setText(title);
        mTitle.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        mText.setText(text);
        mText.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);

        Drawable icon = null;
        if (!TextUtils.isEmpty(packageName)) {
            try {
                icon = mActivity.getPackageManager().getApplicationIcon(packageName);
            } catch (Exception ignored) {
                // Use fallback icon below.
            }
        }
        mIcon.setImageDrawable(icon != null
                ? icon : mActivity.getDrawable(R.drawable.ic_launcher_home));

        mHandler.removeCallbacks(mHideRunnable);
        if (!mVisible) {
            mVisible = true;
            mVisibilityListener.onVisibilityChanged(true);
        }

        mRoot.animate().cancel();
        mRoot.setVisibility(View.VISIBLE);
        mRoot.setAlpha(0f);
        mRoot.setTranslationY(
                -mActivity.getResources().getDimension(R.dimen.arda_notification_peek_offset));
        mRoot.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start();

        mHandler.postDelayed(mHideRunnable, PEEK_DURATION_MS);
    }

    public void hide(boolean animate) {
        mHandler.removeCallbacks(mHideRunnable);
        if (!mVisible) {
            mRoot.setVisibility(View.GONE);
            return;
        }

        mVisible = false;
        Runnable finish = () -> {
            mRoot.setVisibility(View.GONE);
            mRoot.setAlpha(1f);
            mRoot.setTranslationY(0f);
            mVisibilityListener.onVisibilityChanged(false);
        };

        mRoot.animate().cancel();
        if (animate && mRoot.getVisibility() == View.VISIBLE) {
            mRoot.animate()
                    .alpha(0f)
                    .translationY(
                            -mActivity.getResources().getDimension(
                                    R.dimen.arda_notification_peek_offset))
                    .setDuration(150L)
                    .withEndAction(finish)
                    .start();
        } else {
            finish.run();
        }
    }

    public void destroy() {
        mHandler.removeCallbacksAndMessages(null);
        mRoot.animate().cancel();
    }
}
