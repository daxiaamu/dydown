package com.daxiaamu.dydown;

import android.util.Log;
import android.app.Activity;
import android.content.Context;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<Method> saveMethods = ConcurrentHashMap.newKeySet();
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
        int count = 0;
        for (String name : new String[]{"com.ss.android.ugc.aweme.privacy.service.ConsumerPermissionService", "com.ss.android.ugc.aweme.spi.ConsumerPermissionServiceImp"}) {
        Class<?> service = Class.forName(name, false, loader);
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
        }
        installSaveActions(loader, aweme);
        info("native permission hooks=" + count + "; reference=40.5.0(400501)");
        if (count == 0) info("WARNING: native menu signature not found; this version needs adaptation");
    }
    private void installSaveActions(ClassLoader loader, Class<?> aweme) throws Exception {
        installAction(Class.forName("X.0uVu", false, loader), aweme);
        Class<?> factory = Class.forName("com.ss.android.ugc.aweme.share.ShareActionImpl", false, loader);
        int hooks = 0;
        for (Method method : factory.getDeclaredMethods()) {
            if (!method.getReturnType().getName().equals("com.ss.android.ugc.aweme.sharer.ui.SheetAction")) continue;
            hook(method).intercept(chain -> {
                Object action = chain.proceed();
                if (action != null) {
                    try {
                        Method key = action.getClass().getMethod("key");
                        key.setAccessible(true);
                        if ("download".equals(key.invoke(action))) installAction(action.getClass(), aweme);
                    } catch (Exception e) { log(Log.WARN, "DyDown", "Save action not available", e); }
                }
                return action;
            });
            hooks++;
        }
        info("Save action factories=" + hooks);
    }
    private void installAction(Class<?> actionClass, Class<?> aweme) throws Exception {
        for (Method method : actionClass.getMethods()) {
            if (!method.getName().equals("execute") || method.getParameterCount() != 2 || method.getParameterTypes()[0] != Context.class) continue;
            if (!saveMethods.add(method)) return;
            try {
                hook(method).intercept(chain -> {
                    Object item = null;
                    Activity activity = chain.getArg(0) instanceof Activity ? (Activity) chain.getArg(0) : null;
                    for (Class<?> cls = chain.getThisObject().getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                        for (Field field : cls.getDeclaredFields()) {
                            if (field.getType() == aweme) { field.setAccessible(true); item = field.get(chain.getThisObject()); }
                            else if (Activity.class.isAssignableFrom(field.getType())) { field.setAccessible(true); activity = (Activity) field.get(chain.getThisObject()); }
                        }
                    }
                    VideoSource source = VideoSource.from(item);
                    if (activity == null || source == null) { info("Save action has no supported video source"); return chain.proceed(); }
                    VideoSaver.save(activity, source, this::info);
                    return null;
                });
                info("Save action attached: " + actionClass.getName());
            } catch (Exception e) { saveMethods.remove(method); throw e; }
        }
    }

}
