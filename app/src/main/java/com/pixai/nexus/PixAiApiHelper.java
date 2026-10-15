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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PixAiApiHelper {

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

    public static HttpURLConnection openConnectionWithProxy(URL url, String proxyStr) throws Exception {
        if (proxyStr == null || proxyStr.trim().isEmpty()) {
            return (HttpURLConnection) url.openConnection();
        }

        try {
            String raw = proxyStr.trim();
            Proxy.Type pType = Proxy.Type.HTTP;
            if (raw.toLowerCase().startsWith("socks5://") || raw.toLowerCase().startsWith("socks://")) {
                pType = Proxy.Type.SOCKS;
            }
            String clean = raw.replace("http://", "").replace("https://", "").replace("socks5://", "").replace("socks://", "");
            String[] parts = clean.split("@");

            String hostPort = parts.length > 1 ? parts[1] : parts[0];
            String[] hp = hostPort.split(":");
            String host = hp[0];
            int port = (hp.length > 1) ? Integer.parseInt(hp[1].trim()) : 80;

            Proxy proxy = new Proxy(pType, new InetSocketAddress(host, port));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);

            if (parts.length > 1) {
                String userPass = parts[0];
                String authHeader = "Basic " + Base64.encodeToString(userPass.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                conn.setRequestProperty("Proxy-Authorization", authHeader);
                String[] up = userPass.split(":");
                if (up.length >= 2) {
                    final String user = up[0];
                    final String pass = up[1];
                    Authenticator.setDefault(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            if (getRequestorType() == RequestorType.PROXY) {
                                return new PasswordAuthentication(user, pass.toCharArray());
                            }
                            return super.getPasswordAuthentication();
                        }
                    });
                }
            }
            return conn;
        } catch (Exception e) {
            return (HttpURLConnection) url.openConnection();
        }
    }

    public static JSONObject sendGraphQL(JSONObject payload, String token, String proxyStr) throws Exception {
        try {
            return sendGraphQLInternal(payload, token, proxyStr);
        } catch (Exception e) {
            if (proxyStr != null && !proxyStr.trim().isEmpty()) {
                // Tự động Fallback sang Direct nếu Proxy lỗi để không ngắt quãng tác vụ
                return sendGraphQLInternal(payload, token, null);
            }
            throw e;
        }
    }

    private static JSONObject sendGraphQLInternal(JSONObject payload, String token, String proxyStr) throws Exception {
        URL url = new URL(GRAPHQL_URL);
        HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/graphql-response+json,application/json;q=0.9");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        return new JSONObject(sb.toString().trim());
    }

    public static void unblurNsfw(String token, String proxyStr) {
        try {
            JSONObject q = new JSONObject();
            q.put("query", "mutation updateUserSettings($input: UpdateUserSettingsInput!) {\n" +
                    "  updateUserSettings(input: $input) {\n" +
                    "    isNsfw\n" +
                    "    showNsfw\n" +
                    "  }\n" +
                    "}");
            JSONObject vars = new JSONObject();
            JSONObject inp = new JSONObject();
            inp.put("isNsfw", true);
            inp.put("showNsfw", true);
            vars.put("input", inp);
            q.put("variables", vars);
            sendGraphQL(q, token, proxyStr);
        } catch (Exception ignored) {}
    }

    public static long getQuota(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return 0;
        try {
            URL url = new URL("https://api.pixai.art/v2/user/me/quota");
            HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    return json.optLong("amount", 0);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static boolean claimDailyCredits(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            URL url = new URL("https://api.pixai.art/v2/claim/pixai-daily-credits");
            HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"id\":\"pixai-daily-credits\"}".getBytes(StandardCharsets.UTF_8));
            }
            return conn.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static int claimSocialAndVisitQuests(String token, String proxyStr) {
        if (token == null || token.trim().isEmpty()) return 0;
        int successCount = 0;
        String[] platforms = {"discord", "twitter", "instagram", "youtube", "tiktok"};
        for (String p : platforms) {
            try {
                URL url = new URL("https://api.pixai.art/v2/quest-v2/report-social-follow");
                HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("{\"platform\":\"" + p + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                if (conn.getResponseCode() == 200) successCount++;
            } catch (Exception ignored) {}
        }

        String[] visits = {"https://youtu.be/xGBoyWR4MBQ", "https://agent.pixai.art/"};
        for (String v : visits) {
            try {
                URL url = new URL("https://api.pixai.art/v2/quest-v2/report-visit");
                HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(("{\"url\":\"" + v + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                if (conn.getResponseCode() == 200) successCount++;
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
        TaskResult result = new TaskResult();
        result.originalPrompt = prompt;

        try {
            String modelId = "flash".equalsIgnoreCase(profile) ? MODEL_TSUBAKI_FLASH : MODEL_TSUBAKI_PRO;

            JSONObject params = new JSONObject();
            params.put("modelId", modelId);
            params.put("inferenceProfile", profile.toLowerCase());
            params.put("width", width);
            params.put("height", height);
            params.put("priority", 0); // Sử dụng priority 0 để tránh tiêu tốn credit vô ích
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

            // Xử lý Quality Tag
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

            // Negative prompt cho pro/ultra hoặc tuỳ chỉnh
            if (!"flash".equalsIgnoreCase(profile) || (customNegative != null && !customNegative.trim().isEmpty())) {
                String neg = (customNegative != null && !customNegative.trim().isEmpty()) ? customNegative : DEFAULT_NEGATIVE;
                params.put("negativePrompts", neg);
            }

            // Xử lý đính kèm ảnh (Context Image) hoặc LoRA Realism
            if (attachedMediaId != null && !attachedMediaId.trim().isEmpty()) {
                JSONArray ctx = new JSONArray();
                ctx.put(attachedMediaId.trim());
                params.put("contextImages", ctx);
            } else {
                if (isReal) {
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

            if (callback != null) {
                callback.onProgress("Đang gửi yêu cầu khởi tạo tới PixAI Engine (" + profile.toUpperCase() + ")...", 0);
            }

            JSONObject createRes = sendGraphQL(payload, token, proxyStr);

            // Kiểm tra lỗi từ chối hoặc hết credit
            JSONArray errors = createRes.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String errMsg = errors.getJSONObject(0).optString("message", "");
                if (errMsg.toLowerCase().contains("insufficient balance") || errMsg.toLowerCase().contains("balance")) {
                    result.insufficientBalance = true;
                    result.errorMessage = "Tài khoản không đủ Credit để tạo ảnh";
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

            String taskId = taskObj.getString("id");
            result.taskId = taskId;

            // 2. Polling chờ kết quả
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

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 300000) { // 300s (5 phút) timeout chuẩn
                Thread.sleep(2500);
                int elapsedSec = (int) ((System.currentTimeMillis() - start) / 1000);

                if (callback != null) {
                    callback.onProgress("Đang render trên GPU PixAI (Đã chờ " + elapsedSec + "s)...", elapsedSec);
                }

                JSONObject pollRes = sendGraphQL(pollPayload, token, proxyStr);
                JSONObject tNode = pollRes.optJSONObject("data");
                if (tNode != null) tNode = tNode.optJSONObject("task");
                if (tNode != null) {
                    String status = tNode.optString("status", "");
                    if ("completed".equalsIgnoreCase(status)) {
                        JSONObject pObj = tNode.optJSONObject("parameters");
                        if (pObj != null) {
                            result.serverPrompt = pObj.optString("prompts", prompt);
                        } else {
                            result.serverPrompt = prompt;
                        }

                        JSONObject oObj = tNode.optJSONObject("outputs");
                        if (oObj != null) {
                            result.seed = oObj.optLong("seed", 0);
                        }

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

                        result.success = !result.imageUrls.isEmpty();
                        return result;
                    } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                        result.success = false;
                        result.errorMessage = "Tác vụ thất bại trên GPU PixAI (" + status + ")";
                        return result;
                    }
                }
            }

            result.success = false;
            result.errorMessage = "Quá thời gian chờ (Timeout 300s) kết xuất trên GPU PixAI";
            return result;

        } catch (Exception e) {
            result.success = false;
            result.errorMessage = "Lỗi kết nối: " + e.getMessage();
            return result;
        }
    }

    public static String uploadImageToPixAi(byte[] imageBytes, String token, String proxyStr) throws Exception {
        String qUrl = "mutation uploadMedia($input: UploadMediaInput!) {\n" +
                "  uploadMedia(input: $input) {\n" +
                "    uploadUrl\n" +
                "    externalId\n" +
                "    mediaId\n" +
                "  }\n" +
                "}";

        JSONObject vars = new JSONObject();
        JSONObject inp = new JSONObject();
        inp.put("type", "IMAGE");
        inp.put("provider", "S3");
        vars.put("input", inp);

        JSONObject payload1 = new JSONObject();
        payload1.put("query", qUrl);
        payload1.put("variables", vars);

        JSONObject res1 = sendGraphQL(payload1, token, proxyStr);
        JSONObject d = res1.optJSONObject("data");
        if (d != null) d = d.optJSONObject("uploadMedia");
        if (d == null) throw new Exception("Không lấy được S3 Upload URL từ PixAI");

        String uploadUrl = d.getString("uploadUrl");
        String mediaId = d.getString("mediaId");

        URL s3Url = new URL(uploadUrl);
        HttpURLConnection s3Conn = openConnectionWithProxy(s3Url, proxyStr);
        s3Conn.setRequestMethod("PUT");
        s3Conn.setRequestProperty("Content-Type", "image/png");
        s3Conn.setConnectTimeout(25000);
        s3Conn.setReadTimeout(35000);
        s3Conn.setDoOutput(true);

        try (OutputStream os = s3Conn.getOutputStream()) {
            os.write(imageBytes);
        }

        int s3Code = s3Conn.getResponseCode();
        if (s3Code >= 200 && s3Code < 300) {
            return mediaId;
        } else {
            throw new Exception("Lỗi tải ảnh lên S3: HTTP " + s3Code);
        }
    }

    public static boolean claimDaily(String token, String proxyStr) {
        return claimDailyCredits(token, proxyStr);
    }

    public static void claimAllQuests(String token, String proxyStr) {
        claimDailyCredits(token, proxyStr);
        claimSocialAndVisitQuests(token, proxyStr);
    }

    public static String httpRequestWithHeaders(String urlStr, String method, Map<String, String> headers, byte[] body, String proxyStr, int timeoutSec) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = openConnectionWithProxy(url, proxyStr);
            conn.setRequestMethod(method != null ? method : "GET");
            conn.setConnectTimeout(timeoutSec > 0 ? timeoutSec * 1000 : 15000);
            conn.setReadTimeout(timeoutSec > 0 ? timeoutSec * 1000 : 15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    public static String executeSocketHttpRequest(String urlStr, String method, Map<String, String> headers, byte[] body, SecurityShieldManager.ParsedProxy proxy, int timeoutSec) {
        String proxyStr = proxy != null ? proxy.toNormalizedUrl() : null;
        return httpRequestWithHeaders(urlStr, method, headers, body, proxyStr, timeoutSec);
    }

    public static List<String> fetchShareFreeProxyPool(LogCallback log) {
        List<String> list = new ArrayList<>();
        list.add("http://p.webshare.io:80");
        return list;
    }
}
