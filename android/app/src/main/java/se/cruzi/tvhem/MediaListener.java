package se.cruzi.tvhem;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Remembers what was last played in each app (title, channel, picture) for the "Fortsätt" row. */
public class MediaListener extends NotificationListenerService {
    private MediaSessionManager msm;
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed =
        new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override public void onActiveSessionsChanged(List<MediaController> list) { watch(list); }
        };

    @Override
    public void onListenerConnected() {
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(changed, me);
            watch(msm.getActiveSessions(me));
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(changed); } catch (Exception ignored) {}
        watch(null);
    }

    private void watch(List<MediaController> list) {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try { e.getKey().unregisterCallback(e.getValue()); } catch (Exception ignored) {}
        }
        callbacks.clear();
        if (list == null) return;
        for (final MediaController c : list) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { save(c.getPackageName(), m); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { save(c.getPackageName(), c.getMetadata()); }
            };
            try {
                c.registerCallback(cb);
                callbacks.put(c, cb);
                save(c.getPackageName(), c.getMetadata());
            } catch (Exception ignored) {}
        }
    }

    private static String str(MediaMetadata m, String key) {
        CharSequence c = m.getText(key);
        return c == null ? null : c.toString();
    }

    private void save(String pkg, MediaMetadata m) {
        if (pkg == null || m == null) return;
        String title = str(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) title = str(m, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) return;
        String sub = str(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (sub == null) sub = str(m, MediaMetadata.METADATA_KEY_ARTIST);
        if (sub == null) sub = str(m, MediaMetadata.METADATA_KEY_ALBUM);
        String artUri = str(m, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (artUri == null) artUri = str(m, MediaMetadata.METADATA_KEY_ART_URI);
        if (artUri == null) artUri = str(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        try {
            SharedPreferences p = getSharedPreferences("media", MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("all", "{}"));
            JSONObject prev = all.optJSONObject(pkg);
            boolean same = prev != null && title.equals(prev.optString("title"));
            JSONObject o = new JSONObject();
            o.put("title", title);
            if (sub != null) o.put("sub", sub);
            o.put("t", System.currentTimeMillis());
            if (artUri != null && artUri.startsWith("http")) o.put("artUri", artUri);
            if (b != null) o.put("art", toB64(b));
            else if (same && prev.has("art")) o.put("art", prev.getString("art"));
            all.put(pkg, o);
            p.edit().putString("all", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String toB64(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight();
        float s = Math.min(1f, 480f / Math.max(1, w));
        Bitmap sc = s < 1f ? Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(hh * s)), true) : b;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sc.compress(Bitmap.CompressFormat.JPEG, 78, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
