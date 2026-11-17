package top.zesun.berryfcm;

import java.util.HashSet;
import java.util.Set;

public final class GuardOpsSmoke {
    public static void main(String[] args) {
        Set<String> apps = new HashSet<>();
        for (int i = 0; i < 25; i++) apps.add("org.berryfcm.test.app" + i);
        String check = GuardOps.command(apps, false);
        String repair = GuardOps.command(apps, true);
        for (String app : apps) {
            if (!check.contains("p='" + app + "'") || !repair.contains("p='" + app + "'"))
                throw new AssertionError("Selected guard target omitted: " + app);
        }
        if (!check.contains("p='" + GuardOps.GMS + "'"))
            throw new AssertionError("Core Google Play services target omitted");
        System.out.println("PASS: selected guard targets are not silently truncated");
    }
}
