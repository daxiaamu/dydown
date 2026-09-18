package com.daxiaamu.dydown;

import dalvik.system.PathClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Run with app_process against the real installed APK; does not access user data or network. */
public final class CompatibilityProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    private static Object create(ClassLoader loader, String name) throws Exception {
        Constructor<?> constructor = loader.loadClass(name).getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        String prefix = "com.ss.android.ugc.aweme.feed.model.";
        Class<?> awemeType = loader.loadClass(prefix + "Aweme");
        check(awemeType.getDeclaredMethod("getVideo").getReturnType().getName().equals(prefix + "Video"), "getVideo signature");
        check(awemeType.getDeclaredMethod("getDownloadStatus").getReturnType() == int.class, "getDownloadStatus signature");
        Class<?> service = loader.loadClass("com.ss.android.ugc.aweme.privacy.service.ConsumerPermissionService");
        int matches = 0;
        for (Method method : service.getDeclaredMethods()) {
            if (!Arrays.equals(method.getParameterTypes(), new Class<?>[]{awemeType})) continue;
            for (Constructor<?> ctor : method.getReturnType().getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length != 2 || !types[0].isEnum() || types[1].isPrimitive()) continue;
                for (Object item : types[0].getEnumConstants()) {
                    if (((Enum<?>) item).name().equals("NORMAL")) {
                        ctor.setAccessible(true);
                        Object result = ctor.newInstance(item, null);
                        check(Boolean.TRUE.equals(result.getClass().getDeclaredMethod("LIZIZ").invoke(result)), "NORMAL permission result is enabled");
                        matches++;
                    }
                }
            }
        }
        check(matches == 1, "exactly one native permission hook");
        Object aweme = create(loader, prefix + "Aweme");
        check(!DownloadPolicy.prepare(aweme), "empty video is rejected");
        Object video = create(loader, prefix + "Video");
        Object control = create(loader, prefix + "VideoControl");
        Object play = create(loader, prefix + "VideoUrlModel");
        Object privacy = create(loader, "com.ss.ugc.aweme.PrivacyInfoStruct");
        check(ModelFields.set(privacy, "level", 2), "set hidden download state");
        check(ModelFields.set(control, "download_info", privacy), "resolve native download privacy info");
        check(ModelFields.set(play, "url_list", Arrays.asList("https://example.invalid/video.mp4")), "resolve obfuscated URL list");
        check(ModelFields.set(video, "play_addr", play), "resolve play address");
        check(ModelFields.set(aweme, "video", video), "resolve aweme video");
        check(ModelFields.set(aweme, "video_control", control), "resolve video control");
        ModelFields.set(control, "allow_share", false);
        ModelFields.set(control, "allow_download", false);
        ModelFields.set(control, "prevent_download_type", 2);
        ModelFields.set(aweme, "prevent_download", true);
        check(DownloadPolicy.prepare(aweme), "prepare playable host model");
        check(((Number) ModelFields.get(privacy, "level")).intValue() == 0, "clear server hidden/grayed download state");
        check(Boolean.TRUE.equals(ModelFields.get(control, "allow_download")), "enable native download switch");
        check(Integer.valueOf(0).equals(ModelFields.get(control, "prevent_download_type")), "clear download block type");
        check(Boolean.FALSE.equals(ModelFields.get(aweme, "prevent_download")), "clear video download block");
        check(Boolean.FALSE.equals(ModelFields.get(control, "allow_share")), "preserve unrelated share permission");
        check(ModelFields.get(video, "download_addr") == play, "missing download URL falls back to playback");
        check(ModelFields.get(video, "new_download_addr") == play, "new download URL is populated");
        Object original = create(loader, "com.ss.android.ugc.aweme.base.model.UrlModel");
        ModelFields.set(original, "url_list", Arrays.asList("https://example.invalid/original.mp4"));
        ModelFields.set(video, "download_addr", original);
        check(DownloadPolicy.prepare(aweme) && ModelFields.get(video, "download_addr") == original, "preserve existing native download URL");
        ModelFields.set(aweme, "aweme_type", 68);
        check(!DownloadPolicy.prepare(aweme), "image posts excluded");
        ModelFields.set(aweme, "aweme_type", 101);
        check(!DownloadPolicy.prepare(aweme), "live streams excluded");
        System.out.println("ALL COMPATIBILITY CHECKS PASSED");
    }
}
