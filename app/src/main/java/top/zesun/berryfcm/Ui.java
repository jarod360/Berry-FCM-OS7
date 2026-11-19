package top.zesun.berryfcm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
import android.widget.Switch;
import android.widget.TextView;

/** Common small native UI with explicit system-bar insets and independent screens. */
abstract class Ui extends Activity {
    protected AppPrefs prefs;
    protected LinearLayout content;
    private LinearLayout frame;
    private LinearLayout header;
    private LinearLayout nav;
    private LinearLayout navContainer;
    private boolean dark;
    private boolean secondaryPage;
    private boolean syncingSwitches;
    protected int success, warning, danger;
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
        success = dark ? 0xff83d5a5 : 0xff187747;
        warning = dark ? 0xffffcd80 : 0xff935a00;
        danger = dark ? 0xffffa6a7 : 0xffb3261e;

        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        // Explicit edge-to-edge + insets avoids title overlapping OriginOS status bar.
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        secondaryPage = !primaryPage(getClass());
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
        navContainer = new LinearLayout(this);
        navContainer.setPadding(dp(14), dp(6), dp(14), dp(8));
        nav = new LinearLayout(this);
        GradientDrawable capsule = new GradientDrawable();
        capsule.setColor(panel);
        capsule.setCornerRadius(dp(28));
        capsule.setStroke(dp(1), dark ? 0xff324361 : 0xffe0e6f1);
        nav.setBackground(capsule);
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        navContainer.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        frame.addView(navContainer);
        if (secondaryPage) navContainer.setVisibility(View.GONE);
        else buildNavigation();
        setContentView(frame);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            frame.setPadding(left, 0, right, secondaryPage ? bottom : 0);
            header.setPadding(dp(20), top + dp(12), dp(20), dp(9));
            navContainer.setPadding(dp(14), dp(6), dp(14), bottom + dp(8));
            return insets;
        });
    }

    protected void title(String title, String description) {
        header.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (secondaryPage) {
            TextView back = text("‹", 32, false, ink);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("返回上一页");
            back.setFocusable(true);
            back.setClickable(true);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48), dp(48));
            bp.rightMargin = dp(4);
            row.addView(back, bp);
            back.setOnClickListener(v -> finish());
        } else {
            ImageView icon = symbol(symbolFor(title), accent, 27);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(27), dp(27));
            iconParams.rightMargin = dp(11);
            row.addView(icon, iconParams);
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 22, true, ink));
        if (description != null && !description.isEmpty()) {
            TextView caption = text(description, 12, false, muted);
            caption.setPadding(0, dp(3), 0, 0);
            words.addView(caption);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(row);
    }
    private int symbolFor(String label) {
        // Match intent before generic keywords: settings links must not all
        // fall back to the FCM glyph or overload the battery symbol.
        if (label.contains("项目主页")) return R.drawable.ic_bf_code;
        if (label.contains("反馈问题")) return R.drawable.ic_bf_feedback;
        if (label.contains("当前版本")) return R.drawable.ic_bf_info;
        if (label.contains("适配范围")) return R.drawable.ic_bf_device;
        if (label.contains("GitHub")) return R.drawable.ic_bf_code;
        if (label.contains("豁免") || label.contains("休眠")) return R.drawable.ic_bf_moon;
        if (label.contains("恢复原有后台设置")) return R.drawable.ic_bf_refresh;
        if (label.contains("计量流量") || label.contains("后台流量")) return R.drawable.ic_bf_network;
        if (label.contains("诊断")) return R.drawable.ic_bf_diagnostics;
        if (label.contains("更新") || label.contains("升级")) return R.drawable.ic_bf_update;
        if (label.contains("授权") || label.contains("配对")) return R.drawable.ic_bf_auth;
        if (label.contains("电池")) return R.drawable.ic_bf_battery;
        if (label.contains("保活") || label.contains("后台")) return R.drawable.ic_bf_heartbeat;
        if (label.contains("守护")) return R.drawable.ic_bf_guard;
        if (label.contains("设置") || label.contains("主题")) return R.drawable.ic_bf_settings;
        if (label.contains("FCM") || label.contains("监控") || label.contains("连接")) return R.drawable.ic_bf_fcm;
        if (label.contains("概览") || label.contains("首页")) return R.drawable.ic_bf_home;
        return R.drawable.ic_bf_info;
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
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(panel);
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), dark ? 0xff28354c : 0xffe4e9f2);
        box.setBackground(drawable);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(box, lp);
        if (heading != null) {
            box.addView(text(heading, 16, true, ink));
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
        b.setTextColor(primary ? (dark ? 0xff0c1323 : Color.WHITE) : ink);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? accent : (dark ? 0xff2c3b58 : 0xffe9eef8));
        shape.setCornerRadius(dp(14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x55ffffff : 0x22000000),
                shape, null));
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setMinHeight(dp(52));
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        int iconId = actionIconFor(title);
        if (iconId != 0) {
            Drawable icon = getDrawable(iconId);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(primary ? (dark ? 0xff0c1323 : Color.WHITE) : ink);
                icon.setBounds(0, 0, dp(18), dp(18));
                b.setCompoundDrawablePadding(dp(8));
                b.setCompoundDrawablesRelative(icon, null, null, null);
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        box.addView(b, params);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private int actionIconFor(String label) {
        if (label.equals("选择应用") || label.equals("添加守护应用")) return R.drawable.ic_bf_add;
        if (label.equals("检查新版本")) return R.drawable.ic_bf_update;
        if (label.equals("刷新候选应用") || label.equals("检查所选应用")
                || label.equals("检测 mtalk.google.com 连接")
                || label.equals("恢复已停止的应用")) return R.drawable.ic_bf_refresh;
        return 0;
    }
    protected void line(LinearLayout box, String label, String detail, Runnable action) {
        line(box, label, detail, symbolFor(label), action);
    }

    protected void line(LinearLayout box, String label, String detail, int iconRes, Runnable action) {
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setPadding(dp(4), dp(13), dp(4), dp(13));
        ImageView icon = symbol(iconRes, accent, 22);
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
            ImageView arrow = symbol(R.drawable.ic_bf_chevron, muted, 18);
            arrow.setRotation(-90f);
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(18), dp(18));
            arrowParams.leftMargin = dp(10);
            entry.addView(arrow, arrowParams);
        }
        box.addView(entry);
        if (action != null) {
            entry.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(dark ? 0x44ffffff : 0x15000000), null, null));
            entry.setFocusable(true);
            entry.setMinimumHeight(dp(48));
            entry.setOnClickListener(v -> action.run());
        }
    }
    /** Progressive disclosure for infrequent or technical controls. */
    protected LinearLayout disclosure(LinearLayout parent, String caption, boolean expanded) {
        LinearLayout trigger = new LinearLayout(this);
        trigger.setGravity(Gravity.CENTER_VERTICAL);
        trigger.setPadding(dp(2), dp(7), dp(2), dp(7));
        trigger.setMinimumHeight(dp(48));
        trigger.setFocusable(true);
        trigger.setBackground(new RippleDrawable(ColorStateList.valueOf(
                dark ? 0x33ffffff : 0x12000000), null, null));
        TextView heading = text(caption, 16, true, ink);
        trigger.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = symbol(R.drawable.ic_bf_chevron, accent, 22);
        arrow.setRotation(expanded ? 180f : 0f);
        trigger.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(34)));
        trigger.setContentDescription(caption + (expanded ? "，点击收起" : "，点击展开"));
        parent.addView(trigger);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        parent.addView(details);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        trigger.setOnClickListener(v -> {
            boolean opening = details.getVisibility() != View.VISIBLE;
            details.setVisibility(opening ? View.VISIBLE : View.GONE);
            arrow.setRotation(opening ? 180f : 0f);
            trigger.setContentDescription(caption + (opening ? "，点击收起" : "，点击展开"));
        });
        return details;
    }

    protected void factRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView name = text(label, 13, false, muted);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(value, 13, true, ink);
        detail.setGravity(Gravity.END);
        detail.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        detail.setMaxLines(3);
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1.2f));
        parent.addView(row);
    }
    /** One consistent, themed confirmation surface; cancellation never executes a privileged action. */
    protected void confirmAction(String heading, String message, String actionText, Runnable action) {
        confirmAction(heading, message, actionText, action, null);
    }
    protected void confirmAction(String heading, String message, String actionText,
                                 Runnable action, Runnable onCancel) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(16));
        body.addView(text(heading, 18, true, ink));
        TextView detail = text(message, 16, false, muted);
        detail.setPadding(0, dp(12), 0, dp(16));
        body.addView(detail);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END);
        Button cancel = dialogButton("取消", false);
        Button accept = dialogButton(actionText, true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(50), 1);
        cp.rightMargin = dp(8);
        controls.addView(cancel, cp);
        controls.addView(accept, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(controls);
        // Keep the action row reachable in landscape or with large system fonts.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();
        dialog.setOnCancelListener(d -> { if (onCancel != null) onCancel.run(); });
        cancel.setOnClickListener(v -> dialog.cancel());
        accept.setOnClickListener(v -> {
            dialog.dismiss();
            if (!isFinishing() && !isDestroyed()) action.run();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(panel);
            shape.setCornerRadius(dp(22));
            dialog.getWindow().setBackgroundDrawable(shape);
        }
    }
    private Button dialogButton(String caption, boolean emphasized) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(caption);
        b.setTextSize(14);
        b.setTextColor(emphasized ? (dark ? 0xff0c1323 : Color.WHITE) : ink);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(emphasized ? accent : (dark ? 0xff2c3b58 : 0xffe9eef8));
        bg.setCornerRadius(dp(13));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(
                dark ? 0x55ffffff : 0x22000000), bg, null));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    protected int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density + .5f); }
    private static boolean primaryPage(Class<?> type) {
        return type == MainActivity.class || type == MonitorActivity.class
                || type == GuardActivity.class || type == SettingsActivity.class;
    }
    protected void go(Class<? extends Activity> page) {
        if (page.isInstance(this) || isFinishing() || isDestroyed()) return;
        Intent intent = new Intent(this, page);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        // Keep overview as the only root, not a stack of every selected tab.
        if (primaryPage(page) && primaryPage(getClass()) && !(this instanceof MainActivity)) finish();
    }
    protected void syncChecked(Switch view, boolean value) {
        if (view == null || view.isChecked() == value) return;
        boolean previous = syncingSwitches;
        syncingSwitches = true;
        try { view.setChecked(value); }
        finally { syncingSwitches = previous; }
    }
    protected boolean bindingSwitches() { return syncingSwitches; }
    protected int statusColor(MonitorStatus.State state) {
        switch (state) {
            case CONNECTED: return success;
            case DISCONNECTED: case FAILED: return danger;
            case CONNECTING: case AUTH_REQUIRED: return warning;
            default: return muted;
        }
    }
    protected void error(String info) {
        if (!isFinishing() && !isDestroyed())
            android.widget.Toast.makeText(this, info, android.widget.Toast.LENGTH_LONG).show();
    }
    @Override protected void onResume() {
        super.onResume();
        String theme = prefs.theme();
        boolean desiredDark = "dark".equals(theme) || ("system".equals(theme)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES);
        if (desiredDark != dark) recreate();
    }

    private void buildNavigation() {
        Class<?>[] pages = {MainActivity.class, MonitorActivity.class,
                GuardActivity.class, SettingsActivity.class};
        String[] names = {"概览", "监控", "守护", "设置"};
        int[] icons = {R.drawable.ic_bf_home, R.drawable.ic_bf_fcm,
                R.drawable.ic_bf_guard, R.drawable.ic_bf_settings};
        for (int i = 0; i < pages.length; i++) {
            Class<?> target = pages[i];
            boolean selected = target.isInstance(this);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(56));
            item.setClickable(true);
            item.setFocusable(true);
            item.setSelected(selected);
            item.setContentDescription(names[i] + (selected ? "，当前页面" : ""));
            if (selected) {
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(22));
                pill.setColor(dark ? 0xff2b3b61 : 0xffe5ecff);
                item.setBackground(pill);
            } else {
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x44ffffff : 0x15000000), null, null));
            }
            ImageView image = symbol(icons[i], selected ? accent : muted, 22);
            item.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = text(names[i], 13, selected, selected ? accent : muted);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            item.addView(label);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(56), 1);
            itemParams.leftMargin = dp(2);
            itemParams.rightMargin = dp(2);
            nav.addView(item, itemParams);
            item.setOnClickListener(v -> go(target.asSubclass(Activity.class)));
        }
    }

}
