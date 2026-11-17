package top.zesun.berryfcm;

import android.app.Application;
import org.conscrypt.Conscrypt;
import java.security.Security;

public final class BerryFcmApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (Security.getProvider(Conscrypt.PROVIDER_NAME) == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }
    }
}
