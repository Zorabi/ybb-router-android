// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Web content is limited to official account authorization, not the management UI. */
public final class LoginActivity extends Activity {
    private WebView web;
    private boolean completed;
    private static final String RETURN = "https://ybb.baidu.com/m/pages/xiaomi/acc";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        NativeUi ui = new NativeUi(this); LinearLayout root = ui.column(); root.setBackgroundColor(ui.bg);
        LinearLayout bar = ui.row(); bar.addView(ui.button("取消", false, v -> finish()));
        TextView title = ui.label("百度账号登录", 18, ui.text, true); title.setPadding(ui.dp(16), 0, 0, 0); bar.addView(title); root.addView(bar);
        TextView hint = ui.label("请在百度官方页面完成登录", 13, ui.muted, false); hint.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); root.addView(hint);
        web = new WebView(this); root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        web.getSettings().setJavaScriptEnabled(true); web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl(); String host = uri.getHost();
                if ("https".equals(uri.getScheme()) && "ybb.baidu.com".equals(host)) { complete(); return true; }
                return !("https".equals(uri.getScheme()) && ("wappass.baidu.com".equals(host) || "passport.baidu.com".equals(host)));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (url.startsWith(RETURN)) complete();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) { hint.setText("登录页加载失败，请检查网络。"); hint.setOnClickListener(v -> view.reload()); }
            }
        });
        if (state == null || web.restoreState(state) == null) web.loadUrl("https://wappass.baidu.com/passport/login?tpl=websec&u="+Uri.encode(RETURN));
    }
    private void complete() { if (completed) return; completed = true; CookieManager.getInstance().flush(); setResult(RESULT_OK, new Intent()); finish(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else finish(); }
    @Override protected void onSaveInstanceState(Bundle state) { web.saveState(state); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
