package com.limelight.selfframed.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.selfframed.grading.GradingEditorHost;
import com.limelight.selfframed.grading.GradingHotkeys;
import com.limelight.selfframed.grading.GradingPanel;
import com.limelight.selfframed.grading.GradingPreset;
import com.limelight.selfframed.grading.PresetBundle;
import com.limelight.selfframed.grading.ToneCurve;

/**
 * The in-stream picture panel: a column on the right edge of the stream that edits the
 * preset of {@link GradingEditorHost#editSlot()} live. Its heart is the tone-curve editor
 * ({@link CurveEditorView}); brightness, contrast, gamma, saturation and the grayscale mix
 * are compact rows below it.
 *
 * <h3>Layout (top to bottom)</h3>
 * Preset selector "◀ 3 · Name ▶" and the filter state; the tone curve (channel tabs, plot,
 * point readout, key hint); the value rows; the action rows; the stream hotkeys. The content
 * scrolls if the screen is too short; the plot is sized so the curve section fits on the
 * first screen.
 *
 * <h3>E-ink rules</h3>
 * White background, black text and lines, no gray fills, no transparency, no shadows, no
 * ripples or animations (plain stateless backgrounds, instant scrolling, no blinking cursor).
 * Selection and focus are shown by frames and inversion, never by colour alone. Text is
 * 18sp (body) and 22sp (headings). Views are only invalidated when something changed.
 *
 * <h3>Keyboard (the panel owns the keyboard while it is open)</h3>
 * The panel never takes Android focus (the stream view keeps it and forwards keys through
 * {@link #handleKeyEvent}); only the rename field takes focus while it is active. Rows are a
 * focus ring of their own, the focused row has a thick black frame.
 * <pre>
 * Anywhere
 *   Tab / Shift+Tab     next / previous row (wraps around)
 *   Up / Down           previous / next row (stops at the ends), except on the curve row
 *   1-9                 select that preset slot      0   neutral (original picture)
 *   F                   filter on/off (before/after); while neutral is active: back to the
 *                       edited preset, like Ctrl+Alt+Shift+F
 *   Escape / Back       close the panel (acts on key up)
 * Curve row (focused when the panel opens)
 *   Arrows              move the selected point by 0.005 (Shift: 0.04); endpoints only
 *                       move vertically, inner points stay between their neighbours
 *   , . PgUp PgDn       select previous / next point
 *   + = N Insert        insert a point halfway to the next point, on the curve
 *   Delete Backspace -  delete the selected inner point
 *   C (Shift+C)         next (previous) channel: master, R, G, B
 *   R                   reset the channel to the straight line
 * Preset row            Left / Right: previous / next slot (1-9, wraps)
 * Value rows            Left / Right: brightness, contrast, saturation 1 (Shift 10);
 *                       gamma 0.05 (Shift 0.25); weights 0.01 (Shift 0.1)
 * Grayscale mix         Space / Enter toggles; Left = off, Right = on
 * Action rows           Enter / Space (acts on key up, like a button)
 *   Reset preset asks for a second Enter before it replaces the slot with its default.
 * Rename field          typing goes to the field; Enter saves, Escape cancels (both act on
 *                       key up); clicking another row keeps the typed name
 * </pre>
 * Navigation and adjustments act on key down; only arrow keys auto-repeat. Modifier keys and
 * Ctrl+Alt+Shift combinations are never consumed (stream hotkeys).
 *
 * <h3>Mouse</h3>
 * Click a row to focus it; ◀ / ▶ step values and presets; click the filter line to toggle it;
 * click a channel tab to switch channels; the plot supports click-to-add, drag and
 * double-click-to-delete (see {@link CurveEditorView}). The panel swallows all touches inside
 * its bounds so nothing reaches the stream.
 *
 * <h3>Data flow</h3>
 * Every change mutates the preset of {@code host.editSlot()} in place (setters, or setCurve()
 * with a new, validated ToneCurve) and calls {@code host.presetChanged(slot)}, which applies
 * it live and autosaves. {@link #refresh()} re-reads everything from the host and keeps the
 * focused row, the channel and (if it still exists) the selected point.
 */
public final class GradingPanelView extends FrameLayout implements GradingPanel, CurveEditorView.Listener {
    // Rows in focus order
    private static final int ROW_PRESET = 0;
    private static final int ROW_CURVE = 1;
    private static final int ROW_BRIGHTNESS = 2;
    private static final int ROW_CONTRAST = 3;
    private static final int ROW_GAMMA = 4;
    private static final int ROW_SATURATION = 5;
    private static final int ROW_MONO = 6;
    private static final int ROW_MONO_R = 7;
    private static final int ROW_MONO_G = 8;
    private static final int ROW_MONO_B = 9;
    private static final int ROW_RESET_CHANNEL = 10;
    private static final int ROW_RESET_PRESET = 11;
    private static final int ROW_RENAME = 12;
    private static final int ROW_CLOSE = 13;
    private static final int ROW_COUNT = 14;

    private static final String[] CHANNEL_KEYS = GradingPreset.CURVE_KEYS;

    private static final float WIDTH_FRACTION = 0.45f;
    private static final int MAX_WIDTH_DP = 560;
    /** The plot may use at most this share of the panel height. */
    private static final float PLOT_HEIGHT_FRACTION = 0.55f;
    private static final float BODY_SP = 18;
    private static final float HEADING_SP = 22;
    private static final int FRAME_DP = 3;

    // Built from code points so no escape sequences end up in the source
    private static final String TEXT_STYLE = String.valueOf((char) 0xFE0E);
    private static final String ARROW_LEFT = (char) 0x25C0 + TEXT_STYLE;
    private static final String ARROW_RIGHT = (char) 0x25B6 + TEXT_STYLE;
    private static final String EDITED_MARK = " " + (char) 0x2022;

    private final GradingEditorHost host;
    private final float density;
    private final CurvePointEditor editor = new CurvePointEditor();

    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout[] rows = new LinearLayout[ROW_COUNT];
    private final GradientDrawable[] rowFrames = new GradientDrawable[ROW_COUNT];
    private final TextView[] values = new TextView[ROW_COUNT];

    private final TextView presetName;
    private final TextView filterState;
    private final TextView[] channelTabs = new TextView[CHANNEL_KEYS.length];
    private final GradientDrawable[] channelTabFrames = new GradientDrawable[CHANNEL_KEYS.length];
    private final String[] channelNames = new String[CHANNEL_KEYS.length];
    private final CurveEditorView curveView;
    private final TextView pointStatus;
    private final GradientDrawable monoBox;
    private final TextView resetPresetLabel;
    private final TextView renameLabel;
    private final EditText renameField;
    private final TextView renameHint;
    private final int[] weightPercents = new int[3];

    private int focusedRow = ROW_CURVE;
    private int framedRow = -1;
    private int channel;
    private int shownSlot = -1;
    private int activeTab = -1;

    /** Action rows fire on key up of the key that went down on them (like a button). */
    private int pendingKey = KeyEvent.KEYCODE_UNKNOWN;
    private int pendingRow = -1;
    private boolean pendingClose;
    private boolean resetPresetArmed;

    private boolean renaming;
    private int renameSlot;
    private View focusBeforeRename;
    private boolean renameConfirmDown;
    private boolean renameCancelDown;

    private final Runnable scrollToFocusedRow = new Runnable() {
        @Override
        public void run() {
            ensureFocusedRowVisible();
        }
    };

    public GradingPanelView(Context context, GradingEditorHost host) {
        super(context);
        this.host = host;
        this.density = context.getResources().getDisplayMetrics().density;

        // White panel with a solid black edge towards the stream
        LayerDrawable background = new LayerDrawable(new Drawable[] {
                new ColorDrawable(Color.BLACK), new ColorDrawable(Color.WHITE)});
        background.setLayerInset(1, dp(FRAME_DP), 0, 0, 0);
        setBackground(background);
        setPadding(dp(FRAME_DP), 0, 0, 0);
        // Swallow every touch inside the panel so nothing reaches the stream below
        setClickable(true);
        setFocusable(false);
        setSoundEffectsEnabled(false);

        scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setSmoothScrollingEnabled(false);
        addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Header: preset selector and filter state ----
        LinearLayout presetRow = newRow(ROW_PRESET, LinearLayout.HORIZONTAL);
        presetRow.addView(arrowButton(true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                focusRow(ROW_PRESET);
                stepSlot(-1);
            }
        }));
        presetName = text(HEADING_SP, true);
        presetName.setGravity(Gravity.CENTER);
        presetRow.addView(presetName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        presetRow.addView(arrowButton(false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                focusRow(ROW_PRESET);
                stepSlot(1);
            }
        }));

        filterState = text(BODY_SP, false);
        filterState.setGravity(Gravity.CENTER);
        filterState.setPadding(dp(13), dp(4), dp(13), dp(4));
        filterState.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFilter();
            }
        });
        content.addView(filterState, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Tone curve ----
        LinearLayout curveRow = newRow(ROW_CURVE, LinearLayout.VERTICAL);
        TextView curveTitle = text(HEADING_SP, true);
        curveTitle.setText(R.string.sf_ui_curve_title);
        curveRow.addView(curveTitle);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        channelNames[0] = context.getString(R.string.sf_ui_channel_master);
        channelNames[1] = context.getString(R.string.sf_ui_channel_r);
        channelNames[2] = context.getString(R.string.sf_ui_channel_g);
        channelNames[3] = context.getString(R.string.sf_ui_channel_b);
        for (int c = 0; c < CHANNEL_KEYS.length; c++) {
            final int ch = c;
            TextView tab = text(BODY_SP, true);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setPadding(dp(4), dp(6), dp(4), dp(6));
            GradientDrawable frame = new GradientDrawable();
            frame.setColor(Color.WHITE);
            frame.setStroke(dp(2), Color.BLACK);
            tab.setBackground(frame);
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    focusRow(ROW_CURVE);
                    selectChannel(ch);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    c == 0 ? 1.4f : 1f);
            if (c > 0) {
                lp.leftMargin = dp(6);
            }
            tabs.addView(tab, lp);
            channelTabs[c] = tab;
            channelTabFrames[c] = frame;
        }
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.topMargin = dp(6);
        curveRow.addView(tabs, tabsLp);

        String[] shortLabels = {
                context.getString(R.string.sf_ui_channel_short_master),
                context.getString(R.string.sf_ui_channel_short_r),
                context.getString(R.string.sf_ui_channel_short_g),
                context.getString(R.string.sf_ui_channel_short_b),
        };
        curveView = new CurveEditorView(context, editor, shortLabels);
        curveView.setListener(this);
        LinearLayout.LayoutParams plotLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plotLp.topMargin = dp(8);
        curveRow.addView(curveView, plotLp);

        pointStatus = text(BODY_SP, false);
        curveRow.addView(pointStatus);
        TextView curveHint = text(BODY_SP, false);
        curveHint.setText(R.string.sf_ui_curve_hint);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        curveRow.addView(curveHint, hintLp);

        addDivider();

        // ---- Value rows ----
        addValueRow(ROW_BRIGHTNESS, R.string.sf_ui_brightness, false);
        addValueRow(ROW_CONTRAST, R.string.sf_ui_contrast, false);
        addValueRow(ROW_GAMMA, R.string.sf_ui_gamma, false);
        addValueRow(ROW_SATURATION, R.string.sf_ui_saturation, false);

        LinearLayout monoRow = newRow(ROW_MONO, LinearLayout.HORIZONTAL);
        TextView monoLabel = text(BODY_SP, false);
        monoLabel.setText(R.string.sf_ui_mono);
        singleLineLabel(monoLabel);
        monoRow.addView(monoLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView monoValue = text(BODY_SP, true);
        monoValue.setGravity(Gravity.CENTER);
        monoValue.setMinWidth(dp(72));
        monoValue.setPadding(dp(10), dp(4), dp(10), dp(4));
        monoBox = new GradientDrawable();
        monoBox.setColor(Color.WHITE);
        monoBox.setStroke(dp(2), Color.BLACK);
        monoValue.setBackground(monoBox);
        monoRow.addView(monoValue);
        values[ROW_MONO] = monoValue;

        addValueRow(ROW_MONO_R, R.string.sf_ui_weight_r, true);
        addValueRow(ROW_MONO_G, R.string.sf_ui_weight_g, true);
        addValueRow(ROW_MONO_B, R.string.sf_ui_weight_b, true);

        addDivider();

        // ---- Actions ----
        addActionRow(ROW_RESET_CHANNEL, R.string.sf_ui_reset_channel);
        resetPresetLabel = addActionRow(ROW_RESET_PRESET, R.string.sf_ui_reset_preset);

        LinearLayout renameRow = newRow(ROW_RENAME, LinearLayout.VERTICAL);
        renameLabel = text(BODY_SP, true);
        renameLabel.setText(R.string.sf_ui_rename);
        renameRow.addView(renameLabel);
        renameField = new EditText(context);
        configureRenameField(renameField);
        renameRow.addView(renameField, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        renameHint = text(BODY_SP, false);
        renameHint.setText(R.string.sf_ui_rename_hint);
        renameHint.setVisibility(View.GONE);
        renameRow.addView(renameHint);

        addActionRow(ROW_CLOSE, R.string.sf_ui_close);

        addDivider();

        // ---- Footer ----
        TextView footerPanel = text(BODY_SP, false);
        footerPanel.setText(R.string.sf_ui_footer_panel);
        content.addView(footerPanel);
        TextView footerHotkeys = text(BODY_SP, false);
        footerHotkeys.setText(R.string.sf_ui_footer_hotkeys);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(8);
        content.addView(footerHotkeys, footerLp);

        refresh();
    }

    // ------------------------------------------------------------------------------------
    // GradingPanel
    // ------------------------------------------------------------------------------------

    @Override
    public View view() {
        return this;
    }

    @Override
    public void refresh() {
        int slot = host.editSlot();
        PresetBundle bundle = host.bundle();
        GradingPreset p = bundle.slot(slot);
        if (renaming && slot != renameSlot) {
            // The preset under the rename field went away (hotkey): drop the edit
            finishRename(false);
            return;
        }
        if (slot != shownSlot) {
            shownSlot = slot;
            resetPresetArmed = false;
        }

        // Header
        setTextIfChanged(presetName, getContext().getString(R.string.sf_ui_preset_name, slot, p.getName()));
        String filter;
        if (bundle.getActiveSlot() == PresetBundle.NEUTRAL_SLOT) {
            filter = getContext().getString(R.string.sf_ui_filter_neutral, slot);
        } else {
            filter = getContext().getString(host.isFilterEnabled() ? R.string.sf_ui_filter_on : R.string.sf_ui_filter_off);
        }
        setTextIfChanged(filterState, filter);

        // Tone curve
        editor.load(p.getCurve(CHANNEL_KEYS[channel]));
        curveView.setState(p.getCurve(GradingPreset.CURVE_MASTER), p.getCurve(GradingPreset.CURVE_R),
                p.getCurve(GradingPreset.CURVE_G), p.getCurve(GradingPreset.CURVE_B), channel);
        for (int c = 0; c < CHANNEL_KEYS.length; c++) {
            boolean edited = !p.getCurve(CHANNEL_KEYS[c]).isIdentity();
            setTextIfChanged(channelTabs[c], edited ? channelNames[c] + EDITED_MARK : channelNames[c]);
        }
        if (activeTab != channel) {
            for (int c = 0; c < CHANNEL_KEYS.length; c++) {
                boolean active = c == channel;
                // Active channel inverted: black tab, white text
                channelTabFrames[c].setColor(active ? Color.BLACK : Color.WHITE);
                channelTabs[c].setTextColor(active ? Color.WHITE : Color.BLACK);
            }
            activeTab = channel;
        }
        int sel = editor.selected();
        setTextIfChanged(pointStatus, getContext().getString(R.string.sf_ui_point_status, sel + 1, editor.size(),
                PanelValues.toByte(editor.x(sel)), PanelValues.toByte(editor.y(sel))));

        // Values
        setTextIfChanged(values[ROW_BRIGHTNESS], getContext().getString(R.string.sf_ui_value_percent, p.getBrightness()));
        setTextIfChanged(values[ROW_CONTRAST], getContext().getString(R.string.sf_ui_value_percent, p.getContrast()));
        setTextIfChanged(values[ROW_GAMMA], getContext().getString(R.string.sf_ui_value_gamma, p.getGamma()));
        setTextIfChanged(values[ROW_SATURATION], getContext().getString(R.string.sf_ui_value_percent, p.getSaturation()));

        boolean mono = p.isMono();
        setTextIfChanged(values[ROW_MONO], getContext().getString(mono ? R.string.sf_ui_on : R.string.sf_ui_off));
        int monoText = mono ? Color.WHITE : Color.BLACK;
        if (values[ROW_MONO].getCurrentTextColor() != monoText) {
            // "On" inverted, so the state does not hinge on reading the word
            monoBox.setColor(mono ? Color.BLACK : Color.WHITE);
            values[ROW_MONO].setTextColor(monoText);
        }
        int weightsVisibility = mono ? View.VISIBLE : View.GONE;
        for (int row = ROW_MONO_R; row <= ROW_MONO_B; row++) {
            if (rows[row].getVisibility() != weightsVisibility) {
                rows[row].setVisibility(weightsVisibility);
            }
        }
        if (mono) {
            boolean usable = PanelValues.weightPercents(p.getMonoR(), p.getMonoG(), p.getMonoB(), weightPercents);
            setWeightText(ROW_MONO_R, p.getMonoR(), usable, 0);
            setWeightText(ROW_MONO_G, p.getMonoG(), usable, 1);
            setWeightText(ROW_MONO_B, p.getMonoB(), usable, 2);
        }

        // Actions
        setTextIfChanged(resetPresetLabel, getContext().getString(
                resetPresetArmed ? R.string.sf_ui_reset_preset_confirm : R.string.sf_ui_reset_preset));

        if (!isRowVisible(focusedRow)) {
            focusedRow = ROW_MONO;
            scrollToFocused();
        }
        updateFrames();
    }

    @Override
    public void onShown() {
        focusedRow = ROW_CURVE;
        clearPendingKey();
        pendingClose = false;
        resetPresetArmed = false;
        refresh();
        scroll.scrollTo(0, 0);
    }

    @Override
    public void onHidden() {
        finishRename(false);
        curveView.cancelDrag();
        clearPendingKey();
        pendingClose = false;
        resetPresetArmed = false;
        removeCallbacks(scrollToFocusedRow);
    }

    // ------------------------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------------------------

    @Override
    public boolean handleKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (KeyEvent.isModifierKey(code)) {
            return false;
        }
        if (event.isCtrlPressed() && event.isAltPressed() && event.isShiftPressed()) {
            return false;
        }
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return true;
        }
        boolean down = action == KeyEvent.ACTION_DOWN;

        if (renaming) {
            // Normally the field consumes its keys itself; this covers a field that lost focus
            if (!renameKeys.onKey(renameField, code, event) && down && !renameField.isFocused()) {
                renameField.requestFocus();
            }
            return true;
        }

        if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK) {
            // Close on key up, so the down/up pair is consumed while the panel is open
            if (down) {
                if (event.getRepeatCount() == 0) {
                    pendingClose = true;
                }
            } else if (pendingClose) {
                pendingClose = false;
                host.closePanel();
            }
            return true;
        }

        if (!down) {
            if (code == pendingKey) {
                int row = pendingRow;
                clearPendingKey();
                if (row == focusedRow) {
                    activate(row);
                }
            }
            return true;
        }

        if (event.getRepeatCount() > 0 && !isArrow(code)) {
            return true;
        }
        clearPendingKey();
        if (resetPresetArmed && !(focusedRow == ROW_RESET_PRESET && isActivateKey(code))) {
            resetPresetArmed = false;
            refresh();
        }

        boolean shift = event.isShiftPressed();
        if (code == KeyEvent.KEYCODE_TAB) {
            moveFocus(shift ? -1 : 1, true);
            return true;
        }
        // Row keys first: on German layouts '=' is Shift+0 and must not select neutral
        if (handleRowKey(event, code, shift)) {
            return true;
        }

        int slot = GradingHotkeys.slotFor(code);
        if (slot >= 0 && event.hasNoModifiers()) {
            host.selectSlot(slot);
            refresh();
            return true;
        }
        if ((code == KeyEvent.KEYCODE_F || Character.toLowerCase(event.getUnicodeChar()) == 'f')
                && !event.isCtrlPressed() && !event.isAltPressed() && !event.isMetaPressed()) {
            toggleFilter();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            moveFocus(-1, false);
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            moveFocus(1, false);
        }
        return true;
    }

    private boolean handleRowKey(KeyEvent event, int code, boolean shift) {
        switch (focusedRow) {
            case ROW_CURVE:
                return handleCurveKey(event, code, shift);

            case ROW_PRESET:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    stepSlot(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                }
                return false;

            case ROW_BRIGHTNESS:
            case ROW_CONTRAST:
            case ROW_GAMMA:
            case ROW_SATURATION:
            case ROW_MONO_R:
            case ROW_MONO_G:
            case ROW_MONO_B:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    adjust(focusedRow, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, shift);
                    return true;
                }
                return false;

            case ROW_MONO:
                if (isActivateKey(code)) {
                    setMono(!preset().isMono());
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    setMono(code == KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                }
                return false;

            default:
                // Action rows: remember the key, fire on its key up
                if (isActivateKey(code)) {
                    pendingKey = code;
                    pendingRow = focusedRow;
                    return true;
                }
                return false;
        }
    }

    private boolean handleCurveKey(KeyEvent event, int code, boolean shift) {
        double step = shift ? CurvePointEditor.STEP_COARSE : CurvePointEditor.STEP_FINE;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                movePoint(-step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                movePoint(step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                movePoint(0, step);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                movePoint(0, -step);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                selectPoint(-1);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                selectPoint(1);
                return true;
            case KeyEvent.KEYCODE_INSERT:
                insertPoint();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_DEL:
                deletePoint();
                return true;
            default:
                break;
        }
        if (event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) {
            return false;
        }
        // Characters first, so the keys work on any layout (e.g. '+' and '-' on German keyboards)
        int ch = event.getUnicodeChar();
        switch (ch) {
            case ',':
                selectPoint(-1);
                return true;
            case '.':
                selectPoint(1);
                return true;
            case '+':
            case '=':
            case 'n':
            case 'N':
                insertPoint();
                return true;
            case '-':
                deletePoint();
                return true;
            case 'c':
            case 'C':
                selectChannel((channel + (shift ? CHANNEL_KEYS.length - 1 : 1)) % CHANNEL_KEYS.length);
                return true;
            case 'r':
            case 'R':
                resetChannel();
                return true;
            default:
                break;
        }
        if (ch != 0) {
            return false;
        }
        // Keys without a character in the current layout
        switch (code) {
            case KeyEvent.KEYCODE_COMMA:
                selectPoint(-1);
                return true;
            case KeyEvent.KEYCODE_PERIOD:
                selectPoint(1);
                return true;
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            case KeyEvent.KEYCODE_N:
                insertPoint();
                return true;
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                deletePoint();
                return true;
            case KeyEvent.KEYCODE_C:
                selectChannel((channel + (shift ? CHANNEL_KEYS.length - 1 : 1)) % CHANNEL_KEYS.length);
                return true;
            case KeyEvent.KEYCODE_R:
                resetChannel();
                return true;
            default:
                return false;
        }
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean isActivateKey(int code) {
        return code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE;
    }

    private void clearPendingKey() {
        pendingKey = KeyEvent.KEYCODE_UNKNOWN;
        pendingRow = -1;
    }

    // ------------------------------------------------------------------------------------
    // Focus ring
    // ------------------------------------------------------------------------------------

    private boolean isRowVisible(int row) {
        return rows[row] != null && rows[row].getVisibility() == View.VISIBLE;
    }

    private void moveFocus(int direction, boolean wrap) {
        int row = focusedRow;
        for (int i = 0; i < ROW_COUNT; i++) {
            int next = row + direction;
            if (next < 0 || next >= ROW_COUNT) {
                if (!wrap) {
                    return;
                }
                next = (next + ROW_COUNT) % ROW_COUNT;
            }
            row = next;
            if (isRowVisible(row)) {
                focusRow(row);
                return;
            }
        }
    }

    private void focusRow(int row) {
        if (renaming && row != ROW_RENAME) {
            // Clicking elsewhere while renaming keeps the typed name (commit on blur)
            finishRename(true);
        }
        if (!isRowVisible(row) || row == focusedRow) {
            return;
        }
        focusedRow = row;
        clearPendingKey();
        if (resetPresetArmed) {
            resetPresetArmed = false;
            refresh();
        }
        updateFrames();
        scrollToFocused();
    }

    private void updateFrames() {
        if (framedRow == focusedRow) {
            return;
        }
        if (framedRow >= 0) {
            rowFrames[framedRow].setStroke(dp(FRAME_DP), Color.WHITE);
        }
        rowFrames[focusedRow].setStroke(dp(FRAME_DP), Color.BLACK);
        framedRow = focusedRow;
    }

    private void scrollToFocused() {
        removeCallbacks(scrollToFocusedRow);
        post(scrollToFocusedRow);
    }

    /** Instant (non-animated) scroll that brings the focused row into view. */
    private void ensureFocusedRowVisible() {
        View row = rows[focusedRow];
        int viewport = scroll.getHeight();
        if (row.getHeight() == 0 || viewport == 0) {
            return;
        }
        if (focusedRow == ROW_PRESET) {
            scroll.scrollTo(0, 0);
            return;
        }
        int margin = dp(8);
        int top = row.getTop() - margin;
        int bottom = row.getBottom() + margin;
        int scrollY = scroll.getScrollY();
        if (top < scrollY || bottom - top > viewport) {
            // Back to the very top if the row fits there, so the preset header stays in view
            scroll.scrollTo(0, bottom <= viewport ? 0 : Math.max(0, top));
        } else if (bottom > scrollY + viewport) {
            scroll.scrollTo(0, bottom - viewport);
        }
    }

    private void onRowClicked(int row) {
        focusRow(row);
        switch (row) {
            case ROW_MONO:
                setMono(!preset().isMono());
                break;
            case ROW_RESET_CHANNEL:
            case ROW_RESET_PRESET:
            case ROW_RENAME:
            case ROW_CLOSE:
                activate(row);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------

    private GradingPreset preset() {
        return host.bundle().slot(host.editSlot());
    }

    private void activate(int row) {
        switch (row) {
            case ROW_RESET_CHANNEL:
                resetChannel();
                break;
            case ROW_RESET_PRESET:
                if (!resetPresetArmed) {
                    // Destructive and without undo: ask for a second Enter / click
                    resetPresetArmed = true;
                    refresh();
                } else {
                    resetPresetArmed = false;
                    resetPreset();
                }
                break;
            case ROW_RENAME:
                startRename();
                break;
            case ROW_CLOSE:
                host.closePanel();
                break;
            default:
                break;
        }
    }

    private void stepSlot(int direction) {
        host.selectSlot(PanelValues.cyclePresetSlot(host.editSlot(), direction));
        refresh();
    }

    private void toggleFilter() {
        if (host.bundle().getActiveSlot() == PresetBundle.NEUTRAL_SLOT) {
            // Same as the Ctrl+Alt+Shift+F hotkey: from neutral back to the edited preset
            host.selectSlot(host.editSlot());
        } else {
            host.setFilterEnabled(!host.isFilterEnabled());
        }
        refresh();
    }

    private void adjust(int row, int direction, boolean coarse) {
        int slot = host.editSlot();
        GradingPreset p = host.bundle().slot(slot);
        boolean changed;
        switch (row) {
            case ROW_BRIGHTNESS: {
                int old = p.getBrightness();
                p.setBrightness(old + direction * (coarse ? PanelValues.PERCENT_STEP_COARSE : PanelValues.PERCENT_STEP));
                changed = p.getBrightness() != old;
                break;
            }
            case ROW_CONTRAST: {
                int old = p.getContrast();
                p.setContrast(old + direction * (coarse ? PanelValues.PERCENT_STEP_COARSE : PanelValues.PERCENT_STEP));
                changed = p.getContrast() != old;
                break;
            }
            case ROW_SATURATION: {
                int old = p.getSaturation();
                p.setSaturation(old + direction * (coarse ? PanelValues.PERCENT_STEP_COARSE : PanelValues.PERCENT_STEP));
                changed = p.getSaturation() != old;
                break;
            }
            case ROW_GAMMA: {
                double old = p.getGamma();
                p.setGamma(old + direction * (coarse ? PanelValues.GAMMA_STEP_COARSE : PanelValues.GAMMA_STEP));
                changed = p.getGamma() != old;
                break;
            }
            case ROW_MONO_R:
            case ROW_MONO_G:
            case ROW_MONO_B: {
                double r = p.getMonoR(), g = p.getMonoG(), b = p.getMonoB();
                double delta = direction * (coarse ? PanelValues.WEIGHT_STEP_COARSE : PanelValues.WEIGHT_STEP);
                if (row == ROW_MONO_R) {
                    r = PanelValues.stepWeight(r, delta);
                } else if (row == ROW_MONO_G) {
                    g = PanelValues.stepWeight(g, delta);
                } else {
                    b = PanelValues.stepWeight(b, delta);
                }
                changed = r != p.getMonoR() || g != p.getMonoG() || b != p.getMonoB();
                p.setMonoWeights(r, g, b);
                break;
            }
            default:
                return;
        }
        if (changed) {
            host.presetChanged(slot);
        }
        refresh();
    }

    private void setMono(boolean on) {
        int slot = host.editSlot();
        GradingPreset p = host.bundle().slot(slot);
        if (p.isMono() != on) {
            p.setMono(on);
            host.presetChanged(slot);
        }
        refresh();
    }

    private void selectChannel(int c) {
        if (c == channel) {
            return;
        }
        channel = c;
        refresh();
    }

    private void movePoint(double dx, double dy) {
        if (editor.moveSelectedBy(dx, dy)) {
            commitCurve();
        }
    }

    private void selectPoint(int delta) {
        if (editor.selectRelative(delta)) {
            refresh();
        }
    }

    private void insertPoint() {
        if (editor.insertNextToSelected() >= 0) {
            commitCurve();
        }
    }

    private void deletePoint() {
        if (editor.deleteSelected()) {
            commitCurve();
        }
    }

    private void resetChannel() {
        if (editor.reset()) {
            commitCurve();
        } else {
            refresh();
        }
    }

    /** Writes the editor's points into the edited preset and applies them live. */
    private void commitCurve() {
        ToneCurve curve = editor.toCurve();
        if (curve != null) {
            int slot = host.editSlot();
            host.bundle().slot(slot).setCurve(CHANNEL_KEYS[channel], curve);
            host.presetChanged(slot);
        }
        // Re-reads the preset; on the (unreachable) null path this discards the bad edit
        refresh();
    }

    private void resetPreset() {
        int slot = host.editSlot();
        host.bundle().setSlot(slot, host.factoryPreset(slot));
        host.presetChanged(slot);
        refresh();
    }

    // ------------------------------------------------------------------------------------
    // CurveEditorView.Listener
    // ------------------------------------------------------------------------------------

    @Override
    public void onCurveTouched() {
        focusRow(ROW_CURVE);
        refresh();
    }

    @Override
    public void onCurveEdited() {
        commitCurve();
    }

    // ------------------------------------------------------------------------------------
    // Rename
    // ------------------------------------------------------------------------------------

    private final View.OnKeyListener renameKeys = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (!renaming) {
                return false;
            }
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean up = event.getAction() == KeyEvent.ACTION_UP;
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    // Act on key up of a key that went down here, so no stray key up leaks out
                    if (down && event.getRepeatCount() == 0) {
                        renameConfirmDown = true;
                    } else if (up && renameConfirmDown) {
                        renameConfirmDown = false;
                        finishRename(true);
                    }
                    return true;
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_BACK:
                    if (down && event.getRepeatCount() == 0) {
                        renameCancelDown = true;
                    } else if (up && renameCancelDown) {
                        renameCancelDown = false;
                        finishRename(false);
                    }
                    return true;
                case KeyEvent.KEYCODE_TAB:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    // Keep focus in the field and the panel from scrolling
                    return true;
                default:
                    return false;
            }
        }
    };

    private void configureRenameField(EditText field) {
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(GradingPreset.NAME_MAX_LENGTH)});
        field.setTextColor(Color.BLACK);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP + 2);
        field.setTypeface(Typeface.DEFAULT_BOLD);
        // No blinking cursor (e-ink) and no tinted selection fill; typing continues at the end
        field.setCursorVisible(false);
        field.setHighlightColor(Color.TRANSPARENT);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setStroke(dp(2), Color.BLACK);
        field.setBackground(box);
        field.setPadding(dp(8), dp(6), dp(8), dp(6));
        field.setShowSoftInputOnFocus(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            field.setDefaultFocusHighlightEnabled(false);
        }
        field.setFocusable(false);
        field.setVisibility(View.GONE);
        field.setOnKeyListener(renameKeys);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    finishRename(true);
                    return true;
                }
                return false;
            }
        });
    }

    private void startRename() {
        if (renaming) {
            return;
        }
        renaming = true;
        renameSlot = host.editSlot();
        renameConfirmDown = false;
        renameCancelDown = false;
        View root = getRootView();
        focusBeforeRename = root != null ? root.findFocus() : null;

        renameField.setText(host.bundle().slot(renameSlot).getName());
        renameField.setSelection(renameField.length());
        renameLabel.setVisibility(View.GONE);
        renameField.setVisibility(View.VISIBLE);
        renameHint.setVisibility(View.VISIBLE);
        renameField.setFocusable(true);
        renameField.setFocusableInTouchMode(true);
        renameField.requestFocus();
        focusRow(ROW_RENAME);
    }

    private void finishRename(boolean save) {
        if (!renaming) {
            return;
        }
        renaming = false;
        renameConfirmDown = false;
        renameCancelDown = false;
        if (save) {
            String name = renameField.getText().toString().trim();
            int slot = host.editSlot();
            if (slot == renameSlot && !name.isEmpty()) {
                GradingPreset p = host.bundle().slot(slot);
                if (!name.equals(p.getName())) {
                    p.setName(name);
                    host.presetChanged(slot);
                }
            }
        }

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(renameField.getWindowToken(), 0);
        }
        // Hand the keyboard back to whoever had it (the stream view, which forwards keys to us)
        View previous = focusBeforeRename;
        focusBeforeRename = null;
        if (previous != null && previous != renameField && previous.isAttachedToWindow()) {
            previous.requestFocus();
        }
        renameField.setFocusable(false);
        renameField.setVisibility(View.GONE);
        renameHint.setVisibility(View.GONE);
        renameLabel.setVisibility(View.VISIBLE);
        refresh();
    }

    // ------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = dp(MAX_WIDTH_DP);
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? maxWidth
                : Math.min(Math.round(MeasureSpec.getSize(widthMeasureSpec) * WIDTH_FRACTION), maxWidth);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height > 0 && curveView.setMaxPlotSide(Math.round(height * PLOT_HEIGHT_FRACTION))) {
            // Re-measure the path down to the plot even if their own specs did not change
            scroll.forceLayout();
            content.forceLayout();
            ((View) curveView.getParent()).forceLayout();
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }

    // ------------------------------------------------------------------------------------
    // View factories
    // ------------------------------------------------------------------------------------

    private LinearLayout newRow(final int row, int orientation) {
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(orientation);
        if (orientation == LinearLayout.HORIZONTAL) {
            layout.setGravity(Gravity.CENTER_VERTICAL);
        }
        // Focus frame: same stroke width in white when unfocused, so nothing shifts
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.WHITE);
        frame.setStroke(dp(FRAME_DP), Color.WHITE);
        layout.setBackground(frame);
        layout.setPadding(dp(10), dp(6), dp(10), dp(6));
        layout.setMinimumHeight(dp(44));
        layout.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onRowClicked(row);
            }
        });
        layout.setSoundEffectsEnabled(false);
        layout.setFocusable(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        content.addView(layout, lp);
        rows[row] = layout;
        rowFrames[row] = frame;
        return layout;
    }

    private void addValueRow(final int row, int labelRes, boolean indented) {
        LinearLayout layout = newRow(row, LinearLayout.HORIZONTAL);
        if (indented) {
            layout.setPadding(dp(28), dp(6), dp(10), dp(6));
        }
        TextView label = text(BODY_SP, false);
        label.setText(labelRes);
        singleLineLabel(label);
        layout.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layout.addView(arrowButton(true, new OnClickListener() {
            @Override
            public void onClick(View v) {
                focusRow(row);
                adjust(row, -1, false);
            }
        }));
        TextView value = text(BODY_SP, true);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setMinWidth(dp(row >= ROW_MONO_R && row <= ROW_MONO_B ? 116 : 72));
        layout.addView(value);
        layout.addView(arrowButton(false, new OnClickListener() {
            @Override
            public void onClick(View v) {
                focusRow(row);
                adjust(row, 1, false);
            }
        }));
        values[row] = value;
    }

    private TextView addActionRow(int row, int labelRes) {
        LinearLayout layout = newRow(row, LinearLayout.HORIZONTAL);
        TextView label = text(BODY_SP, true);
        label.setText(labelRes);
        layout.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private void addDivider() {
        View line = new View(getContext());
        line.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(6);
        content.addView(line, lp);
    }

    private TextView arrowButton(boolean left, OnClickListener listener) {
        TextView t = text(HEADING_SP, true);
        t.setText(left ? ARROW_LEFT : ARROW_RIGHT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setOnClickListener(listener);
        return t;
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextColor(Color.BLACK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setFocusable(false);
        t.setSoundEffectsEnabled(false);
        return t;
    }

    /** Narrow panels (phone in portrait) shorten labels instead of wrapping them letter by letter. */
    private static void singleLineLabel(TextView label) {
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
    }

    private void setWeightText(int row, double weight, boolean usable, int index) {
        String s = usable
                ? getContext().getString(R.string.sf_ui_value_weight, weight, weightPercents[index])
                : getContext().getString(R.string.sf_ui_value_weight_unused, weight);
        setTextIfChanged(values[row], s);
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
