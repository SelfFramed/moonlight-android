package com.limelight.selfframed.mic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.R;

/**
 * Settings entry "Mikrofon zum PC": shows how pairing works (no setup needed: Ctrl+Alt+Shift+M in the stream,
 * confirm on the PC) and, when PCs are paired, offers to forget them.
 */
public class SfMicPairingsPreference extends Preference {
    public SfMicPairingsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        int n = getContext() instanceof Activity ? MicControl.pairingCount((Activity) getContext()) : 0;
        setSummary(n == 0 ? getContext().getString(R.string.sf_summary_mic_none)
                : getContext().getResources().getQuantityString(R.plurals.sf_summary_mic_paired, n, n));
    }

    @Override
    protected void onClick() {
        if (!(getContext() instanceof Activity) || MicControl.pairingCount((Activity) getContext()) == 0) {
            return;
        }
        Activity activity = (Activity) getContext();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.sf_title_mic)
                .setMessage(R.string.sf_mic_forget_question)
                .setPositiveButton(R.string.sf_mic_forget, (d, w) -> {
                    MicControl.forgetAll(activity);
                    updateSummary();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
