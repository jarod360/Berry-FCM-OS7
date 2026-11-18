package top.zesun.berryfcm;

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
                String chosen = String.valueOf(b.getTag());
                if (!chosen.equals(prefs.theme())) {
                    prefs.theme(chosen);
                    recreate();
                }
            }
        });
        style.addView(theme);

        LinearLayout quick = card("权限与后台", null);
        line(quick, "后台保活", "监控与守护任务", () -> go(BackgroundActivity.class));
        line(quick, "授权管理", "核心授权、辅助权限、通知和快捷磁贴", () -> go(AuthActivity.class));

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
}
