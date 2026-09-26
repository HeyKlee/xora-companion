package nz.kelly.xora.companion;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * The XORA device/continuity API, as a native client sees it.
 *
 * Every request carries an {@code Origin} header because the server's CSRF policy
 * validates origin on authenticated mutations. A device bearer is opaque and
 * cannot carry the double-submit cookie pair, so the server skips that check for
 * {@code Authorization: Bearer} after origin validation.
 *
 * The mint endpoint (POST /devices/pairing-codes) is session-only and is never
 * called here: the code comes from the PC. This app only redeems, and then speaks
 * the audio-handoff protocol as itself.
 */
final class XoraClient {

    private final String base;
    private final String token;
    private final String deviceId;
    private final ExecutorService io;

    XoraClient(String base, String token, String deviceId, ExecutorService io) {
        this.base = base;
        this.token = token;
        this.deviceId = deviceId;
        this.io = io;
    }

    String base() {
        return base;
    }

    String deviceId() {
        return deviceId;
    }

    /**
     * The LIVE XORA server. 9106 is the isolated preview instance whose database
     * has no enrolled devices, so pairing there yields a token that always 401s.
     */
    private static final String DEFAULT_PORT = "9105";

    static String baseUrl(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            return "";
        }
        if (h.startsWith("http://") || h.startsWith("https://")) {
            return h.contains(":") ? h : h + ":" + DEFAULT_PORT;
        }
        return h.contains(":") ? "https://" + h : "https://" + h + ":" + DEFAULT_PORT;
    }

    /** Trust the XORA tailnet cert specifically, not everything. */
    private SSLSocketFactory trustingFactory(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.xora_tailnet_cert)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (X509Certificate cert : (java.util.Collection<X509Certificate>) cf.generateCertificates(in)) {
                ks.setCertificateEntry("xora" + (i++), cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return ssl.getSocketFactory();
    }

    private static String originOf(String fullUrl) {
        try {
            java.net.URI u = java.net.URI.create(fullUrl);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** Extract a human message from the app's {"error":{"message":...}} envelope. */
    static String humanError(String body, int code) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject e = o.optJSONObject("error");
            if (e != null && e.optString("message", "").length() > 0) {
                return e.getString("message");
            }
            String d = o.optString("detail", "");
            if (d.length() > 0) {
                return d;
            }
        } catch (Exception ignored) {
            // fall through to the raw body
        }
        return "HTTP " + code;
    }

    private JSONObject call(Context ctx, String method, String path, JSONObject body) throws Exception {
        String full = base + path;
        HttpURLConnection c = (HttpURLConnection) new URL(full).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(trustingFactory(ctx));
        }
        c.setRequestProperty("Origin", originOf(full));
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        String text = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) {
            throw new IllegalStateException(humanError(text, code));
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    // ---- pairing ---------------------------------------------------------

    /** Redeem a PC-minted code. The only pre-auth call this app makes. */
    static JSONObject pair(Context ctx, String base, String code, String deviceName) throws Exception {
        XoraClient anon = new XoraClient(base, null, null, null);
        return anon.call(ctx, "POST", "/api/v1/devices/pair", new JSONObject()
                .put("pairing_code", code)
                .put("device_name", deviceName)
                .put("platform", "android"));
    }

    // ---- continuity ------------------------------------------------------

    JSONObject transcript(Context ctx, int after) throws Exception {
        JSONObject o = call(ctx, "GET", "/api/v1/devices/transcript?after=" + after, null);
        return o.optJSONArray("items") == null ? new JSONObject() : o;
    }

    int transcriptCount(Context ctx, int after) throws Exception {
        return transcript(ctx, after).getJSONArray("items").length();
    }

    // ---- audio handoff ---------------------------------------------------

    /** The lease, as this device sees it. Read-only. */
    JSONObject audioState(Context ctx) throws Exception {
        return call(ctx, "GET", "/api/v1/devices/audio/state", null);
    }

    /** Take the audio lease as THIS device. The server pins the id for us. */
    JSONObject claimAudio(Context ctx, int cursor) throws Exception {
        return call(ctx, "POST", "/api/v1/devices/audio/claim", new JSONObject()
                .put("device_id", deviceId)
                .put("transcript_cursor", cursor));
    }

    /** Acknowledge a pending transfer addressed to this device. Takes the lease. */
    JSONObject ackTransfer(Context ctx) throws Exception {
        return call(ctx, "POST", "/api/v1/devices/audio/ack", null);
    }

    /** Give up the lease. Only the current owner may do this. */
    JSONObject releaseAudio(Context ctx) throws Exception {
        return call(ctx, "POST", "/api/v1/devices/audio/release", null);
    }
}
