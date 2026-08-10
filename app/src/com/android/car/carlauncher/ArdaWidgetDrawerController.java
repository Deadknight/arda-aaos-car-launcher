/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.viewpager2.widget.ViewPager2;

import java.util.List;

/** Controls the Launcher-only widget panel and its right-edge swipe gesture. */
public final class ArdaWidgetDrawerController {
    public interface OpenStateListener {
        void onOpenStateChanged(boolean open, boolean animate);
    }

    public static final String ACTION_TOGGLE_WIDGET_DRAWER =
            "com.android.car.carlauncher.action.TOGGLE_WIDGET_DRAWER";

    private static final String TAG = "ArdaWidgetDrawer";
    private static final long PROGRESS_HIDE_DELAY_MS = 850L;
    private static final long PROGRESS_FADE_MS = 180L;

    private final Activity mActivity;
    private final View mPanelRoot;
    private final View mPanelCard;
    private final ViewPager2 mPager;
    private final LinearLayout mPageDots;
    private final ArdaWidgetProviderPagerAdapter mAdapter;
    private final OpenStateListener mOpenStateListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final int mEdgeSizePx;
    private final int mSwipeThresholdPx;
    private final int mDotSizePx;
    private final int mDotActiveSizePx;
    private final int mDotGapPx;

    private final Runnable mHideProgressRunnable = this::hidePageDots;
    private final ViewPager2.OnPageChangeCallback mPageChangeCallback =
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageScrolled(
                        int position, float positionOffset, int positionOffsetPixels) {
                    updateDots(position + positionOffset);
                    if (positionOffsetPixels != 0) {
                        showPageDots();
                    }
                }

                @Override
                public void onPageSelected(int position) {
                    updateDots(position);
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING
                            || state == ViewPager2.SCROLL_STATE_SETTLING) {
                        showPageDots();
                    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        schedulePageDotsHide();
                    }
                }
            };

    private boolean mOpen;
    private boolean mTracking;
    private boolean mGestureClaimed;
    private float mDownX;
    private float mDownY;
    private int mPageCount;
    private float mPagePosition;

    public ArdaWidgetDrawerController(
            Activity activity, OpenStateListener openStateListener) {
        mActivity = activity;
        mOpenStateListener = openStateListener;
        mPanelRoot = activity.findViewById(R.id.arda_widget_overlay);
        mPanelCard = activity.findViewById(R.id.arda_widget_drawer);
        mPager = activity.findViewById(R.id.arda_widget_pager);
        mPageDots = activity.findViewById(R.id.arda_widget_page_dots_vertical);
        mEdgeSizePx = activity.getResources().getDimensionPixelSize(
                R.dimen.arda_widget_edge_swipe_width);
        mSwipeThresholdPx = activity.getResources().getDimensionPixelSize(
                R.dimen.arda_widget_swipe_threshold);
        mDotSizePx = activity.getResources().getDimensionPixelSize(
                R.dimen.arda_widget_page_dot_size);
        mDotActiveSizePx = activity.getResources().getDimensionPixelSize(
                R.dimen.arda_widget_page_dot_active_size);
        mDotGapPx = activity.getResources().getDimensionPixelSize(
                R.dimen.arda_widget_page_dot_gap);

        List<AppWidgetProviderInfo> providers = AppWidgetManager.getInstance(activity)
                .getInstalledProvidersForProfile(Process.myUserHandle());
        mAdapter = new ArdaWidgetProviderPagerAdapter(activity, providers);
        mPageCount = mAdapter.getItemCount();
        mPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        mPager.setAdapter(mAdapter);
        buildDots();
        updateDots(0f);
        mPager.registerOnPageChangeCallback(mPageChangeCallback);

        mPageDots.setVisibility(View.GONE);
        mPageDots.setAlpha(0f);
        mPanelCard.setClickable(true);
    }

    public void toggle() {
        if (mOpen) {
            close(true);
        } else {
            open(true);
        }
    }

    public void open(boolean animate) {
        if (mOpen) {
            return;
        }
        mOpen = true;
        mAdapter.startListening();
        mAdapter.refreshVisibleWidgets();
        mPanelRoot.setVisibility(View.VISIBLE);
        mOpenStateListener.onOpenStateChanged(true, animate);
    }

    public void close(boolean animate) {
        if (!mOpen) {
            mPanelRoot.setVisibility(View.GONE);
            mAdapter.stopListening();
            return;
        }
        mOpen = false;
        hidePageDotsImmediately();
        mAdapter.stopListening();
        mOpenStateListener.onOpenStateChanged(false, animate);
    }

    public boolean isOpen() {
        return mOpen;
    }

    /** Observes Activity-level touch events without taking normal Maps/media gestures. */
    public boolean onTouchEvent(MotionEvent event, int rootWidth) {
        if (event.getPointerCount() != 1) {
            mTracking = false;
            mGestureClaimed = false;
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mGestureClaimed = false;
                mTracking = mOpen || x >= rootWidth - mEdgeSizePx;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!mTracking) {
                    return false;
                }
                float dx = x - mDownX;
                float dy = y - mDownY;
                boolean horizontal = Math.abs(dx) > Math.abs(dy) * 1.25f;
                if (!mGestureClaimed && horizontal) {
                    if (!mOpen && dx <= -mSwipeThresholdPx) {
                        mGestureClaimed = true;
                        open(true);
                    } else if (mOpen && dx >= mSwipeThresholdPx) {
                        mGestureClaimed = true;
                        close(true);
                    }
                }
                return mGestureClaimed;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consumed = mGestureClaimed;
                mTracking = false;
                mGestureClaimed = false;
                return consumed;

            default:
                return false;
        }
    }

    public void destroy() {
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        mPager.unregisterOnPageChangeCallback(mPageChangeCallback);
        mAdapter.stopListening();
        mPanelRoot.animate().cancel();
        mPanelCard.animate().cancel();
        mPageDots.animate().cancel();
    }

    private void buildDots() {
        mPageDots.removeAllViews();
        for (int i = 0; i < mPageCount; i++) {
            View dot = new View(mActivity);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(mDotSizePx, mDotSizePx);
            int halfGap = mDotGapPx / 2;
            params.setMargins(0, halfGap, 0, halfGap);
            dot.setLayoutParams(params);
            mPageDots.addView(dot);
        }
    }

    private void updateDots(float pagePosition) {
        mPagePosition = Math.max(
                0f,
                Math.min(pagePosition, Math.max(0, mPageCount - 1)));
        int selectedPage = Math.round(mPagePosition);

        for (int i = 0; i < mPageDots.getChildCount(); i++) {
            View dot = mPageDots.getChildAt(i);
            boolean selected = i == selectedPage;
            int size = selected ? mDotActiveSizePx : mDotSizePx;
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) dot.getLayoutParams();
            params.width = size;
            params.height = size;
            int halfGap = mDotGapPx / 2;
            params.setMargins(0, halfGap, 0, halfGap);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(
                    selected
                            ? R.drawable.arda_widget_page_dot_active
                            : R.drawable.arda_widget_page_dot_inactive);
        }
    }

    private void showPageDots() {
        if (mPageCount <= 1) {
            return;
        }
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        mPageDots.animate().cancel();
        if (mPageDots.getVisibility() != View.VISIBLE) {
            mPageDots.setVisibility(View.VISIBLE);
        }
        mPageDots.animate()
                .alpha(1f)
                .setDuration(PROGRESS_FADE_MS)
                .start();
        Log.i(TAG, "ARDA_WIDGET_PAGE_DOTS visible=true page=" + mPagePosition);
    }

    private void schedulePageDotsHide() {
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        mMainHandler.postDelayed(mHideProgressRunnable, PROGRESS_HIDE_DELAY_MS);
    }

    private void hidePageDots() {
        mPageDots.animate().cancel();
        mPageDots.animate()
                .alpha(0f)
                .setDuration(PROGRESS_FADE_MS)
                .withEndAction(() -> {
                    mPageDots.setVisibility(View.GONE);
                    Log.i(TAG, "ARDA_WIDGET_PAGE_DOTS visible=false");
                })
                .start();
    }

    private void hidePageDotsImmediately() {
        mMainHandler.removeCallbacks(mHideProgressRunnable);
        mPageDots.animate().cancel();
        mPageDots.setAlpha(0f);
        mPageDots.setVisibility(View.GONE);
    }
}
