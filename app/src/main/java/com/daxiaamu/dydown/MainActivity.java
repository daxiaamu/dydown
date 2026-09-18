package com.daxiaamu.dydown;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private ModuleApplication module;
    private TextView status;
    private final Runnable update = () -> status.setText(module.getStatus());
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        module = (ModuleApplication) getApplication();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad * 2, pad, pad);
        layout.setBackgroundColor(Color.rgb(247, 247, 248));
        TextView title = new TextView(this);
        title.setText("DyDown"); title.setTextSize(26); title.setTextColor(Color.rgb(24, 24, 28));
        layout.addView(title);
        status = new TextView(this); status.setTextSize(17); status.setPadding(0, pad, 0, pad);
        status.setTextColor(Color.rgb(35, 90, 65)); layout.addView(status);
        Button retry = new Button(this); retry.setText("重新设置作用域");
        retry.setOnClickListener(v -> module.synchronizeScope()); layout.addView(retry);
        TextView body = new TextView(this); body.setTextSize(17); body.setTextColor(Color.rgb(65, 65, 70));
        body.setLineSpacing(10, 1); body.setPadding(0, pad, 0, 0);
        body.setText("版本 0.2.0 · API 102\n适配版本：40.5.0（400501）\n\n视频范围：当前账号可正常播放，且具备有效 HTTP(S) 视频地址的普通视频。\n\n不覆盖图集、直播、无法播放或已失效的内容；不处理 DRM 加密。不承诺无水印，其他版本尚未验证。");
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(body); layout.addView(scroll);
        setContentView(layout);
    }
    @Override protected void onStart() { super.onStart(); module.observe(update); }
    @Override protected void onStop() { module.removeObserver(update); super.onStop(); }
}
