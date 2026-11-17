package top.zesun.berryfcm;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.annotation.NonNull;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.LocalServices;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

/** AndroidKeyStore ADB identity; six-digit pairing codes never persist. */
final class AdbConnectionManager extends AbsAdbConnectionManager {
    // Conscrypt TLS 1.3 needs RSA-PSS and a raw/no-padding RSA operation.
    // v3 avoids reusing the incompatible previously generated Keystore identity.
    private static final String KEY_ALIAS = "berry_fcm_adb_identity_v3";
    private static AdbConnectionManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;
    private String activeTransport = "";
    private String staticHost = "";
    private int staticPort;

    static synchronized AdbConnectionManager get(Context context) throws Exception {
        if (instance == null) instance = new AdbConnectionManager(context.getApplicationContext());
        return instance;
    }

    private AdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(10, TimeUnit.SECONDS);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1,
                            KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build();
            generator.initialize(spec);
            generator.generateKeyPair();
            // The new identity must be paired explicitly; do not claim the prior
            // v1/v2 identity has authenticated the current TLS client.
            AppPrefs prefs = new AppPrefs(context);
            prefs.shellFailed("ADB 身份已更新；请重新进行无线配对");
            prefs.pairingStatus("TLS 身份已修复，等待重新配对");
        }
        privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
        certificate = store.getCertificate(KEY_ALIAS);
        if (privateKey == null || certificate == null)
            throw new IllegalStateException("无法创建 AndroidKeyStore ADB 身份");
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "BerryFCM"; }

    synchronized boolean pairLocal(int pairingPort, String code) throws Exception {
        disconnectSession();
        return pair("127.0.0.1", pairingPort, code);
    }

    synchronized boolean autoConnectLocal(Context context) throws Exception {
        if ("wireless".equals(activeTransport) && isConnected()) return true;
        disconnectSession();
        boolean connected = connectTls(context.getApplicationContext(), 8000);
        if (connected) activeTransport = "wireless";
        return connected;
    }

    synchronized boolean connectStatic(String host, int port) throws Exception {
        if ("static".equals(activeTransport) && port == staticPort
                && host.equals(staticHost) && isConnected()) return true;
        disconnectSession();
        boolean connected = connect(host, port);
        if (connected) {
            activeTransport = "static";
            staticHost = host;
            staticPort = port;
        }
        return connected;
    }

    synchronized String shell(String command) throws Exception {
        try (AdbStream stream = openStream(LocalServices.SHELL)) {
            OutputStream out = stream.openOutputStream();
            out.write((command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream in = stream.openInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0 && result.size() < 200000) {
                    result.write(buffer, 0, Math.min(n, 200000 - result.size()));
                }
            }
            return result.toString(StandardCharsets.UTF_8);
        }
    }

    synchronized void disconnectSession() {
        activeTransport = "";
        try { disconnect(); } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        // Do not destroy the AndroidKeyStore private key.
        disconnectSession();
    }
}
