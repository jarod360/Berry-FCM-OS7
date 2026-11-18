package top.zesun.berryfcm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class AppPrefs {
    static final String ROOT = "root";
    static final String WIRELESS = "wireless";
    static final String STATIC = "static";
    static final String SHIZUKU = "shizuku";
    static final String EXTERNAL = "external";
    private final SharedPreferences p;

    AppPrefs(Context context) {
        p = context.getSharedPreferences("berry_fcm_guardian", Context.MODE_PRIVATE);
    }
    String mode() { return p.getString("mode", WIRELESS); }
    void mode(String value) {
        if (!value.equals(mode())) {
            p.edit().putString("mode", value).putLong("shell_verified_at", 0).apply();
        }
    }
    void activateMode(String mode, String host, int port, String identity) {
        p.edit().putString("mode", mode).putString("host", host).putInt("static_port", port)
                .putLong("shell_verified_at", System.currentTimeMillis())
                .putLong("shell_verified_elapsed", SystemClock.elapsedRealtime())
                .putString("shell_mode", mode).putString("shell_identity", identity)
                .remove("shell_error").apply();
    }
    String connectionKey() {
        if (SHIZUKU.equals(mode())) return SHIZUKU + ":" + ShizukuShell.authorizedUid();
        return STATIC.equals(mode()) ? STATIC + ":" + host() + ":" + staticPort() : mode();
    }
    String host() { return p.getString("host", "127.0.0.1"); }
    void host(String value) { p.edit().putString("host", value).apply(); }
    int wirelessConnectPort() { return p.getInt("wireless_connect_port", 0); }
    void wirelessConnectPort(int value) { p.edit().putInt("wireless_connect_port", value).apply(); }
    int pairPort() { return p.getInt("pair_port", 0); }
    void pairPort(int value) { p.edit().putInt("pair_port", value).apply(); }
    int staticPort() { return p.getInt("static_port", 5555); }
    void staticPort(int value) { p.edit().putInt("static_port", value).apply(); }

    boolean monitorEnabled() { return p.getBoolean("monitor_enabled", false); }
    void monitorEnabled(boolean value) { p.edit().putBoolean("monitor_enabled", value).apply(); }
    boolean autoReconnect() { return p.getBoolean("auto_reconnect", true); }
    void autoReconnect(boolean value) { p.edit().putBoolean("auto_reconnect", value).apply(); }
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
    long shellVerifiedAt() { return p.getLong("shell_verified_at", 0); }
    String shellMode() { return p.getString("shell_mode", ""); }
    String shellIdentity() { return p.getString("shell_identity", ""); }
    String shellError() { return p.getString("shell_error", ""); }
    boolean shellRecentlyVerified() {
        long wallAge = System.currentTimeMillis() - shellVerifiedAt();
        long elapsedAge = SystemClock.elapsedRealtime() - p.getLong("shell_verified_elapsed", -1);
        return mode().equals(shellMode())
                && wallAge >= 0 && wallAge < 120000
                && elapsedAge >= 0 && elapsedAge < 120000
                && Math.abs(wallAge - elapsedAge) < 5000;
    }
    void shellVerified(String identity) {
        p.edit().putLong("shell_verified_at", System.currentTimeMillis())
                .putLong("shell_verified_elapsed", SystemClock.elapsedRealtime())
                .putString("shell_mode", mode()).putString("shell_identity", identity)
                .remove("shell_error").apply();
    }
    void shellInvalidate() {
        p.edit().putLong("shell_verified_at", 0)
                .putLong("shell_verified_elapsed", 0)
                .remove("shell_error").apply();
    }
    void shellFailed(String message) {
        String reason = message == null ? "Shell 连接失败" : message.trim();
        if (reason.contains("RSA routines") || reason.contains("SSL library")) {
            reason = "TLS 握手失败；请确认无线调试已开启，只有系统撤销配对身份时才重新配对";
        } else {
            reason = reason.replace('\r', ' ').replace('\n', ' ');
            if (reason.length() > 150) reason = reason.substring(0, 150) + "…";
        }
        p.edit().putLong("shell_verified_at", 0).putString("shell_mode", mode())
                .putString("shell_error", reason).apply();
    }
    /** Pairing identity persists; a 120-second Shell probe is not the authorization itself. */
    boolean adbPaired() {
        return p.getBoolean("privkit_pairing_history", false);
    }
    // Historical hint only; current auth requires the upstream ADB check.
    void markAdbPaired() {
        p.edit().putBoolean("privkit_pairing_history", true)
                .putString("privkit_pairing_evidence", "accepted").apply();
    }
    void markPairingExchange() {
        p.edit().putBoolean("privkit_pairing_history", true)
                .putString("privkit_pairing_evidence", "unverified").apply();
    }
    void clearAdbPaired() {
        p.edit().putBoolean("privkit_pairing_history", false)
                .putString("privkit_pairing_evidence", "unverified").apply();
    }
    void rejectAdbIdentity() {
        p.edit().putBoolean("privkit_pairing_history", false)
                .putString("privkit_pairing_evidence", "rejected").apply();
    }
    boolean pairingRejected() { return "rejected".equals(p.getString("privkit_pairing_evidence", "")); }
    void pairingUnavailable() {
        if (!pairingRejected()) p.edit().putString("privkit_pairing_evidence", "unavailable").apply();
    }
    String pairingEvidenceLabel() {
        switch (p.getString("privkit_pairing_evidence", "")) {
            case "accepted": return "历史 ADB 认证已通过 · 当前待验证";
            case "rejected": return "系统拒绝当前密钥 · 需要配对";
            case "unavailable": return "当前无法验证系统配对";
            default: return adbPaired() ? "配对交换已完成 · 认证待验证" : "当前身份尚未验证";
        }
    }
    String pairingStatus() { return p.getString("pairing_status", "尚未配对"); }
    void pairingStatus(String value) { p.edit().putString("pairing_status", value).apply(); }
}
