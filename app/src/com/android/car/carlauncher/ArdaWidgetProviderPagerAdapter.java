/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Hosts one real external AppWidgetProvider per vertical ViewPager page. */
public final class ArdaWidgetProviderPagerAdapter
        extends RecyclerView.Adapter<ArdaWidgetProviderPagerAdapter.WidgetPageHolder> {
    private static final String TAG = "ArdaWidgetHost";
    private static final int HOST_ID = 1024;
    private static final String PREFS_NAME = "arda_widget_host_ids";
    private static final String PREF_KEY_PREFIX = "widget_id:";

    private final Context mContext;
    private final AppWidgetManager mAppWidgetManager;
    private final AppWidgetHost mAppWidgetHost;
    private final SharedPreferences mPreferences;
    private final List<AppWidgetProviderInfo> mProviders = new ArrayList<>();

    private boolean mListening;

    public ArdaWidgetProviderPagerAdapter(
            Context context, List<AppWidgetProviderInfo> providers) {
        mContext = context;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mAppWidgetHost = new AppWidgetHost(context, HOST_ID);
        mPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (providers != null) {
            for (AppWidgetProviderInfo provider : providers) {
                int category = provider.widgetCategory;
                if (category == 0
                        || (category & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0) {
                    mProviders.add(provider);
                }
            }
        }

        mProviders.sort(Comparator.comparing(
                provider -> String.valueOf(provider.loadLabel(context.getPackageManager())),
                String.CASE_INSENSITIVE_ORDER));

        Log.i(TAG, "ARDA_WIDGET_HOST providers=" + mProviders.size());
    }

    public void startListening() {
        if (mListening) {
            return;
        }
        try {
            mAppWidgetHost.startListening();
            mListening = true;
            Log.i(TAG, "ARDA_WIDGET_HOST listening=true");
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to start AppWidgetHost listening", e);
        }
    }

    public void stopListening() {
        if (!mListening) {
            return;
        }
        try {
            mAppWidgetHost.stopListening();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to stop AppWidgetHost listening", e);
        }
        mListening = false;
        Log.i(TAG, "ARDA_WIDGET_HOST listening=false");
    }

    public void refreshVisibleWidgets() {
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public WidgetPageHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {
        return new WidgetPageHolder(LayoutInflater.from(parent.getContext()).inflate(
                R.layout.arda_widget_drawer_page, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull WidgetPageHolder holder, int position) {
        holder.hostContainer.removeAllViews();

        if (mProviders.isEmpty()) {
            showPlaceholder(
                    holder,
                    mContext.getString(R.string.arda_widget_no_providers_title),
                    mContext.getString(R.string.arda_widget_no_providers_summary),
                    null);
            return;
        }

        AppWidgetProviderInfo provider = mProviders.get(position);
        int appWidgetId = ensureBoundWidgetId(provider);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            showProviderPlaceholder(holder, provider,
                    R.string.arda_widget_binding_unavailable);
            Log.w(TAG, "ARDA_WIDGET_HOST hosted=false provider="
                    + provider.provider.flattenToShortString()
                    + " reason=bind_denied");
            return;
        }

        try {
            AppWidgetHostView hostView =
                    mAppWidgetHost.createView(mContext, appWidgetId, provider);
            hostView.setPadding(0, 0, 0, 0);
            holder.hostContainer.addView(
                    hostView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            holder.placeholder.setVisibility(View.GONE);
            updateWidgetSizeWhenMeasured(holder.hostContainer, appWidgetId);
            Log.i(TAG, "ARDA_WIDGET_HOST hosted=true provider="
                    + provider.provider.flattenToShortString()
                    + " appWidgetId=" + appWidgetId
                    + " configurable=" + (provider.configure != null));
        } catch (RuntimeException e) {
            showProviderPlaceholder(holder, provider,
                    R.string.arda_widget_render_failed);
            Log.w(TAG, "ARDA_WIDGET_HOST hosted=false provider="
                    + provider.provider.flattenToShortString()
                    + " reason=create_view_failed", e);
        }
    }

    @Override
    public void onViewRecycled(@NonNull WidgetPageHolder holder) {
        holder.hostContainer.removeAllViews();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return Math.max(1, mProviders.size());
    }

    private int ensureBoundWidgetId(AppWidgetProviderInfo provider) {
        String preferenceKey = PREF_KEY_PREFIX + provider.provider.flattenToString();
        int storedId = mPreferences.getInt(
                preferenceKey, AppWidgetManager.INVALID_APPWIDGET_ID);

        if (storedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            AppWidgetProviderInfo storedInfo = mAppWidgetManager.getAppWidgetInfo(storedId);
            if (storedInfo != null && provider.provider.equals(storedInfo.provider)) {
                return storedId;
            }
            deleteWidgetIdQuietly(storedId);
            mPreferences.edit().remove(preferenceKey).apply();
        }

        int appWidgetId = mAppWidgetHost.allocateAppWidgetId();
        Bundle options = new Bundle();
        options.putInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);

        boolean bound;
        try {
            bound = mAppWidgetManager.bindAppWidgetIdIfAllowed(
                    appWidgetId, provider.provider, options);
        } catch (RuntimeException e) {
            Log.w(TAG, "bindAppWidgetIdIfAllowed failed for " + provider.provider, e);
            bound = false;
        }

        if (!bound) {
            deleteWidgetIdQuietly(appWidgetId);
            return AppWidgetManager.INVALID_APPWIDGET_ID;
        }

        mPreferences.edit().putInt(preferenceKey, appWidgetId).apply();
        return appWidgetId;
    }

    private void updateWidgetSizeWhenMeasured(
            FrameLayout hostContainer, int appWidgetId) {
        hostContainer.post(() -> {
            int widthPx = hostContainer.getWidth();
            int heightPx = hostContainer.getHeight();
            if (widthPx <= 0 || heightPx <= 0) {
                return;
            }

            float density = mContext.getResources().getDisplayMetrics().density;
            int widthDp = Math.max(1, Math.round(widthPx / density));
            int heightDp = Math.max(1, Math.round(heightPx / density));

            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
            options.putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
            try {
                mAppWidgetManager.updateAppWidgetOptions(appWidgetId, options);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to update widget size options id=" + appWidgetId, e);
            }
        });
    }

    private void showProviderPlaceholder(
            WidgetPageHolder holder,
            AppWidgetProviderInfo provider,
            int summaryResId) {
        Drawable icon = null;
        try {
            icon = mContext.getPackageManager().getApplicationIcon(
                    provider.provider.getPackageName());
        } catch (Exception ignored) {
            // Placeholder icon is used below.
        }

        showPlaceholder(
                holder,
                String.valueOf(provider.loadLabel(mContext.getPackageManager())),
                mContext.getString(summaryResId),
                icon);
    }

    private void showPlaceholder(
            WidgetPageHolder holder,
            CharSequence title,
            CharSequence summary,
            Drawable icon) {
        holder.placeholder.setVisibility(View.VISIBLE);
        holder.icon.setImageDrawable(icon != null
                ? icon
                : mContext.getDrawable(R.drawable.arda_ic_widget_placeholder));
        holder.title.setText(title);
        holder.summary.setText(summary);
    }

    private void deleteWidgetIdQuietly(int appWidgetId) {
        try {
            mAppWidgetHost.deleteAppWidgetId(appWidgetId);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to delete stale widget id=" + appWidgetId, e);
        }
    }

    static final class WidgetPageHolder extends RecyclerView.ViewHolder {
        final FrameLayout hostContainer;
        final View placeholder;
        final ImageView icon;
        final TextView title;
        final TextView summary;

        WidgetPageHolder(@NonNull View itemView) {
            super(itemView);
            hostContainer = itemView.findViewById(R.id.arda_widget_host_container);
            placeholder = itemView.findViewById(R.id.arda_widget_placeholder);
            icon = itemView.findViewById(R.id.arda_widget_page_icon);
            title = itemView.findViewById(R.id.arda_widget_page_title);
            summary = itemView.findViewById(R.id.arda_widget_page_summary);
        }
    }
}
