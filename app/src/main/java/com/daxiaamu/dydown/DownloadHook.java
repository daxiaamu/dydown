package com.daxiaamu.dydown;

import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

public final class DownloadHook extends XposedModule {
    private static final String HOST = "com.ss.android.ugc.aweme";
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean errorReported = new AtomicBoolean();
    private boolean mainProcess;
    private void info(String text) { log(Log.INFO, "DyDown", text); }
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        mainProcess = HOST.equals(param.getProcessName());
        if (mainProcess) info("Loaded with libxposed API " + getApiVersion());
    }
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!mainProcess || !HOST.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        if (!installed.compareAndSet(false, true)) return;
        try { install(param.getDefaultClassLoader()); }
        catch (Throwable e) { log(Log.ERROR, "DyDown", "Installation failed", e); }
    }
    private boolean prepare(Object aweme) {
        try { return DownloadPolicy.prepare(aweme); }
        catch (Throwable e) {
            if (errorReported.compareAndSet(false, true)) log(Log.ERROR, "DyDown", "Model mismatch", e);
            return false;
        }
    }
    private void install(ClassLoader loader) throws Exception {
        Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
        hook(aweme.getDeclaredMethod("getVideo")).intercept(chain -> {
            prepare(chain.getThisObject());
            return chain.proceed();
        });
        hook(aweme.getDeclaredMethod("getDownloadStatus")).intercept(chain ->
            prepare(chain.getThisObject()) ? 0 : chain.proceed());
        Class<?> service = Class.forName("com.ss.android.ugc.aweme.privacy.service.ConsumerPermissionService", false, loader);
        int count = 0;
        for (Method method : service.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length != 1 || args[0] != aweme) continue;
            for (Constructor<?> ctor : method.getReturnType().getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length != 2 || !types[0].isEnum() || types[1].isPrimitive()) continue;
                Object normal = null;
                for (Object item : types[0].getEnumConstants()) {
                    if (((Enum<?>) item).name().equals("NORMAL")) normal = item;
                }
                if (normal == null) continue;
                ctor.setAccessible(true);
                final Object allowed = ctor.newInstance(normal, null);
                hook(method).intercept(chain -> {
                    if (!prepare(chain.getArg(0))) return chain.proceed();
                    if (reported.compareAndSet(false, true)) info("Native save permission enabled for a playable video");
                    return allowed;
                });
                info("Native permission hook: " + method.getName());
                count++;
                break;
            }
        }
        info("native permission hooks=" + count + "; reference=40.5.0(400501)");
        if (count == 0) info("WARNING: native menu signature not found; this version needs adaptation");
    }
}
