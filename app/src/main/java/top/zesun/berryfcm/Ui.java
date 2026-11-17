package top.zesun.berryfcm;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Common small native UI with explicit system-bar insets and independent screens. */
abstract class Ui extends Activity {
    protected AppPrefs prefs;
    protected LinearLayout content;
    private LinearLayout frame;
    private LinearLayout header;
    private LinearLayout nav;
    private boolean dark;
    protected int ink, muted, surface, panel, accent;

    @Override protected void onCreate(Bundle state) {
        AppPrefs initial = new AppPrefs(this);
        String requested = initial.theme();
        boolean useDark = "dark".equals(requested) || ("system".equals(requested)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES);
        if (useDark) setTheme(R.style.AppThemeDark);
        super.onCreate(state);
        prefs = new AppPrefs(this);
        String theme = prefs.theme();
        dark = "dark".equals(theme) || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                   == Configuration.UI_MODE_NIGHT_YES);
        ink = dark ? 0xfff0f3fa : 0xff19243a;
        muted = dark ? 0xffa6b2c7 : 0xff68768c;
        surface = dark ? 0xff0c1323 : 0xfff4f6fa;
        panel = dark ? 0xff172238 : Color.WHITE;
        accent = dark ? 0xff8ca8ff : 0xff365ed6;

        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        // Explicit edge-to-edge + insets avoids title overlapping OriginOS status bar.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(surface);
        header = new LinearLayout(this);
        header.setPadding(dp(20), dp(16), dp(20), dp(12));
        header.setOrientation(LinearLayout.VERTICAL);
        frame.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(content);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        nav = new LinearLayout(this);
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        nav.setBackgroundColor(panel);
        frame.addView(nav);
        buildNavigation();
        setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            header.setPadding(dp(20), top + dp(16), dp(20), dp(12));
            nav.setPadding(dp(5), dp(5), dp(5), bottom + dp(5));
            return insets;
        });
    }

    protected void title(String title, String description) {
        header.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = symbol(symbolFor(title), accent, 27);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(27), dp(27));
        iconParams.rightMargin = dp(11);
        row.addView(icon, iconParams);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 23, true, ink));
        if (description != null && !description.isEmpty()) {
            TextView caption = text(description, 12, false, muted);
            caption.setPadding(0, dp(5), 0, 0);
            words.addView(caption);
        }
        row.addView(words);
        header.addView(row);
    }

    private int symbolFor(String label) {
        if (label.contains("诊断")) return R.drawable.ic_bf_diagnostics;
        if (label.contains("更新") || label.contains("升级")) return R.drawable.ic_bf_update;
        if (label.contains("授权") || label.contains("配对")) return R.drawable.ic_bf_auth;
        if (label.contains("保活") || label.contains("电池") || label.contains("后台")) return R.drawable.ic_bf_battery;
        if (label.contains("守护")) return R.drawable.ic_bf_guard;
        if (label.contains("设置") || label.contains("主题")) return R.drawable.ic_bf_settings;
        if (label.contains("FCM") || label.contains("监控") || label.contains("连接")) return R.drawable.ic_bf_fcm;
        if (label.contains("概览") || label.contains("首页")) return R.drawable.ic_bf_home;
        return R.drawable.ic_bf_fcm;
    }
    private ImageView symbol(int drawable, int tint, int size) {
        ImageView image = new ImageView(this);
        image.setImageResource(drawable);
        image.setImageTintList(ColorStateList.valueOf(tint));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(null);
        return image;
    }
    protected TextView text(String value, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }
    protected LinearLayout card(String heading, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(17), dp(16), dp(16));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(panel);
        drawable.setCornerRadius(dp(18));
        box.setBackground(drawable);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(box, lp);
        if (heading != null) {
            box.addView(text(heading, 17, true, ink));
            if (description != null && !description.isEmpty()) {
                TextView desc = text(description, 12, false, muted);
                desc.setPadding(0, dp(7), 0, dp(9));
                box.addView(desc);
            }
        }
        return box;
    }
    protected Button button(LinearLayout box, String title, Runnable action, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : ink);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? accent : (dark ? 0xff2c3b58 : 0xffe9eef8));
        shape.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x55ffffff : 0x22000000),
                shape, null));
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.topMargin = dp(8);
        box.addView(b, params);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    protected void line(LinearLayout box, String label, String detail, Runnable action) {
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setPadding(dp(2), dp(11), dp(2), dp(11));
        ImageView icon = symbol(symbolFor(label), accent, 22);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
        ip.rightMargin = dp(12);
        entry.addView(icon, ip);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(label, 14, true, ink));
        if (detail != null && !detail.isEmpty()) {
            TextView note = text(detail, 12, false, muted);
            note.setPadding(0, dp(3), 0, 0);
            words.addView(note);
        }
        entry.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView arrow = text("›", 21, false, muted);
            arrow.setPadding(dp(8), 0, 0, 0);
            entry.addView(arrow);
        }
        box.addView(entry);
        if (action != null) {
            entry.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(dark ? 0x44ffffff : 0x15000000), null, null));
            entry.setOnClickListener(v -> action.run());
        }
    }
    protected int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density + .5f); }
    protected void go(Class<? extends Activity> page) { startActivity(new Intent(this, page)); }
    protected void error(String info) { android.widget.Toast.makeText(this, info, android.widget.Toast.LENGTH_LONG).show(); }

    private void buildNavigation() {
        Class<?>[] pages = {MainActivity.class, MonitorActivity.class, GuardActivity.class,
                BackgroundActivity.class, SettingsActivity.class};
        String[] names = {"概览", "FCM", "守护", "保活", "设置"};
        int[] icons = {R.drawable.ic_bf_home, R.drawable.ic_bf_fcm,
                R.drawable.ic_bf_guard, R.drawable.ic_bf_battery, R.drawable.ic_bf_settings};
        for (int i = 0; i < pages.length; i++) {
            Class<?> target = pages[i];
            boolean selected = target.isInstance(this);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView image = symbol(icons[i], selected ? accent : muted, 22);
            item.addView(image, new LinearLayout.LayoutParams(dp(21), dp(21)));
            TextView label = text(names[i], 11, selected, selected ? accent : muted);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            item.addView(label);
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(56), 1));
            item.setOnClickListener(v -> {
                if (!target.isInstance(this)) {
                    Intent intent = new Intent(this, target.asSubclass(Activity.class));
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(intent);
                }
            });
        }
    }

}
