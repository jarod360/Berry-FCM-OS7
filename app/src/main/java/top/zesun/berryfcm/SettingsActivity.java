package top.zesun.berryfcm;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

public final class SettingsActivity extends Ui {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("设置与关于", "外观 · 快捷操作 · 版本更新");
        LinearLayout style = card("外观主题", "选择后立即应用到各个页面。");
        RadioGroup theme = new RadioGroup(this);
        theme.setOrientation(RadioGroup.VERTICAL);
        for (String[] choice : new String[][]{
                {"system", "跟随系统"}, {"light", "浅色"}, {"dark", "深色"}}) {
            RadioButton b = new RadioButton(this);
            b.setText(choice[1]);
            b.setTextColor(ink);
            b.setTag(choice[0]);
            theme.addView(b);
            if (prefs.theme().equals(choice[0])) b.setChecked(true);
        }
        theme.setOnCheckedChangeListener((group, id) -> {
            RadioButton b = group.findViewById(id);
            if (b != null) {
                prefs.theme(String.valueOf(b.getTag()));
                recreate();
            }
        });
        style.addView(theme);

        LinearLayout quick = card("下拉快捷设置", "由系统确认添加 BerryFCM 磁贴。监控开启时点击执行一次检查；监控关闭则打开监控页。");
        button(quick, "请求添加 BerryFCM FCM 监控磁贴", this::addTile, true);
        line(quick, "自动恢复", "恢复的是 FCM 后台监控和选中的应用守护，不管理其他软件的无障碍服务。", null);

        LinearLayout about = card("项目与升级", "正式更新来源是此项目 GitHub Releases。Actions Artifact 不等于 Release。");
        button(about, "检查在线更新", () -> go(UpdateActivity.class), true);
        button(about, "查看项目主页", () -> openUrl("https://github.com/jarod360/Berry-FCM-OS7"), false);
        button(about, "查看问题反馈 / Issues", () -> openUrl("https://github.com/jarod360/Berry-FCM-OS7/issues"), false);
        String version = "未知";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        line(about, "当前版本", version + " · 已签名安装包可覆盖更新", null);
    }

    private void openUrl(String url) {
        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)));
    }
    private void addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM");
            return;
        }
        StatusBarManager status = getSystemService(StatusBarManager.class);
        if (status == null) { error("当前系统不支持直接请求添加磁贴"); return; }
        status.requestAddTileService(
                new ComponentName(this, FcmTileService.class),
                "BerryFCM 监控",
                Icon.createWithResource(this, android.R.drawable.stat_notify_sync),
                getMainExecutor(),
                result -> error(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                        ? "磁贴已添加" : "系统未确认添加，可到快捷设置编辑页手动添加"));
    }
}
