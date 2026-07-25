/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.carlauncher;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.car.carlauncher.AppGridFragment.Mode.ALL_APPS;
import static com.android.car.carlauncher.CarLauncherViewModel.CarLauncherViewModelFactory;
import static com.android.systemui.car.Flags.scalableUi;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskStackListener;
import android.car.Car;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.collection.ArraySet;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.MediaLaunchHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaLaunchRouter;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.wm.shell.taskview.TaskView;

import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Basic Launcher for Android Automotive which demonstrates the use of {@link TaskView} to host
 * maps content and uses a Model-View-Presenter structure to display content in cards.
 *
 * <p>Implementations of the Launcher that use the given layout of the main activity
 * (car_launcher.xml) can customize the home screen cards by providing their own
 * {@link HomeCardModule} for R.id.top_card or R.id.bottom_card. Otherwise, implementations that
 * use their own layout should define their own activity rather than using this one.
 *
 * <p>Note: On some devices, the TaskView may render with a width, height, and/or aspect
 * ratio that does not meet Android compatibility definitions. Developers should work with content
 * owners to ensure content renders correctly when extending or emulating this class.
 */
public class CarLauncher extends FragmentActivity {
    public static final String TAG = "CarLauncher";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ACTION_TOGGLE_MAP_FULLSCREEN =
            "com.android.car.carlauncher.action.TOGGLE_MAP_FULLSCREEN";
    private static final String CONTROL_LAYOUT_PERMISSION =
            "com.android.car.carlauncher.permission.CONTROL_LAYOUT";
    private static final String SETTING_MAP_ON_RIGHT = "arda_launcher_map_on_right";
    private static final String SETTING_MAP_WIDTH_PERCENT =
            "arda_launcher_map_width_percent";
    private static final int DEFAULT_MAP_WIDTH_PERCENT = 67;
    private static final int MIN_MAP_WIDTH_PERCENT = 45;
    private static final int MAX_MAP_WIDTH_PERCENT = 80;

    private ActivityManager mActivityManager;
    private Car mCar;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private Set<HomeCardModule> mHomeCardModules;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;
    private boolean mUseSmallCanvasOptimizedMap;
    private ViewGroup mMapsCard;
    private ConstraintLayout mLauncherRoot;
    private View mTopCard;
    private View mBottomCard;
    private boolean mMapFullscreen;
    private boolean mLayoutReceiverRegistered;
    private ContentObserver mLauncherLayoutObserver;

    private final BroadcastReceiver mLayoutToggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_TOGGLE_MAP_FULLSCREEN.equals(intent.getAction())) {
                return;
            }
            mMapFullscreen = !mMapFullscreen;
            Log.i(TAG, "ARDA_LAUNCHER_MAP_FULLSCREEN=" + mMapFullscreen
                    + " ARDA_LAUNCHER_HOME_CARDS_HIDDEN=" + mMapFullscreen);
            applyArdaLauncherLayout();
        }
    };

    @VisibleForTesting
    CarLauncherViewModel mCarLauncherViewModel;
    @VisibleForTesting
    ContentObserver mTosContentObserver;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DEBUG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (!mUseSmallCanvasOptimizedMap
                    && !homeTaskVisible
                    && getTaskViewTaskId() == task.taskId) {
                // The embedded map component received an intent, therefore forcibly bringing the
                // launcher to the foreground.
                bringToForeground();
            }
        }
    };

    private final IntentHandler mIntentHandler = intent -> {
        if (intent != null) {
            ActivityOptions options = ActivityOptions.makeBasic();
            startActivity(intent, options.toBundle());
        }
    };

    // Used instead of IntentHandler because media apps may provide a PendingIntent instead
    private final MediaLaunchHandler mMediaMediaLaunchHandler = mediaSource -> {
        if (DEBUG) {
            Log.d(TAG, "Launching media source " + mediaSource);
        }
        mediaSource.launchActivity(CarLauncher.this, ActivityOptions.makeBasic());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) {
            Log.d(TAG, "onCreate(" + getUserId() + ") displayId=" + getDisplayId());
        }
        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);

        // TODO(b/408491355): remove `isDewdActive()` checks and clean up legacy logic when all
        //   targets are migrated to DEWD.
        if (isDewdActive()) {
            if (DEBUG) {
                Log.d(TAG, "Dewd Launcher active");
            }

            if (!scalableUi()) {
                Log.e(TAG, "Scalable UI is disabled - home screen will appear empty!");
            }

            setContentView(R.layout.home);
            return;
        }

        // Since MUMD/MUPAND is introduced, CarLauncher can be called in the main display of
        // visible background users.
        // For Passenger scenarios, replace the maps_card with AppGridActivity, as currently
        // there is no maps use-case for passengers.
        boolean isPassengerDisplay = isPassengerDisplay();

        // Don't show the maps panel in multi window mode.
        // NOTE: CTS tests for split screen are not compatible with activity views on the
        // default activity of the launcher
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
            initializeArdaLauncherLayoutControls();
            // Passenger displays do not require TaskView Embedding
            if (!isPassengerDisplay) {
                mUseSmallCanvasOptimizedMap =
                        CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(this);

                mActivityManager = getSystemService(ActivityManager.class);
                mCarLauncherTaskId = getTaskId();
                TaskStackChangeListeners.getInstance().registerTaskStackListener(
                        mTaskStackListener);

                // Setting as trusted overlay to let touches pass through.
                getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
                // To pass touches to the underneath task.
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                // We don't want to show Map card unnecessarily for the headless user 0
                if (!UserHelperLite.isHeadlessSystemUser(getUserId())) {
                    mMapsCard = findViewById(R.id.maps_card);
                    if (mMapsCard != null) {
                        setupRemoteCarTaskView(mMapsCard);
                    }
                }
            } else {
                // For Passenger display show the AppGridFragment in place of the Maps view.
                // Also we can skip initializing all the TaskView related objects as they are
                // not used in this case.
                getSupportFragmentManager().beginTransaction().replace(R.id.maps_card,
                        AppGridFragment.newInstance(ALL_APPS)).commit();

            }
        }

        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);

        initializeCards();

        setupContentObserversForTos();
    }

    private void setupRemoteCarTaskView(ViewGroup parent) {
        mCarLauncherViewModel = new ViewModelProvider(this,
                new CarLauncherViewModelFactory(this, getMapsIntent()))
                .get(CarLauncherViewModel.class);

        getLifecycle().addObserver(mCarLauncherViewModel);
        addOnNewIntentListener(mCarLauncherViewModel.getNewIntentListener());

        setUpRemoteCarTaskViewObserver(parent);
    }

    private void setUpRemoteCarTaskViewObserver(ViewGroup parent) {
        mCarLauncherViewModel.getRemoteCarTaskView().observe(this, taskView -> {
            if (taskView == null || taskView.getParent() == parent) {
                // Discard if the parent is still the same because it doesn't signify a config
                // change.
                return;
            }
            if (taskView.getParent() != null) {
                // Discard the previous parent as its invalid now.
                ((ViewGroup) taskView.getParent()).removeView(taskView);
            }
            parent.removeAllViews(); // Just a defense against a dirty parent.
            parent.addView(taskView);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        maybeLogReady();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        releaseArdaLauncherLayoutControls();

        if (isDewdActive()) {
            // no-op
            return;
        }

        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        unregisterTosContentObserver();
        release();
    }

    private void initializeArdaLauncherLayoutControls() {
        mLauncherRoot = findViewById(R.id.launcher_root);
        mTopCard = findViewById(R.id.top_card);
        mBottomCard = findViewById(R.id.bottom_card);
        mMapsCard = findViewById(R.id.maps_card);

        if (mLauncherRoot == null || mMapsCard == null) {
            return;
        }

        registerReceiver(
                mLayoutToggleReceiver,
                new IntentFilter(ACTION_TOGGLE_MAP_FULLSCREEN),
                CONTROL_LAYOUT_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED);
        mLayoutReceiverRegistered = true;

        mLauncherLayoutObserver = new ContentObserver(new Handler(getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                applyArdaLauncherLayout();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_MAP_ON_RIGHT),
                false,
                mLauncherLayoutObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_MAP_WIDTH_PERCENT),
                false,
                mLauncherLayoutObserver);

        mLauncherRoot.post(this::applyArdaLauncherLayout);
    }

    private void releaseArdaLauncherLayoutControls() {
        if (mLauncherLayoutObserver != null) {
            getContentResolver().unregisterContentObserver(mLauncherLayoutObserver);
            mLauncherLayoutObserver = null;
        }
        if (mLayoutReceiverRegistered) {
            unregisterReceiver(mLayoutToggleReceiver);
            mLayoutReceiverRegistered = false;
        }
    }

    private void applyArdaLauncherLayout() {
        if (mLauncherRoot == null || mMapsCard == null) {
            return;
        }

        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(mLauncherRoot);
        int margin = getResources().getDimensionPixelSize(R.dimen.main_screen_widget_margin);

        if (mMapFullscreen) {
            if (mTopCard != null) {
                constraints.setVisibility(R.id.top_card, View.GONE);
            }
            if (mBottomCard != null) {
                constraints.setVisibility(R.id.bottom_card, View.GONE);
            }
            constraints.clear(R.id.maps_card, ConstraintSet.LEFT);
            constraints.clear(R.id.maps_card, ConstraintSet.RIGHT);
            constraints.clear(R.id.maps_card, ConstraintSet.TOP);
            constraints.clear(R.id.maps_card, ConstraintSet.BOTTOM);
            constraints.connect(
                    R.id.maps_card, ConstraintSet.LEFT,
                    ConstraintSet.PARENT_ID, ConstraintSet.LEFT);
            constraints.connect(
                    R.id.maps_card, ConstraintSet.RIGHT,
                    ConstraintSet.PARENT_ID, ConstraintSet.RIGHT);
            constraints.connect(
                    R.id.maps_card, ConstraintSet.TOP,
                    ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            constraints.connect(
                    R.id.maps_card, ConstraintSet.BOTTOM,
                    ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            setHorizontalMargins(constraints, R.id.maps_card, margin, margin);
            constraints.setMargin(R.id.maps_card, ConstraintSet.TOP, margin);
            constraints.setMargin(R.id.maps_card, ConstraintSet.BOTTOM, margin);
            constraints.applyTo(mLauncherRoot);
            return;
        }

        if (mTopCard != null) {
            constraints.setVisibility(R.id.top_card, View.VISIBLE);
        }
        if (mBottomCard != null) {
            constraints.setVisibility(R.id.bottom_card, View.VISIBLE);
        }

        View guideline = findViewById(R.id.vertical_barrier);
        if (guideline == null) {
            constraints.applyTo(mLauncherRoot);
            return;
        }

        boolean mapOnRight = Settings.Global.getInt(
                getContentResolver(), SETTING_MAP_ON_RIGHT, 1) != 0;
        int mapWidthPercent = Settings.Global.getInt(
                getContentResolver(),
                SETTING_MAP_WIDTH_PERCENT,
                DEFAULT_MAP_WIDTH_PERCENT);
        mapWidthPercent = Math.max(
                MIN_MAP_WIDTH_PERCENT,
                Math.min(MAX_MAP_WIDTH_PERCENT, mapWidthPercent));
        float mapFraction = mapWidthPercent / 100f;
        constraints.setGuidelinePercent(
                R.id.vertical_barrier,
                mapOnRight ? 1f - mapFraction : mapFraction);

        clearHorizontalConstraints(constraints, R.id.top_card);
        clearHorizontalConstraints(constraints, R.id.bottom_card);
        clearHorizontalConstraints(constraints, R.id.maps_card);

        if (mapOnRight) {
            connectBetweenParentAndGuideline(
                    constraints, R.id.top_card, true, R.id.vertical_barrier);
            connectBetweenParentAndGuideline(
                    constraints, R.id.bottom_card, true, R.id.vertical_barrier);
            connectBetweenGuidelineAndParent(
                    constraints, R.id.maps_card, R.id.vertical_barrier, true);
            setHorizontalMargins(constraints, R.id.top_card, margin, 0);
            setHorizontalMargins(constraints, R.id.bottom_card, margin, 0);
            setHorizontalMargins(constraints, R.id.maps_card, margin, margin);
        } else {
            connectBetweenParentAndGuideline(
                    constraints, R.id.maps_card, true, R.id.vertical_barrier);
            connectBetweenGuidelineAndParent(
                    constraints, R.id.top_card, R.id.vertical_barrier, true);
            connectBetweenGuidelineAndParent(
                    constraints, R.id.bottom_card, R.id.vertical_barrier, true);
            setHorizontalMargins(constraints, R.id.maps_card, margin, margin);
            setHorizontalMargins(constraints, R.id.top_card, 0, margin);
            setHorizontalMargins(constraints, R.id.bottom_card, 0, margin);
        }

        constraints.setMargin(R.id.maps_card, ConstraintSet.TOP, 0);
        constraints.setMargin(R.id.maps_card, ConstraintSet.BOTTOM, 0);
        constraints.applyTo(mLauncherRoot);
        Log.i(TAG, "ARDA_LAUNCHER_LAYOUT mapOnRight=" + mapOnRight
                + " mapWidthPercent=" + mapWidthPercent);
    }

    private static void clearHorizontalConstraints(ConstraintSet constraints, int viewId) {
        constraints.clear(viewId, ConstraintSet.LEFT);
        constraints.clear(viewId, ConstraintSet.RIGHT);
        constraints.clear(viewId, ConstraintSet.START);
        constraints.clear(viewId, ConstraintSet.END);
    }

    private static void connectBetweenParentAndGuideline(
            ConstraintSet constraints, int viewId, boolean parentOnLeft, int guidelineId) {
        if (!parentOnLeft) {
            throw new IllegalArgumentException("Only LTR launcher layouts are supported");
        }
        constraints.connect(
                viewId, ConstraintSet.LEFT,
                ConstraintSet.PARENT_ID, ConstraintSet.LEFT);
        constraints.connect(
                viewId, ConstraintSet.RIGHT,
                guidelineId, ConstraintSet.LEFT);
    }

    private static void connectBetweenGuidelineAndParent(
            ConstraintSet constraints, int viewId, int guidelineId, boolean parentOnRight) {
        if (!parentOnRight) {
            throw new IllegalArgumentException("Only LTR launcher layouts are supported");
        }
        constraints.connect(
                viewId, ConstraintSet.LEFT,
                guidelineId, ConstraintSet.RIGHT);
        constraints.connect(
                viewId, ConstraintSet.RIGHT,
                ConstraintSet.PARENT_ID, ConstraintSet.RIGHT);
    }

    private static void setHorizontalMargins(
            ConstraintSet constraints, int viewId, int left, int right) {
        constraints.setMargin(viewId, ConstraintSet.LEFT, left);
        constraints.setMargin(viewId, ConstraintSet.RIGHT, right);
    }

    private void unregisterTosContentObserver() {
        if (mTosContentObserver != null) {
            Log.i(TAG, "Unregister content observer for tos state");
            getContentResolver().unregisterContentObserver(mTosContentObserver);
            mTosContentObserver = null;
        }
    }

    private int getTaskViewTaskId() {
        if (mCarLauncherViewModel != null) {
            return mCarLauncherViewModel.getRemoteCarTaskViewTaskId();
        }
        return INVALID_TASK_ID;
    }

    private void release() {
        if (mMapsCard != null) {
            // This is important as the TaskView is preserved during config change in ViewModel and
            // to avoid the memory leak, it should be plugged out of the View hierarchy.
            mMapsCard.removeAllViews();
            mMapsCard = null;
        }

        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isDewdActive()) {
            // no-op
            return;
        }

        initializeCards();
    }

    private void initializeCards() {
        if (mHomeCardModules == null) {
            mHomeCardModules = new ArraySet<>();
            for (String providerClassName : getResources().getStringArray(
                    R.array.config_homeCardModuleClasses)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule)
                            Class.forName(providerClassName).newInstance();
                    if (Flags.mediaCardFullscreen()) {
                        if (cardModule.getCardResId() == R.id.top_card) {
                            findViewById(R.id.top_card).setVisibility(View.GONE);
                        }
                    }
                    cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */this));
                    mHomeCardModules.add(cardModule);
                    if (DEBUG) {
                        long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                        Log.d(TAG, "Initialization of HomeCardModule class " + providerClassName
                                + " took " + reflectionTime + " ms");
                    }
                } catch (IllegalAccessException | InstantiationException
                         | ClassNotFoundException e) {
                    Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
                }
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : mHomeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView().getFragment());
        }
        transaction.commitNow();
    }

    /** Logs that the Activity is ready. Used for startup time diagnostics. */
    private void maybeLogReady() {
        boolean isResumed = isResumed();
        if (isResumed) {
            // We should report every time - the Android framework will take care of logging just
            // when it's effectively drawn for the first time, but....
            reportFullyDrawn();
            if (!mIsReadyLogged) {
                // ... we want to manually check that the Log.i below (which is useful to show
                // the user id) is only logged once (otherwise it would be logged every time the
                // user taps Home)
                Log.i(TAG, "Launcher for user " + getUserId() + " is ready");
                mIsReadyLogged = true;
            }
        }
    }

    /** Brings the Car Launcher to the foreground. */
    private void bringToForeground() {
        if (mCarLauncherTaskId != INVALID_TASK_ID) {
            mActivityManager.moveTaskToFront(mCarLauncherTaskId,  /* flags= */ 0);
        }
    }

    @VisibleForTesting
    protected Intent getMapsIntent() {
        Intent mapIntent = mUseSmallCanvasOptimizedMap
                ? CarLauncherUtils.getSmallCanvasOptimizedMapIntent(this)
                : CarLauncherUtils.getMapsIntent(this);

        // Don't want to show this Activity in Recents.
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return mapIntent;
    }

    private void setupContentObserversForTos() {
        if (AppLauncherUtils.tosStatusUninitialized(/* context = */ this)
                || !AppLauncherUtils.tosAccepted(/* context = */ this)) {
            Log.i(TAG, "TOS not accepted, setting up content observers for TOS state");
        } else {
            Log.i(TAG,
                    "TOS accepted, state will remain accepted, don't need to observe this value");
            return;
        }
        mTosContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                // Release the task view and re-initialize the remote car task view with the new
                // maps intent whenever an onChange is received. This is because the TOS state
                // can go from uninitialized to not accepted during which there could be a race
                // condition in which the maps activity is from the uninitialized state.
                Set<String> tosDisabledApps = AppLauncherUtils.getTosDisabledPackages(
                        getBaseContext());
                boolean tosAccepted = AppLauncherUtils.tosAccepted(getBaseContext());
                Log.i(TAG, "TOS state updated:" + tosAccepted);
                if (DEBUG) {
                    Log.d(TAG, "TOS disabled apps:" + tosDisabledApps);
                }

                if (mCarLauncherViewModel != null
                        && mCarLauncherViewModel.getRemoteCarTaskView().getValue() != null) {
                    // Reinitialize the remote car task view with the new maps intent
                    mCarLauncherViewModel.initializeRemoteCarTaskView(getMapsIntent());
                    setUpRemoteCarTaskViewObserver(mMapsCard);
                }

                if (tosAccepted) {
                    unregisterTosContentObserver();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_UNACCEPTED_TOS_DISABLED_APPS),
                /* notifyForDescendants*/ false,
                mTosContentObserver);
    }

    /** Returns {@code true} if the launcher is currently running on a passenger display. */
    private boolean isPassengerDisplay() {
        UserManager um = getSystemService(UserManager.class);
        return getDisplayId() != Display.DEFAULT_DISPLAY
                || um.isVisibleBackgroundUsersOnDefaultDisplaySupported();
    }

    /** Returns {@code true} if the declarative launcher configuration is active. */
    private boolean isDewdActive() {
        // Note: for now, passengers do not support dewd
        return !isPassengerDisplay() && getResources().getBoolean(R.bool.config_useDewdLauncher);
    }
}
