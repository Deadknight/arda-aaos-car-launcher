/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher.homescreen.audio.media;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.constraintlayout.motion.widget.MotionLayout;

import com.android.car.carlauncher.R;

/**
 * Media-card MotionLayout with deterministic horizontal page switching.
 *
 * <p>ARDA_MEDIA_PHASE1H3_EXACT_DOWN_OWNER. ARDA_MEDIA_PHASE1H1_DETERMINISTIC_SWIPE. The page callback is fired as soon as a
 * horizontal drag crosses the threshold instead of waiting for ACTION_UP. This matters on the
 * secondary page because nested RecyclerViews may cancel or retarget the final up event. Vertical
 * list scrolling, visible seek-bar dragging, button taps, and multi-touch remain untouched.</p>
 */
public final class ArdaMediaSwipeLayout extends MotionLayout {
    private static final String ARDA_PHASE1H1_MARKER =
            "ARDA_MEDIA_PHASE1H1_DETERMINISTIC_SWIPE";
    private static final String ARDA_PHASE1H3_MARKER =
            "ARDA_MEDIA_PHASE1H3_EXACT_DOWN_OWNER";

    public interface PageSwipeListener {
        void onSwipeTowardSecondaryPage();
        void onSwipeTowardNowPlaying();
    }

    private final int mTouchSlop;
    private final int mPageThresholdPx;
    private final Rect mGlobalHitRect = new Rect();

    private @Nullable PageSwipeListener mListener;
    private float mDownX;
    private float mDownY;
    private boolean mTracking;
    private boolean mHorizontalSwipe;
    private boolean mGestureExcluded;
    private boolean mChildTouchCancelled;
    private boolean mPageTriggered;
    private boolean mOwnsTouchStream;

    public ArdaMediaSwipeLayout(Context context) {
        this(context, null);
    }

    public ArdaMediaSwipeLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ArdaMediaSwipeLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mPageThresholdPx = Math.max(mTouchSlop * 3,
                Math.round(28f * getResources().getDisplayMetrics().density));
    }

    public void setPageSwipeListener(@Nullable PageSwipeListener listener) {
        mListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // ARDA_MEDIA_PHASE1H4_NOW_PLAYING_ONLY: no secondary page means no custom horizontal
        // gesture ownership. Use the framework dispatch path so taps and child controls behave
        // exactly like a normal MotionLayout.
        if (mListener == null) {
            return super.dispatchTouchEvent(event);
        }
        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            beginGesture(event);
            // ARDA_MEDIA_PHASE1H3_EXACT_DOWN_OWNER: this is the exact UI-lab candidate.
            // Dispatch DOWN to children first, then retain ownership of the stream even when
            // MotionLayout/children report false. Later MOVE events can therefore reach the
            // horizontal detector. Child taps and vertical RecyclerView scrolling still receive
            // their events through super.dispatchTouchEvent().
            boolean childHandledDown = super.dispatchTouchEvent(event);
            mOwnsTouchStream = mTracking;
            setTag(ARDA_PHASE1H3_MARKER);
            return mOwnsTouchStream || childHandledDown;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 1) {
            disableGestureForCurrentStream();
        } else if (action == MotionEvent.ACTION_MOVE) {
            updateGesture(event);
            if (mHorizontalSwipe) {
                cancelNestedChildTouchIfNeeded(event);
                triggerPageSwipeOnMoveIfNeeded(event);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (mHorizontalSwipe) {
                triggerPageSwipeOnMoveIfNeeded(event);
                resetGesture();
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            resetGesture();
        }

        boolean handled = super.dispatchTouchEvent(event);
        boolean consume = mOwnsTouchStream || handled;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            resetGesture();
        }
        return consume;
    }

    private void beginGesture(MotionEvent event) {
        mDownX = event.getX();
        mDownY = event.getY();
        mTracking = event.getPointerCount() == 1;
        mHorizontalSwipe = false;
        mChildTouchCancelled = false;
        mPageTriggered = false;
        mGestureExcluded = isPointInsideVisibleView(
                findViewById(R.id.playback_seek_bar), event.getRawX(), event.getRawY());
    }

    private void disableGestureForCurrentStream() {
        mTracking = false;
        mHorizontalSwipe = false;
        mGestureExcluded = true;
    }

    private void updateGesture(MotionEvent event) {
        if (!mTracking || mGestureExcluded || mHorizontalSwipe) {
            return;
        }
        float dx = event.getX() - mDownX;
        float dy = event.getY() - mDownY;
        if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.08f) {
            mHorizontalSwipe = true;
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    }

    private void triggerPageSwipeOnMoveIfNeeded(MotionEvent event) {
        if (mPageTriggered || mListener == null) {
            return;
        }
        float dx = event.getX() - mDownX;
        float dy = event.getY() - mDownY;
        if (Math.abs(dx) < mPageThresholdPx || Math.abs(dx) <= Math.abs(dy) * 1.08f) {
            return;
        }
        mPageTriggered = true;
        if (dx < 0f) {
            mListener.onSwipeTowardSecondaryPage();
        } else {
            mListener.onSwipeTowardNowPlaying();
        }
    }

    private void cancelNestedChildTouchIfNeeded(MotionEvent source) {
        if (mChildTouchCancelled) {
            return;
        }
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        mChildTouchCancelled = true;
    }

    private boolean isPointInsideVisibleView(@Nullable View view, float rawX, float rawY) {
        if (view == null || !view.isShown() || view.getAlpha() <= 0.15f) {
            return false;
        }
        if (!view.getGlobalVisibleRect(mGlobalHitRect)) {
            return false;
        }
        return mGlobalHitRect.contains(Math.round(rawX), Math.round(rawY));
    }

    private void resetGesture() {
        mTracking = false;
        mHorizontalSwipe = false;
        mGestureExcluded = false;
        mChildTouchCancelled = false;
        mPageTriggered = false;
        mOwnsTouchStream = false;
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }
}
