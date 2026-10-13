package com.limelight.selfframed.mic;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.R;

/**
 * Settings entry for the microphone pairing code. Stores the code normalized, rejects invalid input with a short
 * message, shows the pairing state, and asks for the microphone permission right away, so the first
 * Ctrl+Alt+Shift+M in the stream works without a system dialog.
 */
public class SfMicCodePreference extends EditTextPreference {
    public SfMicCodePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setText(String text) {
        String trimmed = text != null ? text.trim() : "";
        String code = MicCode.normalize(trimmed);
        if (!trimmed.isEmpty() && code == null) {
            Toast.makeText(getContext(), R.string.sf_mic_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        super.setText(code != null ? code : "");
        updateSummary();
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        if (positiveResult && MicCode.normalize(getText()) != null && MicSender.supportsRuntimePermission()
                && getContext() instanceof Activity) {
            Activity activity = (Activity) getContext();
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MicControl.PERMISSION_REQUEST);
            }
        }
    }

    private void updateSummary() {
        String code = MicCode.normalize(getText());
        setSummary(code == null ? getContext().getString(R.string.sf_summary_mic_code_none)
                : getContext().getString(R.string.sf_summary_mic_code_set, code.substring(code.length() - 4)));
    }
}
