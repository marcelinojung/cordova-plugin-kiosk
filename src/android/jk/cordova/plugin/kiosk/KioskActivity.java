package jk.cordova.plugin.kiosk;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import org.apache.cordova.*;

import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ViewAnimator;

import java.lang.reflect.Method;

public class KioskActivity extends CordovaActivity {

    private static final String PREF_KIOSK_MODE = "pref_kiosk_mode";
    private static final int REQUEST_CODE = 123467;
    private static boolean inImmersiveSystemUiTransition = false;
    private static int immersiveSystemUiOptions = 0;
    private static boolean allowImmersiveSystemUiOverlay = false;
    public static boolean running = false;
    Object statusBarService;
    ActivityManager am;
    String TAG = "KioskActivity";

    private ViewAnimator viewAnimator;
    private SurfaceView surfaceView;
    private CustomViewGroup immersiveSystemUiOverlayView;

    public ViewAnimator getViewAnimator() {
        return viewAnimator;
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    public void setSurfaceView(SurfaceView surfaceView) {
        this.surfaceView = surfaceView;
    }

    protected void onStart() {
        super.onStart();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        if(Build.VERSION.SDK_INT >= 23) {
            sp.edit().putBoolean(PREF_KIOSK_MODE, false).commit();
            checkDrawOverlayPermission();
        } else {
            sp.edit().putBoolean(PREF_KIOSK_MODE, true).commit();
            allowImmersiveSystemUiOverlay = true;
        }
        running = true;
    }
    //http://stackoverflow.com/questions/7569937/unable-to-add-window-android-view-viewrootw44da9bc0-permission-denied-for-t
    @TargetApi(Build.VERSION_CODES.M)
    public void checkDrawOverlayPermission() {
        if (!Settings.canDrawOverlays(this.getApplicationContext())) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    protected void onActivityResult(int requestCode, int resultCode,  Intent data) {
        if (requestCode == REQUEST_CODE) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
            sp.edit().putBoolean(PREF_KIOSK_MODE, true).commit();
            if (Settings.canDrawOverlays(this)) {
                allowImmersiveSystemUiOverlay = true;
            }
        }
    }

    //http://stackoverflow.com/questions/25284233/prevent-status-bar-for-appearing-android-modified?answertab=active#tab-top
    private void addOverlay() {
        WindowManager manager = ((WindowManager) getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE));

        WindowManager.LayoutParams localLayoutParams = new WindowManager.LayoutParams();
        localLayoutParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        localLayoutParams.gravity = Gravity.TOP;
        localLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|

                // this is to enable the notification to recieve touch events
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |

                // Draws over status bar
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        localLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        localLayoutParams.height = (int) (50 * getResources()
                .getDisplayMetrics().scaledDensity);
        localLayoutParams.format = PixelFormat.TRANSPARENT;

        immersiveSystemUiOverlayView = new CustomViewGroup(this);
        manager.addView(immersiveSystemUiOverlayView, localLayoutParams);
    }

    private void removeOverlay() {
        WindowManager manager = ((WindowManager) getApplicationContext()
                .getSystemService(Context.WINDOW_SERVICE));

        if (immersiveSystemUiOverlayView != null) {
            manager.removeView(immersiveSystemUiOverlayView);
        }
    }

    protected void onStop() {
        super.onStop();
        running = false;
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        super.init();
        loadUrl(launchUrl);

        final android.os.Handler setTimeout = new android.os.Handler();

        // Runnable that removes the Fullscreen Overlay that prevents the User from interacting
        // with the SystemUI.
        final Runnable runnableRemoveOverlay = new Runnable() {
            public void run() {
                removeOverlay();
                inImmersiveSystemUiTransition = false;
            }
        };

        // Runnable that hides the SystemUI in case SystemUiVisibility flags are set properly.
        final Runnable runnableHideSystemUI = new Runnable() {
            public void run() {
                getWindow().getDecorView().setSystemUiVisibility(immersiveSystemUiOptions);
                setTimeout.postDelayed(runnableRemoveOverlay, 1000);
            }
        };

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            @Override
            public void onSystemUiVisibilityChange(int visibility) {
                if (visibility == View.STATUS_BAR_VISIBLE) {

                    if (inImmersiveSystemUiTransition) {
                        // It seems the User clicks randomly around.
                        // Strategy: Keep intercepting touch events and de-install Timeouts
                        // that would otherwise remove the Overlay.
                        setTimeout.removeCallbacks(runnableHideSystemUI);
                        setTimeout.removeCallbacks(runnableRemoveOverlay);
                    } else {
                        // We cannot prevent SystemUI from appearing. But we can prevent the
                        // User from interacting with SystemUI.
                        // Strategy: Show fullscreen overlay that intercepts touch events.
                        inImmersiveSystemUiTransition = true;

                        if (allowImmersiveSystemUiOverlay)
                            addOverlay();
                    }

                    // Install Timeout that hides SystemUI after 1sec and hides overlay after 2secs.
                    setTimeout.postDelayed(runnableHideSystemUI, 1000);

                }
            }
        });

    }

    private void collapseNotifications()
    {
        try
        {
            if(statusBarService == null) {
                statusBarService = getSystemService("statusbar");
            }

            Class<?> statusBarManager = Class.forName("android.app.StatusBarManager");

            if (Build.VERSION.SDK_INT <= 16)
            {
                Method collapseStatusBar = statusBarManager.getMethod("collapse");
                collapseStatusBar.setAccessible(true);
                collapseStatusBar.invoke(statusBarService);
                return;
            }
            Method collapseStatusBar = statusBarManager.getMethod("collapsePanels");
            collapseStatusBar.setAccessible(true);
            collapseStatusBar.invoke(statusBarService);
        }
        catch (Exception e)
        {
            Log.e(TAG, e.toString());
        }
    }

    public void onPause()
    {
        super.onPause();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        sp.edit().putBoolean(PREF_KIOSK_MODE, true).commit();
        if(!sp.getBoolean(PREF_KIOSK_MODE, false)) {
            return;
        }
        if(am == null) {
            am = ((ActivityManager)getSystemService("activity"));
        }
        am.moveTaskToFront(getTaskId(), 1);
        sendBroadcast(new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
        collapseNotifications();
    }

    @Override
    public void onBackPressed() {
        recreate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        sp.edit().putBoolean(PREF_KIOSK_MODE, true).commit();
        if(!sp.getBoolean(PREF_KIOSK_MODE, false)) {
            return;
        }
        if (!hasFocus) {
            if(am == null) {
                am = ((ActivityManager)getSystemService("activity"));
            }
            am.moveTaskToFront(getTaskId(), 1);
            sendBroadcast(new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
            collapseNotifications();
        }

        if (hasFocus && immersiveMode) {

            // Immersive Mode is enabled since 4.4 (API Level 19) and most likely enabled via
            // Cordova Preferences.
            // Strategy: Listen on SystemUI changes and let SystemUI disappear immediately after it appears.
            // Cordova sets SYSTEM_UI_FLAG_IMMERSIVE_STICKY flag, but we need SYSTEM_UI_FLAG_IMMERSIVE in
            // order to listen to SystemUI changes.
            immersiveSystemUiOptions = getWindow().getDecorView().getSystemUiVisibility()

                    // Cordova sets Sticky per default. Remove the flag as it prevents ui-change event delegation.
                    & ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

                    // Make sure that Immersive flag is set. This will enable ui-change event delegation.
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;

            // Overwrite Cordova's UI Visibility Options.
            getWindow().getDecorView().setSystemUiVisibility(immersiveSystemUiOptions);
        }
    }

    //http://stackoverflow.com/questions/25284233/prevent-status-bar-for-appearing-android-modified?answertab=active#tab-top
    public class CustomViewGroup extends ViewGroup {

        public CustomViewGroup(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return true;
        }
    }

    @Override
    protected void createViews() {
        appView.getView().setId(View.generateViewId());
        appView.getView().setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        viewAnimator = new ViewAnimator(this);
        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        viewAnimator.addView(appView.getView());
        viewAnimator.addView(surfaceView);
        setContentView(viewAnimator);

        if (preferences.contains("BackgroundColor")) {
            int backgroundColor = preferences.getInteger("BackgroundColor", Color.BLACK);
            // Background of activity:
            appView.getView().setBackgroundColor(backgroundColor);
        }

        appView.getView().requestFocusFromTouch();
    }

}
