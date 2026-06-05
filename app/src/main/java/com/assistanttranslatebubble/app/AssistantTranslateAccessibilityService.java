package com.assistanttranslatebubble.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class AssistantTranslateAccessibilityService extends AccessibilityService {
    private static final String GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String GOOGLE_ASSISTANT_PACKAGE = "com.google.android.apps.googleassistant";
    private static final String GOOGLE_LENS_PACKAGE = "com.google.ar.lens";
    private static final int MAX_CLICK_ATTEMPTS = 30;
    private static final int MAX_SETTLE_CHECKS = 42;

    private static volatile AssistantTranslateAccessibilityService connectedService;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean clickLoopScheduled;
    private boolean assistantSettleCheckScheduled;
    private boolean activeWindowCheckScheduled;
    private boolean waitingForAssistantSettle;
    private int activeRequestId = -1;
    private int clickAttempts;
    private int assistantSettleChecks;

    static AssistantTranslateAccessibilityService getConnectedService() {
        return connectedService;
    }

    static void stopConnectedWork() {
        AssistantTranslateAccessibilityService service = connectedService;
        if (service != null) {
            service.handler.post(service::stopInternalWork);
        }
    }

    @Override
    protected void onServiceConnected() {
        connectedService = this;
    }

    @Override
    public void onDestroy() {
        stopInternalWork();
        if (connectedService == this) {
            connectedService = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!PermissionUtils.isAutomationActive(this)) {
            stopInternalWork();
            return;
        }
        if (AssistantTranslateController.hasPendingRequest()) {
            scheduleClickLoop(160L);
        }
        if (waitingForAssistantSettle) {
            scheduleAssistantSettleCheck(60L);
        }
        if (AssistantTranslateController.isTranslationActive() && !waitingForAssistantSettle) {
            scheduleActiveWindowCheck(140L);
        }
    }

    @Override
    public void onInterrupt() {
    }

    void openAssistantWithHomeLongPress() {
        Rect bounds = getDisplayBounds();
        float x = bounds.centerX();
        float y = bounds.bottom - PermissionUtils.dp(this, 24);

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 850L))
                .build();

        boolean dispatched = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        scheduleClickLoop(450L);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        scheduleClickLoop(450L);
                    }
                },
                handler
        );

        if (!dispatched) {
            scheduleClickLoop(450L);
        }
    }

    boolean closeTranslationWindow() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    void watchAssistantCloseAfterBack() {
        waitingForAssistantSettle = true;
        assistantSettleChecks = 0;
        scheduleAssistantSettleCheck(180L);
    }

    boolean isGoogleTranslationWindowVisible() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && isAllowedPackage(root.getPackageName())) {
                    return true;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && isAllowedPackage(root.getPackageName());
    }

    boolean clickVisibleTranslateButton() {
        return clickTranslateButton();
    }

    private void scheduleClickLoop(long delayMs) {
        if (!PermissionUtils.isAutomationActive(this)) {
            return;
        }
        int requestId = AssistantTranslateController.currentRequestId();
        if (activeRequestId != requestId) {
            activeRequestId = requestId;
            clickAttempts = 0;
        }
        if (clickLoopScheduled) {
            return;
        }
        clickLoopScheduled = true;
        handler.postDelayed(this::tryClickTranslateButton, delayMs);
    }

    private void scheduleAssistantSettleCheck(long delayMs) {
        if (!PermissionUtils.isAutomationActive(this)) {
            return;
        }
        if (assistantSettleCheckScheduled) {
            return;
        }
        assistantSettleCheckScheduled = true;
        handler.postDelayed(this::checkAssistantSettled, delayMs);
    }

    private void scheduleActiveWindowCheck(long delayMs) {
        if (!PermissionUtils.isAutomationActive(this)) {
            return;
        }
        if (activeWindowCheckScheduled) {
            return;
        }
        activeWindowCheckScheduled = true;
        handler.postDelayed(this::checkActiveWindowStillVisible, delayMs);
    }

    private void checkActiveWindowStillVisible() {
        activeWindowCheckScheduled = false;
        if (!PermissionUtils.isAutomationActive(this)) {
            stopInternalWork();
            return;
        }
        if (!AssistantTranslateController.isTranslationActive() || waitingForAssistantSettle) {
            return;
        }
        if (!isGoogleTranslationWindowVisible()) {
            AssistantTranslateController.onAssistantWindowDismissedExternally();
        }
    }

    private void checkAssistantSettled() {
        assistantSettleCheckScheduled = false;
        if (!PermissionUtils.isAutomationActive(this)) {
            stopInternalWork();
            return;
        }
        if (!waitingForAssistantSettle) {
            return;
        }

        assistantSettleChecks += 1;
        if (!isGoogleTranslationWindowVisible()) {
            waitingForAssistantSettle = false;
            AssistantTranslateController.onAssistantWindowSettled(true);
            return;
        }

        if (assistantSettleChecks >= MAX_SETTLE_CHECKS) {
            waitingForAssistantSettle = false;
            AssistantTranslateController.onAssistantWindowSettled(false);
            return;
        }

        scheduleAssistantSettleCheck(120L);
    }

    private void tryClickTranslateButton() {
        clickLoopScheduled = false;
        if (!PermissionUtils.isAutomationActive(this)) {
            stopInternalWork();
            return;
        }
        if (!AssistantTranslateController.hasPendingRequest()) {
            return;
        }

        clickAttempts += 1;
        if (clickTranslateButton()) {
            AssistantTranslateController.markTranslationActive();
            showToast("번역 버튼을 눌렀습니다.", Toast.LENGTH_SHORT);
            return;
        }

        if (clickAttempts < MAX_CLICK_ATTEMPTS) {
            scheduleClickLoop(260L);
        } else {
            AssistantTranslateController.clearPendingRequest();
            showToast("번역 버튼을 찾지 못했습니다.", Toast.LENGTH_LONG);
        }
    }

    private void showToast(String message, int duration) {
        if (AssistantTranslateController.shouldShowFeedback()) {
            Toast.makeText(this, message, duration).show();
        }
    }

    private void stopInternalWork() {
        handler.removeCallbacksAndMessages(null);
        clickLoopScheduled = false;
        assistantSettleCheckScheduled = false;
        activeWindowCheckScheduled = false;
        waitingForAssistantSettle = false;
        activeRequestId = -1;
        clickAttempts = 0;
        assistantSettleChecks = 0;
        AssistantTranslateController.resetAll();
    }

    private boolean clickTranslateButton() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && clickCandidateInRoot(root)) {
                    return true;
                }
            }
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && clickCandidateInRoot(root);
    }

    private boolean clickCandidateInRoot(AccessibilityNodeInfo root) {
        boolean rootPackageAllowed = isAllowedPackage(root.getPackageName());
        AccessibilityNodeInfo candidate = findTranslateCandidate(root, rootPackageAllowed, 0);
        return candidate != null && clickNode(candidate);
    }

    private AccessibilityNodeInfo findTranslateCandidate(
            AccessibilityNodeInfo node,
            boolean packageAllowed,
            int depth
    ) {
        if (node == null || depth > 80 || !node.isVisibleToUser()) {
            return null;
        }

        boolean nodePackageAllowed = packageAllowed || isAllowedPackage(node.getPackageName());
        if (nodePackageAllowed && isTranslateLabel(node.getText())) {
            return node;
        }
        if (nodePackageAllowed && isTranslateLabel(node.getContentDescription())) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i += 1) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo candidate = findTranslateCandidate(child, nodePackageAllowed, depth + 1);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 8; i += 1) {
            if (current.isEnabled() && current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return tapCenter(node);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            return false;
        }

        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 80L))
                .build();
        return dispatchGesture(gesture, null, handler);
    }

    private boolean isTranslateLabel(CharSequence label) {
        if (label == null) {
            return false;
        }
        String normalized = label.toString()
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.equals("번역")
                || normalized.contains("화면 번역")
                || normalized.contains("페이지 번역")
                || normalized.equals("translate")
                || normalized.contains("translate this screen")
                || normalized.contains("translate screen")
                || normalized.contains("translate page");
    }

    private boolean isAllowedPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        String value = packageName.toString();
        return value.equals(GOOGLE_APP_PACKAGE)
                || value.equals(GOOGLE_ASSISTANT_PACKAGE)
                || value.equals(GOOGLE_LENS_PACKAGE)
                || value.startsWith(GOOGLE_APP_PACKAGE + ".")
                || value.startsWith(GOOGLE_ASSISTANT_PACKAGE + ".");
    }

    private Rect getDisplayBounds() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.getCurrentWindowMetrics().getBounds();
        }

        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        Display display = windowManager.getDefaultDisplay();
        display.getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }
}
