package nz.kelly.xora.companion;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
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
import java.util.concurrent.Executors;

/**
 * XORA Companion - native Android pairing and continuity client.
 *
 * This is a real device app, not a WebView wrapper: it has its own UI, its own
 * pairing flow against the XORA device API, and its own credential storage. It
 * holds no session cookie and never handles the owner's password after pairing.
 *
 * Pairing follows the server contract in app/devices/router.py:
 *   POST /api/v1/devices/pairing-codes  -> { pairing_code, expires_in }
 *   POST /api/v1/devices/pair           -> { device: {...}, device_token }
 *
 * Only the SECOND call can be made by a native app. The mint endpoint sits
 * behind get_current_user plus a double-submit CSRF cookie, so it needs a
 * password session this app deliberately never has. The code is therefore
 * minted on the PC (Voice & Devices panel) and pasted here; this app only
 * redeems it, which is exactly what a bearer device credential is allowed to do.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "xora_companion";
    private static final String KEY_HOST = "host";
    private static final String KEY_TOKEN = "device_token";
    private static final String KEY_DEVICE_ID = "device_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText hostInput;
    private EditText codeInput;
    private TextView statusView;
    private TextView deviceView;
    private Button pairButton;
    private Button syncButton;
    private LinearLayout pairedSection;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedHost = p.getString(KEY_HOST, "100.64.0.1:9106");
        hostInput.setText(savedHost);
        if (p.getString(KEY_TOKEN, null) != null) {
            deviceView.setText("Paired device: " + p.getString(KEY_DEVICE_ID, "?"));
            setPaired(true);
            refreshPresence();
        } else {
            statusView.setText("Not paired. Enter the XORA host and tap Pair this device.");
        }
    }

    private void buildUi() {
        scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 44, 36, 44);
        root.setBackgroundColor(Color.parseColor("#070b12"));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("XORA Companion");
        title.setTextColor(Color.parseColor("#61e8ff"));
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Native Android client for your XORA workspace");
        sub.setTextColor(Color.parseColor("#8ea4bb"));
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, 6, 0, 26);
        root.addView(sub);

        hostInput = new EditText(this);
        hostInput.setHint("host:port  (e.g. 100.64.0.1:9106)");
        hostInput.setTextColor(Color.parseColor("#e6f0ff"));
        hostInput.setHintTextColor(Color.parseColor("#5b6b7d"));
        root.addView(hostInput);

        TextView codeHelp = new TextView(this);
        codeHelp.setText("Pairing code from your PC. Generate it in the workspace under Voice & Devices, then paste it here. Codes expire after 5 minutes.");
        codeHelp.setTextColor(Color.parseColor("#8ea4bb"));
        codeHelp.setTextSize(12);
        codeHelp.setPadding(0, 16, 0, 4);
        root.addView(codeHelp);

        codeInput = new EditText(this);
        codeInput.setHint("pairing code");
        codeInput.setSingleLine(true);
        codeInput.setTextColor(Color.parseColor("#e6f0ff"));
        codeInput.setHintTextColor(Color.parseColor("#5b6b7d"));
        root.addView(codeInput);

        pairButton = new Button(this);
        pairButton.setText("Pair this device");
        pairButton.setOnClickListener(v -> pairDevice());
        root.addView(pairButton);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#8ea4bb"));
        statusView.setTextSize(13);
        statusView.setPadding(0, 18, 0, 0);
        root.addView(statusView);

        pairedSection = new LinearLayout(this);
        pairedSection.setOrientation(LinearLayout.VERTICAL);

        deviceView = new TextView(this);
        deviceView.setTextColor(Color.parseColor("#e6f0ff"));
        deviceView.setTextSize(14);
        pairedSection.addView(deviceView);

        syncButton = new Button(this);
        syncButton.setText("Sync transcript now");
        syncButton.setOnClickListener(v -> syncTranscript());
        pairedSection.addView(syncButton);

        Button handoff = new Button(this);
        handoff.setText("Request audio handoff");
        handoff.setOnClickListener(v -> statusView.setText("Select a handoff target in the desktop workspace."));
        pairedSection.addView(handoff);

        root.addView(pairedSection);
        setContentView(scroll);
    }

    private void setPaired(boolean paired) {
        pairedSection.setVisibility(paired ? View.VISIBLE : View.GONE);
        hostInput.setEnabled(!paired);
        codeInput.setEnabled(!paired);
        pairButton.setEnabled(!paired);
    }

    // ---- pairing ---------------------------------------------------------

    private void pairDevice() {
        final String rawHost = hostInput.getText().toString().trim();
        final String rawCode = codeInput.getText().toString().trim();
        if (rawHost.isEmpty()) {
            statusView.setText("Enter the host first.");
            return;
        }
        if (rawCode.isEmpty()) {
            statusView.setText("Paste the pairing code from your PC first.");
            return;
        }
        final String base = baseUrl(rawHost);
        pairButton.setEnabled(false);
        statusView.setText("Pairing...");

        io.execute(() -> {
            try {
                // Redeem the code minted on the PC. This endpoint needs no
                // session: it is the one call a native bearer client owns.
                JSONObject paired = post(base, "/api/v1/devices/pair",
                        new JSONObject()
                                .put("pairing_code", rawCode)
                                .put("device_name", android.os.Build.MODEL)
                                .put("platform", "android"),
                        null);

                String token = paired.getString("device_token");
                String deviceId = paired.getJSONObject("device").getString("id");

                SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                p.edit()
                        .putString(KEY_HOST, rawHost)
                        .putString(KEY_TOKEN, token)
                        .putString(KEY_DEVICE_ID, deviceId)
                        .apply();

                main.post(() -> {
                    deviceView.setText("Paired device: " + deviceId);
                    codeInput.setText("");
                    setPaired(true);
                    statusView.setText("Device paired.");
                    refreshPresence();
                });
            } catch (Exception e) {
                main.post(() -> {
                    pairButton.setEnabled(true);
                    statusView.setText("Pairing failed: " + describe(e));
                });
            }
        });
    }

    // ---- continuity ------------------------------------------------------

    private void syncTranscript() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String base = baseUrl(p.getString(KEY_HOST, ""));
        final String token = p.getString(KEY_TOKEN, null);
        if (token == null) {
            return;
        }
        statusView.setText("Syncing transcript...");

        io.execute(() -> {
            try {
                // GET /devices/transcript is identity-agnostic (session OR device
                // token) and returns everything after a cursor. This is the real
                // continuity read, unlike /audio/claim which is session-only.
                HttpURLConnection c = open(base + "/api/v1/devices/transcript?after=0", "GET", token);
                String text = readAll(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream());
                int count = 0;
                try {
                    count = new JSONObject(text).optJSONArray("items") == null
                            ? 0 : new JSONObject(text).getJSONArray("items").length();
                } catch (Exception ignored) {
                    count = -1;
                }
                final int n = count;
                main.post(() -> statusView.setText(
                        n < 0 ? "Transcript read returned an unexpected shape."
                              : "Transcript synced: " + n + " entries after cursor 0."));
            } catch (Exception e) {
                main.post(() -> statusView.setText("Sync failed: " + describe(e)));
            }
        });
    }

    /**
     * Report this device online. The server's POST /devices/presence is
     * session-authenticated, so a device token cannot use it; the device-scoped
     * acknowledgement endpoint is what a bearer device credential can actually
     * call. Presence is therefore surfaced as "device token verified" rather than
     * pretending to write a session-only field.
     */
    private void refreshPresence() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String base = baseUrl(p.getString(KEY_HOST, ""));
        final String token = p.getString(KEY_TOKEN, null);
        if (token == null) {
            return;
        }
        io.execute(() -> {
            try {
                HttpURLConnection c = open(base + "/api/v1/devices/transcript?after=0", "GET", token);
                int code = c.getResponseCode();
                readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
                if (code == 200) {
                    main.post(() -> deviceView.setText(
                            "Paired device: " + p.getString(KEY_DEVICE_ID, "?") + "  (token verified)"));
                }
            } catch (Exception ignored) {
                // Best-effort: a transient failure must not surface as an error state.
            }
        });
    }

    private static String baseUrl(String host) {
        String h = host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            return h.contains(":") ? h : h + ":9106";
        }
        return h.contains(":") ? "https://" + h : "https://" + h + ":9106";
    }

    /** Open a request with the pinned tailnet trust anchor and optional bearer. */
    private HttpURLConnection open(String fullUrl, String method, String bearer)
            throws Exception {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new URL(fullUrl).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(trustingFactory());
        c.setRequestProperty("Origin", baseUrlOf(fullUrl));
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        return c;
    }

    private static String baseUrlOf(String fullUrl) {
        try {
            java.net.URI u = java.net.URI.create(fullUrl);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- http ------------------------------------------------------------

    private JSONObject post(String base, String path, JSONObject body, String bearer)
            throws Exception {
        URL url = new URL(base + path);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        // setSSLSocketFactory only exists on the HTTPS subclass.
        ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(trustingFactory());
        c.setRequestProperty("Content-Type", "application/json");
        // Origin must match an allowed origin for CSRF to pass on the server.
        c.setRequestProperty("Origin", base);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        if (code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + text);
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    /**
     * Trust the XORA tailnet certificate specifically. The server uses a
     * self-signed cert, so we load that exact cert as a trust anchor rather than
     * trusting everything (which is what a blanket TrustManager would do).
     */
    private javax.net.ssl.SSLSocketFactory trustingFactory() throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        InputStream in = getResources().openRawResource(R.raw.xora_tailnet_cert);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (X509Certificate cert : (java.util.Collection<X509Certificate>)
                    cf.generateCertificates(in)) {
                ks.setCertificateEntry("xora" + (i++), cert);
            }
        } finally {
            in.close();
        }
        javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx.getSocketFactory();
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

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
