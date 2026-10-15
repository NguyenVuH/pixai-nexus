package com.pixai.nexus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android Micro-WebView Headless Engine (Chạy trong tiến trình độc lập :registrar)
 * Bám sát 100% kiến trúc C# Outer Loop trong PixAI_Ultimate_Pro.cs:
 * - Chạy trên tiến trình :registrar riêng biệt, có thư mục dữ liệu WebView độc lập hoàn toàn với Web chính.
 * - Dọn sạch WebStorage & Cookie của riêng tiến trình :registrar trước khi mở trang đăng ký
 *   và sau khi lấy xong token để mỗi lần tạo acc đều giống hệt lần đầu tiên (100% Guest mới).
 */
public class HeadlessAccountRegistrar {

    public interface RegistrationCallback {
        void onProgress(String message, String color);
        void onSuccess(String email, String password, String token);
        void onError(String error);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView headlessWebView;
    private boolean isFinished = false;
    private boolean stepLoopRunning = false;

    public HeadlessAccountRegistrar(Context context) {
        this.context = context;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void startHeadlessRegistration(String email, String password, RegistrationCallback callback) {
        mainHandler.post(() -> {
            try {
                isFinished = false;
                stepLoopRunning = false;

                cleanup();

                callback.onProgress("[*] Khởi động phiên PixAI độc lập (:registrar)...", "#94A3B8");

                // Xóa sạch dữ liệu lưu trữ của phiên đăng ký trước trong tiến trình độc lập :registrar
                // (Hoàn toàn không chạm tới WebView chính của ứng dụng)
                try {
                    WebStorage.getInstance().deleteAllData();
                } catch (Throwable ignored) {}

                headlessWebView = new WebView(context);
                headlessWebView.onResume();
                headlessWebView.resumeTimers();
                headlessWebView.clearCache(true);

                WebSettings settings = headlessWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                settings.setLoadsImagesAutomatically(false);

                // Sử dụng User-Agent Chrome Mobile chuẩn đã xóa dấu vết "; wv" của Android WebView
                settings.setUserAgentString(SecurityShieldManager.getCleanUserAgent());

                // Dọn sạch toàn bộ cookie và trạng thái phiên cũ trong tiến trình :registrar để luôn giống hệt lần chạy đầu tiên (Clear All Data)
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(headlessWebView, true);
                try {
                    cm.removeAllCookies(null);
                    cm.removeSessionCookies(null);
                } catch (Throwable ignored) {}
                cm.setCookie("https://pixai.art", "user_token=; path=/; domain=.pixai.art; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                cm.setCookie("https://pixai.art", "pixai_token=; path=/; domain=.pixai.art; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                cm.setCookie("https://pixai.art", "token=; path=/; domain=.pixai.art; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                cm.setCookie("https://pixai.art", "token_hint=; path=/; domain=.pixai.art; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                cm.setCookie("https://api.pixai.art", "user_token=; path=/; domain=.pixai.art; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                cm.flush();

                final String pixaiHookJs =
                    "(() => {" +
                    "  try {" +
                    "    if (!window.__pixai_storage_wiped_once) {" +
                    "      window.__pixai_storage_wiped_once = true;" +
                    "      try { localStorage.clear(); sessionStorage.clear(); } catch(e) {}" +
                    "      try {" +
                    "        if (window.indexedDB && window.indexedDB.databases) {" +
                    "          window.indexedDB.databases().then(dbs => dbs.forEach(db => { if (db && db.name) window.indexedDB.deleteDatabase(db.name); })).catch(()=>{});" +
                    "        }" +
                    "      } catch(e) {}" +
                    "    }" +
                    "    if (window.__pixai_hook_installed) return;" +
                    "    window.__pixai_hook_installed = true;" +
                    "    window.__pixai_captured_jwt = window.__pixai_captured_jwt || '';" +
                    "    window.__pixai_auth_error = window.__pixai_auth_error || '';" +
                    "    function isFatalAuthEndpoint(urlStr, status, bodyTxt) {" +
                    "      if (!urlStr || status < 400) return false;" +
                    "      if (status === 401 || (bodyTxt && bodyTxt.includes('must be logged in'))) return false;" +
                    "      let lower = String(urlStr).toLowerCase();" +
                    "      return lower.includes('/register') || lower.includes('/signup') || lower.includes('/sign-up') || lower.includes('/login') || lower.includes('/sign-in');" +
                    "    }" +
                    "    const origFetch = window.fetch;" +
                    "    if (origFetch) {" +
                    "      window.fetch = function(input, init) {" +
                    "        let u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');" +
                    "        return origFetch.apply(this, arguments).then(resp => {" +
                    "          try {" +
                    "            resp.clone().text().then(txt => {" +
                    "              if (isFatalAuthEndpoint(u, resp.status, txt)) {" +
                    "                window.__pixai_auth_error = resp.status + ':' + txt.substring(0, 180);" +
                    "              }" +
                    "              let m = txt.match(/eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+/);" +
                    "              if (m && m[0] && m[0].length > 40) {" +
                    "                window.__pixai_captured_jwt = m[0];" +
                    "              }" +
                    "            }).catch(()=>{});" +
                    "          } catch(e) {}" +
                    "          return resp;" +
                    "        });" +
                    "      };" +
                    "    }" +
                    "    const origOpen = XMLHttpRequest.prototype.open;" +
                    "    const origSend = XMLHttpRequest.prototype.send;" +
                    "    XMLHttpRequest.prototype.open = function(m, u) { this.__px_u = u || ''; return origOpen.apply(this, arguments); };" +
                    "    XMLHttpRequest.prototype.send = function() {" +
                    "      this.addEventListener('load', function() {" +
                    "        try {" +
                    "          let txt = this.responseText || '';" +
                    "          if (isFatalAuthEndpoint(this.__px_u, this.status, txt)) {" +
                    "            window.__pixai_auth_error = this.status + ':' + txt.substring(0, 180);" +
                    "          }" +
                    "          let m = txt.match(/eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+/);" +
                    "          if (m && m[0] && m[0].length > 40) {" +
                    "            window.__pixai_captured_jwt = m[0];" +
                    "          }" +
                    "        } catch(e) {}" +
                    "      });" +
                    "      return origSend.apply(this, arguments);" +
                    "    };" +
                    "  } catch(e) {}" +
                    "})();";

                headlessWebView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                        return true;
                    }

                    @Override
                    public void onProgressChanged(WebView view, int newProgress) {
                        super.onProgressChanged(view, newProgress);
                        if (newProgress >= 95 && !stepLoopRunning && !isFinished) {
                            view.evaluateJavascript(pixaiHookJs, null);
                            startOuterAutomationLoop(email, password, pixaiHookJs, callback);
                        }
                    }
                });

                headlessWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (request != null && request.getUrl() != null) {
                            android.webkit.WebResourceResponse blocked = SecurityShieldManager.interceptWebViewRequest(request.getUrl());
                            if (blocked != null) return blocked;
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageStarted(WebView view, String url, Bitmap favicon) {
                        super.onPageStarted(view, url, favicon);
                        view.evaluateJavascript(SecurityShieldManager.getStealthInjectionJs(), null);
                        view.evaluateJavascript(pixaiHookJs, null);
                        callback.onProgress("  [*] Đang nạp (Qua Khiên Bảo Mật): " + url, "#94A3B8");
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        view.evaluateJavascript(SecurityShieldManager.getStealthInjectionJs(), null);
                        view.evaluateJavascript(pixaiHookJs, null);
                        if (!stepLoopRunning && !isFinished) {
                            startOuterAutomationLoop(email, password, pixaiHookJs, callback);
                        }
                    }

                    @Override
                    public void onReceivedHttpAuthRequest(WebView view, android.webkit.HttpAuthHandler handler, String host, String realm) {
                        if (!SecurityShieldManager.handleHttpAuthRequest(handler, host, realm)) {
                            super.onReceivedHttpAuthRequest(view, handler, host, realm);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                        super.onReceivedError(view, request, error);
                        if (request.isForMainFrame()) {
                            callback.onProgress("  [!] Cảnh báo tải trang: " + error.getDescription(), "#FBBF24");
                        }
                    }
                });

                callback.onProgress("  [*] Kết nối tới https://pixai.art/en/login...", "#94A3B8");
                headlessWebView.loadUrl("https://pixai.art/en/login");

                mainHandler.postDelayed(() -> {
                    if (!stepLoopRunning && !isFinished && headlessWebView != null) {
                        headlessWebView.evaluateJavascript(pixaiHookJs, null);
                        startOuterAutomationLoop(email, password, pixaiHookJs, callback);
                    }
                }, 4200);

            } catch (Throwable ex) {
                callback.onError("Lỗi khởi tạo: " + ex.getMessage());
            }
        });
    }

    // ================= 100% C# ARCHITECTURE: JAVA OUTER LOOP AUTOMATION (ĐÃ KIỂM CHỨNG THỰC TẾ) =================
    private void startOuterAutomationLoop(String email, String password, String pixaiHookJs, RegistrationCallback callback) {
        if (stepLoopRunning || isFinished) return;
        stepLoopRunning = true;
        callback.onProgress("[*] Bắt đầu tự động hóa đăng ký PixAI...", "#38BDF8");

        findAndClickEmailButton(email, password, pixaiHookJs, 0, callback);
    }

    // BƯỚC 1: Tìm và click "Continue with Email" (lặp tối đa 25 lần, mỗi 400ms)
    private void findAndClickEmailButton(String email, String password, String pixaiHookJs, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 25) {
            callback.onError("Không tìm thấy nút Continue with Email sau 12 giây chờ.");
            cleanup();
            return;
        }

        if (attempt % 4 == 0) {
            callback.onProgress("  [*] Đang đợi nút Continue with Email... (lần " + (attempt + 1) + "/25)", "#94A3B8");
        }

        headlessWebView.evaluateJavascript(pixaiHookJs, null);

        String clickJs =
            "(() => {" +
            "  try {" +
            "    let inp = document.querySelector('input#email, input[name=email], input[type=email]');" +
            "    if (inp) return 'ALREADY_FORM';" +
            "    let dashed = document.querySelector('button[class*=\"border-dashed\"], button.border-dashed');" +
            "    if (dashed) { dashed.click(); return 'CLICKED_DASHED'; }" +
            "    const btns = [...document.querySelectorAll('button, a, div[role=button]')];" +
            "    const b = btns.find(x => ((x.innerText || x.textContent || '').toLowerCase().includes('email')));" +
            "    if (b) { b.click(); return 'CLICKED_TEXT'; }" +
            "    if (window.location.pathname === '/' || window.location.pathname === '/en') {" +
            "      const logBtn = btns.find(x => /log in|sign in|đăng nhập/i.test(x.innerText || ''));" +
            "      if (logBtn) { logBtn.click(); return 'CLICKED_HOME_LOGIN'; }" +
            "    }" +
            "    return 'WAITING (btns: ' + btns.length + ')';" +
            "  } catch (e) { return 'ERR: ' + e.message; }" +
            "})()";

        headlessWebView.evaluateJavascript(clickJs, val -> {
            String res = unquote(val);
            if (res.contains("CLICKED") || res.contains("ALREADY_FORM")) {
                callback.onProgress("  [✓] Đã mở form Email (" + res + ")!", "#34D399");
                mainHandler.postDelayed(() -> fillEmailStep(email, password, 0, callback), 500);
            } else {
                mainHandler.postDelayed(() -> findAndClickEmailButton(email, password, pixaiHookJs, attempt + 1, callback), 400);
            }
        });
    }

    // BƯỚC 2A: Điền Email & Bấm Submit bước 1
    private void fillEmailStep(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 25) {
            callback.onError("Không tìm thấy ô nhập Email sau khi bấm nút.");
            cleanup();
            return;
        }

        String safeEmail = email.replace("\\", "\\\\").replace("'", "\\'");

        String emailJs = String.format(
            "(() => {" +
            "  try {" +
            "    let emailInp = document.querySelector('input#email, input[name=email], input[type=email], input:not([type=password]):not([type=hidden]):not([type=checkbox])');" +
            "    if (!emailInp) {" +
            "      let dashed = document.querySelector('button[class*=\"border-dashed\"], button.border-dashed');" +
            "      if (dashed) { dashed.click(); return 'RECLICKED_EMAIL_BTN'; }" +
            "      const btns = [...document.querySelectorAll('button, a, div[role=button]')];" +
            "      const b = btns.find(x => ((x.innerText || x.textContent || '').toLowerCase().includes('email')));" +
            "      if (b) { b.click(); return 'RECLICKED_EMAIL_TEXT'; }" +
            "      return 'NO_EMAIL_INPUT';" +
            "    }" +
            "    emailInp.focus();" +
            "    let oldVal = emailInp.value;" +
            "    let s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
            "    s.call(emailInp, '%s');" +
            "    if (emailInp._valueTracker) { try { emailInp._valueTracker.setValue(oldVal); } catch(e){} }" +
            "    emailInp.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    emailInp.dispatchEvent(new Event('change', { bubbles: true }));" +
            "    let sub = document.querySelector('button[type=submit]');" +
            "    if (!sub) {" +
            "      let btns = [...document.querySelectorAll('button')];" +
            "      sub = btns.find(x => /continue|tiếp tục|next|submit/i.test(x.innerText || ''));" +
            "    }" +
            "    if (sub && !sub.disabled) {" +
            "      sub.click();" +
            "      return 'SUBMITTED_EMAIL';" +
            "    }" +
            "    return 'EMAIL_SET_WAIT_SUBMIT';" +
            "  } catch(e) { return 'ERR:' + e.message; }" +
            "})()", safeEmail);

        headlessWebView.evaluateJavascript(emailJs, val -> {
            String res = unquote(val);
            if (res.contains("SUBMITTED_EMAIL")) {
                callback.onProgress("  [✓] Đã nhập Email & bấm Tiếp tục: " + email, "#34D399");
                mainHandler.postDelayed(() -> fillPasswordStep(email, password, 0, callback), 600);
            } else if (res.contains("EMAIL_SET_WAIT_SUBMIT")) {
                mainHandler.postDelayed(() -> fillPasswordStep(email, password, 0, callback), 500);
            } else {
                mainHandler.postDelayed(() -> fillEmailStep(email, password, attempt + 1, callback), 400);
            }
        });
    }

    // BƯỚC 2B: Chờ ô Mật khẩu xuất hiện, điền Mật khẩu & Bấm Submit bước 2
    private void fillPasswordStep(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 30) {
            callback.onError("Không thấy ô nhập mật khẩu trên form PixAI sau 15 giây chờ.");
            cleanup();
            return;
        }

        String safeEmail = email.replace("\\", "\\\\").replace("'", "\\'");
        String safePass = password.replace("\\", "\\\\").replace("'", "\\'");

        String passJs = String.format(
            "(() => {" +
            "  try {" +
            "    let passInp = document.querySelector('input#password, input[name=password], input[type=password]');" +
            "    if (!passInp) {" +
            "      let subEmail = document.querySelector('button[type=submit]');" +
            "      if (subEmail && !subEmail.disabled) { subEmail.click(); return 'RECLICKED_SUBMIT_EMAIL'; }" +
            "      return 'WAITING_PASS_INPUT';" +
            "    }" +
            "    passInp.focus();" +
            "    let oldVal = passInp.value;" +
            "    let s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
            "    s.call(passInp, '%s');" +
            "    if (passInp._valueTracker) { try { passInp._valueTracker.setValue(oldVal); } catch(e){} }" +
            "    passInp.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    passInp.dispatchEvent(new Event('change', { bubbles: true }));" +
            "    passInp.dispatchEvent(new Event('blur', { bubbles: true }));" +
            "    let sub = document.querySelector('button[type=submit]');" +
            "    if (!sub) {" +
            "      let btns = [...document.querySelectorAll('button')];" +
            "      sub = btns.find(x => /continue|tiếp tục|sign in|submit|log in|sign up/i.test(x.innerText || ''));" +
            "    }" +
            "    if (sub && !sub.disabled) {" +
            "      sub.click();" +
            "      return 'SUBMITTED_PASS';" +
            "    }" +
            "    return 'PASS_SET_WAIT_SUBMIT';" +
            "  } catch(e) { return 'ERR:' + e.message; }" +
            "})()", safePass);

        headlessWebView.evaluateJavascript(passJs, val -> {
            String res = unquote(val);
            if (res.contains("SUBMITTED_PASS")) {
                callback.onProgress("  [✓] Đã điền Mật khẩu & gửi đăng ký PixAI, đang chờ cấp Token...", "#34D399");
                mainHandler.postDelayed(() -> scanForToken(email, password, 0, callback), 500);
            } else if (res.contains("PASS_SET_WAIT_SUBMIT")) {
                mainHandler.postDelayed(() -> waitAndClickSubmitStep(email, password, 0, callback), 350);
            } else {
                mainHandler.postDelayed(() -> fillPasswordStep(email, password, attempt + 1, callback), 450);
            }
        });
    }

    // BƯỚC 2C: Chờ nút Submit hoàn tất kích hoạt rồi click
    private void waitAndClickSubmitStep(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 20) {
            mainHandler.postDelayed(() -> scanForToken(email, password, 0, callback), 300);
            return;
        }

        String subJs =
            "(() => {" +
            "  try {" +
            "    let sub = document.querySelector('button[type=submit]');" +
            "    if (!sub) {" +
            "      let btns = [...document.querySelectorAll('button')];" +
            "      sub = btns.find(x => /continue|tiếp tục|sign in|submit|log in|sign up/i.test(x.innerText || ''));" +
            "    }" +
            "    if (!sub) return 'NO_SUBMIT_BTN';" +
            "    if (sub.disabled) return 'STILL_DISABLED';" +
            "    sub.click();" +
            "    return 'CLICKED_ENABLED_SUBMIT';" +
            "  } catch(e) { return 'ERR:' + e.message; }" +
            "})()";

        headlessWebView.evaluateJavascript(subJs, val -> {
            String res = unquote(val);
            if (res.startsWith("CLICKED_ENABLED_SUBMIT")) {
                callback.onProgress("  [✓] Đã gửi thông tin tạo tài khoản thành công!", "#34D399");
                mainHandler.postDelayed(() -> scanForToken(email, password, 0, callback), 500);
            } else {
                mainHandler.postDelayed(() -> waitAndClickSubmitStep(email, password, attempt + 1, callback), 320);
            }
        });
    }

    // BƯỚC 3: Quét Token từ CookieManager, Fetch/XHR Hook, LocalStorage & SessionStorage
    private void scanForToken(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 35) {
            callback.onError("Hết thời gian chờ cấp Token từ PixAI.");
            cleanup();
            return;
        }

        if (attempt % 3 == 0) {
            callback.onProgress("  [*] Đang chờ trích xuất phiên token... (lần " + (attempt + 1) + "/35)", "#94A3B8");
        }

        // 1. Kiểm tra qua Java CookieManager của tiến trình :registrar (cả pixai.art và api.pixai.art)
        try {
            CookieManager cm = CookieManager.getInstance();
            String c1 = cm.getCookie("https://pixai.art");
            String c2 = cm.getCookie("https://api.pixai.art");
            String combined = (c1 != null ? c1 : "") + "; " + (c2 != null ? c2 : "");
            if (!combined.trim().isEmpty()) {
                Matcher m = Pattern.compile("(?:user_token|pixai_token|token)=(eyJ[a-zA-Z0-9_\\-\\.]+)").matcher(combined);
                if (m.find()) {
                    String tok = m.group(1);
                    if (tok != null && tok.length() > 30) {
                        isFinished = true;
                        callback.onProgress("  [✓] Đã trích xuất token PixAI từ CookieManager!", "#34D399");
                        cleanup();
                        callback.onSuccess(email, password, tok);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Kiểm tra qua Fetch/XHR Hook + LocalStorage + SessionStorage + Cookie
        String scanJs =
            "(() => {" +
            "  try {" +
            "    function clean(s) { return s ? String(s).replace(/^\"+|\"+$/g, '').trim() : ''; }" +
            "    if (window.__pixai_captured_jwt && window.__pixai_captured_jwt.startsWith('eyJ') && window.__pixai_captured_jwt.length > 40) {" +
            "      return 'TOKEN:' + window.__pixai_captured_jwt;" +
            "    }" +
            "    let t = clean(localStorage.getItem('https://api.pixai.art:token')) || clean(localStorage.getItem('user_token')) || clean(localStorage.getItem('token')) || clean(localStorage.getItem('pixai_token'));" +
            "    if (t && t.length > 30 && t.startsWith('eyJ')) return 'TOKEN:' + t;" +
            "    for (let i = 0; i < localStorage.length; i++) {" +
            "      let k = localStorage.key(i);" +
            "      let v = clean(localStorage.getItem(k));" +
            "      if (v && v.length > 30 && v.startsWith('eyJ')) return 'TOKEN:' + v;" +
            "      if (v && v.includes('eyJ')) {" +
            "        let m = v.match(/eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+/);" +
            "        if (m && m[0].length > 40) return 'TOKEN:' + m[0];" +
            "      }" +
            "    }" +
            "    for (let i = 0; i < sessionStorage.length; i++) {" +
            "      let k = sessionStorage.key(i);" +
            "      let v = clean(sessionStorage.getItem(k));" +
            "      if (v && v.includes('eyJ')) {" +
            "        let m = v.match(/eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+/);" +
            "        if (m && m[0].length > 40) return 'TOKEN:' + m[0];" +
            "      }" +
            "    }" +
            "    let m = document.cookie.match(/(?:user_token|pixai_token|token)=(eyJ[a-zA-Z0-9_\\-\\.]+)/);" +
            "    if (m && m[1]) return 'TOKEN:' + m[1];" +
            "    if (window.__pixai_auth_error && window.__pixai_auth_error.length > 0) {" +
            "      return 'AUTH_ERR:' + window.__pixai_auth_error;" +
            "    }" +
            "    return 'WAITING';" +
            "  } catch(e) { return 'ERR: ' + e.message; }" +
            "})()";

        headlessWebView.evaluateJavascript(scanJs, val -> {
            String res = unquote(val);
            if (res.startsWith("TOKEN:")) {
                String token = res.substring(6).trim().replace("\"", "");
                isFinished = true;
                callback.onProgress("  [✓] Đã trích xuất token PixAI thành công!", "#34D399");
                cleanup();
                callback.onSuccess(email, password, token);
            } else if (res.startsWith("AUTH_ERR:")) {
                String errDetail = res.substring(9).trim();
                isFinished = true;
                cleanup();
                if (errDetail.contains("TOO_MANY_REQUESTS") || errDetail.startsWith("429:")) {
                    callback.onError("PIXAI_RATE_LIMIT_429: IP/Proxy hiện tại bị PixAI giới hạn tốc độ (429 Too Many Requests).");
                } else if (errDetail.contains("INVALID_EMAIL")) {
                    callback.onError("PIXAI_INVALID_EMAIL_400: Tên miền email bị PixAI từ chối (" + email + ").");
                } else {
                    callback.onError("PixAI từ chối đăng ký: " + errDetail);
                }
            } else {
                if (attempt == 10) {
                    headlessWebView.evaluateJavascript(
                        "(() => { let s = document.querySelector('button[type=submit]'); if (s && !s.disabled) s.click(); })()",
                        null
                    );
                }
                mainHandler.postDelayed(() -> scanForToken(email, password, attempt + 1, callback), 500);
            }
        });
    }

    // ================= ĐĂNG KÝ WEBSHARE NGẦM ĐỘC LẬP 100% TRONG TIẾN TRÌNH :registrar =================
    @SuppressLint("SetJavaScriptEnabled")
    public void startHeadlessWebshareRegistration(String email, String password, RegistrationCallback callback) {
        mainHandler.post(() -> {
            try {
                isFinished = false;
                stepLoopRunning = false;

                cleanup();

                callback.onProgress("[*] Khởi động phiên Webshare ngầm độc lập (:registrar)...", "#94A3B8");

                headlessWebView = new WebView(context);
                // Cấp phát kích thước khung hình thật 1080x2400 để Google Invisible reCAPTCHA nhận diện màn hình chuẩn
                try {
                    headlessWebView.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY)
                    );
                    headlessWebView.layout(0, 0, 1080, 2400);
                } catch (Throwable ignored) {}
                headlessWebView.onResume();
                headlessWebView.resumeTimers();

                WebSettings settings = headlessWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                // Bật tải ảnh để Google Invisible reCAPTCHA tải đầy đủ logo_48.png và tài nguyên xác thực
                settings.setLoadsImagesAutomatically(true);
                settings.setUserAgentString(SecurityShieldManager.getCleanUserAgent());

                // Chỉ xóa cookie của riêng miền .webshare.io (ssotoken, newDesignLoginToken, token, sessionid, _tid),
                // giữ nguyên cookie .google.com / .gstatic.com để Invisible reCAPTCHA tin cậy tuyệt đối
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(headlessWebView, true);
                String[] wsCookieNames = new String[]{"ssotoken", "newDesignLoginToken", "token", "auth_token", "sessionid", "_tid"};
                String[] wsCookieUrls = new String[]{"https://dashboard.webshare.io", "https://webshare.io", "https://proxy.webshare.io"};
                for (String u : wsCookieUrls) {
                    for (String cn : wsCookieNames) {
                        cm.setCookie(u, cn + "=; path=/; domain=.webshare.io; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                        cm.setCookie(u, cn + "=; path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    }
                }
                cm.flush();

                final String wsHookJs =
                    "(() => {" +
                    "  try {" +
                    "    try {" +
                    "      Object.defineProperty(navigator, 'webdriver', { get: () => false, configurable: true });" +
                    "      Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });" +
                    "      Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });" +
                    "      document.hasFocus = () => true;" +
                    "    } catch(e) {}" +
                    "    if (window.__ws_headless_hooked) return;" +
                    "    window.__ws_headless_hooked = true;" +
                    "    window.__ws_captured_token = window.__ws_captured_token || '';" +
                    "    window.__ws_api_error = window.__ws_api_error || '';" +
                    "    window.__ws_register_req_sent = false;" +
                    "    const origFetch = window.fetch;" +
                    "    if (origFetch) {" +
                    "      window.fetch = function(input, init) {" +
                    "        let u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');" +
                    "        try {" +
                    "          if (u.includes('/register')) window.__ws_register_req_sent = true;" +
                    "          if (init && init.headers) {" +
                    "            let auth = init.headers['Authorization'] || init.headers['authorization'] || (init.headers.get && init.headers.get('Authorization'));" +
                    "            if (auth && String(auth).includes('Token ')) {" +
                    "              let tk = String(auth).replace(/^Token\\s+/i, '').trim();" +
                    "              if (tk.length >= 20 && !tk.startsWith('phc_')) window.__ws_captured_token = tk;" +
                    "            }" +
                    "          }" +
                    "        } catch(e) {}" +
                    "        return origFetch.apply(this, arguments).then(resp => {" +
                    "          try {" +
                    "            if (u.includes('webshare.io') && !u.includes('sakotis.webshare.io')) {" +
                    "              resp.clone().json().then(data => {" +
                    "                if (data && typeof data.token === 'string' && data.token.length >= 20 && !data.token.startsWith('phc_')) {" +
                    "                  window.__ws_captured_token = String(data.token).trim();" +
                    "                }" +
                    "                if (resp.status >= 400 && (u.includes('/register') || u.includes('/login')) && data) {" +
                    "                  window.__ws_api_error = resp.status + ':' + JSON.stringify(data);" +
                    "                }" +
                    "              }).catch(() => {});" +
                    "            }" +
                    "          } catch(e) {}" +
                    "          return resp;" +
                    "        });" +
                    "      };" +
                    "    }" +
                    "    const origOpen = XMLHttpRequest.prototype.open;" +
                    "    const origSend = XMLHttpRequest.prototype.send;" +
                    "    const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;" +
                    "    XMLHttpRequest.prototype.open = function(method, url) {" +
                    "      this.__ws_url = String(url || '');" +
                    "      if (this.__ws_url.includes('/register')) window.__ws_register_req_sent = true;" +
                    "      return origOpen.apply(this, arguments);" +
                    "    };" +
                    "    XMLHttpRequest.prototype.setRequestHeader = function(k, v) {" +
                    "      try {" +
                    "        if (k && k.toLowerCase() === 'authorization' && v && String(v).includes('Token ')) {" +
                    "          let tk = String(v).replace(/^Token\\s+/i, '').trim();" +
                    "          if (tk.length >= 20 && !tk.startsWith('phc_')) window.__ws_captured_token = tk;" +
                    "        }" +
                    "      } catch(e) {}" +
                    "      return origSetHeader.apply(this, arguments);" +
                    "    };" +
                    "    XMLHttpRequest.prototype.send = function() {" +
                    "      this.addEventListener('load', function() {" +
                    "        try {" +
                    "          let u = this.__ws_url || '';" +
                    "          if (u.includes('sakotis.webshare.io')) return;" +
                    "          let txt = (this.responseType === '' || this.responseType === 'text') ? (this.responseText || '') : (typeof this.response === 'string' ? this.response : JSON.stringify(this.response || {}));" +
                    "          if (this.status >= 400 && (u.includes('/register') || u.includes('/login'))) {" +
                    "            window.__ws_api_error = this.status + ':' + (txt || '');" +
                    "          } else if (txt && txt.includes('\"token\"') && (u.includes('webshare.io') || u.includes('/register') || u.includes('/login'))) {" +
                    "            let data = JSON.parse(txt);" +
                    "            if (data && typeof data.token === 'string' && data.token.length >= 20 && !data.token.startsWith('phc_')) {" +
                    "              window.__ws_captured_token = String(data.token).trim();" +
                    "            }" +
                    "          }" +
                    "        } catch(e) {}" +
                    "      });" +
                    "      return origSend.apply(this, arguments);" +
                    "    };" +
                    "  } catch(e) {}" +
                    "})();";

                headlessWebView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                        return true;
                    }

                    @Override
                    public void onProgressChanged(WebView view, int newProgress) {
                        super.onProgressChanged(view, newProgress);
                        view.evaluateJavascript(wsHookJs, null);
                        if (newProgress >= 85 && !stepLoopRunning && !isFinished) {
                            stepLoopRunning = true;
                            fillWebshareForm(email, password, 0, callback);
                        }
                    }
                });

                headlessWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView view, String url, Bitmap favicon) {
                        super.onPageStarted(view, url, favicon);
                        view.evaluateJavascript(wsHookJs, null);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        view.evaluateJavascript(wsHookJs, null);
                        if (!stepLoopRunning && !isFinished) {
                            stepLoopRunning = true;
                            fillWebshareForm(email, password, 0, callback);
                        }
                    }
                });

                callback.onProgress("  [*] Kết nối ngầm tới https://dashboard.webshare.io/register...", "#94A3B8");
                headlessWebView.loadUrl("https://dashboard.webshare.io/register");

                mainHandler.postDelayed(() -> {
                    if (!stepLoopRunning && !isFinished && headlessWebView != null) {
                        stepLoopRunning = true;
                        fillWebshareForm(email, password, 0, callback);
                    }
                }, 3500);

            } catch (Throwable ex) {
                callback.onError("Lỗi khởi tạo phiên Webshare ngầm: " + ex.getMessage());
            }
        });
    }

    private void fillWebshareForm(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 40) {
            callback.onError("Không tìm thấy form đăng ký Webshare sau 16 giây chờ.");
            cleanup();
            return;
        }

        if (attempt % 5 == 0) {
            callback.onProgress("  [*] Đang điền form Webshare & khởi tạo phiên đăng ký... (" + (attempt + 1) + "/40)", "#94A3B8");
        }

        String safeEmail = email.replace("'", "\\'");
        String safePass = password.replace("'", "\\'");
        boolean forceSubmit = (attempt >= 12);

        String fillJs = String.format(
            "(() => {" +
            "  try {" +
            "    if (window.__ws_captured_token && window.__ws_captured_token.length >= 20) return 'TOKEN:' + window.__ws_captured_token;" +
            "    let emailInp = document.querySelector('input[data-testid=\"email-input\"], input#email-input, input[type=\"email\"], input[name=\"email\"]');" +
            "    let passInp = document.querySelector('input[data-testid=\"password-input\"], input[type=\"password\"], input[name=\"password\"]');" +
            "    if (!emailInp || !passInp) {" +
            "      let emailSignBtn = Array.from(document.querySelectorAll('button, a')).find(b => /sign up with email|continue with email/i.test(b.innerText || ''));" +
            "      if (emailSignBtn) emailSignBtn.click();" +
            "      return 'WAITING_INPUTS';" +
            "    }" +
            "    function setReactVal(el, val) {" +
            "      el.focus();" +
            "      el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));" +
            "      el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));" +
            "      el.dispatchEvent(new MouseEvent('click', { bubbles: true }));" +
            "      let oldVal = el.value;" +
            "      let s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
            "      s.call(el, val);" +
            "      if (el._valueTracker) { try { el._valueTracker.setValue(oldVal); } catch(e){} }" +
            "      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));" +
            "      el.dispatchEvent(new Event('input', { bubbles: true }));" +
            "      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true }));" +
            "      el.dispatchEvent(new Event('change', { bubbles: true }));" +
            "      el.dispatchEvent(new Event('blur', { bubbles: true }));" +
            "    }" +
            "    let justFilled = false;" +
            "    if (emailInp.value !== '%s') { setReactVal(emailInp, '%s'); justFilled = true; }" +
            "    if (passInp.value !== '%s') { setReactVal(passInp, '%s'); justFilled = true; }" +
            "    let tosCheck = document.querySelector('[data-testid=\"tos-checkbox\"] input[type=\"checkbox\"], input[type=\"checkbox\"]');" +
            "    if (tosCheck && !tosCheck.checked) {" +
            "      tosCheck.click();" +
            "      if (!tosCheck.checked) {" +
            "        let tosWrap = document.querySelector('[data-testid=\"tos-checkbox\"]');" +
            "        if (tosWrap) tosWrap.click();" +
            "      }" +
            "      justFilled = true;" +
            "    }" +
            "    if (justFilled) return 'FILLED_WAIT_REACT_RENDER';" +
            "    let hasAnchor = !!document.querySelector('iframe[src*=\"recaptcha/api2/anchor\"], iframe[src*=\"recaptcha/enterprise/anchor\"]');" +
            "    let recapReady = hasAnchor && (typeof window.grecaptcha !== 'undefined' && (typeof window.grecaptcha.execute === 'function' || (window.grecaptcha.enterprise && typeof window.grecaptcha.enterprise.execute === 'function')));" +
            "    if (!recapReady && !%b) return 'WAITING_RECAPTCHA';" +
            "    if (!window.__ws_anchor_ready_tick) { window.__ws_anchor_ready_tick = 1; return 'WAITING_ANCHOR_WARMUP'; }" +
            "    if (window.__ws_anchor_ready_tick < 3 && !%b) { window.__ws_anchor_ready_tick++; return 'WAITING_ANCHOR_WARMUP'; }" +
            "    let subBtn = document.querySelector('button[data-testid=\"signup-button\"], button[type=\"submit\"]');" +
            "    if (subBtn && !subBtn.disabled) {" +
            "      subBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));" +
            "      subBtn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));" +
            "      subBtn.click();" +
            "      return 'SUBMITTED_WEBSHARE';" +
            "    }" +
            "    return 'WAITING_BUTTON';" +
            "  } catch(e) { return 'ERR:' + e.message; }" +
            "})()", safeEmail, safeEmail, safePass, safePass, forceSubmit, forceSubmit);

        headlessWebView.evaluateJavascript(fillJs, val -> {
            String res = unquote(val);
            if (res.startsWith("TOKEN:")) {
                String tok = res.substring(6).trim();
                isFinished = true;
                callback.onProgress("  [✓] Đã lấy được API Token Webshare!", "#34D399");
                cleanup();
                callback.onSuccess(email, password, tok);
            } else if (res.contains("SUBMITTED_WEBSHARE")) {
                callback.onProgress("  [✓] Đã điền Email, Mật khẩu & gửi đăng ký Webshare!", "#34D399");
                mainHandler.postDelayed(() -> scanForWebshareToken(email, password, 0, callback), 600);
            } else {
                mainHandler.postDelayed(() -> fillWebshareForm(email, password, attempt + 1, callback), 400);
            }
        });
    }

    private void scanForWebshareToken(String email, String password, int attempt, RegistrationCallback callback) {
        if (isFinished || headlessWebView == null) return;

        if (attempt > 55) {
            callback.onError("RECAPTCHA_OR_TIMEOUT: Webshare yêu cầu xác thực trên màn hình chính.");
            cleanup();
            return;
        }

        if (attempt % 5 == 0) {
            callback.onProgress("  [*] Đang chờ Webshare cấp phát API Token... (lần " + (attempt + 1) + "/55)", "#94A3B8");
        }

        try {
            CookieManager cm = CookieManager.getInstance();
            String[] cookieDomains = new String[]{"https://dashboard.webshare.io", "https://proxy.webshare.io", "https://webshare.io"};
            for (String dUrl : cookieDomains) {
                String cookies = cm.getCookie(dUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    Matcher m = Pattern.compile("(?:ssotoken|newDesignLoginToken|token|auth_token)=\"?([a-zA-Z0-9_\\-]{20,64})\"?").matcher(cookies);
                    while (m.find()) {
                        String tok = m.group(1);
                        if (tok != null && tok.length() >= 20 && !tok.startsWith("phc_")) {
                            isFinished = true;
                            callback.onProgress("  [✓] Đã trích xuất API Token Webshare từ CookieManager!", "#34D399");
                            cleanup();
                            callback.onSuccess(email, password, tok);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        String safeEmail = email.replace("'", "\\'");
        String safePass = password.replace("'", "\\'");

        String scanJs =
            "(() => {" +
            "  try {" +
            "    if (window.__ws_captured_token && window.__ws_captured_token.length >= 20 && !window.__ws_captured_token.startsWith('phc_')) return 'TOKEN:' + window.__ws_captured_token;" +
            "    if (window.__ws_api_error) return 'API_ERR:' + window.__ws_api_error;" +
            "    let cm = document.cookie.match(/(?:ssotoken|newDesignLoginToken|token|auth_token)=\"?([a-zA-Z0-9_\\-]{20,64})\"?/i);" +
            "    if (cm && cm[1] && !cm[1].startsWith('phc_')) return 'TOKEN:' + cm[1];" +
            "    for (let s of [localStorage, sessionStorage]) {" +
            "      if (!s) continue;" +
            "      for (let i = 0; i < s.length; i++) {" +
            "        let k = s.key(i);" +
            "        if (!k || k.includes('posthog') || k.includes('intercom') || k.includes('klaviyo')) continue;" +
            "        let v = String(s.getItem(k) || '').replace(/^\"+|\"+$/g, '').trim();" +
            "        if (/ssotoken|newDesignLoginToken|token|auth/i.test(k) && /^[a-zA-Z0-9_\\-]{25,60}$/.test(v) && !v.startsWith('phc_')) return 'TOKEN:' + v;" +
            "        if (v.startsWith('{') && v.includes('\"token\"')) {" +
            "          try {" +
            "            let parsed = JSON.parse(v);" +
            "            if (parsed && typeof parsed.token === 'string' && parsed.token.length >= 20 && !parsed.token.startsWith('phc_')) return 'TOKEN:' + parsed.token;" +
            "          } catch(e){}" +
            "        }" +
            "      }" +
            "    }" +
            "    let bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';" +
            "    if (/throttled|too many attempts/i.test(bodyTxt)) return 'API_ERR:429:Throttled';" +
            "    if (/cannot be used|disposable|valid email address|already exists|suspicious/i.test(bodyTxt)) return 'API_ERR:400:EmailRejected';" +
            "    let bf = document.querySelector('iframe[src*=\"recaptcha/api2/bframe\"], iframe[src*=\"recaptcha/enterprise/bframe\"]');" +
            "    if (bf) {" +
            "      let rect = bf.getBoundingClientRect();" +
            "      let pStyle = bf.parentElement ? window.getComputedStyle(bf.parentElement) : null;" +
            "      if (rect.height > 200 && (!pStyle || (pStyle.visibility !== 'hidden' && pStyle.opacity !== '0'))) {" +
            "        window.__ws_bframe_ticks = (window.__ws_bframe_ticks || 0) + 1;" +
            "        if (window.__ws_bframe_ticks >= 4) return 'RECAPTCHA_CHALLENGE';" +
            "      }" +
            "    }" +
            "    return 'WAITING:' + (window.__ws_register_req_sent ? 'REQ_SENT' : 'RECAPTCHA_EXEC');" +
            "  } catch(e) { return 'ERR:' + e.message; }" +
            "})()";

        headlessWebView.evaluateJavascript(scanJs, val -> {
            String res = unquote(val);
            if (res.startsWith("TOKEN:")) {
                String token = res.substring(6).trim().replace("\"", "");
                isFinished = true;
                callback.onProgress("  [✓] Đã trích xuất thành công API Token Webshare từ tiến trình ngầm!", "#34D399");
                cleanup();
                callback.onSuccess(email, password, token);
            } else if (res.startsWith("API_ERR:")) {
                String err = res.substring(8);
                isFinished = true;
                cleanup();
                callback.onError("API_ERR:" + err);
            } else if (res.equals("RECAPTCHA_CHALLENGE")) {
                isFinished = true;
                cleanup();
                callback.onError("RECAPTCHA_CHALLENGE: Cần xác nhận nhanh 1 chạm trên cửa sổ Webshare.");
            } else {
                // TUYỆT ĐỐI KHÔNG bấm lại signup-button mỗi 3 giây vì sẽ gọi grecaptcha.reset() làm hủy tiến trình reCAPTCHA!
                // Chỉ thử click lại đúng 1 lần duy nhất ở attempt == 24 (sau 12 giây) nếu request /register chưa được gửi đi.
                if (attempt == 24 && res.contains("RECAPTCHA_EXEC")) {
                    headlessWebView.evaluateJavascript(
                        String.format(
                        "(() => {" +
                        "  if (window.__ws_register_req_sent) return;" +
                        "  let sub = document.querySelector('button[data-testid=\"signup-button\"], button[type=\"submit\"]');" +
                        "  if (sub && !sub.disabled) sub.click();" +
                        "})()", safeEmail, safePass), null);
                }
                mainHandler.postDelayed(() -> scanForWebshareToken(email, password, attempt + 1, callback), 500);
            }
        });
    }

    private String unquote(String val) {
        if (val == null) return "";
        val = val.trim();
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1);
        }
        return val.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void cleanup() {
        stepLoopRunning = false;
        if (headlessWebView != null) {
            try {
                headlessWebView.evaluateJavascript("try { localStorage.clear(); sessionStorage.clear(); } catch(e){}", null);
                headlessWebView.stopLoading();
                headlessWebView.onPause();
                headlessWebView.destroy();
            } catch (Throwable ignored) {}
            headlessWebView = null;
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {}
    }

    public void destroy() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup();
        } else {
            mainHandler.post(this::cleanup);
        }
    }
}
