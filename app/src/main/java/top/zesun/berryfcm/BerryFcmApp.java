package top.zesun.berryfcm;

import android.app.Application;
import org.conscrypt.Conscrypt;
import java.security.Provider;
import java.security.Security;

public final class BerryFcmApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Provider provider = Conscrypt.newProvider();
        if (Security.getProvider(provider.getName()) == null) {
            Security.insertProviderAt(provider, 1);
        }
    }
}
