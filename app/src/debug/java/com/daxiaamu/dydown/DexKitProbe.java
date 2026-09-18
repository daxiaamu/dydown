package com.daxiaamu.dydown;

import dalvik.system.PathClassLoader;
import java.util.Collections;
import java.util.List;

/** Forces uncached discovery against a real APK; never uses the reference class name. */
public final class DexKitProbe {
    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
        System.out.println("PASS " + text);
    }
    public static void main(String[] args) {
        try { run(args); } catch (Throwable error) { error.printStackTrace(System.out); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        ClassLoader loader = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader());
        Class<?> model = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme");
        List<String> paths = Collections.singletonList(args[0]);
        long start = android.os.SystemClock.elapsedRealtime();
        Class<?> action = ActionResolver.scan(paths, loader, model, System.out::println);
        check(action != null, "uncached DEX discovery without reference class name");
        check(ActionResolver.executeMethod(action, model) != null, "discovered action has exact callable signature");
        check(ActionResolver.validate(action.getName(), loader, model) == action, "cached action revalidation");
        check(ActionResolver.validate("missing.removed.Action", loader, model) == null, "stale class rejected");
        check(ActionResolver.validate(model.getName(), loader, model) == null, "non-action class rejected");
        check(ActionResolver.unique(Collections.emptySet(), loader, model) == null, "no candidates retains native behavior");
        String key = ActionResolver.fingerprint(paths, 400501L, 1);
        check(key.equals(ActionResolver.fingerprint(paths, 400501L, 1)), "cache identity stable for unchanged APK");
        check(!key.equals(ActionResolver.fingerprint(paths, 400502L, 1)), "version change invalidates cache");
        check(!key.equals(ActionResolver.fingerprint(paths, 400501L, 2)), "same-version reinstall invalidates cache");
        System.out.println("Resolved " + action.getName() + " in " + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
        System.out.println("ALL DEXKIT CHECKS PASSED");
    }
}
