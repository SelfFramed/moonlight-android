package com.limelight.selfframed.grading;

import android.content.Context;
import android.content.Intent;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * Settings entry that opens {@link GradingPresetsActivity}. An explicit intent in code works
 * for every application ID (debug builds carry a suffix), which an XML intent cannot.
 */
public class SfPresetsPreference extends Preference {
    public SfPresetsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SfPresetsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SfPresetsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        getContext().startActivity(new Intent(getContext(), GradingPresetsActivity.class));
    }
}
