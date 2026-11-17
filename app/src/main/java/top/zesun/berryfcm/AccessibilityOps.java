package top.zesun.berryfcm;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

    static boolean hasPersistentPermission(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static String readEnabledDirect(Context context) {
        ContentResolver resolver = context.getContentResolver();
        String enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int flag = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        return (enabled == null ? "" : enabled) + "\n" + flag;
    }

    static boolean applyDirect(Context context, Set<String> previouslyManaged, Set<String> selected) {
        if (!hasPersistentPermission(context)) throw new SecurityException("缺少 WRITE_SECURE_SETTINGS 授权");
        ContentResolver resolver = context.getContentResolver();
        String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String merged = mergeEnabled(current, previouslyManaged, selected);
        boolean servicesWritten = Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
        boolean flagWritten = Settings.Secure.putInt(
                resolver, Settings.Secure.ACCESSIBILITY_ENABLED, merged.isEmpty() ? 0 : 1);
        return servicesWritten && flagWritten;
    }

    static String mergeEnabled(String current, Set<String> previouslyManaged, Set<String> selected) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (current != null && !current.isEmpty() && !"null".equals(current)) {
            for (String component : current.split(":")) {
                if (!component.isEmpty() && !previouslyManaged.contains(component)) result.add(component);
            }
        }
        result.addAll(selected);
        return String.join(":", result);
    }

    static void verifyEnabled(String raw, Set<String> previouslyManaged, Set<String> selected) {
        String[] lines = raw == null ? new String[0] : raw.split("\\R", 2);
        Set<String> actual = parseComponents(lines.length == 0 ? "" : lines[0]);
        for (String component : selected) {
            if (!actual.contains(component)) throw new IllegalStateException("系统未确认目标服务：" + component);
        }
        for (String component : previouslyManaged) {
            if (!selected.contains(component) && actual.contains(component))
                throw new IllegalStateException("系统仍返回已取消的服务：" + component);
        }
        int expectedFlag = actual.isEmpty() ? 0 : 1;
        if (lines.length > 1) {
            try {
                int actualFlag = Integer.parseInt(lines[1].trim());
                if (actualFlag != expectedFlag) throw new IllegalStateException("无障碍总开关状态与服务列表不一致");
            } catch (NumberFormatException ignored) {
                throw new IllegalStateException("无法读取无障碍总开关状态");
            }
        }
    }

    private static Set<String> parseComponents(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) return result;
        for (String component : raw.trim().split(":")) if (!component.isEmpty()) result.add(component);
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
                + "if [ -n \"$new\" ]; then settings put secure accessibility_enabled 1; "
                + "else settings put secure accessibility_enabled 0; fi; "
                + "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled";
    }

    static String readEnabledCommand() {
        return "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled";
    }

    private static String join(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.join(":", sorted);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
