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

/** Persistent AndroidKeystore ADB identity; never persist six-digit pairing codes. */
final class AdbConnectionManager extends AbsAdbConnectionManager {
    private static final String KEY_ALIAS = "berry_fcm_adb_identity";
    private static AdbConnectionManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbConnectionManager get(Context context) throws Exception {
        if (instance == null) instance = new AdbConnectionManager(context.getApplicationContext());
        return instance;
    }

    private AdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build();
            generator.initialize(spec);
            generator.generateKeyPair();
        }
        privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
        certificate = store.getCertificate(KEY_ALIAS);
        if (privateKey == null || certificate == null)
            throw new IllegalStateException("无法创建 ADB AndroidKeyStore 身份");
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "BerryFCM-Guardian"; }

    synchronized boolean pairLocal(int pairingPort, String code) throws Exception {
        disconnectSession();
        // The pairing port is discovered via mDNS from this same device.
        return pair("127.0.0.1", pairingPort, code);
    }

    synchronized boolean autoConnectLocal(Context context) throws Exception {
        disconnectSession();
        return autoConnect(context.getApplicationContext(), 8000);
    }

    synchronized boolean connectStatic(String host, int port) throws Exception {
        disconnectSession();
        return connect(host, port);
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
        try { disconnect(); } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        // AbsAdbConnectionManager.close() destroys the private key, which belongs
        // to AndroidKeyStore. Preserve it; disconnect only the active session.
        disconnectSession();
    }
}
