package top.zesun.berryfcm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative application stopped-state checks. No AccessibilityService. */
final class GuardOps {
    static final String GMS = "com.google.android.gms";
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+");
    private GuardOps() {}

    static String heartbeatCommand() {
        // A completed broadcast only means the request was accepted, not that
        // FCM has reconnected or delivered a push message.
        return "am broadcast --user 0 -a com.google.android.intent.action.MCS_HEARTBEAT "
                + "-p com.google.android.gms 2>&1; "
                + "am broadcast --user 0 -a com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW "
                + "-p com.google.android.gms 2>&1; "
                + "am broadcast --user 0 -a com.google.android.gcm.intent.SEND "
                + "-n com.google.android.gms/.gcm.GcmSenderProxy 2>&1";
    }

    static String command(Set<String> selected, boolean repair) {
        List<String> items = new ArrayList<>(selected);
        if (!items.contains(GMS)) items.add(GMS);
        Collections.sort(items);
        StringBuilder cmd = new StringBuilder();
        for (String pkg : items) {
            if (!PACKAGE.matcher(pkg).matches()) continue;
            cmd.append("p='").append(pkg).append("'; ")
               .append("l=\"$(dumpsys -t 3 package \"$p\" 2>/dev/null | ")
               .append("grep -E '^[[:space:]]*User 0:.*installed=' | head -n 1)\"; ")
               .append("case \" $l \" in ");
            if (repair) {
                cmd.append("*' stopped=true '*) cmd package unstop --user 0 \"$p\" >/dev/null 2>&1; ")
                   .append("l=\"$(dumpsys -t 3 package \"$p\" 2>/dev/null | ")
                   .append("grep -E '^[[:space:]]*User 0:.*installed=' | head -n 1)\"; ")
                   .append("case \" $l \" in *' stopped=false '*) echo \"$p|restored\";; ")
                   .append("*) echo \"$p|repair_failed\";; esac;; ");
            } else {
                cmd.append("*' stopped=true '*) echo \"$p|stopped\";; ");
            }
            cmd.append("*' stopped=false '*) echo \"$p|running\";; ")
               .append("*) echo \"$p|unknown\";; esac; ");
        }
        return cmd.toString();
    }
}
