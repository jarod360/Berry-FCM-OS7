package top.zesun.berryfcm;

/** One presentation verdict for overview, monitor detail and foreground notification.
 * No Android dependency; live authorization and a fresh native sample are required.
 */
final class MonitorStatus {
    enum State {
        AUTH_REQUIRED("需要授权"), DISABLED("监控未开启"), NOT_RUNNING("监控未运行"),
        WAITING("等待有效检查"), FAILED("连接检查失败"), UNKNOWN("暂时无法确认 FCM 状态"),
        CONNECTED("FCM 已连接"), CONNECTING("FCM 正在重连（尚未连接）"),
        DISCONNECTED("FCM 已断开");
        final String label;
        State(String label) { this.label = label; }
    }
    private MonitorStatus() {}
    static State resolve(boolean authorized, boolean enabled, boolean running,
                         long age, boolean failed, boolean nativeState, String raw) {
        if (!authorized) return State.AUTH_REQUIRED;
        if (!enabled) return State.DISABLED;
        if (!running) return State.NOT_RUNNING;
        if (failed) return State.FAILED;
        if (age < 0 || age > 90) return State.WAITING;
        if (!nativeState) return State.UNKNOWN;
        if ("connected".equals(raw)) return State.CONNECTED;
        if ("connecting".equals(raw)) return State.CONNECTING;
        if ("disconnected".equals(raw)) return State.DISCONNECTED;
        return State.UNKNOWN;
    }
    static String duration(long seconds) {
        if (seconds < 0) return "未知";
        long days = seconds / 86400, hours = seconds / 3600 % 24;
        long minutes = seconds / 60 % 60, secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("天 ");
        if (hours > 0) out.append(hours).append("小时 ");
        if (minutes > 0) out.append(minutes).append("分 ");
        out.append(secs).append("秒");
        return out.toString();
    }
}
