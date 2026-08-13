/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.carlauncher.homescreen.audio.media;

import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.getFirstCustomActionInSet;
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.skipBackStandardActions;
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.skipForwardStandardActions;
import static com.android.car.media.common.ui.PlaybackCardControllerUtilities.updatePlayButtonWithPlaybackState;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.car.apps.common.util.ViewUtils;
import com.android.car.carlauncher.R;
import com.android.car.media.common.CustomPlaybackAction;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackController;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackStateWrapper;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.ui.PlaybackCardController;
import com.android.car.media.common.ui.PlaybackHistoryController;
import com.android.car.media.common.ui.PlaybackQueueController;

import java.util.ArrayList;
import java.util.List;

public class MediaCardController extends PlaybackCardController implements
        /* ARDA_MEDIA_PHASE1H_REFERENCE_REWORK */
        /* ARDA_MEDIA_PHASE1H1_RECYCLER_SWIPE_BRIDGE */
        MediaCardPanelViewPagerAdapter.ViewPagerQueueCreator,
        MediaCardPanelViewPagerAdapter.ViewPagerHistoryCreator {

    private static final String ARDA_PHASE1H5_MARKER =
            "ARDA_MEDIA_PHASE1H5_STATIC_NOW_PLAYING";

    private final MediaLaunchRouter mMediaLaunchRouter = MediaLaunchRouter.getInstance();
    private View mPanelHandlebar;
    private LinearLayout mPanel;
    private MotionLayout mMotionLayout;
    private MediaCardFragment.MediaCardViewModel mCardViewModel;
    private ImageButton mSkipPrevButton;
    private ImageButton mSkipNextButton;
    private int mSkipPrevVisibility;
    private int mSkipNextVisibility;
    private int mAlbumCoverVisibility;
    private int mSubtitleVisibility;
    private int mLogoVisibility;
    private TextView mPlaybackTimeView;
    private TextView mSecondaryPageTitle;

    private PlaybackQueueController mPlaybackQueueController;
    private PlaybackHistoryController mPlaybackHistoryController;

    private ViewPager2 mPager;
    private MediaCardPanelViewPagerAdapter mPagerAdapter;
    private Handler mHandler;

    @Override
    public void createQueueController(ViewGroup queueContainer) {
        mPlaybackQueueController = new PlaybackQueueController(
                queueContainer, /* queueResource */ Resources.ID_NULL,
                R.layout.media_card_queue_item, R.layout.media_card_queue_header_item,
                getViewLifecycleOwner(), mDataModel, mCardViewModel.getMediaItemsRepository(),
                /* uxrContentLimiter */ null, /* uxrConfigurationId */ 0);
        mPlaybackQueueController.setShowTimeForActiveQueueItem(false);
        mPlaybackQueueController.setShowIconForActiveQueueItem(false);
        mPlaybackQueueController.setShowThumbnailForQueueItem(true);
        mPlaybackQueueController.setShowSubtitleForQueueItem(true);
        installSecondaryRecyclerSwipeBridge(queueContainer, R.id.queue_list);
    }

    @Override
    public void createHistoryController(ViewGroup historyContainer) {
        mPlaybackHistoryController = new PlaybackHistoryController(getViewLifecycleOwner(),
                mCardViewModel, historyContainer, R.layout.media_card_history_item,
                R.layout.media_card_history_header_item, /* uxrConfigurationId */ 0);
        mPlaybackHistoryController.setupView();
        installSecondaryRecyclerSwipeBridge(historyContainer, R.id.history_list);
    }

    /** Builder for {@link MediaCardController}. Overrides build() method to return
     * NowPlayingController rather than base {@link PlaybackCardController}
     */
    public static class Builder extends PlaybackCardController.Builder {

        @Override
        public MediaCardController build() {
            MediaCardController controller = new MediaCardController(this);
            controller.setupController();
            return controller;
        }
    }

    public MediaCardController(Builder builder) {
        super(builder);

        mCardViewModel = (MediaCardFragment.MediaCardViewModel) mViewModel;
        mView.setOnClickListener(view -> {
            launchMediaAppOrClosePanel();
        });
        mView.findViewById(R.id.empty_panel).setOnClickListener(view -> {
            launchMediaAppOrClosePanel();
        });

        mPager = mView.findViewById(R.id.view_pager);
        mPagerAdapter = new MediaCardPanelViewPagerAdapter(mView.getContext());
        // ARDA_MEDIA_PHASE1H5_STATIC_NOW_PLAYING: keep the secondary controller object available
        // for API compatibility, but do not attach its page while we focus on Now Playing.
        mPager.setAdapter(null);
        mPager.setUserInputEnabled(false);
        mPager.setVisibility(View.GONE);
        mPagerAdapter.setQueueControllerProvider(this);
        mPagerAdapter.setHistoryControllerProvider(this);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {

            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                if (!mCardViewModel.getPanelExpanded()) {
                    return;
                }
                boolean hasQueue = getMediaHasQueue();
                selectOverflow(false);
                selectQueue(hasQueue);
                selectHistory(!hasQueue);
            }
        });

        mMotionLayout = mView.findViewById(R.id.motion_layout);
        // Phase 1H.4 keeps the normal card click path: tapping the compact Now Playing surface
        // opens the active generic media host. No compact queue gesture is registered here.
        mMotionLayout.setTag(ARDA_PHASE1H5_MARKER);
        mMotionLayout.setOnClickListener(view -> launchMediaAppOrClosePanel());

        mPanel = mView.findViewById(R.id.button_panel_background);
        mPanelHandlebar = mView.findViewById(R.id.media_card_panel_handlebar);

        mSkipPrevButton = mView.findViewById(R.id.playback_action_id1);
        mSkipNextButton = mView.findViewById(R.id.playback_action_id2);
        mPlaybackTimeView = mView.findViewById(R.id.playback_time);
        mSecondaryPageTitle = mView.findViewById(
                R.id.arda_media_secondary_page_title);
        if (mSecondaryPageTitle != null) {
            mSecondaryPageTitle.setVisibility(View.GONE);
        }

        // ARDA_MEDIA_PHASE1H5_STATIC_NOW_PLAYING: the compact card no longer uses the
        // queue MotionScene. Keeping transition callbacks here could restore stale end-state
        // visibility, so the Now Playing surface is managed as a normal static layout.

        if (mMotionLayout instanceof ArdaMediaSwipeLayout) {
            // Queue/source page is temporarily disabled. A null listener makes the custom
            // MotionLayout use normal Android touch dispatch without horizontal page capture.
            ((ArdaMediaSwipeLayout) mMotionLayout).setPageSwipeListener(null);
        }
        mPanelHandlebar.setVisibility(View.GONE);
        mPanelHandlebar.setOnClickListener(null);

        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected void setupController() {
        // A FragmentActivity-scoped MediaCardViewModel survives Launcher view recreation. If the
        // previous card instance was left on Queue/Media Source, that stale expanded flag used to
        // make the newly-created card start on the secondary page. Phase 1H.4 intentionally keeps
        // the compact surface in Now Playing-only mode while its visual treatment is refined.
        resetToNowPlayingOnControllerStart();
        super.setupController();

        mSkipPrevVisibility = mSkipPrevButton.getVisibility();
        mSkipNextVisibility = mSkipNextButton.getVisibility();
        mAlbumCoverVisibility = mAlbumCover.getVisibility();
        mSubtitleVisibility = mSubtitle.getVisibility();
        mLogoVisibility = mLogo.getVisibility();

        forceStaticNowPlayingVisible();
        // Apply once more after MotionLayout has completed its first layout/restoration pass.
        mMotionLayout.post(this::forceStaticNowPlayingVisible);
    }

    private void resetToNowPlayingOnControllerStart() {
        mCardViewModel.setPanelExpanded(false);
        mCardViewModel.setQueueVisible(false);
        mCardViewModel.setHistoryVisible(false);
        mCardViewModel.setOverflowExpanded(false);
    }

    /**
     * Restores the compact card as a static Now Playing surface.
     *
     * <p>Phase 1H.4 detached the pager but left the MotionScene attached. A restored/end
     * MotionLayout progress could therefore leave every Now Playing child at alpha 0 even though
     * the queue page itself was gone. Phase 1H.5 removes that scene from XML and defensively
     * restores the visible children after view-state restoration.</p>
     */
    private void forceStaticNowPlayingVisible() {
        resetToNowPlayingOnControllerStart();
        mPanel.setEnabled(false);
        unselectAllPanelButtons();

        View secondary = mView.findViewById(R.id.media_card_panel_content_container);
        if (secondary != null) {
            secondary.setVisibility(View.GONE);
        }
        if (mPager != null) {
            mPager.setVisibility(View.GONE);
        }
        if (mQueueButton != null) {
            mQueueButton.setVisibility(View.GONE);
        }
        if (mSecondaryPageTitle != null) {
            mSecondaryPageTitle.setVisibility(View.GONE);
        }

        restoreStaticView(R.id.empty_panel, true);
        restoreStaticView(R.id.album_art, true);
        restoreStaticView(R.id.arda_media_art_scrim, true);
        restoreStaticView(R.id.media_widget_app_icon, true);
        restoreStaticView(R.id.button_panel_background, true);
        restoreStaticView(R.id.title, true);
        restoreStaticView(R.id.subtitle, true);
        restoreStaticView(R.id.playback_seek_bar, false);
        restoreStaticView(R.id.playback_time, false);
        restoreStaticView(R.id.play_pause_button, true);
        restoreStaticView(R.id.playback_action_id1, false);
        restoreStaticView(R.id.playback_action_id2, false);

        mSeekBar.setEnabled(true);
        updatePlaybackTime(mDataModel.getProgress().getValue());
    }

    private void restoreStaticView(int id, boolean forceVisible) {
        View view = mView.findViewById(id);
        if (view == null) {
            return;
        }
        view.setAlpha(1f);
        if (forceVisible) {
            view.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void updateMetadata(MediaItemMetadata metadata) {
        super.updateMetadata(metadata);
        if (mCardViewModel.getPanelExpanded()) {
            mSubtitleVisibility = mSubtitle.getVisibility();
            mSubtitle.setVisibility(View.GONE);
        }
    }

    @Override
    protected void updateAlbumCoverWithDrawable(Drawable drawable) {
        Drawable drawableToUse = drawable == null ? mView.getResources().getDrawable(
                /* drawable */ R.drawable.media_card_default_album_art,
                /* theme */ mView.getContext().getTheme())
                : drawable;
        super.updateAlbumCoverWithDrawable(drawableToUse);
        if (mCardViewModel.getPanelExpanded()) {
            mAlbumCoverVisibility = mAlbumCover.getVisibility();
            mAlbumCover.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    protected void updateLogoWithDrawable(Drawable drawable) {
        super.updateLogoWithDrawable(drawable);
        if (mCardViewModel.getPanelExpanded()) {
            mLogoVisibility = mLogo.getVisibility();
            mLogo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void updateMediaSource(MediaSource mediaSource) {
        super.updateMediaSource(mediaSource);
        if (mCardViewModel.getPanelExpanded()) {
            mAppIcon.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    protected void updateProgress(PlaybackProgress progress) {
        super.updateProgress(progress);
        ViewUtils.setVisible(mSeekBar, progress != null && progress.hasTime());
        updatePlaybackTime(progress);
        if (progress == null || !progress.hasTime()) {
            mLogo.setVisibility(View.GONE);
        } else if (mDataModel.getMetadata().getValue() != null) {
            Uri logoUri = mLogo.prepareToDisplay(mDataModel.getMetadata().getValue());
            if (logoUri != null && !mCardViewModel.getPanelExpanded()) {
                mLogo.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updatePlaybackTime(PlaybackProgress progress) {
        if (mPlaybackTimeView == null) {
            return;
        }
        boolean showTime = progress != null
                && progress.hasTime()
                && !mCardViewModel.getPanelExpanded();
        ViewUtils.setVisible(mPlaybackTimeView, showTime);
        if (showTime) {
            mPlaybackTimeView.setText(new StringBuilder(progress.getCurrentTimeText())
                    .append(" / ")
                    .append(progress.getMaxTimeText()));
        } else {
            mPlaybackTimeView.setText(null);
        }
    }

    @Override
    protected void updatePlaybackState(PlaybackViewModel.PlaybackStateWrapper playbackState) {
        PlaybackController playbackController = mDataModel.getPlaybackController().getValue();
        if (playbackState != null) {
            updatePlayButtonWithPlaybackState(mPlayPauseButton, playbackState, playbackController);
            List<PlaybackViewModel.RawCustomPlaybackAction> usedCustomActions =
                    updateSkipButtonsAndReturnUsedStandardCustomActions(
                            playbackState, playbackController);

            // Custom actions remain handled by the media session/controller; there is no third
            // overflow page in the two-page ARDA surface.
            ViewUtils.setVisible(mActionOverflowButton, false);
            mPagerAdapter.setHasOverflow(false);
            mPagerAdapter.notifyPlaybackStateChanged(playbackState,
                    playbackController, usedCustomActions);
        } else {
            mSkipPrevButton.setVisibility(View.GONE);
            mSkipNextButton.setVisibility(View.GONE);
        }

        if (mCardViewModel.getPanelExpanded()) {
            mSkipPrevVisibility = mSkipPrevButton.getVisibility();
            mSkipNextVisibility = mSkipNextButton.getVisibility();
            mSkipPrevButton.setVisibility(View.GONE);
            mSkipNextButton.setVisibility(View.GONE);
        }
    }

    @Override
    protected void setUpActionsOverflowButton() {
        super.setUpActionsOverflowButton();
        ViewUtils.setVisible(mActionOverflowButton, false);
        selectOverflow(false);
    }

    @Override
    protected void handleCustomActionsOverflowButtonClicked(View overflow) {
        // No third overflow page in the two-page ARDA media surface.
    }

    @Override
    protected void setUpQueueButton() {
        super.setUpQueueButton();
        ViewUtils.setVisible(mQueueButton, false);
        selectQueue(false);
    }

    @Override
    protected void updateQueueState(boolean hasQueue, boolean isQueueVisible) {
        mPagerAdapter.setHasQueue(hasQueue);
        ViewUtils.setVisible(mQueueButton, false);
        forceStaticNowPlayingVisible();
    }

    @Override
    protected void handleQueueButtonClicked(View queue) {
        // Queue is intentionally disabled during the Now Playing visual pass.
        resetToNowPlayingOnControllerStart();
    }

    @Override
    protected void setUpHistoryButton() {
        super.setUpHistoryButton();
        ViewUtils.setVisible(mHistoryButton, false);
        selectHistory(false);
    }

    @Override
    protected void handleHistoryButtonClicked(View history) {
        // History/source page is intentionally disabled with Queue.
        resetToNowPlayingOnControllerStart();
    }

    private void setOverflowState(boolean isExpanded, boolean stateSetThroughClick) {
        selectOverflow(false);
    }

    private void setQueueState(boolean isVisible, boolean stateSetThroughClick) {
        selectQueue(false);
    }

    private void setHistoryState(boolean isVisible, boolean stateSetThroughClick) {
        selectHistory(false);
    }


    /**
     * Adds a direct gesture bridge to the nested queue/history RecyclerView.
     *
     * <p>The parent MotionLayout observes the whole card, but some CarUiRecyclerView builds keep
     * ownership of their touch stream after intercepting it. This listener switches pages at the
     * list level as soon as a clearly horizontal drag crosses the threshold. Vertical scrolling is
     * never intercepted.</p>
     */
    private void installSecondaryRecyclerSwipeBridge(ViewGroup container, int recyclerViewId) {
        View candidate = container.findViewById(recyclerViewId);
        if (!(candidate instanceof RecyclerView)) {
            return;
        }
        RecyclerView recyclerView = (RecyclerView) candidate;
        int touchSlop = ViewConfiguration.get(recyclerView.getContext()).getScaledTouchSlop();
        int thresholdPx = Math.max(touchSlop * 3, Math.round(28f
                * recyclerView.getResources().getDisplayMetrics().density));

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float mDownX;
            private float mDownY;
            private boolean mTracking;
            private boolean mTriggered;

            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mTracking = event.getPointerCount() == 1;
                    mTriggered = false;
                    return false;
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    mTracking = false;
                    return false;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    if (!mTracking || mTriggered || event.getPointerCount() != 1) {
                        return mTriggered;
                    }
                    float dx = event.getX() - mDownX;
                    float dy = event.getY() - mDownY;
                    if (Math.abs(dx) >= thresholdPx
                            && Math.abs(dx) > Math.abs(dy) * 1.08f) {
                        mTriggered = true;
                        rv.stopScroll();
                        rv.post(MediaCardController.this::toggleMediaPage);
                        return true;
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    boolean consume = mTriggered;
                    mTracking = false;
                    mTriggered = false;
                    return consume;
                }
                return mTriggered;
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mTracking = false;
                    mTriggered = false;
                }
            }
        });
    }


    private void toggleMediaPage() {
        forceStaticNowPlayingVisible();
    }

    private void openSecondaryPage() {
        // Intentionally disabled in ARDA_MEDIA_PHASE1H5_STATIC_NOW_PLAYING.
        forceStaticNowPlayingVisible();
    }

    private void updateSecondaryPageTitle(boolean hasQueue) {
        if (mSecondaryPageTitle != null) {
            mSecondaryPageTitle.setText(hasQueue
                    ? R.string.media_card_queue_header_title
                    : R.string.media_card_history_header_title);
        }
    }

    private void launchMediaAppOrClosePanel() {
        forceStaticNowPlayingVisible();
        mMediaLaunchRouter.handleLaunchMedia(mDataModel.getMediaSource().getValue());
    }

    private void animateClosePanel() {
        forceStaticNowPlayingVisible();
    }

    private void unselectPanel() {
        mPanel.setEnabled(false);
        unselectAllPanelButtons();
    }

    private void selectQueue(boolean shouldSelect) {
        mCardViewModel.setQueueVisible(shouldSelect);
        if (mQueueButton != null) {
            mQueueButton.setSelected(shouldSelect);
        }
    }

    private void selectOverflow(boolean shouldSelect) {
        mCardViewModel.setOverflowExpanded(shouldSelect);
        if (mActionOverflowButton != null) {
            mActionOverflowButton.setSelected(shouldSelect);
        }
    }

    private void selectHistory(boolean shouldSelect) {
        mCardViewModel.setHistoryVisible(shouldSelect);
        if (mHistoryButton != null) {
            mHistoryButton.setSelected(shouldSelect);
        }
    }

    private void unselectAllPanelButtons() {
        selectOverflow(false);
        selectQueue(false);
        selectHistory(false);
    }

    private void saveViewVisibilityBeforeAnimation() {
        mSubtitleVisibility = mSubtitle.getVisibility();
        mLogoVisibility = mLogo.getVisibility();
        mSkipPrevVisibility = mSkipPrevButton.getVisibility();
        mSkipNextVisibility = mSkipNextButton.getVisibility();
        mAlbumCoverVisibility = mAlbumCover.getVisibility();
        mSeekBar.setEnabled(false);
    }

    private void restoreExtraViewsWhenPanelClosed() {
        mAlbumCover.setVisibility(mAlbumCoverVisibility);
        mAppIcon.setVisibility(View.VISIBLE);
        mSkipPrevButton.setVisibility(mSkipPrevVisibility);
        mSkipNextButton.setVisibility(mSkipNextVisibility);
        mSubtitle.setVisibility(mSubtitleVisibility);
        mLogo.setVisibility(mLogoVisibility);
        mSeekBar.setEnabled(true);
    }

    /**
     * Set the mSkipNextButton and mSkipPrevButton with a skip action Drawable if sent by the
     * playbackState, otherwise with a skipForwardStandardAction and skipBackStandardAction
     * respectively. If none exist, hide the button.
     */
    private List<PlaybackViewModel.RawCustomPlaybackAction>
            updateSkipButtonsAndReturnUsedStandardCustomActions(PlaybackStateWrapper playbackState,
            PlaybackController playbackController) {
        List<PlaybackViewModel.RawCustomPlaybackAction> usedCustomActions =
                new ArrayList<PlaybackViewModel.RawCustomPlaybackAction>();
        updateSkipNextButtonWithSkipOrStandardAction(playbackState, playbackController,
                usedCustomActions);
        updateSkipPrevButtonWithSkipOrStandardAction(playbackState, playbackController,
                usedCustomActions);
        return usedCustomActions;
    }

    private void updateSkipNextButtonWithSkipOrStandardAction(
            PlaybackStateWrapper playbackState, PlaybackController playbackController,
            List<PlaybackViewModel.RawCustomPlaybackAction> usedCustomActions) {
        boolean isSkipNextEnabled = playbackState.isSkipNextEnabled();
        boolean isSkipNextReserved = playbackState.isSkipNextReserved();
        if ((isSkipNextEnabled || isSkipNextReserved)) {
            updateButton(mSkipNextButton, mView.getContext().getDrawable(
                    com.android.car.media.common.R.drawable.ic_skip_next),
                    mView.getContext().getDrawable(R.drawable.arda_media_round_button_background),
                    true, isSkipNextEnabled, (v) -> {
                if (playbackController != null) {
                    playbackController.skipToNext();
                }
            });
        } else {
            PlaybackViewModel.RawCustomPlaybackAction skipForwardCustomAction =
                    getFirstCustomActionInSet(playbackState.getCustomActions(),
                            skipForwardStandardActions);
            if (skipForwardCustomAction != null) {
                boolean isCustomActionUsed =
                        updateButtonWithCustomAction(mSkipNextButton, skipForwardCustomAction,
                                playbackController);
                if (isCustomActionUsed) {
                    usedCustomActions.add(skipForwardCustomAction);
                }
            } else {
                updateButton(mSkipNextButton, null, null, false, false, null);
            }
        }
    }

    private void updateSkipPrevButtonWithSkipOrStandardAction(
            PlaybackStateWrapper playbackState, PlaybackController playbackController,
            List<PlaybackViewModel.RawCustomPlaybackAction> usedCustomActions) {
        boolean isSkipPrevEnabled = playbackState.isSkipPreviousEnabled();
        boolean isSkipPrevReserved = playbackState.iSkipPreviousReserved();
        if ((isSkipPrevEnabled || isSkipPrevReserved)) {
            updateButton(mSkipPrevButton, mView.getContext().getDrawable(
                    com.android.car.media.common.R.drawable.ic_skip_previous),
                    mView.getContext().getDrawable(R.drawable.arda_media_round_button_background),
                    true, isSkipPrevEnabled, (v) -> {
                    if (playbackController != null) {
                        playbackController.skipToPrevious();
                    }
                });
        } else {
            PlaybackViewModel.RawCustomPlaybackAction skipBackCustomAction =
                    getFirstCustomActionInSet(playbackState.getCustomActions(),
                            skipBackStandardActions);
            if (skipBackCustomAction != null) {
                boolean isCustomActionUsed =
                        updateButtonWithCustomAction(mSkipPrevButton, skipBackCustomAction,
                                playbackController);
                if (isCustomActionUsed) {
                    usedCustomActions.add(skipBackCustomAction);
                }
            } else {
                updateButton(mSkipPrevButton, null, null, false, false, null);
            }
        }
    }

    private void updateButton(ImageButton button, Drawable imageDrawable,
            Drawable backgroundDrawable, boolean isVisible, boolean isEnabled,
            View.OnClickListener listener) {
        button.setImageDrawable(imageDrawable);
        button.setBackground(backgroundDrawable);
        ViewUtils.setVisible(button, isVisible);
        button.setEnabled(isEnabled);
        button.setOnClickListener(listener);
    }

    private boolean updateButtonWithCustomAction(ImageButton button,
            PlaybackViewModel.RawCustomPlaybackAction rawCustomAction,
            PlaybackController playbackController) {
        CustomPlaybackAction customAction = rawCustomAction
                .fetchDrawable(mView.getContext());
        if (customAction != null) {
            updateButton(button, customAction.mIcon, mView.getContext().getDrawable(
                    R.drawable.arda_media_round_button_background), true, true, (v) -> {
                if (playbackController != null) {
                        playbackController.doCustomAction(
                                customAction.mAction, customAction.mExtras);
                }
            });
            return true;
        } else {
            updateButton(button, null, null, false, false, null);
            return false;
        }
    }

    private int getOverflowTabIndex() {
        return -1;
    }

    private int getQueueTabIndex() {
        return getMediaHasQueue() ? 0 : -1;
    }

    private int getHistoryTabIndex() {
        return getMediaHasQueue() ? -1 : 0;
    }

    private boolean hasOverflow() {
        return false;
    }
}
