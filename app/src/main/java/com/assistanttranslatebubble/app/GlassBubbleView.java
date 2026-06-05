package com.assistanttranslatebubble.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.FrameLayout;

final class GlassBubbleView extends FrameLayout {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diamondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path diamond = new Path();

    GlassBubbleView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        fillPaint.setColor(Color.argb(128, 112, 118, 126));
        diamondPaint.setColor(Color.argb(218, 246, 247, 249));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, fillPaint);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float diamondRadius = PermissionUtils.dp(getContext(), 5.5f);
        diamond.reset();
        diamond.moveTo(centerX, centerY - diamondRadius);
        diamond.lineTo(centerX + diamondRadius, centerY);
        diamond.lineTo(centerX, centerY + diamondRadius);
        diamond.lineTo(centerX - diamondRadius, centerY);
        diamond.close();
        canvas.drawPath(diamond, diamondPaint);
    }
}
