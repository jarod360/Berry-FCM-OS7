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
        if (privateKey == null || certificate == null) throw new IllegalStateException("无法创建 ADB AndroidKeyStore 身份");
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "BerryFCM-Guardian"; }

    boolean pairAndConnect(Context context, String host, int pairingPort, String code) throws Exception {
        disconnectSession();
        boolean paired = pair(host, pairingPort, code);
        if (!paired) return false;
        return autoConnect(context.getApplicationContext(), 10000);
    }

    boolean autoConnectLocal(Context context) throws Exception {
        disconnectSession();
        return autoConnect(context.getApplicationContext(), 8000);
    }

    boolean connectStatic(String host, int port) throws Exception {
        disconnectSession();
        return connect(host, port);
    }

    String shell(String command) throws Exception {
        try (AdbStream stream = openStream(LocalServices.SHELL)) {
            OutputStream out = stream.openOutputStream();
            out.write((command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream in = stream.openInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0) result.write(buffer, 0, n);
            }
            return result.toString(StandardCharsets.UTF_8);
        }
    }

    void disconnectSession() {
        try { disconnect(); } catch (Exception ignored) { }
    }

    @Override public synchronized void close() {
        // AbsAdbConnectionManager.close() also calls PrivateKey.destroy(). The ADB
        // identity is held in AndroidKeyStore, so disconnect without destroying it.
        disconnectSession();
    }
}
