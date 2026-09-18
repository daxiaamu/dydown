package com.daxiaamu.dydown;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/** Version-scoped discovery. A string hit alone is never enough to install a hook. */
public final class ActionResolver {
    private static final String SHEET = "com.ss.android.ugc.aweme.sharer.ui.SheetAction";
    private static final String SHARE = "com.ss.android.ugc.aweme.sharer.ui.SharePackage";
    private static final String SCHEMA = "dydown-resolver-2/dexkit-2.2.0";

    static Method executeMethod(Class<?> type, Class<?> model) throws ReflectiveOperationException {
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) return null;
        ClassLoader loader = type.getClassLoader();
        if (!Class.forName(SHEET, false, loader).isAssignableFrom(type)) return null;
        int models = 0, activities = 0;
        for (Class<?> cls = type; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() == model) models++;
                if (Activity.class.isAssignableFrom(field.getType())) activities++;
            }
        }
        if (models != 1 || activities != 1) return null;
        Method key = type.getMethod("key");
        if (key.getReturnType() != String.class || Modifier.isStatic(key.getModifiers())) return null;
        Method execute = type.getMethod("execute", Context.class, Class.forName(SHARE, false, loader));
        return execute.getReturnType() == void.class && !Modifier.isStatic(execute.getModifiers()) ? execute : null;
    }

    static Class<?> validate(String name, ClassLoader loader, Class<?> model) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            return executeMethod(type, model) == null ? null : type;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

    static Class<?> unique(Set<String> names, ClassLoader loader, Class<?> model) {
        Class<?> result = null;
        for (String name : names) {
            Class<?> candidate = validate(name, loader, model);
            if (candidate == null) continue;
            if (result != null) return null;
            result = candidate;
        }
        return result;
    }

    static List<String> paths(ApplicationInfo info) {
        List<String> paths = new ArrayList<>();
        paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) java.util.Collections.addAll(paths, info.splitSourceDirs);
        java.util.Collections.sort(paths);
        return paths;
    }

    // Include split APKs and DEX CRCs: neither a same-version reinstall nor a split update reuses old hits.
    static String fingerprint(List<String> paths, long version, long updated) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((SCHEMA + ":" + version + ":" + updated).getBytes(StandardCharsets.UTF_8));
        for (String path : paths) {
            File file = new File(path);
            digest.update(("\n" + path + ":" + file.length() + ":" + file.lastModified()).getBytes(StandardCharsets.UTF_8));
            try (ZipFile zip = new ZipFile(file)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().matches("classes[0-9]*\\.dex")) {
                        digest.update((entry.getName() + ":" + entry.getCrc() + ":" + entry.getSize()).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    /** Called on a worker; no disk scan or native library loading on the UI thread. */
    static Class<?> resolve(Context context, ClassLoader loader, Class<?> model, Consumer<String> log) throws Exception {
        PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        List<String> paths = paths(context.getApplicationInfo());
        String identity = fingerprint(paths, pkg.getLongVersionCode(), pkg.lastUpdateTime);
        SharedPreferences cache = context.getSharedPreferences("dydown_dexkit_v1", Context.MODE_PRIVATE);
        if (identity.equals(cache.getString("identity", ""))) {
            Class<?> cached = validate(cache.getString("action", ""), loader, model);
            if (cached != null) { log.accept("DexKit cache hit"); return cached; }
        }
        cache.edit().clear().apply();
        long started = android.os.SystemClock.elapsedRealtime();
        Class<?> found = scan(paths, loader, model, log);
        if (found != null) {
            cache.edit().putString("identity", identity).putString("action", found.getName()).apply();
            log.accept("DexKit resolved in " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
        }
        // Do not cache misses: a transient loading failure should be retried next process start.
        return found;
    }

    static Class<?> scan(List<String> paths, ClassLoader loader, Class<?> model, Consumer<String> log) throws Exception {
        System.loadLibrary("dexkit");
        Set<String> strong = new LinkedHashSet<>(), fallback = new LinkedHashSet<>();
        for (String path : paths) {
            try (ZipFile zip = new ZipFile(path)) { if (zip.getEntry("classes.dex") == null) continue; }
            try (DexKitBridge bridge = DexKitBridge.create(path)) {
                for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("DownloadAction#execute", "download_method")))) strong.add(data.getName());
                for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("downloadAction hidden but invoke ", "download_select_type")))) strong.add(data.getName());
                for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("realDownload", "download_start", "download_method")))) fallback.add(data.getName());
            }
        }
        log.accept("DexKit string hits=" + strong.size() + "/" + fallback.size());
        // Multiple structurally valid strong hits are ambiguous, not a reason to try a weaker query.
        Set<String> candidates = new LinkedHashSet<>();
        for (String name : strong) if (validate(name, loader, model) != null) candidates.add(name);
        if (candidates.isEmpty()) for (String name : fallback) if (validate(name, loader, model) != null) candidates.add(name);
        log.accept("DexKit validated candidates=" + candidates.size());
        return unique(candidates, loader, model);
    }
    private ActionResolver() { }
}
