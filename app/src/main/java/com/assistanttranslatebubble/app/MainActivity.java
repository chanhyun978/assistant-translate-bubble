package com.assistanttranslatebubble.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 10;
    private static final int BG = 0xFFF6F7F9;
    private static final int SURFACE = 0xFFFFFFFF;
    private static final int TEXT = 0xFF151A23;
    private static final int MUTED = 0xFF697281;
    private static final int LINE = 0xFFE8EBF0;
    private static final int BLUE = 0xFF2364D2;
    private static final int GOOD_BG = 0xFFE9F7EF;
    private static final int GOOD_TEXT = 0xFF167247;
    private static final int NEED_BG = 0xFFFFF2D8;
    private static final int NEED_TEXT = 0xFF9A6400;
    private static final int OFF_BG = 0xFFEEF0F3;
    private static final int OFF_TEXT = 0xFF596170;

    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView notificationStatus;
    private TextView bubbleStatus;
    private TextView automationStatus;
    private TextView modeStatus;
    private Switch modeSwitch;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateStatuses();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(BG);
        applySystemBarInsets(scrollView);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                PermissionUtils.dp(this, 22),
                PermissionUtils.dp(this, 24),
                PermissionUtils.dp(this, 22),
                PermissionUtils.dp(this, 28)
        );
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("번역 버블", 30, TEXT, true);
        content.addView(title);

        TextView subtitle = text("탭하면 번역, 길게 누르면 설정", 15, MUTED, false);
        subtitle.setPadding(0, PermissionUtils.dp(this, 6), 0, PermissionUtils.dp(this, 24));
        content.addView(subtitle);

        content.addView(sectionLabel("상태"));
        LinearLayout statusPanel = panel();
        content.addView(statusPanel);

        overlayStatus = statusPill();
        accessibilityStatus = statusPill();
        notificationStatus = statusPill();
        bubbleStatus = statusPill();
        automationStatus = statusPill();

        statusPanel.addView(row("오버레이", "화면 위에 버블 표시", overlayStatus, smallButton("설정", view -> openOverlaySettings())));
        statusPanel.addView(separator());
        statusPanel.addView(row("접근성", "Assistant 번역 버튼 자동 선택", accessibilityStatus, smallButton("설정", view -> openAccessibilitySettings())));
        statusPanel.addView(separator());
        statusPanel.addView(row("알림", "버블 서비스 유지", notificationStatus, smallButton("요청", view -> requestNotificationPermission())));
        statusPanel.addView(separator());
        statusPanel.addView(row("버블", "화면 위 바로가기", bubbleStatus, smallButton("중지", view -> stopBubble())));
        statusPanel.addView(separator());
        statusPanel.addView(row("자동화", "버블이 켜졌을 때만 이벤트 처리", automationStatus, smallButton("종료", view -> shutdownAutomation())));

        addTopMargin(content, sectionLabel("동작"), 22);
        LinearLayout modePanel = panel();
        modePanel.setPadding(
                PermissionUtils.dp(this, 16),
                PermissionUtils.dp(this, 14),
                PermissionUtils.dp(this, 16),
                PermissionUtils.dp(this, 14)
        );
        content.addView(modePanel);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modePanel.addView(modeRow);

        LinearLayout modeTextGroup = new LinearLayout(this);
        modeTextGroup.setOrientation(LinearLayout.VERTICAL);
        modeTextGroup.setPadding(0, 0, PermissionUtils.dp(this, 12), 0);
        modeRow.addView(modeTextGroup, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        modeTextGroup.addView(text("홈 버튼 방식", 16, TEXT, true));
        modeStatus = text("", 13, MUTED, false);
        modeStatus.setPadding(0, PermissionUtils.dp(this, 4), 0, 0);
        modeTextGroup.addView(modeStatus);

        modeSwitch = new Switch(this);
        modeSwitch.setOnCheckedChangeListener(this::onModeChanged);
        modeRow.addView(modeSwitch);

        startButton = mainButton("버블 시작", true, view -> startBubble());
        addTopMargin(content, startButton, 24);

        Button testButton = mainButton("번역 테스트", false, view -> AssistantTranslateController.requestTranslation(this));
        addTopMargin(content, testButton, 10);

        updateStatuses();
        scrollView.post(scrollView::requestApplyInsets);
        return scrollView;
    }

    private void onModeChanged(CompoundButton buttonView, boolean isChecked) {
        buttonView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        PermissionUtils.setUseHomeLongPress(this, isChecked);
        updateStatuses();
    }

    private void updateStatuses() {
        boolean overlayAllowed = PermissionUtils.canDrawOverlays(this);
        boolean accessibilityEnabled = PermissionUtils.isAccessibilityEnabled(this);
        boolean notificationsAllowed = PermissionUtils.canPostNotifications(this);
        boolean useHomeLongPress = PermissionUtils.useHomeLongPress(this);
        boolean bubbleRunning = BubbleService.isRunning();
        boolean automationActive = PermissionUtils.isAutomationActive(this);

        setStatus(overlayStatus, overlayAllowed ? "허용됨" : "필요", overlayAllowed, false);
        setStatus(accessibilityStatus, accessibilityEnabled ? "켜짐" : "필요", accessibilityEnabled, false);
        setStatus(notificationStatus, notificationsAllowed ? "허용됨" : "필요", notificationsAllowed, false);
        setStatus(bubbleStatus, bubbleRunning ? "실행 중" : "꺼짐", bubbleRunning, true);
        setStatus(automationStatus, automationActive ? "켜짐" : "꺼짐", automationActive, true);

        modeSwitch.setOnCheckedChangeListener(null);
        modeSwitch.setChecked(useHomeLongPress);
        modeSwitch.setOnCheckedChangeListener(this::onModeChanged);
        modeStatus.setText(useHomeLongPress
                ? "갤럭시에서 안정적인 실행 방식"
                : "Assistant 실행 요청 방식");

        startButton.setEnabled(overlayAllowed);
        startButton.setAlpha(overlayAllowed ? 1f : 0.48f);
    }

    private void startBubble() {
        if (!PermissionUtils.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        if (!PermissionUtils.canPostNotifications(this)) {
            requestNotificationPermission();
            return;
        }

        Intent intent = new Intent(this, BubbleService.class);
        try {
            PermissionUtils.setAutomationActive(this, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            updateStatuses();
        } catch (RuntimeException error) {
            PermissionUtils.setAutomationActive(this, false);
            AssistantTranslateController.resetAll();
            AssistantTranslateAccessibilityService.stopConnectedWork();
            Toast.makeText(this, "버블을 시작하지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopBubble() {
        shutdownAutomation();
    }

    private void shutdownAutomation() {
        stopService(new Intent(this, BubbleService.class));
        PermissionUtils.setAutomationActive(this, false);
        AssistantTranslateController.resetAll();
        AssistantTranslateAccessibilityService.stopConnectedWork();
        updateStatuses();
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this, "목록에서 '번역 버블 자동 실행'을 켜 주세요.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else {
            updateStatuses();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(0, topInset, 0, bottomInset);
            return insets;
        });
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                PermissionUtils.dp(this, 14),
                PermissionUtils.dp(this, 6),
                PermissionUtils.dp(this, 14),
                PermissionUtils.dp(this, 6)
        );
        panel.setBackground(rounded(SURFACE, 8));
        panel.setElevation(PermissionUtils.dp(this, 1));
        return panel;
    }

    private LinearLayout row(String title, String detail, TextView status, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, PermissionUtils.dp(this, 11), 0, PermissionUtils.dp(this, 11));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(0, 0, PermissionUtils.dp(this, 12), 0);
        row.addView(labels, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        labels.addView(text(title, 15, TEXT, true));
        TextView detailView = text(detail, 12, MUTED, false);
        detailView.setPadding(0, PermissionUtils.dp(this, 2), 0, 0);
        labels.addView(detailView);

        row.addView(status);

        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                PermissionUtils.dp(this, 62),
                PermissionUtils.dp(this, 36)
        );
        actionParams.leftMargin = PermissionUtils.dp(this, 8);
        row.addView(action, actionParams);
        return row;
    }

    private View separator() {
        View view = new View(this);
        view.setBackgroundColor(LINE);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
        ));
        return view;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 12, MUTED, true);
        label.setPadding(0, 0, 0, PermissionUtils.dp(this, 8));
        return label;
    }

    private TextView statusPill() {
        TextView view = text("", 12, GOOD_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(PermissionUtils.dp(this, 58));
        view.setPadding(
                PermissionUtils.dp(this, 9),
                PermissionUtils.dp(this, 5),
                PermissionUtils.dp(this, 9),
                PermissionUtils.dp(this, 5)
        );
        return view;
    }

    private void setStatus(TextView view, String value, boolean active, boolean offState) {
        view.setText(value);
        int bg = active ? GOOD_BG : (offState ? OFF_BG : NEED_BG);
        int fg = active ? GOOD_TEXT : (offState ? OFF_TEXT : NEED_TEXT);
        view.setTextColor(fg);
        view.setBackground(rounded(bg, 8));
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button button = baseButton(label, listener);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setBackground(rounded(0xFFF1F3F6, 8));
        return button;
    }

    private Button mainButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = baseButton(label, listener);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setBackground(rounded(primary ? BLUE : SURFACE, 8));
        button.setElevation(primary ? PermissionUtils.dp(this, 2) : 0);
        button.setMinHeight(PermissionUtils.dp(this, 48));
        return button;
    }

    private Button baseButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(
                PermissionUtils.dp(this, 10),
                0,
                PermissionUtils.dp(this, 10),
                0
        );
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.onClick(view);
        });
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setIncludeFontPadding(true);
        if (bold) {
            textView.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(PermissionUtils.dp(this, radiusDp));
        return drawable;
    }

    private void addTopMargin(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = PermissionUtils.dp(this, topDp);
        parent.addView(child, params);
    }
}
