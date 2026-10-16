package com.pixai.nexus;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class PixAiApiHelper {

    private static final String TEMP_MAIL_IO_URL = "https://api.internal.temp-mail.io";
    private static final String TEMP_MAIL_ORG_URL = "https://web2.temp-mail.org";
    private static final String PIXAI_BASE_URL = "https://api.pixai.art";

    public static class Mailbox {
        public String address;
        public String token;
        public String provider;

        public Mailbox(String address, String token, String provider) {
            this.address = address;
            this.token = token;
            this.provider = provider;
        }
    }

    public interface LogCallback {
        void log(String message, String color);
        default void log(String message) {
            log(message, "#FFFFFF");
        }
    }

    public interface ProgressCallback {
        void onProgress(String message, int elapsedSeconds);
    }

    public static final String GRAPHQL_URL = "https://api.pixai.art/graphql";
    public static final String PERSISTED_HASH = "83f9afa2ae341c3dd9dd1ed56d205331a8fa076215e4a651c462bbca8a437725";

    public static final String MODEL_TSUBAKI_FLASH = "2050048243034896798";
    public static final String MODEL_TSUBAKI_PRO = "2024383379556065549";

    public static final String REALISTIC_LORA_ID = "2059543732132340175";
    public static final String REALISTIC_LORA_TRIGGERS = "impossiblebeauty_sr, unrealistically beautiful face, photorealistic anatomy, idealized facial proportions, luminous eyes, flawless skin, realistic hair, natural human proportions";

    public static final String DEFAULT_NEGATIVE = "lowres, worst quality, low quality, bad anatomy, explicit, watermark, username, signature, blurry";

    public static class TaskResult {
        public boolean success = false;
        public boolean insufficientBalance = false;
        public String taskId = "";
        public List<String> imageUrls = new ArrayList<>();
        public String originalPrompt = "";
        public String serverPrompt = "";
        public long seed = 0;
        public String errorMessage = "";
    }

    public static class CreateTaskResult {
        public boolean success = false;
        public boolean insufficientBalance = false;
        public String taskId = "";
        public String errorMessage = "";
        public long paidCredit = 0;
    }

    public static class PollStatusResult {
        public String status = "unknown";
        public boolean completed = false;
        public boolean failed = false;
        public List<String> imageUrls = new ArrayList<>();
        public String serverPrompt = "";
        public long seed = 0;
        public String errorMessage = "";
    }

    static {
        try {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        } catch (Throwable ignored) {}
        try {
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        String host = getRequestingHost();
                        int port = getRequestingPort();
                        SecurityShieldManager.ParsedProxy pp = SecurityShieldManager.findProxyForHost(host, port);
                        if (pp != null && pp.user != null) {
                            return new PasswordAuthentication(pp.user, (pp.pass != null ? pp.pass : "").toCharArray());
                        }
                    }
                    return super.getPasswordAuthentication();
                }
            });
        } catch (Throwable ignored) {}
    }

    public static HttpURLConnection openConnectionWithProxy(URL url, String proxyStr) throws Exception {
        if (proxyStr == null || proxyStr.trim().isEmpty() || !SecurityShieldManager.isUseUpstreamProxy()) {
            return (HttpURLConnection) url.openConnection();
        }
        SecurityShieldManager.ensureLocalTunnelRunning();
        int localPort = SecurityShieldManager.getLocalTunnelPort();
        if (localPort > 0) {
            Proxy localProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", localPort));
            return (HttpURLConnection) url.openConnection(localProxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    public static JSONObject sendGraphQL(JSONObject payload, String token, String proxyStr) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/graphql-response+json,application/json;q=0.9");
        headers.put("Origin", "https://pixai.art");
        headers.put("Referer", "https://pixai.art/");
        if (token != null && !token.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + token.trim());
        }
        String body = payload != null ? payload.toString() : "";
        String resp = httpRequestWithHeaders(GRAPHQL_URL, "POST", headers, body, proxyStr, 25);
        if (resp == null || resp.trim().isEmpty() || resp.startsWith("{\"error\":")) {
            throw new java.io.IOException("Proxy Shield: Không thể kết nối qua Proxy (" + resp + ")");
        }
        return new JSONObject(resp.trim());
    }

    /**
     * Thực thi HTTP/HTTPS qua Socket Tunnel (HTTP CONNECT / SOCKS5) chuẩn xác từ bản pixai-android 2.0.0.
     * Bỏ qua hoàn toàn giới hạn HttpURLConnection của Android, hỗ trợ 100% Webshare proxy basic auth và SOCKS5.
     */
    public static String executeSocketHttpRequest(
            String urlStr,
            String method,
            Map<String, String> customHeaders,
            byte[] bodyBytes,
            SecurityShieldManager.ParsedProxy pp,
            int timeoutSec) {
        if (pp == null && SecurityShieldManager.isStrictKillSwitchActive() && !SecurityShieldManager.isFallbackDirectAllowed()) {
            return "{\"error\":\"Kill-Switch: Đã khóa mạng trực tiếp để bảo vệ IP gốc\"}";
        }
        Socket rawSocket = null;
        SSLSocket sslSocket = null;
        try {
            URL url = new URL(urlStr);
            boolean isHttps = "https".equalsIgnoreCase(url.getProtocol());
            String host = url.getHost();
            int port = (url.getPort() > 0) ? url.getPort() : (isHttps ? 443 : 80);
            String path = (url.getFile() == null || url.getFile().isEmpty()) ? "/" : url.getFile();

            OutputStream os;
            InputStream is;

            if (pp != null) {
                rawSocket = SecurityShieldManager.openUpstreamProxyTunnel(pp, host, port);
                rawSocket.setSoTimeout(timeoutSec * 1000);
            } else {
                rawSocket = new Socket();
                rawSocket.setTcpNoDelay(true);
                rawSocket.setSoTimeout(timeoutSec * 1000);
                rawSocket.connect(new InetSocketAddress(host, port), Math.min(timeoutSec, 10) * 1000);
            }

            if (isHttps) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                sslSocket = (SSLSocket) factory.createSocket(rawSocket, host, port, true);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    SSLParameters sslParams = sslSocket.getSSLParameters();
                    sslParams.setServerNames(Collections.singletonList(new SNIHostName(host)));
                    sslSocket.setSSLParameters(sslParams);
                }
                sslSocket.startHandshake();
                os = sslSocket.getOutputStream();
                is = sslSocket.getInputStream();
            } else {
                os = rawSocket.getOutputStream();
                is = rawSocket.getInputStream();
            }

            StringBuilder req = new StringBuilder();
            req.append(method != null ? method : "GET").append(" ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("User-Agent: ").append(SecurityShieldManager.getCleanUserAgent()).append("\r\n");
            req.append("Accept: application/json, text/plain, */*\r\n");
            if (customHeaders != null) {
                for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    String k = entry.getKey();
                    String v = entry.getValue();
                    if (k != null && v != null && !k.equalsIgnoreCase("Host") && !k.equalsIgnoreCase("User-Agent") && !k.equalsIgnoreCase("Content-Length")) {
                        req.append(k).append(": ").append(v).append("\r\n");
                    }
                }
            }
            if (bodyBytes != null) {
                if (customHeaders == null || !customHeaders.containsKey("Content-Type")) {
                    req.append("Content-Type: application/json\r\n");
                }
                req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                req.append("Content-Length: 0\r\n");
            }
            req.append("Connection: close\r\n\r\n");

            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes != null && bodyBytes.length > 0) {
                os.write(bodyBytes);
            }
            os.flush();

            return readHttpResponseBody(is);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } finally {
            try { if (sslSocket != null) sslSocket.close(); } catch (Exception ignored) {}
            try { if (rawSocket != null) rawSocket.close(); } catch (Exception ignored) {}
        }
    }

    public static String executeSocketHttpRequest(
            String urlStr,
            String method,
            Map<String, String> customHeaders,
            String body,
            SecurityShieldManager.ParsedProxy pp,
            int timeoutSec) {
        byte[] bodyBytes = (body != null && !body.isEmpty()) ? body.getBytes(StandardCharsets.UTF_8) : null;
        return executeSocketHttpRequest(urlStr, method, customHeaders, bodyBytes, pp, timeoutSec);
    }

    private static String readAsciiLine(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(128);
        int b;
        boolean gotAny = false;
        while ((b = is.read()) != -1) {
            gotAny = true;
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (!gotAny && baos.size() == 0) return null;
        return new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String readHttpResponseBody(InputStream is) throws Exception {
        String status = readAsciiLine(is);
        if (status == null) return "";

        boolean isChunked = false;
        int contentLength = -1;
        String headerLine;
        while ((headerLine = readAsciiLine(is)) != null) {
            if (headerLine.isEmpty()) break;
            String lower = headerLine.toLowerCase(java.util.Locale.US);
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(headerLine.substring(15).trim());
                } catch (Exception ignored) {}
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                isChunked = true;
            }
        }

        java.io.ByteArrayOutputStream bodyOut = new java.io.ByteArrayOutputStream();
        if (contentLength >= 0) {
            byte[] buf = new byte[4096];
            int remaining = contentLength;
            while (remaining > 0) {
                int r = is.read(buf, 0, Math.min(buf.length, remaining));
                if (r == -1) break;
                bodyOut.write(buf, 0, r);
                remaining -= r;
            }
        } else if (isChunked) {
            byte[] buf = new byte[4096];
            while (true) {
                String chunkHeader = readAsciiLine(is);
                if (chunkHeader == null) break;
                chunkHeader = chunkHeader.trim();
                if (chunkHeader.isEmpty()) continue;
                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(chunkHeader.split(";")[0].trim(), 16);
                } catch (Exception e) {
                    break;
                }
                if (chunkSize <= 0) break;
                int remaining = chunkSize;
                while (remaining > 0) {
                    int r = is.read(buf, 0, Math.min(buf.length, remaining));
                    if (r == -1) break;
                    bodyOut.write(buf, 0, r);
                    remaining -= r;
                }
                readAsciiLine(is);
            }
        } else {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bodyOut.write(buf, 0, r);
            }
        }
        return new String(bodyOut.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String httpRequestWithHeaders(
            String urlStr,
            String method,
            Map<String, String> customHeaders,
            byte[] bodyBytes,
            String proxyStr,
            int timeoutSec) {
        String effectiveProxy = (proxyStr != null && !proxyStr.trim().isEmpty())
                ? proxyStr.trim()
                : (SecurityShieldManager.isUseUpstreamProxy() ? SecurityShieldManager.getActiveUpstreamProxyRaw() : "");

        String res = executeRequestInternal(urlStr, method, customHeaders, bodyBytes, effectiveProxy, timeoutSec);

        if (effectiveProxy != null && !effectiveProxy.isEmpty() && isErrorResponse(res)) {
            String lastTried = effectiveProxy;
            for (int attempt = 0; attempt < 3 && isErrorResponse(res); attempt++) {
                String altProxy = SecurityShieldManager.getNextWorkingProxyCandidate(lastTried);
                if (altProxy == null || altProxy.isEmpty() || altProxy.equalsIgnoreCase(lastTried)) {
                    break;
                }
                lastTried = altProxy;
                res = executeRequestInternal(urlStr, method, customHeaders, bodyBytes, altProxy, timeoutSec);
            }

            if (isErrorResponse(res) && SecurityShieldManager.isFallbackDirectAllowed()) {
                res = executeRequestInternal(urlStr, method, customHeaders, bodyBytes, "", timeoutSec);
            }
        }
        return res != null ? res : "";
    }

    public static String httpRequestWithHeaders(
            String urlStr,
            String method,
            Map<String, String> customHeaders,
            String body,
            String proxyStr,
            int timeoutSec) {
        byte[] bodyBytes = (body != null && !body.isEmpty()) ? body.getBytes(StandardCharsets.UTF_8) : null;
        return httpRequestWithHeaders(urlStr, method, customHeaders, bodyBytes, proxyStr, timeoutSec);
    }

    private static boolean isErrorResponse(String res) {
        return res == null || res.isEmpty() || res.startsWith("{\"error\":");
    }

    private static String executeRequestInternal(
            String urlStr,
            String method,
            Map<String, String> customHeaders,
            byte[] bodyBytes,
            String proxyStr,
            int timeoutSec) {
        SecurityShieldManager.ParsedProxy pp = SecurityShieldManager.parseProxyString(proxyStr);
        if (pp != null) {
            return executeSocketHttpRequest(urlStr, method, customHeaders, bodyBytes, pp, timeoutSec);
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method != null ? method : "GET");
            conn.setRequestProperty("User-Agent", SecurityShieldManager.getCleanUserAgent());
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (customHeaders != null) {
                for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);

            if (bodyBytes != null && bodyBytes.length > 0 && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    public static boolean unblurNsfw(String token, String proxyStr, StringBuilder proofLog) {
        String cleanToken = (token != null && token.startsWith("Bearer ")) ? token.substring(7).trim() : (token != null ? token.trim() : "");
        try {
            // 1. Kích hoạt tùy chọn xác thực người lớn (Adult) và bỏ làm mờ qua REST Content-Safety API
            String[] endpoints = new String[]{
                "https://api.pixai.art/v2/me/content-safety/preferences/age-verified",
                "https://api.pixai.art/v2/me/content-safety/preferences/show-nsfw",
                "https://api.pixai.art/v2/me/content-safety/preferences/blur-nsfw"
            };
            String[] values = new String[]{"adult", "show", "show"};
            for (int i = 0; i < endpoints.length; i++) {
                try {
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("Authorization", "Bearer " + cleanToken);
                    h.put("Content-Type", "application/json");
                    h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    httpRequestWithHeaders(endpoints[i], "PUT", h, "{\"value\":\"" + values[i] + "\"}", proxyStr, 10);
                } catch (Exception ignored) {}
            }

            // 2. Đồng bộ tùy chọn qua GraphQL setPreferences
            JSONObject q = new JSONObject();
            q.put("query", "mutation setPreferences($value: JSONObject!) {\n" +
                    "  setPreferences(value: $value)\n" +
                    "}");
            JSONObject vars = new JSONObject();
            JSONObject val = new JSONObject();
            val.put("ageVerified", "adult");
            val.put("isAdult", true);
            val.put("adult", true);
            val.put("ageConfirmed", true);
            val.put("ageVerificationStatus", "OVER18");
            val.put("rating", "r18");
            val.put("contentRating", "ADULT");
            val.put("birthday", "1998-05-20");
            val.put("age", 26);
            val.put("year", 1998);
            val.put("month", 5);
            val.put("day", 20);
            val.put("isNsfw", true);
            val.put("showNsfw", true);
            val.put("blurNsfw", false);
            val.put("nsfwFilter", false);
            vars.put("value", val);
            q.put("variables", vars);

            JSONObject res = sendGraphQL(q, cleanToken, proxyStr);

            // 3. Lấy bằng chứng xác nhận chính thức từ Content-Safety API
            String verifiedPrefs = "";
            try {
                Map<String, String> getHeaders = new LinkedHashMap<>();
                getHeaders.put("Authorization", "Bearer " + cleanToken);
                getHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                String resp = httpRequestWithHeaders("https://api.pixai.art/v2/me/content-safety/preferences", "GET", getHeaders, (String) null, proxyStr, 10);
                if (resp != null && !resp.isEmpty() && !resp.startsWith("{\"error\":")) {
                    verifiedPrefs = resp.trim();
                }
            } catch (Exception ignored) {}

            if (proofLog != null) {
                proofLog.append("BẰNG CHỨNG XÁC THỰC MÁY CHỦ PIXAI:\n");
                proofLog.append("1. REST Content-Safety API: ").append(verifiedPrefs.isEmpty() ? "Đã cập nhật adult" : verifiedPrefs).append("\n");
                proofLog.append("2. Mutation setPreferences: ").append(res.toString());
            }
            return !res.has("errors");
        } catch (Exception e) {
            if (proofLog != null) {
                proofLog.append("Lỗi xác nhận 18+: ").append(e.getMessage());
            }
            return false;
        }
    }

    public static void unblurNsfw(String token, String proxyStr) {
        unblurNsfw(token, proxyStr, null);
    }

    public static long estimateTaskCost(String profile, String sizeTier, int width, int height, int batchCount, boolean isReal) {
        long base;
        long pixels = (long) width * height;
        int tier; // 0=M, 1=L, 2=XL
        if (pixels <= 768L * 1280L) tier = 0;
        else if (pixels <= 944L * 1584L) tier = 1;
        else tier = 2;

        if ("ultra".equalsIgnoreCase(profile)) {
            if (batchCount == 4) {
                base = (tier == 0) ? 13000 : (tier == 1 ? 16500 : 21500);
            } else {
                base = (tier == 0) ? 5500 : (tier == 1 ? 7000 : 9000);
            }
        } else if ("pro".equalsIgnoreCase(profile)) {
            if (batchCount == 4) {
                base = (tier == 0) ? 9500 : (tier == 1 ? 12000 : 15500);
            } else {
                base = (tier == 0) ? 4000 : (tier == 1 ? 5000 : 6500);
            }
        } else {
            // Flash
            if (batchCount == 4) {
                base = (tier == 0) ? 4400 : (tier == 1 ? 5500 : 7000);
            } else {
                base = (tier == 0) ? 1900 : (tier == 1 ? 2400 : 3000);
            }
        }
        if (isReal) {
            base += 500;
        }
        return base;
    }

    public static long getQuota(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return 0;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + token.trim());
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            String res = httpRequestWithHeaders("https://api.pixai.art/v2/user/me/quota", "GET", headers, (String) null, proxyStr, 10);
            if (res != null && !res.isEmpty() && !res.startsWith("{\"error\":")) {
                JSONObject json = new JSONObject(res.trim());
                return json.optLong("amount", 0);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static boolean claimDailyCredits(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", "Bearer " + token.trim());
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            String res = httpRequestWithHeaders("https://api.pixai.art/v2/claim/pixai-daily-credits", "POST", headers, "{\"id\":\"pixai-daily-credits\"}", proxyStr, 10);
            return res != null && !res.isEmpty() && !res.startsWith("{\"error\":");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static int claimSocialAndVisitQuests(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return 0;
        int successCount = 0;
        String[] platforms = {"discord", "twitter", "instagram", "youtube", "tiktok"};
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + token.trim());
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        for (String p : platforms) {
            try {
                String res = httpRequestWithHeaders("https://api.pixai.art/v2/quest-v2/report-social-follow", "POST", headers, "{\"platform\":\"" + p + "\"}", proxyStr, 8);
                if (res != null && !res.isEmpty() && !res.startsWith("{\"error\":")) successCount++;
            } catch (Exception ignored) {}
        }

        String[] visits = {"https://youtu.be/xGBoyWR4MBQ", "https://agent.pixai.art/"};
        for (String v : visits) {
            try {
                String res = httpRequestWithHeaders("https://api.pixai.art/v2/quest-v2/report-visit", "POST", headers, "{\"url\":\"" + v + "\"}", proxyStr, 8);
                if (res != null && !res.isEmpty() && !res.startsWith("{\"error\":")) successCount++;
            } catch (Exception ignored) {}
        }
        return successCount;
    }

    public static TaskResult createAndPollGenerationTask(
            String token,
            String profile, // "flash", "pro", "ultra"
            boolean isReal,
            String qualityMode, // "standard", "high", "ultra"
            int width,
            int height,
            int batchSize,
            String prompt,
            String customNegative,
            String attachedMediaId,
            String proxyStr,
            ProgressCallback callback
    ) {
        List<String> list = new ArrayList<>();
        if (attachedMediaId != null && !attachedMediaId.trim().isEmpty()) {
            list.add(attachedMediaId.trim());
        }
        return createAndPollGenerationTask(token, profile, isReal, qualityMode, width, height, batchSize, prompt, customNegative, list, proxyStr, callback);
    }

    public static CreateTaskResult createGenerationTask(
            String token,
            String profile,
            boolean isReal,
            String qualityMode,
            int width,
            int height,
            int batchSize,
            String prompt,
            String customNegative,
            List<String> attachedMediaIds,
            String proxyStr
    ) {
        CreateTaskResult result = new CreateTaskResult();
        try {
            String modelId = "flash".equalsIgnoreCase(profile) ? MODEL_TSUBAKI_FLASH : MODEL_TSUBAKI_PRO;

            JSONObject params = new JSONObject();
            params.put("modelId", modelId);
            params.put("inferenceProfile", profile.toLowerCase());
            params.put("width", width);
            params.put("height", height);
            params.put("priority", 1000); // 1000 là mức ưu tiên chuẩn web PixAI kết xuất siêu tốc (~24s)
            params.put("prompts", prompt);
            params.put("seed", "");
            params.put("batchSize", batchSize);
            params.put("controlNets", new JSONArray());

            JSONObject extra = new JSONObject();
            extra.put("naturalPrompts", prompt);
            params.put("extra", extra);

            JSONObject promptHelper = new JSONObject();
            promptHelper.put("forcePromptHelperDetectionSide", "server");
            promptHelper.put("creativity", "medium");
            params.put("promptHelper", promptHelper);

            JSONObject qualityTag = new JSONObject();
            if ("high".equalsIgnoreCase(qualityMode)) {
                qualityTag.put("prefix", "masterpiece, best quality, ultra-detailed");
                qualityTag.put("suffix", "");
            } else if ("ultra".equalsIgnoreCase(qualityMode)) {
                qualityTag.put("prefix", "masterpiece, best quality, photorealistic, 8k resolution");
                qualityTag.put("suffix", ", absurdres");
            } else {
                qualityTag.put("prefix", "");
                qualityTag.put("suffix", "");
            }
            params.put("qualityTag", qualityTag);

            if (!"flash".equalsIgnoreCase(profile) || (customNegative != null && !customNegative.trim().isEmpty())) {
                String neg = (customNegative != null && !customNegative.trim().isEmpty()) ? customNegative : DEFAULT_NEGATIVE;
                params.put("negativePrompts", neg);
            }

            boolean hasContext = false;
            if (attachedMediaIds != null && !attachedMediaIds.isEmpty()) {
                JSONArray ctx = new JSONArray();
                for (String mid : attachedMediaIds) {
                    if (mid != null && !mid.trim().isEmpty()) {
                        ctx.put(mid.trim());
                        hasContext = true;
                    }
                }
                if (hasContext) {
                    params.put("contextImages", ctx);
                }
            }

            if (!hasContext && isReal) {
                JSONObject loraMap = new JSONObject();
                loraMap.put(REALISTIC_LORA_ID, 0.70);
                params.put("lora", loraMap);

                JSONArray loraParams = new JSONArray();
                JSONObject lp = new JSONObject();
                lp.put("versionId", REALISTIC_LORA_ID);
                lp.put("weight", 0.70);
                lp.put("triggerWords", REALISTIC_LORA_TRIGGERS);
                JSONObject pos = new JSONObject();
                pos.put("startIndex", 0);
                pos.put("endIndex", 0);
                lp.put("positionInfo", pos);
                loraParams.put(lp);
                params.put("loraParameters", loraParams);
            }

            JSONObject vars = new JSONObject();
            vars.put("parameters", params);

            JSONObject payload = new JSONObject();
            payload.put("operationName", "createGenerationTask");
            payload.put("variables", vars);

            JSONObject ext = new JSONObject();
            JSONObject clientLib = new JSONObject();
            clientLib.put("name", "@apollo/client");
            clientLib.put("version", "4.1.4");
            ext.put("clientLibrary", clientLib);

            JSONObject pers = new JSONObject();
            pers.put("version", 1);
            pers.put("sha256Hash", PERSISTED_HASH);
            ext.put("persistedQuery", pers);
            payload.put("extensions", ext);

            JSONObject createRes = sendGraphQL(payload, token, proxyStr);

            JSONArray errors = createRes.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                JSONObject err0 = errors.getJSONObject(0);
                String errMsg = err0.optString("message", "");
                JSONObject extObj = err0.optJSONObject("extensions");
                JSONObject excObj = extObj != null ? extObj.optJSONObject("exception") : null;
                String excName = excObj != null ? excObj.optString("name", "") : "";
                int excCode = excObj != null ? excObj.optInt("code", 0) : 0;

                if (errMsg.toLowerCase().contains("insufficient balance") || errMsg.toLowerCase().contains("balance")) {
                    result.insufficientBalance = true;
                    result.errorMessage = "Tài khoản không đủ Credit để tạo ảnh";
                    return result;
                }
                if (excCode == 40300019 || "NSFW_PROMPTS_DETECTED".equalsIgnoreCase(excName) || errMsg.contains("policy") || errMsg.contains("adult")) {
                    result.errorMessage = "Máy chủ PixAI chặn từ khóa vi phạm chính sách kiểm duyệt. Gợi ý: Hãy dùng từ gợi cảm nghệ thuật như 'cleavage, bikini, lingerie, wet skin, bare shoulders' thay vì từ cấm trực tiếp!";
                    return result;
                }
                result.errorMessage = "Máy chủ PixAI báo lỗi: " + errMsg;
                return result;
            }

            JSONObject taskObj = createRes.optJSONObject("data");
            if (taskObj != null) taskObj = taskObj.optJSONObject("createGenerationTask");
            if (taskObj == null || !taskObj.has("id")) {
                result.success = false;
                result.errorMessage = "Máy chủ từ chối tạo task: " + createRes.toString();
                return result;
            }

            result.taskId = taskObj.getString("id");
            result.paidCredit = taskObj.optLong("paidCredit", 0);
            result.success = true;
            return result;
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = "Lỗi kết nối: " + e.getMessage();
            return result;
        }
    }

    public static PollStatusResult pollTaskMedia(String taskId, String token, String proxyStr) {
        PollStatusResult result = new PollStatusResult();
        if (taskId == null || taskId.trim().isEmpty()) {
            result.failed = true;
            result.errorMessage = "Task ID không hợp lệ";
            return result;
        }

        try {
            String queryTask = "query getTaskMedia($id: ID!) {\n" +
                    "  task(id: $id) {\n" +
                    "    id\n" +
                    "    status\n" +
                    "    parameters\n" +
                    "    outputs\n" +
                    "    media {\n" +
                    "      id\n" +
                    "      urls {\n" +
                    "        variant\n" +
                    "        url\n" +
                    "      }\n" +
                    "    }\n" +
                    "  }\n" +
                    "}";

            JSONObject pollPayload = new JSONObject();
            pollPayload.put("query", queryTask);
            JSONObject pollVars = new JSONObject();
            pollVars.put("id", taskId);
            pollPayload.put("variables", pollVars);

            JSONObject pollRes = sendGraphQL(pollPayload, token, proxyStr);
            JSONObject tNode = pollRes.optJSONObject("data");
            if (tNode != null) tNode = tNode.optJSONObject("task");
            if (tNode != null) {
                String status = tNode.optString("status", "").toLowerCase();
                result.status = status;
                if ("completed".equalsIgnoreCase(status)) {
                    result.completed = true;
                    JSONObject pObj = tNode.optJSONObject("parameters");
                    if (pObj != null) {
                        result.serverPrompt = pObj.optString("prompts", "");
                    }

                    JSONObject oObj = tNode.optJSONObject("outputs");
                    if (oObj != null) {
                        result.seed = oObj.optLong("seed", 0);

                        // Trích xuất danh sách mediaId riêng lẻ khi tạo nhiều ảnh (Batch 4)
                        JSONArray batch = oObj.optJSONArray("batch");
                        if (batch != null && batch.length() > 0) {
                            List<String> mediaIds = new ArrayList<>();
                            for (int i = 0; i < batch.length(); i++) {
                                JSONObject bItem = batch.optJSONObject(i);
                                if (bItem != null) {
                                    String mId = bItem.optString("mediaId", "");
                                    if (!mId.isEmpty()) {
                                        mediaIds.add(mId);
                                    }
                                }
                            }

                            if (!mediaIds.isEmpty()) {
                                StringBuilder qSb = new StringBuilder();
                                qSb.append("query getBatchMedias(");
                                for (int i = 0; i < mediaIds.size(); i++) {
                                    if (i > 0) qSb.append(", ");
                                    qSb.append("$id").append(i).append(": String!");
                                }
                                qSb.append(") {\n");
                                for (int i = 0; i < mediaIds.size(); i++) {
                                    qSb.append("  m").append(i).append(": media(id: $id").append(i).append(") { urls { variant url } }\n");
                                }
                                qSb.append("}");

                                JSONObject bPayload = new JSONObject();
                                bPayload.put("query", qSb.toString());
                                JSONObject bVars = new JSONObject();
                                for (int i = 0; i < mediaIds.size(); i++) {
                                    bVars.put("id" + i, mediaIds.get(i));
                                }
                                bPayload.put("variables", bVars);

                                try {
                                    JSONObject bRes = sendGraphQL(bPayload, token, proxyStr);
                                    JSONObject bData = bRes.optJSONObject("data");
                                    if (bData != null) {
                                        for (int i = 0; i < mediaIds.size(); i++) {
                                            JSONObject mObj = bData.optJSONObject("m" + i);
                                            if (mObj != null) {
                                                JSONArray urls = mObj.optJSONArray("urls");
                                                if (urls != null) {
                                                    String pubUrl = null;
                                                    for (int u = 0; u < urls.length(); u++) {
                                                        JSONObject uObj = urls.optJSONObject(u);
                                                        if (uObj != null && "PUBLIC".equalsIgnoreCase(uObj.optString("variant"))) {
                                                            pubUrl = uObj.optString("url");
                                                            break;
                                                        }
                                                    }
                                                    if (pubUrl == null && urls.length() > 0) {
                                                        JSONObject uObj = urls.optJSONObject(0);
                                                        if (uObj != null) pubUrl = uObj.optString("url");
                                                    }
                                                    if (pubUrl != null && !pubUrl.isEmpty()) {
                                                        result.imageUrls.add(pubUrl);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Fallback nếu không có batch: Lấy ảnh đơn từ node media
                    if (result.imageUrls.isEmpty()) {
                        JSONObject media = tNode.optJSONObject("media");
                        if (media != null) {
                            JSONArray urls = media.optJSONArray("urls");
                            if (urls != null) {
                                for (int i = 0; i < urls.length(); i++) {
                                    JSONObject u = urls.getJSONObject(i);
                                    if ("PUBLIC".equalsIgnoreCase(u.optString("variant"))) {
                                        result.imageUrls.add(u.optString("url"));
                                    }
                                }
                                if (result.imageUrls.isEmpty() && urls.length() > 0) {
                                    result.imageUrls.add(urls.getJSONObject(0).optString("url"));
                                }
                            }
                        }
                    }
                    return result;
                } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                    result.failed = true;
                    result.errorMessage = "Tác vụ thất bại trên GPU PixAI (" + status + ")";
                    return result;
                }
                return result;
            }

            JSONArray errors = pollRes.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                result.errorMessage = errors.getJSONObject(0).optString("message", "");
            }
            return result;
        } catch (Exception e) {
            result.errorMessage = e.getMessage();
            return result;
        }
    }

    public static TaskResult createAndPollGenerationTask(
            String token,
            String profile,
            boolean isReal,
            String qualityMode,
            int width,
            int height,
            int batchSize,
            String prompt,
            String customNegative,
            List<String> attachedMediaIds,
            String proxyStr,
            ProgressCallback callback
    ) {
        TaskResult result = new TaskResult();
        result.originalPrompt = prompt;

        CreateTaskResult cr = createGenerationTask(token, profile, isReal, qualityMode, width, height, batchSize, prompt, customNegative, attachedMediaIds, proxyStr);
        if (!cr.success) {
            result.success = false;
            result.insufficientBalance = cr.insufficientBalance;
            result.errorMessage = cr.errorMessage;
            return result;
        }

        result.taskId = cr.taskId;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 600000) { // 10 phút tối đa
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {}

            int elapsed = (int) ((System.currentTimeMillis() - start) / 1000);
            if (callback != null) {
                callback.onProgress("Đang render trên GPU PixAI (Đã chờ " + elapsed + "s)...", elapsed);
            }

            PollStatusResult pr = pollTaskMedia(cr.taskId, token, proxyStr);
            if (pr.completed) {
                result.success = !pr.imageUrls.isEmpty();
                result.imageUrls = pr.imageUrls;
                result.serverPrompt = pr.serverPrompt.isEmpty() ? prompt : pr.serverPrompt;
                result.seed = pr.seed;
                return result;
            } else if (pr.failed) {
                result.success = false;
                result.errorMessage = pr.errorMessage;
                return result;
            }
        }

        result.success = false;
        result.errorMessage = "Quá thời gian chờ (Timeout) kết xuất trên GPU PixAI";
        return result;
    }

    public static String uploadImageToPixAi(byte[] imageBytes, String token, String proxyStr) throws Exception {
        String cleanToken = (token != null && token.startsWith("Bearer ")) ? token.substring(7).trim() : (token != null ? token.trim() : "");
        String qUrl = "mutation uploadMedia($input: UploadMediaInput!) {\n" +
                "  uploadMedia(input: $input) {\n" +
                "    uploadUrl\n" +
                "    externalId\n" +
                "    mediaId\n" +
                "  }\n" +
                "}";

        // Bước 1: Yêu cầu S3 uploadUrl và externalId
        JSONObject vars = new JSONObject();
        JSONObject inp = new JSONObject();
        inp.put("type", "IMAGE");
        inp.put("provider", "S3");
        vars.put("input", inp);

        JSONObject payload1 = new JSONObject();
        payload1.put("query", qUrl);
        payload1.put("variables", vars);

        JSONObject res1 = sendGraphQL(payload1, cleanToken, proxyStr);
        JSONObject d = res1.optJSONObject("data");
        if (d != null) d = d.optJSONObject("uploadMedia");
        if (d == null) throw new Exception("Không lấy được S3 Upload URL từ PixAI");

        String uploadUrl = d.getString("uploadUrl");
        String externalId = d.getString("externalId");

        // Bước 2: Đẩy dữ liệu ảnh nhị phân lên AWS S3 qua Socket Tunnel
        Map<String, String> s3Headers = new LinkedHashMap<>();
        s3Headers.put("Content-Type", "image/jpeg");
        String s3Resp = httpRequestWithHeaders(uploadUrl, "PUT", s3Headers, imageBytes, proxyStr, 35);
        if (s3Resp != null && s3Resp.startsWith("{\"error\":")) {
            throw new Exception("Lỗi tải ảnh lên S3: " + s3Resp);
        }

        // Bước 3: Đăng ký xác nhận mediaId hoàn chỉnh với PixAI qua externalId
        JSONObject vars2 = new JSONObject();
        JSONObject inp2 = new JSONObject();
        inp2.put("type", "IMAGE");
        inp2.put("provider", "S3");
        inp2.put("externalId", externalId);
        inp2.put("imageType", "image/jpeg");
        vars2.put("input", inp2);

        JSONObject payload2 = new JSONObject();
        payload2.put("query", qUrl);
        payload2.put("variables", vars2);

        JSONObject res2 = sendGraphQL(payload2, cleanToken, proxyStr);
        JSONObject d2 = res2.optJSONObject("data");
        if (d2 != null) d2 = d2.optJSONObject("uploadMedia");
        String confirmedMediaId = (d2 != null) ? d2.optString("mediaId", null) : null;

        if (confirmedMediaId != null && !confirmedMediaId.isEmpty() && !"null".equalsIgnoreCase(confirmedMediaId)) {
            return confirmedMediaId;
        } else {
            throw new Exception("Máy chủ PixAI không cấp mediaId hợp lệ cho ảnh mẫu");
        }
    }

    public static boolean claimDaily(String token, String proxyStr) {
        return claimDailyCredits(token, proxyStr);
    }

    public static void claimAllQuests(String token, String proxyStr) {
        claimDailyCredits(token, proxyStr);
        claimSocialAndVisitQuests(token, proxyStr);
    }

    public static List<String> fetchShareFreeProxyPool(LogCallback log) {
        List<String> list = new ArrayList<>();
        list.add("http://p.webshare.io:80");
        return list;
    }

    public static Mailbox createMailbox(String proxyStr) {
        return createMailbox(proxyStr, null);
    }

    public static Mailbox createMailbox(String proxyStr, LogCallback log) {
        return createMailboxMultiProvider(proxyStr, false, 0, log);
    }

    public static Mailbox createMailboxMultiProvider(String proxyStr, boolean forWebshare, int startProviderOffset, LogCallback log) {
        String[] order = new String[]{"temp-mail.org", "temp-mail.io"};
        for (int i = 0; i < order.length; i++) {
            String provider = order[(i + Math.abs(startProviderOffset)) % order.length];
            Mailbox mb = null;

            if ("temp-mail.org".equals(provider)) {
                if (log != null) log.log("Đang thử cấp phát qua Temp-Mail.org...", "#60A5FA");
                mb = createTempMailOrgMailbox(proxyStr, log);
            } else if ("temp-mail.io".equals(provider)) {
                if (log != null) log.log("Đang cấp phát qua Temp-Mail.io (Miền chuẩn PixAI)...", "#60A5FA");
                mb = createTempMailIoMailbox(proxyStr, true, log);
            }

            if (mb != null && mb.address != null && !mb.address.isEmpty()) {
                return mb;
            }
        }
        return null;
    }

    private static boolean isStrictPixAiCompatibleEmail(String email) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase(java.util.Locale.US);
        if ("ooynib.com".equalsIgnoreCase(domain)) return false;
        String[] parts = domain.split("\\.");
        if (parts.length != 2) return false;
        String tld = parts[1];
        return "com".equals(tld) || "net".equals(tld) || "org".equals(tld);
    }

    private static Mailbox createTempMailIoMailbox(String proxyStr, boolean strictPixAiDomain, LogCallback log) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json");
                String body = "{\"min_name_length\":10,\"max_name_length\":10}";
                String resp = httpRequestWithHeaders(TEMP_MAIL_IO_URL + "/api/v3/email/new", "POST", headers, body.getBytes(StandardCharsets.UTF_8), proxyStr, 10);
                if (resp != null && resp.contains("\"email\"")) {
                    JSONObject obj = new JSONObject(resp);
                    String email = obj.optString("email", "").trim();
                    String token = obj.optString("token", email).trim();
                    if (!email.isEmpty()) {
                        if (strictPixAiDomain && !isStrictPixAiCompatibleEmail(email) && attempt < 3) {
                            continue;
                        }
                        if (log != null) log.log("Đã cấp phát email chuẩn (temp-mail.io): " + email, "#34D399");
                        return new Mailbox(email, token, "temp-mail.io");
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Mailbox createTempMailOrgMailbox(String proxyStr, LogCallback log) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "https://temp-mail.org");
        headers.put("Referer", "https://temp-mail.org/en/");
        headers.put("Accept", "application/json, text/plain, */*");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String res = httpRequestWithHeaders(TEMP_MAIL_ORG_URL + "/mailbox", "POST", headers, "{}".getBytes(StandardCharsets.UTF_8), proxyStr, 6);
                if (res != null && res.contains("\"mailbox\"") && res.contains("\"token\"")) {
                    String mailbox = "";
                    String token = "";
                    Matcher mM = Pattern.compile("\"mailbox\"\\s*:\\s*\"([^\"]+)\"").matcher(res);
                    Matcher mT = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(res);
                    if (mM.find()) mailbox = mM.group(1);
                    if (mT.find()) token = mT.group(1);
                    if (!mailbox.isEmpty() && !token.isEmpty()) {
                        if (log != null) log.log("Đã cấp phát email (temp-mail.org): " + mailbox, "#34D399");
                        return new Mailbox(mailbox, token, "temp-mail.org");
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    public static List<String> fetchMailboxMessagesBodies(Mailbox mb, String proxyStr) {
        List<String> bodies = new ArrayList<>();
        if (mb == null || mb.address == null || mb.address.isEmpty()) return bodies;

        try {
            if ("temp-mail.org".equalsIgnoreCase(mb.provider)) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Origin", "https://temp-mail.org");
                headers.put("Referer", "https://temp-mail.org/en/");
                headers.put("Accept", "application/json, text/plain, */*");
                if (mb.token != null && !mb.token.isEmpty()) {
                    headers.put("Authorization", "Bearer " + mb.token.trim());
                }

                String listJson = httpRequestWithHeaders(TEMP_MAIL_ORG_URL + "/messages", "GET", headers, null, proxyStr, 10);
                if (listJson != null && listJson.contains("\"messages\"")) {
                    bodies.add(listJson);
                    Matcher mId = Pattern.compile("\"_id\"\\s*:\\s*\"([a-f0-9]{20,30})\"").matcher(listJson);
                    while (mId.find()) {
                        String msgId = mId.group(1);
                        String fullMsg = httpRequestWithHeaders(TEMP_MAIL_ORG_URL + "/messages/" + msgId, "GET", headers, null, proxyStr, 10);
                        if (fullMsg != null && !fullMsg.isEmpty()) {
                            bodies.add(fullMsg);
                        }
                    }
                }
            } else if ("temp-mail.io".equalsIgnoreCase(mb.provider)) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Accept", "application/json");
                String listJson = httpRequestWithHeaders(TEMP_MAIL_IO_URL + "/api/v3/email/" + mb.address.trim() + "/messages", "GET", headers, null, proxyStr, 10);
                if (listJson != null && listJson.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(listJson.trim());
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject msgObj = arr.optJSONObject(i);
                        if (msgObj != null) {
                            bodies.add(msgObj.toString());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return bodies;
    }

    public static String waitForPixAICode(Mailbox mb, String email, String pixaiToken, String proxyStr, int timeoutSeconds, LogCallback log) {
        if (mb == null) return null;
        long start = System.currentTimeMillis();
        long lastLogTime = 0;
        boolean hasResentOnce = false;

        while ((System.currentTimeMillis() - start) < (timeoutSeconds * 1000L)) {
            try {
                Thread.sleep(2400);

                long elapsed = (System.currentTimeMillis() - start) / 1000L;
                if (log != null && (System.currentTimeMillis() - lastLogTime > 6500)) {
                    lastLogTime = System.currentTimeMillis();
                    log.log("Đang lắng nghe OTP từ " + mb.address + " (" + mb.provider + " • " + elapsed + "s)...", "#94A3B8");
                }

                if (!hasResentOnce && elapsed >= 15 && email != null && !email.isEmpty() && pixaiToken != null && !pixaiToken.isEmpty()) {
                    hasResentOnce = true;
                    if (log != null) {
                        log.log("Đang yêu cầu PixAI gửi lại thư OTP xác thực...", "#60A5FA");
                    }
                    sendVerificationEmail(email, pixaiToken, proxyStr, null);
                }

                List<String> messages = fetchMailboxMessagesBodies(mb, proxyStr);
                for (String fullMsgJson : messages) {
                    String cleanMsg = fullMsgJson.replace("\\/", "/").replace("&amp;", "&").replace("\\u0026", "&");
                    Matcher mLink = Pattern.compile("https://pixai\\.art/[^\\s\"'<>\\\\*]*verify-email[^\\s\"'<>\\\\*]*").matcher(cleanMsg);
                    if (mLink.find()) {
                        String link = mLink.group(0);
                        Matcher cm = Pattern.compile("code=([a-zA-Z0-9_\\-]+)").matcher(link);
                        if (cm.find()) {
                            if (log != null) log.log("Đã bắt được mã xác thực email PixAI!", "#34D399");
                            return cm.group(1);
                        }
                    }

                    Matcher mCode = Pattern.compile("code=([0-9]{6})").matcher(cleanMsg);
                    if (mCode.find()) {
                        if (log != null) log.log("Đã bắt được mã OTP PixAI: " + mCode.group(1), "#34D399");
                        return mCode.group(1);
                    }

                    Matcher mDigits = Pattern.compile("\\b([0-9]{6})\\b").matcher(cleanMsg);
                    if (mDigits.find()) {
                        if (log != null) log.log("Đã trích xuất mã 6 số OTP: " + mDigits.group(1), "#34D399");
                        return mDigits.group(1);
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static boolean sendVerificationEmail(String email, String token, String proxyStr, LogCallback log) {
        try {
            JSONObject vars = new JSONObject();
            JSONObject inp = new JSONObject();
            inp.put("email", email.trim());
            vars.put("input", inp);

            JSONObject q1 = new JSONObject();
            q1.put("query", "mutation updateUserSensitiveInfos($input: UpdateUserSensitiveInfosInput!) { updateUserSensitiveInfos(input: $input) { id email emailVerified } }");
            q1.put("variables", vars);
            JSONObject res1 = sendGraphQL(q1, token, proxyStr);

            JSONObject vars2 = new JSONObject();
            JSONObject inp2 = new JSONObject();
            inp2.put("intent", "VerifyEmail");
            inp2.put("approach", "Email");
            vars2.put("input", inp2);

            JSONObject q2 = new JSONObject();
            q2.put("query", "mutation genCode($input: GenCodeInput!) { genCode(input: $input) }");
            q2.put("variables", vars2);
            JSONObject res2 = sendGraphQL(q2, token, proxyStr);

            boolean sent = res2 != null && res2.has("data") && res2.getJSONObject("data").has("genCode");
            if (log != null) {
                if (sent) log.log("Đã yêu cầu PixAI gửi mã OTP xác thực tới hòm thư!", "#34D399");
                else log.log("Đang chuẩn bị gửi mã xác thực email...", "#94A3B8");
            }
            return sent;
        } catch (Exception e) {
            if (log != null) log.log("Lỗi sendVerificationEmail: " + e.getMessage(), "#F87171");
            return false;
        }
    }

    public static boolean verifyEmailCode(String token, String code, String proxyStr) {
        try {
            JSONObject vars = new JSONObject();
            vars.put("code", code.trim());
            JSONObject q = new JSONObject();
            q.put("query", "mutation verifyEmail($code: String!) { verifyEmail(code: $code) }");
            q.put("variables", vars);
            JSONObject res = sendGraphQL(q, token, proxyStr);
            return res != null && res.has("data") && res.getJSONObject("data").optBoolean("verifyEmail", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static int claimFeaturedQuests(String token, String proxyStr) {
        String[] urls = new String[]{"https://youtu.be/xGBoyWR4MBQ", "https://agent.pixai.art/"};
        int count = 0;
        for (String u : urls) {
            try {
                URL url = new URL(PIXAI_BASE_URL + "/v2/quest-v2/report-visit");
                HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("{\"url\":\"" + u + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                if (conn.getResponseCode() == 200) count++;
            } catch (Exception ignored) {}
        }
        return count > 0 ? count : 2;
    }

    public static String makeSafePassword() {
        String uppers = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lowers = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String specials = "!@#$*";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        sb.append(uppers.charAt(r.nextInt(uppers.length())));
        sb.append(lowers.charAt(r.nextInt(lowers.length())));
        sb.append(digits.charAt(r.nextInt(digits.length())));
        sb.append(specials.charAt(r.nextInt(specials.length())));
        String all = uppers + lowers + digits + specials;
        for (int i = 0; i < 8; i++) {
            sb.append(all.charAt(r.nextInt(all.length())));
        }
        return sb.toString();
    }
}
