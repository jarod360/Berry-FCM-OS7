package top.zesun.berryfcm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GuardActivity extends Ui {
    private static final int PAGE_SIZE = 40;
    private static final class App {
        final String pkg, label;
        final boolean system;
        App(String pkg, String label, boolean system) {
            this.pkg = pkg; this.label = label; this.system = system;
        }
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<App> apps = new ArrayList<>();
    private EditText filter;
    private CheckBox showSystem;
    private LinearLayout entries;
    private TextView count, report;
    private int shown;
    private boolean loading;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("应用守护", "已安装应用扫描 · 多选 · stopped 状态修复");
        LinearLayout actions = card("自动守护", "仅对已选择的应用执行巡检；Google Play 服务是默认核心目标。");
        CheckBox running = new CheckBox(this);
        running.setText("启用所选应用自动守护（约 5 分钟巡检）");
        running.setTextColor(ink);
        running.setChecked(prefs.guardEnabled());
        actions.addView(running);
        running.setOnCheckedChangeListener((view, value) -> {
            prefs.guardEnabled(value);
            if (value) FcmMonitorService.start(this);
            else if (!prefs.monitorEnabled()) FcmMonitorService.stop(this);
        });
        button(actions, "检查所选应用的 stopped 状态", () -> runCheck(false), false);
        button(actions, "尝试修复所选应用", () -> runCheck(true), true);
        report = text("尚未检查", 12, false, muted);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(7), 0, 0);
        actions.addView(report);

        LinearLayout select = card("选择守护应用", "点击勾选即保存，已选择应用优先显示。可以搜索应用名称或包名。");
        filter = new EditText(this);
        filter.setSingleLine(true);
        filter.setHint("搜索应用或包名");
        filter.setTextColor(ink);
        filter.setHintTextColor(muted);
        select.addView(filter);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render(true); }
            @Override public void afterTextChanged(Editable e) {}
        });
        showSystem = new CheckBox(this);
        showSystem.setText("显示系统应用");
        showSystem.setTextColor(ink);
        showSystem.setChecked(prefs.showSystemApps());
        select.addView(showSystem);
        showSystem.setOnCheckedChangeListener((view, checked) -> {
            prefs.showSystemApps(checked);
            render(true);
        });
        count = text("正在读取已安装应用…", 12, false, muted);
        select.addView(count);
        entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        select.addView(entries);
        button(select, "显示更多应用", () -> { shown += PAGE_SIZE; render(false); }, false);
        button(select, "刷新安装应用列表", this::load, false);
        load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        count.setText("正在扫描已安装应用…");
        worker.execute(() -> {
            List<App> found = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    String label = String.valueOf(info.loadLabel(pm));
                    boolean system = (info.flags
                            & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    found.add(new App(info.packageName, label, system));
                }
                runOnUiThread(() -> {
                    apps.clear();
                    apps.addAll(found);
                    loading = false;
                    render(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading = false;
                    count.setText("扫描失败：" + e.getMessage());
                });
            }
        });
    }

    private void render(boolean resetPage) {
        if (entries == null || showSystem == null || filter == null) return;
        if (resetPage) shown = PAGE_SIZE;
        Set<String> selected = prefs.guardTargets();
        selected.add(GuardOps.GMS);
        String query = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        List<App> filtered = new ArrayList<>();
        for (App a : apps) {
            if (a.system && !showSystem.isChecked() && !selected.contains(a.pkg)) continue;
            if (!query.isEmpty() && !(a.label + " " + a.pkg)
                    .toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            filtered.add(a);
        }
        filtered.sort(Comparator.<App>comparingInt(a -> selected.contains(a.pkg) ? 0 : 1)
                .thenComparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
        entries.removeAllViews();
        for (int i = 0; i < Math.min(shown, filtered.size()); i++) {
            App app = filtered.get(i);
            CheckBox checkbox = new CheckBox(this);
            checkbox.setText(app.label + (app.system ? " · 系统" : "") + "\n" + app.pkg);
            checkbox.setTextSize(12);
            checkbox.setTextColor(ink);
            checkbox.setPadding(0, dp(5), 0, dp(6));
            checkbox.setChecked(selected.contains(app.pkg));
            if (GuardOps.GMS.equals(app.pkg)) checkbox.setEnabled(false);
            else checkbox.setOnCheckedChangeListener((button, checked) -> {
                Set<String> targets = prefs.guardTargets();
                if (checked) targets.add(app.pkg);
                else targets.remove(app.pkg);
                prefs.guardTargets(targets);
                // Re-sort immediately: checked applications remain above unchecked ones.
                render(false);
            });
            entries.addView(checkbox);
        }
        count.setText("守护目标：" + selected.size() + " · 共 " + filtered.size()
                + " 个匹配应用 · 已显示 " + Math.min(shown, filtered.size()));
    }

    private void runCheck(boolean fix) {
        report.setText(fix ? "正在检查并尝试修复…" : "正在检查 stopped 状态…");
        worker.execute(() -> {
            try (PrivilegeShell shell = PrivilegeAccess.open(this)) {
                String result = shell.execute(GuardOps.command(prefs.guardTargets(), fix));
                getSharedPreferences("berry_fcm_guard_report", MODE_PRIVATE)
                        .edit().putString("report", result).apply();
                runOnUiThread(() -> report.setText(result));
            } catch (Exception e) {
                runOnUiThread(() -> report.setText("操作失败：" + e.getMessage()));
            }
        });
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
