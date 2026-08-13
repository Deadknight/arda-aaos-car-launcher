/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.car.carlauncher.homescreen.audio.media;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TableLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.carlauncher.R;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackController;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackStateWrapper;

import java.util.List;

/**
 * Single secondary-page adapter used by the ARDA two-page media surface.
 *
 * <p>The page shows the real playback queue when available. When the source does not publish a
 * queue, it safely falls back to the existing Media Source/history controller.</p>
 */
public final class MediaCardPanelViewPagerAdapter extends
        RecyclerView.Adapter<MediaCardPanelViewPagerAdapter.PanelViewHolder> {

    private final Context mContext;
    private boolean mHasQueue;
    private ViewPagerQueueCreator mQueueCreator;
    private ViewPagerHistoryCreator mHistoryCreator;

    // The queue/history containers already include their RecyclerView children in XML.
    // Therefore childCount cannot be used as a controller-created sentinel. Bind once per
    // actual ViewPager container instance instead.
    private ViewGroup mQueueBoundContainer;
    private ViewGroup mHistoryBoundContainer;

    public MediaCardPanelViewPagerAdapter(Context context) {
        mContext = context;
    }

    @NonNull
    @Override
    public PanelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PanelViewHolder(LayoutInflater.from(mContext).inflate(
                R.layout.media_card_panel_content_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PanelViewHolder holder, int position) {
        TableLayout overflowGrid = holder.itemView.findViewById(R.id.overflow_grid);
        FrameLayout queue = holder.itemView.findViewById(R.id.queue_list_container);
        FrameLayout history = holder.itemView.findViewById(R.id.history_list_container);

        overflowGrid.setVisibility(View.GONE);
        if (mHasQueue) {
            queue.setVisibility(View.VISIBLE);
            history.setVisibility(View.GONE);
            if (mQueueCreator != null && mQueueBoundContainer != queue) {
                mQueueCreator.createQueueController(queue);
                mQueueBoundContainer = queue;
            }
        } else {
            queue.setVisibility(View.GONE);
            history.setVisibility(View.VISIBLE);
            if (mHistoryCreator != null && mHistoryBoundContainer != history) {
                mHistoryCreator.createHistoryController(history);
                mHistoryBoundContainer = history;
            }
        }
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    public void setHasQueue(boolean hasQueue) {
        if (mHasQueue != hasQueue) {
            mHasQueue = hasQueue;
            notifyDataSetChanged();
        }
    }

    public void setQueueControllerProvider(ViewPagerQueueCreator queueCreator) {
        mQueueCreator = queueCreator;
    }

    public void setHistoryControllerProvider(ViewPagerHistoryCreator historyCreator) {
        mHistoryCreator = historyCreator;
    }

    /** Custom actions remain on Now Playing; there is no third overflow page. */
    public void setHasOverflow(boolean hasOverflow) {
        // Intentionally ignored in ARDA Phase 1G-B.
    }

    /** Playback state does not change the single queue/source page structure. */
    public void notifyPlaybackStateChanged(PlaybackStateWrapper playbackState,
            PlaybackController playbackController,
            List<PlaybackViewModel.RawCustomPlaybackAction> customActionsToExclude) {
        // Intentionally no-op in ARDA Phase 1G-B.
    }

    static final class PanelViewHolder extends RecyclerView.ViewHolder {
        PanelViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    interface ViewPagerQueueCreator {
        void createQueueController(ViewGroup queueContainer);
    }

    interface ViewPagerHistoryCreator {
        void createHistoryController(ViewGroup historyContainer);
    }
}
