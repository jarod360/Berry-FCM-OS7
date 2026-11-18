package top.zesun.berryfcm;

import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateActivity extends Ui {
    private static final String REPO = "https://github.com/jarod360/Berry-FCM-OS7";
    private static final String API = "https://api.github.com/repos/jarod360/Berry-FCM-OS7/releases?per_page=20";
    private static final String CERT_SHA256 = "BB328497280F72FE6662E84D7D0D0B15BAF425872B9B360287ED120529D97615";
    private static final Pattern NUMBER = Pattern.compile("alpha[.-]([0-9]+)\\.apk$");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean checkedOnOpen, resumed, checking, verifying;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable watchDownload = this::pollDownload;
    private TextView details, notes;
    private Button download, install;
    private String apkUrl, apkName, digest;
    private int remoteVersion;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        title("版本更新", "应用内下载并安装新版");
        LinearLayout panel = card("检查更新", "只从官方 GitHub Releases 下载；安装需系统确认。");
        details = text("当前版本：0.1.0-alpha." + localVersion() + "\n尚未检查更新", 14, false, ink);
        panel.addView(details);
        button(panel, "检查新版本", this::checkRelease, true);
        download = button(panel, "立即更新", this::downloadApk, true);
        download.setVisibility(View.GONE);
        install = button(panel, "继续安装", this::verifyInstall, false);
        install.setVisibility(getPreferences(MODE_PRIVATE).getLong("id", 0) > 0 ? View.VISIBLE : View.GONE);
        LinearLayout releaseNotes = disclosure(panel, "版本更新说明", false);
        notes = text("尚无更新说明", 12, false, muted);
        notes.setTextIsSelectable(true);
        notes.setPadding(0, dp(16), 0, 0);
        releaseNotes.addView(notes);
        LinearLayout help = card("更新来源", "测试版 Actions Artifact 不属于正式在线更新。");
        line(help, "查看 GitHub Releases", "官方已发布版本", () -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(REPO + "/releases"))));
    }

    private int localVersion() {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception error) { return 0; }
    }
    private void checkRelease() {
        if (checking || verifying || getPreferences(MODE_PRIVATE).getBoolean("auto_install", false)) return;
        checking = true;
        apkUrl = null;
        apkName = null;
        remoteVersion = 0;
        download.setVisibility(View.GONE);
        details.setText("正在查询 GitHub Releases…");
        worker.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "BerryFCM");
                if (conn.getResponseCode() != 200)
                    throw new IllegalStateException("GitHub API HTTP " + conn.getResponseCode());
                byte[] content;
                try (InputStream in = conn.getInputStream();
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (bytes.size() + read > 200000)
                            throw new IllegalStateException("GitHub Release 响应超过大小上限");
                        bytes.write(buffer, 0, read);
                    }
                    content = bytes.toByteArray();
                }
                JSONArray releases = new JSONArray(new String(content, StandardCharsets.UTF_8));
                int best = 0;
                JSONObject asset = null, release = null;
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject item = releases.getJSONObject(i);
                    if (item.optBoolean("draft", false)) continue;
                    JSONArray assets = item.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject a = assets.getJSONObject(j);
                        String name = a.optString("name", "");
                        Matcher matcher = NUMBER.matcher(name);
                        boolean compatible = name.startsWith("BerryFCM-Guardian-v")
                                || name.startsWith("BerryFCM-v");
                        if (!compatible || !matcher.find()) continue;
                        int n = Integer.parseInt(matcher.group(1));
                        String url = a.optString("browser_download_url", "");
                        if ((n > best || (n == best && name.startsWith("BerryFCM-v")))
                                && url.startsWith(REPO + "/releases/download/")) {
                            best = n;
                            asset = a;
                            release = item;
                        }
                    }
                }
                JSONObject selected = asset, current = release;
                int releaseNumber = best;
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    if (selected == null) {
                        details.setText("尚无正式签名的 Release APK；当前版本号：" + localVersion());
                        notes.setText("Actions 构建产物不作为自动更新来源。");
                        download.setVisibility(View.GONE);
                        return;
                    }
                    remoteVersion = releaseNumber;
                    apkUrl = selected.optString("browser_download_url");
                    apkName = selected.optString("name");
                    digest = selected.optString("digest", "");
                    boolean newer = remoteVersion > localVersion();
                    details.setText("当前版本号：" + localVersion()
                            + "\n最新 Release：" + current.optString("tag_name")
                            + "\nRelease 版本号：" + remoteVersion
                            + "\n" + (newer ? "发现新版本" : "已是最新版本或本机版本更高"));
                    notes.setText("更新说明\n" + current.optString("body", "暂无更新说明"));
                    download.setVisibility(newer ? View.VISIBLE : View.GONE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    checking = false;
                    if (isDestroyed() || isFinishing()) return;
                    details.setText("检查更新失败：" + error.getMessage());
                    download.setVisibility(View.GONE);
                });
            } finally { if (conn != null) conn.disconnect(); }
        });
    }

    private void downloadApk() {
        if (checking || verifying || getPreferences(MODE_PRIVATE).getBoolean("auto_install", false)
                || apkName == null || apkUrl == null || remoteVersion <= localVersion()) return;
        if (!apkName.matches("[a-zA-Z0-9._-]+\\.apk")) {
            details.setText("文件名称不安全，拒绝下载"); return;
        }
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("下载目录不可用");
            DownloadManager dm = getSystemService(DownloadManager.class);
            long oldId = getPreferences(MODE_PRIVATE).getLong("id", 0);
            if (oldId > 0) dm.remove(oldId);
            File existing = new File(dir, apkName);
            if (existing.exists() && !existing.delete()) throw new IllegalStateException("旧下载文件无法清理");
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setTitle(apkName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, apkName);
            long id = dm.enqueue(req);
            if (!getPreferences(MODE_PRIVATE).edit().putLong("id", id)
                    .putString("name", apkName).putString("digest", digest)
                    .putInt("version", remoteVersion).putBoolean("auto_install", true)
                    .remove("waiting_install_permission").commit()) {
                dm.remove(id);
                throw new IllegalStateException("无法保存下载记录");
            }
            download.setVisibility(View.GONE);
            install.setVisibility(View.GONE);
            details.setText("正在下载新版，完成后自动校验并打开系统安装窗口。");
            ui.removeCallbacks(watchDownload);
            ui.post(watchDownload);
        } catch (Exception error) { details.setText("下载失败：" + error.getMessage()); }
    }

    private void pollDownload() {
        if (!resumed || verifying) return;
        SharedPreferences state = getPreferences(MODE_PRIVATE);
        if (!state.getBoolean("auto_install", false)) return;
        long id = state.getLong("id", 0);
        try {
            DownloadManager dm = getSystemService(DownloadManager.class);
            try (android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c == null || !c.moveToFirst()) throw new IllegalStateException("下载记录不存在");
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    verifyInstall();
                    return;
                }
                if (status == DownloadManager.STATUS_FAILED)
                    throw new IllegalStateException("系统下载失败，原因代码 " + c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                details.setText("正在下载新版" + (total > 0 ? " · " + Math.min(100, done * 100 / total) + "%" : "")
                        + "\n下载完成后自动校验安装。");
            }
            ui.postDelayed(watchDownload, 1000);
        } catch (Exception ex) {
            state.edit().remove("auto_install").remove("waiting_install_permission").apply();
            details.setText("下载失败：" + ex.getMessage());
            download.setVisibility(apkUrl != null && remoteVersion > localVersion() ? View.VISIBLE : View.GONE);
        }
    }

    private void verifyInstall() {
        if (verifying) return;
        verifying = true;
        ui.removeCallbacks(watchDownload);
        install.setVisibility(View.GONE);
        details.setText("正在校验下载状态、包名、版本号与签名…");
        worker.execute(() -> {
            try {
                SharedPreferences state = getPreferences(MODE_PRIVATE);
                long id = state.getLong("id", 0);
                String name = state.getString("name", "");
                if (id == 0 || !name.matches("[a-zA-Z0-9._-]+\\.apk"))
                    throw new IllegalStateException("没有有效下载记录");
                DownloadManager dm = getSystemService(DownloadManager.class);
                try (android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c == null || !c.moveToFirst()) throw new IllegalStateException("下载记录不存在");
                    if (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            != DownloadManager.STATUS_SUCCESSFUL)
                        throw new IllegalStateException("下载尚未成功，请稍后重试");
                }
                File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                if (!file.isFile() || file.length() < 1024) throw new IllegalStateException("下载 APK 缺失或不完整");
                String expected = state.getString("digest", "");
                if (expected.startsWith("sha256:")) {
                    String actual = hex(hashFile(file));
                    if (!actual.equalsIgnoreCase(expected.substring(7)))
                        throw new SecurityException("下载 SHA256 与 GitHub Release 不一致");
                }
                int flags = Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                PackageInfo info = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), flags);
                if (info == null || !getPackageName().equals(info.packageName))
                    throw new SecurityException("包名不一致");
                long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                if (code <= localVersion()) throw new IllegalStateException("APK 版本号没有升级");
                int expectedVersion = state.getInt("version", 0);
                if (expectedVersion > 0 && code != expectedVersion)
                    throw new SecurityException("APK 版本与选择的 Release 不一致");
                Signature[] signers = Build.VERSION.SDK_INT >= 28
                        ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
                        : info.signatures;
                if (signers == null || signers.length != 1
                        || !hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))
                                .equalsIgnoreCase(CERT_SHA256))
                    throw new SecurityException("APK 签名证书与当前 BerryFCM 固定证书不一致");

                Uri uri = dm.getUriForDownloadedFile(id);
                if (uri == null) throw new IllegalStateException("系统无法提供安装 URI");
                runOnUiThread(() -> {
                    verifying = false;
                    if (isDestroyed() || isFinishing()) return;
                    install.setVisibility(View.VISIBLE);
                    if (!resumed) return; // do not open an installer from the background
                    state.edit().remove("auto_install").apply();
                    try {
                        if (Build.VERSION.SDK_INT >= 26
                                && !getPackageManager().canRequestPackageInstalls()) {
                            state.edit().putBoolean("waiting_install_permission", true).apply();
                            details.setText("签名已通过。首次更新请允许 BerryFCM 安装应用，返回后自动继续。");
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                            return;
                        }
                        state.edit().remove("waiting_install_permission").apply();
                        details.setText("校验通过，请在系统安装窗口确认更新。");
                        Intent intent = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(intent);
                    } catch (Exception error) {
                        details.setText("系统安装器无法打开：" + error.getMessage());
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    verifying = false;
                    if (isDestroyed() || isFinishing()) return;
                    getPreferences(MODE_PRIVATE).edit().remove("auto_install").remove("waiting_install_permission").apply();
                    details.setText("安装校验未通过：" + error.getMessage());
                    install.setVisibility(View.VISIBLE);
                    if (apkUrl != null && remoteVersion > localVersion()) download.setVisibility(View.VISIBLE);
                });
            }
        });
    }
    private static byte[] hashFile(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = in.read(bytes)) > 0) md.update(bytes, 0, n);
        }
        return md.digest();
    }
    private static String hex(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (byte b : data) s.append(String.format(Locale.ROOT, "%02X", b & 255));
        return s.toString();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        SharedPreferences state = getPreferences(MODE_PRIVATE);
        int version = state.getInt("version", 0);
        if (version > 0 && version <= localVersion()) {
            long id = state.getLong("id", 0);
            if (id > 0) { try { getSystemService(DownloadManager.class).remove(id); } catch (RuntimeException ignored) {} }
            state.edit().clear().apply();
            install.setVisibility(View.GONE);
        }
        if (state.getBoolean("waiting_install_permission", false)) {
            state.edit().remove("waiting_install_permission").apply();
            if (getPackageManager().canRequestPackageInstalls()) verifyInstall();
            else { details.setText("尚未允许安装应用，可点击继续安装重试。"); install.setVisibility(View.VISIBLE); }
        } else if (state.getBoolean("auto_install", false)) {
            ui.removeCallbacks(watchDownload);
            ui.post(watchDownload);
        } else if (!checkedOnOpen) {
            checkedOnOpen = true;
            checkRelease();
        }
    }
    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(watchDownload);
        super.onPause();
    }
    @Override protected void onDestroy() { ui.removeCallbacks(watchDownload); worker.shutdownNow(); super.onDestroy(); }
}
