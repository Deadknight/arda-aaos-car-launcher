package com.android.car.carlauncher;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Runtime ARDA launcher canvas controller.
 *
 * The map/media TaskViews keep their own surfaces. This controller changes only
 * the Launcher window/content background visible around those cards.
 */
public final class ArdaLauncherBackgroundController {
    private static final String TAG = "ArdaLauncherBackground";

    private static final String KEY_MODE = "arda_launcher_background_mode";
    private static final String KEY_COLOR = "arda_launcher_background_color";
    private static final String KEY_URI = "arda_launcher_background_uri";
    private static final String KEY_REVISION = "arda_visual_theme_revision";

    private static final int MODE_DEFAULT = 0;
    private static final int MODE_COLOR = 1;
    private static final int MODE_IMAGE = 2;

    private static final WeakHashMap<Activity, ArdaLauncherBackgroundController> sControllers =
            new WeakHashMap<>();

    private final WeakReference<Activity> mActivityRef;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mObserver;

    private View mDecor;
    private View mContent;
    private View mLauncherRoot;

    private Drawable mOriginalDecorBackground;
    private Drawable mOriginalContentBackground;
    private Drawable mOriginalRootBackground;
    private boolean mOriginalsCaptured;
    private boolean mRegistered;

    private ArdaLauncherBackgroundController(Activity activity) {
        mActivityRef = new WeakReference<>(activity);
        mResolver = activity.getContentResolver();
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                apply();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                apply();
            }
        };
    }

    public static void attach(Activity activity) {
        synchronized (sControllers) {
            if (sControllers.containsKey(activity)) {
                return;
            }
            ArdaLauncherBackgroundController controller =
                    new ArdaLauncherBackgroundController(activity);
            sControllers.put(activity, controller);
            controller.start();
        }
    }

    private void start() {
        Activity activity = mActivityRef.get();
        if (activity == null) {
            return;
        }

        mDecor = activity.getWindow().getDecorView();
        mContent = activity.findViewById(android.R.id.content);
        if (mContent instanceof ViewGroup && ((ViewGroup) mContent).getChildCount() == 1) {
            mLauncherRoot = ((ViewGroup) mContent).getChildAt(0);
        }

        captureOriginals();

        mResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_REVISION),
                false,
                mObserver);
        mRegistered = true;

        mDecor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                apply();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stop();
            }
        });

        apply();
    }

    private void stop() {
        if (mRegistered) {
            mResolver.unregisterContentObserver(mObserver);
            mRegistered = false;
        }
        Activity activity = mActivityRef.get();
        if (activity != null) {
            synchronized (sControllers) {
                sControllers.remove(activity);
            }
        }
    }

    private void captureOriginals() {
        if (mOriginalsCaptured) {
            return;
        }
        mOriginalDecorBackground = mDecor != null ? mDecor.getBackground() : null;
        mOriginalContentBackground = mContent != null ? mContent.getBackground() : null;
        mOriginalRootBackground = mLauncherRoot != null ? mLauncherRoot.getBackground() : null;
        mOriginalsCaptured = true;
    }

    private void apply() {
        Activity activity = mActivityRef.get();
        if (activity == null || mDecor == null) {
            return;
        }

        final int mode = Settings.Global.getInt(mResolver, KEY_MODE, MODE_DEFAULT);
        final String colorValue = Settings.Global.getString(mResolver, KEY_COLOR);
        final String uriValue = Settings.Global.getString(mResolver, KEY_URI);

        Drawable drawable = null;
        String applied = "default";

        if (mode == MODE_COLOR) {
            try {
                drawable = new ColorDrawable(
                        Color.parseColor(colorValue != null ? colorValue : "#FF0B1016"));
                applied = "color:" + colorValue;
            } catch (RuntimeException e) {
                Log.e(TAG, "COLOR_PARSE_FAIL value=" + colorValue, e);
            }
        } else if (mode == MODE_IMAGE && uriValue != null && !uriValue.isEmpty()) {
            try (InputStream input = mResolver.openInputStream(Uri.parse(uriValue))) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    drawable = new CenterCropBitmapDrawable(bitmap);
                    applied = "image:" + uriValue;
                } else {
                    Log.e(TAG, "IMAGE_DECODE_FAIL uri=" + uriValue);
                }
            } catch (Exception e) {
                Log.e(TAG, "IMAGE_LOAD_FAIL uri=" + uriValue, e);
            }
        }

        if (drawable == null) {
            restoreOriginals();
            Log.i(TAG, "APPLY mode=DEFAULT");
            return;
        }

        // The visible black canvas can come from the Window, android.R.id.content,
        // or the launcher's single root child depending on the active theme/layout.
        // Apply the same canvas drawable to all three while leaving TaskView/card
        // surfaces untouched.
        if (mDecor != null) {
            mDecor.setBackground(drawable);
        }
        if (mContent != null) {
            mContent.setBackground(drawable);
        }
        if (mLauncherRoot != null) {
            mLauncherRoot.setBackground(drawable);
        }

        Log.i(TAG, "APPLY mode=" + mode
                + " value=" + applied
                + " root=" + (mLauncherRoot != null));
    }

    private void restoreOriginals() {
        if (!mOriginalsCaptured) {
            return;
        }
        if (mDecor != null) {
            mDecor.setBackground(mOriginalDecorBackground);
        }
        if (mContent != null) {
            mContent.setBackground(mOriginalContentBackground);
        }
        if (mLauncherRoot != null) {
            mLauncherRoot.setBackground(mOriginalRootBackground);
        }
    }

    /** Drawable that center-crops the selected bitmap into whatever launcher size is active. */
    private static final class CenterCropBitmapDrawable extends Drawable {
        private final Bitmap mBitmap;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect mSrc = new Rect();

        CenterCropBitmapDrawable(Bitmap bitmap) {
            mBitmap = bitmap;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty() || mBitmap.getWidth() <= 0 || mBitmap.getHeight() <= 0) {
                return;
            }

            float bitmapAspect = (float) mBitmap.getWidth() / mBitmap.getHeight();
            float boundsAspect = (float) bounds.width() / bounds.height();

            if (bitmapAspect > boundsAspect) {
                int srcWidth = Math.round(mBitmap.getHeight() * boundsAspect);
                int left = (mBitmap.getWidth() - srcWidth) / 2;
                mSrc.set(left, 0, left + srcWidth, mBitmap.getHeight());
            } else {
                int srcHeight = Math.round(mBitmap.getWidth() / boundsAspect);
                int top = (mBitmap.getHeight() - srcHeight) / 2;
                mSrc.set(0, top, mBitmap.getWidth(), top + srcHeight);
            }

            canvas.drawBitmap(mBitmap, mSrc, bounds, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
