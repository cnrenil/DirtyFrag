package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Disabled compatibility component for upgrades; boot work belongs to module plans. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Older component-enable overrides must never start the removed flow.
    }
}
