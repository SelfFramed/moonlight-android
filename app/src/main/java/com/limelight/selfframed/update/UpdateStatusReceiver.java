package com.limelight.selfframed.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * Status of a self-update install session (see {@link UpdateInstaller}). Android first reports
 * PENDING_USER_ACTION with its confirmation screen, which we show; on success the app is
 * replaced and restarted by the system.
 */
public class UpdateStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!UpdateInstaller.ACTION_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = confirmationIntent(intent);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                LimeLog.info("SelfFramed update installed");
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                // The user said no in Android's dialog
                break;
            default:
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                LimeLog.warning("SelfFramed update failed: " + status + " " + message);
                Toast.makeText(context, context.getString(R.string.sf_update_failed,
                        message != null ? message : String.valueOf(status)), Toast.LENGTH_LONG).show();
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmationIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
