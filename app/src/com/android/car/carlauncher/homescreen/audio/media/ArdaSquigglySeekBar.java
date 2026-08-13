/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher.homescreen.audio.media;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;

/** SeekBar retaining native seek/accessibility behavior with an AA-inspired active wave. */
public final class ArdaSquigglySeekBar extends SeekBar {
    private static final float TRACK_STROKE_DP = 2f;
    private static final float WAVE_STROKE_DP = 2.5f;
    private static final float WAVE_AMPLITUDE_DP = 2.7f;
    private static final float WAVE_LENGTH_DP = 12f;
    private static final float MARKER_HEIGHT_DP = 14f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mWavePath = new Path();
    private final float mDensity;

    public ArdaSquigglySeekBar(Context context) {
        this(context, null);
    }

    public ArdaSquigglySeekBar(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.seekBarStyle);
    }

    public ArdaSquigglySeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        setThumb(null);
        setProgressDrawable(null);
        setSplitTrack(false);
        setWillNotDraw(false);

        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setStrokeWidth(dp(TRACK_STROKE_DP));

        mWavePaint.setStyle(Paint.Style.STROKE);
        mWavePaint.setStrokeCap(Paint.Cap.ROUND);
        mWavePaint.setStrokeJoin(Paint.Join.ROUND);
        mWavePaint.setStrokeWidth(dp(WAVE_STROKE_DP));

        mMarkerPaint.setStyle(Paint.Style.STROKE);
        mMarkerPaint.setStrokeCap(Paint.Cap.ROUND);
        mMarkerPaint.setStrokeWidth(dp(WAVE_STROKE_DP));
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int start = getPaddingStart();
        int end = getWidth() - getPaddingEnd();
        if (end <= start) {
            return;
        }

        float centerY = getHeight() / 2f;
        float fraction = getMax() > 0 ? (float) getProgress() / (float) getMax() : 0f;
        fraction = Math.max(0f, Math.min(1f, fraction));
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float activeEnd = rtl
                ? end - ((end - start) * fraction)
                : start + ((end - start) * fraction);

        int inactiveColor = resolveTint(
                getProgressBackgroundTintList(), Color.argb(115, 255, 255, 255));
        int activeColor = resolveTint(getProgressTintList(), Color.WHITE);
        if (!isEnabled()) {
            inactiveColor = withAlpha(inactiveColor, 80);
            activeColor = withAlpha(activeColor, 115);
        }
        mTrackPaint.setColor(inactiveColor);
        mWavePaint.setColor(activeColor);
        mMarkerPaint.setColor(activeColor);

        canvas.drawLine(start, centerY, end, centerY, mTrackPaint);

        float waveStart = rtl ? end : start;
        float direction = rtl ? -1f : 1f;
        float activeLength = Math.abs(activeEnd - waveStart);
        if (activeLength <= 0.5f) {
            return;
        }

        mWavePath.reset();
        mWavePath.moveTo(waveStart, centerY);
        float wavelength = dp(WAVE_LENGTH_DP);
        float amplitude = dp(WAVE_AMPLITUDE_DP);
        int steps = Math.max(2, (int) (activeLength / dp(2f)));
        for (int i = 1; i <= steps; i++) {
            float distance = activeLength * i / steps;
            float x = waveStart + direction * distance;
            float y = centerY + (float) Math.sin(
                    (distance / wavelength) * Math.PI * 2f) * amplitude;
            mWavePath.lineTo(x, y);
        }
        canvas.drawPath(mWavePath, mWavePaint);

        float halfMarker = dp(MARKER_HEIGHT_DP) / 2f;
        canvas.drawLine(activeEnd, centerY - halfMarker,
                activeEnd, centerY + halfMarker, mMarkerPaint);
    }

    private int resolveTint(ColorStateList tint, int fallback) {
        return tint == null ? fallback
                : tint.getColorForState(getDrawableState(), tint.getDefaultColor());
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * mDensity;
    }
}
