package top.zesun.berryfcm;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class AccessibilityOps {
    static final class Entry {
        final String component;
        final String label;
        Entry(String component, String label) { this.component = component; this.label = label; }
    }

    static List<Entry> discover(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(AccessibilityService.SERVICE_INTERFACE);
        List<ResolveInfo> matches;
        if (Build.VERSION.SDK_INT >= 33) {
            matches = pm.queryIntentServices(query, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA));
        } else {
            //noinspection deprecation
            matches = pm.queryIntentServices(query, PackageManager.GET_META_DATA);
        }
        List<Entry> result = new ArrayList<>();
        for (ResolveInfo info : matches) {
            ServiceInfo service = info.serviceInfo;
            if (service == null || !"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission)) continue;
            String component = new ComponentName(service.packageName, service.name).flattenToString();
            CharSequence label = service.loadLabel(pm);
            result.add(new Entry(component, label == null ? service.packageName : label.toString()));
        }
        Collections.sort(result, Comparator.comparing(e -> e.label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static String applyCommand(Set<String> previouslyManaged, Set<String> selected) {
        String old = join(previouslyManaged);
        String add = join(selected);
        String awk = "awk -v old=" + quote(old) + " -v add=" + quote(add) + " -v current=\"$cur\" 'BEGIN {"
                + "n=split(old,o,\":\"); for(i=1;i<=n;i++) if(o[i]!=\"\") remove[o[i]]=1;"
                + "n=split(current,c,\":\"); for(i=1;i<=n;i++) if(c[i]!=\"\" && c[i]!=\"null\" && !remove[c[i]] && !seen[c[i]]++) list[++count]=c[i];"
                + "n=split(add,a,\":\"); for(i=1;i<=n;i++) if(a[i]!=\"\" && !seen[a[i]]++) list[++count]=a[i];"
                + "for(i=1;i<=count;i++){if(i>1) printf \":\"; printf \"%s\",list[i]} printf \"\\n\"}'";
        return "cur=$(settings get secure enabled_accessibility_services 2>/dev/null); "
                + "new=$(printf '' | " + awk + "); "
                + "settings put secure enabled_accessibility_services \"$new\"; "
                + "if [ -n \"$new\" ]; then settings put secure accessibility_enabled 1; fi; "
                + "settings get secure enabled_accessibility_services";
    }

    static String readEnabledCommand() {
        return "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled";
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(':');
            out.append(value);
        }
        return out.toString();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
