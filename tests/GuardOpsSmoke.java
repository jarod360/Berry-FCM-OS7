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
        java.util.List<String> batches = GuardOps.commandBatches(apps, true);
        if (batches.size() != 6) throw new AssertionError("wrong batch count");
        String joined = String.join("\n", batches);
        for (String app : apps) {
            String needle = "p='" + app + "'";
            if (joined.indexOf(needle) < 0 || joined.indexOf(needle) != joined.lastIndexOf(needle))
                throw new AssertionError("Missing or duplicate target " + app);
        }
        String gms = "p='" + GuardOps.GMS + "'";
        if (joined.indexOf(gms) < 0 || joined.indexOf(gms) != joined.lastIndexOf(gms))
            throw new AssertionError("GMS must appear once");
        for (String batch : batches) if (batch.split("p='", -1).length - 1 > 5)
            throw new AssertionError("Batch exceeds bounded shell duration");
        if (!GuardOps.heartbeatCommand().equals("am broadcast --user 0 -a com.google.android.intent.action.MCS_HEARTBEAT -p com.google.android.gms 2>&1"))
            throw new AssertionError("Guard heartbeat must be one exported, targeted request");
        System.out.println("PASS: selected guard targets are not silently truncated; bounded batches, one GMS target and one targeted heartbeat");
    }
}
