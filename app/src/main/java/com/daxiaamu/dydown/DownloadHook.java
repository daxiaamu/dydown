package com.daxiaamu.dydown;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

public final class DownloadHook extends XposedModule {
    private static final String HOST = "com.ss.android.ugc.aweme";
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean discoveryStarted = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean errorReported = new AtomicBoolean();
    private final Set<Method> saveMethods = ConcurrentHashMap.newKeySet();
    private boolean mainProcess;
    private void info(String text) { log(Log.INFO, "DyDown", text); }
    private void unavailable(String stage, Throwable error) {
        // A missing optional signature must not stop other independent hooks.
        info(stage + " unavailable: " + error.getClass().getSimpleName());
    }
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        mainProcess = HOST.equals(param.getProcessName());
        if (mainProcess) info("Loaded with libxposed API " + getApiVersion());
    }
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!mainProcess || !HOST.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        if (!installed.compareAndSet(false, true)) return;
        try { install(param.getDefaultClassLoader()); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Model", e); }
    }
    private boolean prepare(Object aweme) {
        try { return DownloadPolicy.prepare(aweme); }
        catch (RuntimeException | LinkageError e) {
            if (errorReported.compareAndSet(false, true)) unavailable("Model fields", e);
            return false;
        }
    }
    private void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
        try {
            Method video = aweme.getDeclaredMethod("getVideo");
            if (video.getReturnType() == void.class || video.getReturnType().isPrimitive()) throw new NoSuchMethodException();
            hook(video).intercept(chain -> { prepare(chain.getThisObject()); return chain.proceed(); });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Video getter", e); }
        try {
            Method status = aweme.getDeclaredMethod("getDownloadStatus");
            if (status.getReturnType() != int.class) throw new NoSuchMethodException();
            hook(status).intercept(chain -> prepare(chain.getThisObject()) ? 0 : chain.proceed());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Status getter", e); }
        for (String name : new String[]{"com.ss.android.ugc.aweme.privacy.service.ConsumerPermissionService", "com.ss.android.ugc.aweme.spi.ConsumerPermissionServiceImp"}) {
            try { installPermission(Class.forName(name, false, loader), aweme); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Permission", e); }
        }
        try { installFactories(loader, aweme); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Action factory", e); }
        hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
            Object result = chain.proceed();
            Context context = (Context) chain.getArg(0);
            if (HOST.equals(context.getPackageName()) && discoveryStarted.compareAndSet(false, true)) {
                // Only the exact tested build may use an obfuscated name without a DEX scan.
                try {
                    if (context.getPackageManager().getPackageInfo(HOST, 0).getLongVersionCode() == 400501L) {
                        Class<?> known = ActionResolver.validate("X.0uVu", loader, aweme);
                        if (known != null) installAction(known, aweme);
                    }
                } catch (Exception | LinkageError e) { unavailable("Reference action", e); }
                Thread worker = new Thread(() -> {
                    try {
                        Class<?> action = ActionResolver.resolve(context, loader, aweme, this::info);
                        if (action != null) installAction(action, aweme);
                        else info("No unique action; retaining native behavior");
                    } catch (Exception | LinkageError e) { unavailable("DexKit discovery", e); }
                }, "DyDown-DexKit");
                worker.setDaemon(true);
                worker.start();
            }
            return result;
        });
    }
    private void installPermission(Class<?> service, Class<?> aweme) throws ReflectiveOperationException {
        List<Method> candidates = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        for (Method method : service.getDeclaredMethods()) {
            Class<?>[] args = method.getParameterTypes();
            if (args.length != 1 || args[0] != aweme || Modifier.isAbstract(method.getModifiers())) continue;
            for (Constructor<?> ctor : method.getReturnType().getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length != 2 || !types[0].isEnum() || types[1].isPrimitive()) continue;
                Object normal = null;
                for (Object item : types[0].getEnumConstants()) if (((Enum<?>) item).name().equals("NORMAL")) normal = item;
                if (normal == null) continue;
                ctor.setAccessible(true);
                candidates.add(method); results.add(ctor.newInstance(normal, null));
                break;
            }
        }
        if (candidates.size() != 1) { info("Permission signature absent or ambiguous; skipped"); return; }
        Object allowed = results.get(0);
        hook(candidates.get(0)).intercept(chain -> {
            if (!prepare(chain.getArg(0))) return chain.proceed();
            if (reported.compareAndSet(false, true)) info("Native save permission enabled for a playable video");
            return allowed;
        });
        info("Native permission hook: " + service.getSimpleName() + "." + candidates.get(0).getName());
    }
    private void installFactories(ClassLoader loader, Class<?> aweme) throws ReflectiveOperationException {
        Class<?> factory = Class.forName("com.ss.android.ugc.aweme.share.ShareActionImpl", false, loader);
        for (Method method : factory.getDeclaredMethods()) {
            if (!method.getReturnType().getName().equals("com.ss.android.ugc.aweme.sharer.ui.SheetAction")) continue;
            try {
                hook(method).intercept(chain -> {
                    Object action = chain.proceed();
                    if (action != null) {
                        try {
                            if (isDownload(action)) installAction(action.getClass(), aweme);
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Factory result", e); }
                    }
                    return action;
                });
            } catch (RuntimeException | LinkageError e) { unavailable("Factory hook", e); }
        }
    }
    private static boolean isDownload(Object action) throws ReflectiveOperationException {
        Method key = action.getClass().getMethod("key");
        key.setAccessible(true);
        return "download".equals(key.invoke(action));
    }
    private synchronized void installAction(Class<?> actionClass, Class<?> aweme) throws ReflectiveOperationException {
        Method method = ActionResolver.executeMethod(actionClass, aweme);
        if (method == null || !saveMethods.add(method)) return;
        try {
            hook(method).intercept(chain -> {
                VideoSource source = null;
                Activity activity = chain.getArg(0) instanceof Activity ? (Activity) chain.getArg(0) : null;
                try {
                    Object action = chain.getThisObject();
                    if (isDownload(action)) {
                        Object item = null;
                        for (Class<?> cls = action.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                            for (Field field : cls.getDeclaredFields()) {
                                if (Modifier.isStatic(field.getModifiers())) continue;
                                if (field.getType() == aweme) { field.setAccessible(true); item = field.get(action); }
                                else if (Activity.class.isAssignableFrom(field.getType())) {
                                    field.setAccessible(true);
                                    Activity owner = (Activity) field.get(action);
                                    if (owner != null) activity = owner;
                                }
                            }
                        }
                        source = VideoSource.from(item);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { unavailable("Action data", e); }
                if (activity == null || source == null || activity.isFinishing() || activity.isDestroyed()) return chain.proceed();
                VideoSaver.save(activity, source, this::info);
                return null;
            });
            info("Save action attached: " + actionClass.getName());
        } catch (RuntimeException | LinkageError e) { saveMethods.remove(method); throw e; }
    }
}
