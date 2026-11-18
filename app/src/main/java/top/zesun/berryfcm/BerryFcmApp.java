package top.zesun.berryfcm;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

public final class BerryFcmApp extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Priv Kit v0.20.0's documented initialization (also used by GKD).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        // Priv Kit uses the platform Conscrypt exporter and default TLS context.
        // libadb selects its own provider for legacy TCP; never replace the
        // process-wide TLS provider with the unrelated bundled Conscrypt.
        PrivKitShell.initialize();
    }
}
