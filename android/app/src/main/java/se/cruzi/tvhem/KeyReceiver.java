package se.cruzi.tvhem;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 'hemvakt' hands over the phone's adb key once, so Hem can start the guard itself after a restart. Only the shell may send (DUMP). */
public class KeyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String k = i.getStringExtra("k");
        try {
            if (k == null) throw new Exception("ingen nyckel");
            AdbSelf.saveKey(c, k);
            setResultData("nyckel sparad");
            Log.d(AdbSelf.TAG, "självstart: nyckel sparad");
        } catch (Exception e) { setResultData("fel: " + e.getMessage()); }
    }
}
