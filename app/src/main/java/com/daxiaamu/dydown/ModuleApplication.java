package com.daxiaamu.dydown;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String HOST = "com.ss.android.ugc.aweme";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private XposedService service;
    private boolean inFlight;
    private String status = "等待 LSPosed 连接。请先在框架中启用本模块。";
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }
    public String getStatus() { return status; }
    public void observe(Runnable listener) { listeners.add(listener); listener.run(); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }
    private void publish(String text) { status = text; for (Runnable listener : listeners) listener.run(); }
    @Override public void onServiceBind(XposedService bound) {
        main.post(() -> { service = bound; inFlight = false; synchronizeScope(); });
    }
    @Override public void onServiceDied(XposedService dead) {
        main.post(() -> {
            if (service != dead) return;
            service = null; inFlight = false;
            publish("框架连接已断开，请重新打开模块。");
        });
    }
    public void synchronizeScope() {
        XposedService current = service;
        if (current == null) { publish("未连接 LSPosed。请在支持 API 102 的框架中启用本模块，再重新打开。"); return; }
        try {
            if (current.getScope().contains(HOST)) {
                publish("目标作用域已设置 ✓\n强行停止目标应用后重新打开即可生效。");
                return;
            }
            if (inFlight) return;
            inFlight = true;
            publish("正在自动申请目标作用域…如框架弹出确认，请允许。");
            current.requestScope(Collections.singletonList(HOST), new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> approved) {
                    main.post(() -> {
                        if (service != current) return;
                        inFlight = false;
                        try {
                            publish(current.getScope().contains(HOST)
                                ? "目标作用域已设置 ✓\n强行停止目标应用后重新打开即可生效。"
                                : "框架未授予目标作用域，可点击下方按钮重试。");
                        } catch (RuntimeException e) { publish("读取作用域失败，可重试。"); }
                    });
                }
                @Override public void onScopeRequestFailed(String message) {
                    main.post(() -> {
                        if (service != current) return;
                        inFlight = false;
                        publish("自动设置失败：" + message + "\n可点击下方按钮重试，或在 LSPosed 中勾选目标应用。");
                    });
                }
            });
        } catch (RuntimeException e) {
            inFlight = false;
            publish("作用域服务暂不可用：" + e.getClass().getSimpleName());
        }
    }
}
