package com.limelight.selfframed.update;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.BuildConfig;
import com.limelight.R;

/** Settings entry "check for updates now"; its summary shows the installed version. */
public class SfUpdatePreference extends Preference {
    public SfUpdatePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public SfUpdatePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public SfUpdatePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setSummary(context.getString(R.string.sf_summary_update_check, BuildConfig.VERSION_NAME));
    }

    @Override
    protected void onClick() {
        if (getContext() instanceof Activity) {
            UpdateChecker.checkNow((Activity) getContext());
        }
    }
}
