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
    static final String MANUAL = "manual";
    private static final String FILE = "berry_fcm_guardian";
    private final SharedPreferences p;

    AppPrefs(Context c) { p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }
    String mode() { return p.getString("mode", WIRELESS); }
    void mode(String v) { p.edit().putString("mode", v).apply(); }
    String host() { return p.getString("host", "127.0.0.1"); }
    void host(String v) { p.edit().putString("host", v).apply(); }
    int pairPort() { return p.getInt("pair_port", 37123); }
    void pairPort(int v) { p.edit().putInt("pair_port", v).apply(); }
    int staticPort() { return p.getInt("static_port", 5555); }
    void staticPort(int v) { p.edit().putInt("static_port", v).apply(); }
    boolean autoRestore() { return p.getBoolean("auto_restore", false); }
    void autoRestore(boolean v) { p.edit().putBoolean("auto_restore", v).apply(); }
    Set<String> selected() { return new HashSet<>(p.getStringSet("selected_services", Collections.emptySet())); }
    void selected(Set<String> v) { p.edit().putStringSet("selected_services", new HashSet<>(v)).apply(); }
    Set<String> managed() { return new HashSet<>(p.getStringSet("managed_services", Collections.emptySet())); }
    void managed(Set<String> v) { p.edit().putStringSet("managed_services", new HashSet<>(v)).apply(); }
}
