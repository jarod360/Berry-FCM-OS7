package top.zesun.berryfcm;

/** Do not implicitly unbox a null port when NSD is required. */
final class AdbConnectPort {
    private AdbConnectPort() { }
    static Integer select(int manual, int system) {
        if (manual > 0) return Integer.valueOf(manual);
        return system > 0 ? Integer.valueOf(system) : null;
    }
}
