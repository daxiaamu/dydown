package com.daxiaamu.dydown;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A tap starts one transactional, cancellable save; only validated media is published. */
public final class VideoSaver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    public static void save(Activity activity, VideoSource source, Consumer<String> logger) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> save(activity, source, logger)); return; }
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!ACTIVE.add(source.id)) { Toast.makeText(activity, "此视频正在保存", Toast.LENGTH_SHORT).show(); return; }
        Context context = activity.getApplicationContext();
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setTitle("保存视频"); dialog.setMessage("正在连接…");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL); dialog.setIndeterminate(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消", (d, w) -> cancelled.set(true));
        dialog.setOnCancelListener(d -> cancelled.set(true));
        try { dialog.show(); }
        catch (RuntimeException e) { ACTIVE.remove(source.id); logger.accept("Save screen unavailable"); return; }
        logger.accept("Save started: id=" + source.id + ", candidates=" + source.urls.size());
        WORKER.execute(() -> {
            File temp = null;
            Uri pending = null;
            String result = "保存失败";
            try {
                temp = File.createTempFile("dydown-", ".mp4", context.getCacheDir());
                Exception last = null;
                boolean valid = false;
                for (int attempt = 0; attempt < source.urls.size(); attempt++) {
                    checkCancelled(cancelled);
                    try {
                        download(source.urls.get(attempt), temp, cancelled, (done, total) -> MAIN.post(() -> {
                            if (activity.isDestroyed() || activity.isFinishing()) return;
                            dialog.setIndeterminate(total <= 0);
                            dialog.setMessage("正在保存…");
                            if (total > 0) dialog.setProgress((int) Math.min(99, done * 100 / total));
                        }));
                        validate(temp);
                        valid = true;
                        break;
                    } catch (Exception e) {
                        last = e;
                        logger.accept("Candidate " + (attempt + 1) + " failed: " + safeError(e));
                    }
                }
                checkCancelled(cancelled);
                if (!valid) throw last == null ? new IOException("没有可用的视频地址") : last;
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "DyDown_" + source.id + "_" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DyDown");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                pending = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (pending == null) throw new IOException("无法创建相册文件");
                try (InputStream in = new FileInputStream(temp); OutputStream out = context.getContentResolver().openOutputStream(pending)) {
                    if (out == null) throw new IOException("无法写入相册");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) { checkCancelled(cancelled); out.write(buffer, 0, read); }
                }
                checkCancelled(cancelled);
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(pending, values, null, null) != 1) throw new IOException("相册登记失败");
                logger.accept("Save complete: id=" + source.id + ", bytes=" + temp.length());
                pending = null;
                result = "已保存到相册 · Movies/DyDown";
            } catch (Exception e) {
                result = cancelled.get() ? "已取消保存" : "保存失败：" + safeError(e);
                logger.accept(cancelled.get() ? "Save cancelled" : "Save failed: " + safeError(e));
            } finally {
                if (pending != null) {
                    try { context.getContentResolver().delete(pending, null, null); } catch (RuntimeException ignored) { }
                }
                if (temp != null && temp.exists() && !temp.delete()) logger.accept("Temporary file cleanup deferred");
                ACTIVE.remove(source.id);
                final String message = result;
                MAIN.post(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing() && dialog.isShowing()) { try { dialog.dismiss(); } catch (RuntimeException ignored) { } }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private static String safeError(Exception e) {
        // Do not log signed URLs or response bodies.
        if (e instanceof SaveException) return e.getMessage();
        return e.getClass().getSimpleName();
    }
    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled.get()) throw new SaveException("已取消");
    }
    private interface Progress { void update(long done, long total); }
    private static void download(String address, File target, AtomicBoolean cancelled, Progress progress) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            for (int redirects = 0; ; redirects++) {
                checkCancelled(cancelled);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects >= 5) throw new SaveException("重定向失败");
                    URL next = new URL(url, location);
                    if (!next.getProtocol().equals("http") && !next.getProtocol().equals("https")) throw new SaveException("地址协议不支持");
                    connection.disconnect(); connection = null; url = next; continue;
                }
                if (code != 200) throw new SaveException("HTTP " + code);
                break;
            }
            String type = connection.getContentType();
            if (type != null && (type.toLowerCase(Locale.ROOT).contains("text/") || type.toLowerCase(Locale.ROOT).contains("json") || type.toLowerCase(Locale.ROOT).contains("mpegurl"))) throw new SaveException("响应不是视频文件");
            long total = connection.getContentLengthLong(), done = 0, lastUpdate = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(cancelled); out.write(buffer, 0, read); done += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 500) { progress.update(done, total); lastUpdate = now; }
                }
            }
            if (done == 0 || (total > 0 && total != done)) throw new SaveException("视频下载不完整");
        } finally { if (connection != null) connection.disconnect(); }
    }
    private static void validate(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[12];
            if (input.read(header) != 12 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') throw new SaveException("不是受支持的 MP4 文件");
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!"yes".equals(hasVideo) || duration == null || Long.parseLong(duration) <= 0) throw new SaveException("视频校验失败");
        } finally { retriever.release(); }
    }
    private static final class SaveException extends IOException { SaveException(String message) { super(message); } }
    private VideoSaver() { }
}
