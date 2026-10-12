package com.limelight.selfframed.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.limelight.selfframed.grading.GradingEditorHost;
import com.limelight.selfframed.grading.GradingPanel;

/**
 * Placeholder until the real panel lands (see docs/selfframed/BEDIENUNG.md).
 */
public final class GradingPanelView extends TextView implements GradingPanel {
    private final GradingEditorHost host;

    public GradingPanelView(Context context, GradingEditorHost host) {
        super(context);
        this.host = host;
        setBackgroundColor(Color.WHITE);
        setTextColor(Color.BLACK);
        setTextSize(20);
        setPadding(24, 24, 24, 24);
        refresh();
    }

    @Override
    public View view() {
        return this;
    }

    @Override
    public void refresh() {
        setText("Preset " + host.editSlot() + ": " + host.bundle().slot(host.editSlot()).getName());
    }

    @Override
    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                host.closePanel();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onShown() {
    }

    @Override
    public void onHidden() {
    }
}
