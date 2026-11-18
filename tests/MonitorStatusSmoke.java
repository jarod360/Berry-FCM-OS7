package top.zesun.berryfcm;
public final class MonitorStatusSmoke {
    private static void expect(MonitorStatus.State expected, boolean auth, boolean enabled,
            boolean running, long age, boolean failed, boolean nativeState, String raw) {
        MonitorStatus.State actual = MonitorStatus.resolve(auth, enabled, running, age, failed, nativeState, raw);
        if (actual != expected) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        expect(MonitorStatus.State.AUTH_REQUIRED, false,true,true,0,false,true,"connected");
        expect(MonitorStatus.State.DISABLED,true,false,true,0,false,true,"connected");
        expect(MonitorStatus.State.NOT_RUNNING,true,true,false,0,false,true,"connected");
        expect(MonitorStatus.State.FAILED,true,true,true,0,true,true,"connected");
        expect(MonitorStatus.State.WAITING,true,true,true,-1,false,true,"connected");
        expect(MonitorStatus.State.WAITING,true,true,true,91,false,true,"connected");
        expect(MonitorStatus.State.UNKNOWN,true,true,true,0,false,false,"connected");
        expect(MonitorStatus.State.CONNECTED,true,true,true,90,false,true,"connected");
        expect(MonitorStatus.State.CONNECTING,true,true,true,10,false,true,"connecting");
        expect(MonitorStatus.State.DISCONNECTED,true,true,true,10,false,true,"disconnected");
        expect(MonitorStatus.State.UNKNOWN,true,true,true,10,false,true,"unknown");
        if (!"6分 40秒".equals(MonitorStatus.duration(400))) throw new AssertionError("duration");
        if (!"1天 1小时 1分 1秒".equals(MonitorStatus.duration(90061))) throw new AssertionError("duration days");
        System.out.println("PASS: shared monitor truth table and duration formatting");
    }
}
