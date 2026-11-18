package top.zesun.berryfcm;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.View;

public final class SettingsActivity extends Ui {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("设置", "主题、快捷操作与版本");
        LinearLayout style = card("外观", "选择主题后立即应用到所有页面。");
        RadioGroup theme = new RadioGroup(this);
        theme.setOrientation(RadioGroup.HORIZONTAL);
        for (String[] choice : new String[][]{
                {"system", "跟随系统"}, {"light", "浅色"}, {"dark", "深色"}}) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(choice[1]);
            b.setTextSize(13);
            b.setTextColor(ink);
            b.setGravity(android.view.Gravity.CENTER_VERTICAL);
            b.setMinHeight(dp(48));
            b.setTag(choice[0]);
            theme.addView(b, new LinearLayout.LayoutParams(0, dp(54), 1));
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

        LinearLayout quick = card("快捷设置", "默认不提供磁贴。完成授权后，可自行添加到系统快捷栏。");
        button(quick, "添加 FCM 快捷磁贴", this::addTile, false);
        if (FcmTileService.offered(this)) button(quick, "移除 FCM 快捷磁贴", () -> {
            FcmTileService.setOffered(this, false);
            recreate();
        }, false);
        line(quick, "授权与连接", "更换 Root / ADB 方式或重新配对", () -> go(AuthActivity.class));
        line(quick, "自动恢复", "恢复的是 FCM 后台监控和选中的应用守护，不管理其他软件的无障碍服务。", null);

        LinearLayout about = card("版本与更新", "在线更新只检查 GitHub Releases，不读取 Actions 测试包。");
        button(about, "检查新版本", () -> go(UpdateActivity.class), true);
        line(about, "项目主页", "GitHub · 源码与版本说明", () -> openUrl("https://github.com/jarod360/Berry-FCM-OS7"));
        line(about, "反馈问题", "GitHub Issues", () -> openUrl("https://github.com/jarod360/Berry-FCM-OS7/issues"));
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
        if (!PrivilegeAccess.readyForUserAction(this)) {
            error("请先完成授权，再添加磁贴");
            go(AuthActivity.class);
            return;
        }
        boolean alreadyOffered = FcmTileService.offered(this);
        FcmTileService.setOffered(this, true);
        if (Build.VERSION.SDK_INT < 33) {
            error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM");
            recreate();
            return;
        }
        StatusBarManager status = getSystemService(StatusBarManager.class);
        if (status == null) { error("请在系统下拉快捷栏的编辑页面手动添加 BerryFCM"); recreate(); return; }
        try { status.requestAddTileService(
                new ComponentName(this, FcmTileService.class),
                "BerryFCM 监控",
                Icon.createWithResource(this, R.drawable.ic_bf_fcm),
                getMainExecutor(),
                result -> {
                    boolean added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                    if (!added && !alreadyOffered) FcmTileService.setOffered(this, false);
                    error(added ? "磁贴已添加" : "未添加磁贴");
                    recreate();
                });
        } catch (RuntimeException ex) {
            if (!alreadyOffered) FcmTileService.setOffered(this, false);
            error("无法打开系统磁贴添加窗口");
        }
    }
}
