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

    static String command(Set<String> selected, boolean repair) {
        List<String> items = new ArrayList<>(selected);
        if (!items.contains(GMS)) items.add(GMS);
        Collections.sort(items);
        StringBuilder cmd = new StringBuilder();
        int done = 0;
        for (String pkg : items) {
            if (!PACKAGE.matcher(pkg).matches()) continue;
            if (++done > 10) break; // bounded work for background shell access
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
