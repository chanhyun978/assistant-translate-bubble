package com.assistanttranslatebubble.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

public class BubbleService extends Service {
    private static final String CHANNEL_ID = "translate_bubble_status";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREF_BUBBLE_X = "bubble_x";
    private static final String PREF_BUBBLE_Y = "bubble_y";
    private static final long SCREENSHOT_CAPTURE_DELAY_MS = 80L;
    private static final long SCREENSHOT_RESTORE_DELAY_MS = 700L;
    private static final long SCREENSHOT_HAPTIC_MS = 42L;
    private static final int SCREENSHOT_HAPTIC_AMPLITUDE = 245;
    private static final int MODE_SWITCH_DISTANCE_DP = 34;

    private static volatile boolean running;
    private static volatile BubbleService activeService;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = this::triggerLongPressAction;

    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private View bubbleView;
    private View dismissTargetView;
    private View dismissIndicatorView;
    private int touchSlop;
    private float downRawX;
    private float downRawY;
    private int downParamX;
    private int downParamY;
    private boolean dragged;
    private boolean draggingOverDismissTarget;
    private boolean longPressTriggered;
    private String longPressSelectedAction;

    static boolean isRunning() {
        return running;
    }

    static void notifyAssistantSettled() {
        BubbleService service = activeService;
        if (service != null) {
            service.handler.post(service::pulseAssistantReady);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        activeService = this;
        PermissionUtils.setAutomationActive(this, true);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        startForegroundServiceNotification();

        if (PermissionUtils.canDrawOverlays(this)) {
            addBubble();
        } else {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PermissionUtils.setAutomationActive(this, true);
        if (bubbleView == null && PermissionUtils.canDrawOverlays(this)) {
            addBubble();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeDismissTarget();
        removeBubble();
        PermissionUtils.setAutomationActive(this, false);
        AssistantTranslateController.resetAll();
        AssistantTranslateAccessibilityService.stopConnectedWork();
        running = false;
        if (activeService == this) {
            activeService = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addBubble() {
        if (windowManager == null || bubbleView != null) {
            return;
        }

        int size = PermissionUtils.dp(this, 56);
        bubbleParams = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bubbleParams.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            bubbleParams.setBlurBehindRadius(PermissionUtils.dp(this, 18));
        }
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = PermissionUtils.prefs(this).getInt(
                PREF_BUBBLE_X,
                getScreenWidth() - PermissionUtils.dp(this, 76)
        );
        bubbleParams.y = PermissionUtils.prefs(this).getInt(
                PREF_BUBBLE_Y,
                PermissionUtils.dp(this, 180)
        );

        bubbleView = createBubbleView();
        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (RuntimeException error) {
            bubbleView = null;
            stopSelf();
        }
    }

    private View createBubbleView() {
        FrameLayout bubble = new GlassBubbleView(this);
        bubble.setContentDescription("번역 버블");
        bubble.setClickable(true);
        bubble.setFocusable(false);
        bubble.setOnTouchListener(this::handleBubbleTouch);
        return bubble;
    }

    private boolean handleBubbleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downParamX = bubbleParams.x;
                downParamY = bubbleParams.y;
                dragged = false;
                draggingOverDismissTarget = false;
                longPressTriggered = false;
                longPressSelectedAction = null;
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - downRawX;
                float deltaY = event.getRawY() - downRawY;
                if (longPressTriggered) {
                    handleLongPressModeDrag(deltaY);
                    return true;
                }
                if (!dragged && Math.hypot(deltaX, deltaY) > touchSlop) {
                    dragged = true;
                    handler.removeCallbacks(longPressRunnable);
                    AssistantTranslateController.closeTranslationBecauseBubbleMoved();
                    showDismissTarget();
                }
                if (dragged) {
                    bubbleParams.x = clamp(downParamX + Math.round(deltaX), 0, getScreenWidth() - bubbleParams.width);
                    bubbleParams.y = clamp(downParamY + Math.round(deltaY), 0, getScreenHeight() - bubbleParams.height);
                    updateBubblePosition();
                    setDismissTargetActive(isInDismissArea());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (dragged) {
                    boolean shouldDismiss = event.getActionMasked() == MotionEvent.ACTION_UP && isInDismissArea();
                    if (shouldDismiss) {
                        triggerDismissHaptic(view);
                    }
                    hideDismissTarget();
                    if (shouldDismiss) {
                        stopSelf();
                    } else {
                        saveBubblePosition();
                    }
                } else if (!longPressTriggered && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    triggerTapAction();
                }
                return true;

            default:
                return true;
        }
    }

    private void triggerDismissHaptic(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private void triggerLongPressAction() {
        if (bubbleView == null || dragged) {
            return;
        }
        longPressTriggered = true;
        bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        animateBubblePress();
    }

    private void handleLongPressModeDrag(float deltaY) {
        int threshold = PermissionUtils.dp(this, MODE_SWITCH_DISTANCE_DP);
        if (deltaY <= -threshold) {
            selectBubbleActionFromGesture(PermissionUtils.BUBBLE_ACTION_TRANSLATE);
        } else if (deltaY >= threshold) {
            selectBubbleActionFromGesture(PermissionUtils.BUBBLE_ACTION_SCREENSHOT);
        }
    }

    private void selectBubbleActionFromGesture(String action) {
        if (action.equals(longPressSelectedAction)) {
            return;
        }
        longPressSelectedAction = action;
        if (action.equals(PermissionUtils.bubbleAction(this))) {
            return;
        }
        PermissionUtils.setBubbleAction(this, action);
        if (bubbleView != null) {
            bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            animateBubblePress();
        }
        showModeChangedToast(action);
    }

    private void triggerTapAction() {
        if (bubbleView == null) {
            return;
        }
        if (PermissionUtils.useScreenshotAction(this)) {
            triggerScreenshotAction();
            return;
        }
        if (AssistantTranslateController.handleBubbleTap(this)) {
            bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            animateBubblePress();
        }
    }

    private void triggerScreenshotAction() {
        AssistantTranslateAccessibilityService service =
                AssistantTranslateAccessibilityService.getConnectedService();
        if (service == null || bubbleView == null) {
            return;
        }

        View capturedBubble = bubbleView;
        triggerScreenshotHaptic(capturedBubble);
        removeBubbleImmediately();
        handler.postDelayed(() -> {
            boolean requested = service.takeSystemScreenshot();
            handler.postDelayed(() -> restoreBubbleAfterScreenshot(capturedBubble),
                    requested ? SCREENSHOT_RESTORE_DELAY_MS : 160L);
        }, SCREENSHOT_CAPTURE_DELAY_MS);
    }

    private void restoreBubbleAfterScreenshot(View capturedBubble) {
        if (windowManager == null || capturedBubble == null || bubbleView != null) {
            return;
        }
        try {
            windowManager.addView(capturedBubble, bubbleParams);
            bubbleView = capturedBubble;
        } catch (RuntimeException error) {
            bubbleView = null;
            stopSelf();
        }
    }

    private void triggerScreenshotHaptic(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        vibrator.vibrate(VibrationEffect.createOneShot(
                SCREENSHOT_HAPTIC_MS,
                SCREENSHOT_HAPTIC_AMPLITUDE
        ));
    }

    private void showModeChangedToast(String action) {
        String label = PermissionUtils.BUBBLE_ACTION_SCREENSHOT.equals(action)
                ? "스크린샷"
                : "번역";
        Toast.makeText(this, label + " 모드로 변경됨", Toast.LENGTH_SHORT).show();
    }

    private void animateBubblePress() {
        bubbleView.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(70L)
                .withEndAction(() -> bubbleView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100L)
                        .start())
                .start();
    }

    private void pulseAssistantReady() {
        if (bubbleView == null) {
            return;
        }
        bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        bubbleView.animate().cancel();
        bubbleView.setScaleX(1f);
        bubbleView.setScaleY(1f);
        bubbleView.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(90L)
                .withEndAction(() -> bubbleView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150L)
                        .start())
                .start();
    }

    private void showDismissTarget() {
        if (windowManager == null || dismissTargetView != null) {
            return;
        }

        dismissTargetView = createDismissTargetView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                PermissionUtils.dp(this, 104),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.START;
        try {
            windowManager.addView(dismissTargetView, params);
            dismissTargetView.animate().alpha(1f).setDuration(120L).start();
        } catch (RuntimeException error) {
            dismissTargetView = null;
            dismissIndicatorView = null;
        }
    }

    private View createDismissTargetView() {
        FrameLayout container = new FrameLayout(this);
        container.setAlpha(0f);

        dismissIndicatorView = new View(this);
        dismissIndicatorView.setBackground(rounded(Color.argb(92, 31, 39, 53), 2));

        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                PermissionUtils.dp(this, 44),
                PermissionUtils.dp(this, 4),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        indicatorParams.bottomMargin = PermissionUtils.dp(this, 22);
        container.addView(dismissIndicatorView, indicatorParams);
        return container;
    }

    private void setDismissTargetActive(boolean active) {
        if (draggingOverDismissTarget == active) {
            return;
        }
        draggingOverDismissTarget = active;

        if (dismissIndicatorView != null) {
            dismissIndicatorView.setBackground(rounded(
                    active ? Color.argb(210, 31, 39, 53) : Color.argb(92, 31, 39, 53),
                    2
            ));
            dismissIndicatorView.animate()
                    .scaleX(active ? 1.55f : 1f)
                    .alpha(active ? 1f : 0.72f)
                    .setDuration(120L)
                    .start();
        }

        if (bubbleView != null) {
            bubbleView.animate()
                    .scaleX(active ? 0.86f : 1f)
                    .scaleY(active ? 0.86f : 1f)
                    .setDuration(120L)
                    .start();
        }
    }

    private void hideDismissTarget() {
        setDismissTargetActive(false);
        if (dismissTargetView == null) {
            return;
        }

        View target = dismissTargetView;
        target.animate()
                .alpha(0f)
                .setDuration(90L)
                .withEndAction(this::removeDismissTarget)
                .start();
    }

    private void removeDismissTarget() {
        if (windowManager == null || dismissTargetView == null) {
            dismissTargetView = null;
            dismissIndicatorView = null;
            return;
        }
        try {
            windowManager.removeView(dismissTargetView);
        } catch (IllegalArgumentException ignored) {
        }
        dismissTargetView = null;
        dismissIndicatorView = null;
        draggingOverDismissTarget = false;
    }

    private boolean isInDismissArea() {
        if (bubbleParams == null) {
            return false;
        }
        int centerY = bubbleParams.y + (bubbleParams.height / 2);
        return centerY >= getScreenHeight() - PermissionUtils.dp(this, 112);
    }

    private void updateBubblePosition() {
        if (windowManager == null || bubbleView == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void saveBubblePosition() {
        PermissionUtils.prefs(this)
                .edit()
                .putInt(PREF_BUBBLE_X, bubbleParams.x)
                .putInt(PREF_BUBBLE_Y, bubbleParams.y)
                .apply();
    }

    private void removeBubble() {
        if (windowManager == null || bubbleView == null) {
            return;
        }
        try {
            windowManager.removeView(bubbleView);
        } catch (IllegalArgumentException ignored) {
        }
        bubbleView = null;
    }

    private void removeBubbleImmediately() {
        if (windowManager == null || bubbleView == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(bubbleView);
        } catch (IllegalArgumentException ignored) {
        }
        bubbleView = null;
    }

    private void startForegroundServiceNotification() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("번역 버블 실행 중")
                .setContentText("버블을 탭하면 선택한 동작을 실행합니다.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "번역 버블",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(PermissionUtils.dp(this, radiusDp));
        return drawable;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
