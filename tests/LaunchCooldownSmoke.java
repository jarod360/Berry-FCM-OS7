package top.zesun.berryfcm;

public final class LaunchCooldownSmoke {
    private static void expect(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        long interval = 300000;
        expect(0, LaunchCooldown.remaining(interval,-1,-1,-1,1,1000,1));
        expect(290000, LaunchCooldown.remaining(interval,100000,1000000,7,110000,1010000,7));
        expect(290000, LaunchCooldown.remaining(interval,100000,1000000,7,110000,901000,7));
        expect(0, LaunchCooldown.remaining(interval,100000,1000000,7,400000,1300000,7));
        expect(0, LaunchCooldown.remaining(interval,100000,1000000,7,1000,1010000,8));
        expect(290000, LaunchCooldown.remaining(interval,100000,1000000,-1,110000,1010000,-1));
        expect(290000, LaunchCooldown.remaining(interval,100000,1000000,-1,1000,1010000,-1));
        expect(300000, LaunchCooldown.remaining(interval,100000,1000000,-1,110000,900000,-1));
        System.out.println("PASS: restart-safe Priv Kit launch cooldown");
    }
}
