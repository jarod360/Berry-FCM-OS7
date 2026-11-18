package top.zesun.berryfcm;

/** Persisted ADB bootstrap throttling in the same boot, independent of app PID. */
final class LaunchCooldown {
    static long remaining(long interval, long lastElapsed, long lastWall, int lastBoot,
            long nowElapsed, long nowWall, int nowBoot) {
        if (lastElapsed < 0 || lastWall < 0) return 0;
        if (lastBoot >= 0 && nowBoot >= 0) {
            if (lastBoot != nowBoot) return 0;
            long age = nowElapsed - lastElapsed;
            return age < 0 ? 0 : Math.max(0, interval - age);
        }
        // A ROM may hide boot_count. Use both clocks when they agree;
        // otherwise conservatively fall back to the wall-clock age.
        long wallAge = nowWall - lastWall;
        long elapsedAge = nowElapsed - lastElapsed;
        if (elapsedAge >= 0 && wallAge >= 0 && Math.abs(elapsedAge - wallAge) < 5000)
            return Math.max(0, interval - elapsedAge);
        return wallAge < 0 ? interval : Math.max(0, interval - wallAge);
    }
}
