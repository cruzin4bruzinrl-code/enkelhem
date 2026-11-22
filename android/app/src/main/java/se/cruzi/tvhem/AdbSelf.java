package se.cruzi.tvhem;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Starts the guard after a restart of the box without the phone: Hem turns on wireless debugging,
 * connects to the box's own adb over TLS with the phone's (already paired) adb key, and runs the
 * guard as the shell user - exactly what 'hemvakt' in Termux does.
 * The key is handed over once by 'hemvakt' (KeyReceiver).
 */
final class AdbSelf {
    static final String TAG = "HemTv";
    private static final int CNXN = 0x4e584e43, STLS = 0x534c5453, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
        WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;
    private static volatile long lastTry;

    static File keyFile(Context c) { return new File(c.getFilesDir(), "adbkey"); }

    static boolean hasKey(Context c) { return keyFile(c).length() > 100; }

    static void saveKey(Context c, String pemOrB64) throws Exception {
        String pem = pemOrB64.contains("BEGIN") ? pemOrB64 : new String(Base64.decode(pemOrB64, Base64.DEFAULT), StandardCharsets.US_ASCII);
        loadKey(pem); // must parse
        try (FileOutputStream o = new FileOutputStream(keyFile(c))) { o.write(pem.getBytes(StandardCharsets.US_ASCII)); }
    }

    private static PrivateKey loadKey(String pem) throws Exception {
        String b = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(b, Base64.DEFAULT)));
    }

    /** Start the guard in the background if it is not running. Safe to call often. */
    static void ensureGuard(final Context c) {
        long now = System.currentTimeMillis();
        try {
            String g = Settings.Global.getString(c.getContentResolver(), "hem_guard");
            long at = g == null ? 0 : Long.parseLong(g.trim());
            if (now - at < 75000) return;
        } catch (Exception ignored) {}
        if (!hasKey(c) || now - lastTry < 90000) return;
        lastTry = now;
        new Thread(() -> {
            try { run(c); } catch (Throwable t) { Log.d(TAG, "självstart av vakten misslyckades: " + t); }
        }).start();
    }

    private static void run(Context c) throws Exception {
        Log.d(TAG, "självstart: vakten är inte igång, startar den");
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1)
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        } catch (Exception e) { Log.d(TAG, "självstart: kunde inte slå på trådlös felsökning " + e); }
        int port = 0;
        for (int i = 0; i < 4 && port <= 0; i++) {
            port = prop("service.adb.tls.port");
            if (port <= 0) port = discover(c, 6000);
            if (port <= 0) Thread.sleep(2500);
        }
        if (port <= 0) { Log.d(TAG, "självstart: hittar ingen adb-port"); return; }
        String apk = c.getApplicationInfo().sourceDir;
        String cmd = "for p in $(pgrep -f 'se.cruzi.tvhem.Gu[a]rd'); do kill $p; done; CLASSPATH=" + apk
            + " nohup app_process /system/bin se.cruzi.tvhem.Guard >/data/local/tmp/hemvakt.log 2>&1 </dev/null &";
        String out = shell(c, "127.0.0.1", port, cmd);
        Log.d(TAG, "självstart: vakten startad via port " + port + " " + out.trim());
    }

    private static int prop(String name) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable t) { return 0; }
    }

    private static int discover(Context c, long ms) {
        final NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        final int[] port = {0};
        final CountDownLatch done = new CountDownLatch(1);
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onServiceFound(NsdServiceInfo s) {
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo i, int e) {}
                        @Override public void onServiceResolved(NsdServiceInfo i) {
                            if (isLocal(i)) { port[0] = i.getPort(); done.countDown(); }
                        }
                    });
                } catch (Exception ignored) {}
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l);
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
        try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) {}
        return port[0];
    }

    @SuppressWarnings("deprecation")
    private static boolean isLocal(NsdServiceInfo i) {
        try {
            java.net.InetAddress a = i.getHost();
            if (a == null) return true;
            return java.net.NetworkInterface.getByInetAddress(a) != null || a.isLoopbackAddress();
        } catch (Exception e) { return true; }
    }

    // ---------- adb over TLS, just enough to run one shell command ----------

    private static String shell(Context c, String host, int port, String cmd) throws Exception {
        PrivateKey key = loadKey(new String(Files.readAllBytes(keyFile(c).toPath()), StandardCharsets.US_ASCII));
        RSAPrivateCrtKey rk = (RSAPrivateCrtKey) key;
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rk.getModulus(), rk.getPublicExponent()));
        X509Certificate cert = selfSigned(key, pub);

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 5000);
        raw.setSoTimeout(8000);
        InputStream in = raw.getInputStream();
        OutputStream out = raw.getOutputStream();
        send(out, CNXN, 0x01000001, 1024 * 1024, "host::features=shell_v2,cmd\0".getBytes(StandardCharsets.UTF_8));
        int[] h = new int[6];
        byte[] d = read(in, h);
        if (h[0] != STLS) throw new Exception("adb svarade " + Integer.toHexString(h[0]) + " i stället för STLS");
        send(out, STLS, 0x01000000, 0, new byte[0]);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(new KeyManager[]{new OneKey(key, cert)}, new TrustManager[]{new TrustAll()}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        s.startHandshake();
        in = s.getInputStream();
        out = s.getOutputStream();
        d = read(in, h);
        if (h[0] == AUTH) throw new Exception("boxen godkänner inte nyckeln");
        if (h[0] != CNXN) throw new Exception("väntade CNXN, fick " + Integer.toHexString(h[0]));

        send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        long until = System.currentTimeMillis() + 8000;
        try {
            while (System.currentTimeMillis() < until) {
                d = read(in, h);
                if (h[0] == WRTE) { res.write(d); send(out, OKAY, 1, h[1], new byte[0]); }
                else if (h[0] == CLSE) { send(out, CLSE, 1, h[1], new byte[0]); break; }
            }
        } catch (java.net.SocketTimeoutException ignored) {}
        try { s.close(); } catch (Exception ignored) {}
        return res.toString("UTF-8");
    }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(cmd ^ 0xffffffff).put(data);
        o.write(b.array());
        o.flush();
    }

    private static byte[] read(InputStream i, int[] h) throws Exception {
        DataInputStream in = new DataInputStream(i);
        byte[] hd = new byte[24];
        in.readFully(hd);
        ByteBuffer b = ByteBuffer.wrap(hd).order(ByteOrder.LITTLE_ENDIAN);
        for (int k = 0; k < 6; k++) h[k] = b.getInt();
        byte[] d = new byte[h[3]];
        in.readFully(d);
        return d;
    }

    // ---------- a minimal self-signed certificate for the key (adbd only looks at the public key) ----------

    private static X509Certificate selfSigned(PrivateKey key, PublicKey pub) throws Exception {
        byte[] alg = seq(new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}, new byte[]{0x05, 0x00});
        byte[] name = seq(tlv(0x31, seq(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}, tlv(0x0c, "hem".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(tlv(0x17, "250101000000Z".getBytes(StandardCharsets.US_ASCII)), tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})), tlv(0x02, BigInteger.valueOf(System.currentTimeMillis()).toByteArray()),
            alg, name, validity, name, pub.getEncoded());
        Signature sg = Signature.getInstance("SHA256withRSA");
        sg.initSign(key);
        sg.update(tbs);
        byte[] sig = sg.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, alg, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return tlv(0x30, o.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(v, 0, n);
        return o.toByteArray();
    }

    private static final class OneKey extends X509ExtendedKeyManager {
        private final PrivateKey key; private final X509Certificate cert;
        OneKey(PrivateKey k, X509Certificate c) { key = k; cert = c; }
        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"hem"}; }
        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "hem"; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, javax.net.ssl.SSLEngine e) { return "hem"; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
        @Override public PrivateKey getPrivateKey(String a) { return key; }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
