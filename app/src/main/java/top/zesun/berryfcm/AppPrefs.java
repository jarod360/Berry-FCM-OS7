package top.zesun.berryfcm;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class AppPrefs {
    static final String ROOT = "root";
    static final String WIRELESS = "wireless";
    static final String STATIC = "static";
    static final String EXTERNAL = "external";
    private final SharedPreferences p;

    AppPrefs(Context context) {
        p = context.getSharedPreferences("berry_fcm_guardian", Context.MODE_PRIVATE);
    }
    String mode() { return p.getString("mode", WIRELESS); }
    void mode(String value) { p.edit().putString("mode", value).apply(); }
    String host() { return p.getString("host", "127.0.0.1"); }
    void host(String value) { p.edit().putString("host", value).apply(); }
    int pairPort() { return p.getInt("pair_port", 0); }
    void pairPort(int value) { p.edit().putInt("pair_port", value).apply(); }
    int staticPort() { return p.getInt("static_port", 5555); }
    void staticPort(int value) { p.edit().putInt("static_port", value).apply(); }

    boolean monitorEnabled() { return p.getBoolean("monitor_enabled", false); }
    void monitorEnabled(boolean value) { p.edit().putBoolean("monitor_enabled", value).apply(); }
    boolean guardEnabled() { return p.getBoolean("guard_enabled", false); }
    void guardEnabled(boolean value) { p.edit().putBoolean("guard_enabled", value).apply(); }
    boolean startOnBoot() { return p.getBoolean("start_on_boot", true); }
    void startOnBoot(boolean value) { p.edit().putBoolean("start_on_boot", value).apply(); }
    boolean quietMonitor() { return p.getBoolean("quiet_monitor", true); }
    void quietMonitor(boolean value) { p.edit().putBoolean("quiet_monitor", value).apply(); }
    boolean hideRecents() { return p.getBoolean("hide_recents", false); }
    void hideRecents(boolean value) { p.edit().putBoolean("hide_recents", value).apply(); }
    boolean showSystemApps() { return p.getBoolean("show_system_apps", false); }
    void showSystemApps(boolean value) { p.edit().putBoolean("show_system_apps", value).apply(); }
    String theme() { return p.getString("theme", "system"); }
    void theme(String value) { p.edit().putString("theme", value).apply(); }

    Set<String> guardTargets() {
        return new HashSet<>(p.getStringSet("guard_targets", Collections.emptySet()));
    }
    void guardTargets(Set<String> targets) {
        p.edit().putStringSet("guard_targets", new HashSet<>(targets)).apply();
    }
    String pairingStatus() { return p.getString("pairing_status", "尚未配对"); }
    void pairingStatus(String value) { p.edit().putString("pairing_status", value).apply(); }
}
