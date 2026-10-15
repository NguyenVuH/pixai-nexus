package com.pixai.nexus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
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

    public static final String GRAPHQL_URL = "https://api.pixai.art/graphql";
    public static final String PERSISTED_HASH = "83f9afa2ae341c3dd9dd1ed56d205331a8fa076215e4a651c462bbca8a437725";

    public static final String MODEL_TSUBAKI_FLASH = "2050048243034896798";
    public static final String MODEL_TSUBAKI_PRO = "2024383379556065549";

    public static final String REALISTIC_LORA_ID = "2059543732132340175";
    public static final String REALISTIC_LORA_TRIGGERS = "impossiblebeauty_sr, unrealistically beautiful face, photorealistic anatomy, idealized facial proportions, luminous eyes, flawless skin, realistic hair, natural human proportions";

    public static final String DEFAULT_NEGATIVE = "lowres, worst quality, low quality, bad anatomy, explicit";

    public static class TaskResult {
        public boolean success;
        public String taskId;
        public List<String> imageUrls = new ArrayList<>();
        public String originalPrompt;
        public String serverPrompt;
        public long seed;
        public String errorMessage;
    }

    public static JSONObject sendGraphQL(JSONObject payload, String token, String proxyStr) throws Exception {
        try {
            return sendGraphQLInternal(payload, token, proxyStr);
        } catch (Exception e) {
            if (proxyStr != null && !proxyStr.trim().isEmpty()) {
                // Tự động Fallback sang Direct an toàn nếu proxy lỗi để không làm mất tác vụ
                return sendGraphQLInternal(payload, token, null);
            }
            throw e;
        }
    }

    private static JSONObject sendGraphQLInternal(JSONObject payload, String token, String proxyStr) throws Exception {
        URL url = new URL(GRAPHQL_URL);
        HttpURLConnection conn;

        if (proxyStr != null && !proxyStr.trim().isEmpty()) {
            try {
                String clean = proxyStr.replace("http://", "").replace("https://", "");
                String[] parts = clean.split("@");
                String hostPort = parts.length > 1 ? parts[1] : parts[0];
                String[] hp = hostPort.split(":");
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                conn = (HttpURLConnection) url.openConnection(proxy);
            } catch (Exception e) {
                conn = (HttpURLConnection) url.openConnection();
            }
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/graphql-response+json,application/json;q=0.9");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }
        conn.setConnectTimeout(25000);
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

    public static TaskResult createAndPollGenerationTask(
            String token,
            String profile, // "flash", "pro", "ultra"
            boolean isReal,
            int width,
            int height,
            int batchSize,
            String prompt,
            String customNegative,
            String attachedMediaId,
            String proxyStr
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
            params.put("priority", 1000);
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

            // Negative prompt cho pro/ultra
            if (!"flash".equalsIgnoreCase(profile)) {
                String neg = (customNegative != null && !customNegative.trim().isEmpty()) ? customNegative : DEFAULT_NEGATIVE;
                params.put("negativePrompts", neg);
            }

            // Xử lý đính kèm ảnh (Context Image) hoặc LoRA Realism
            if (attachedMediaId != null && !attachedMediaId.trim().isEmpty()) {
                JSONArray ctx = new JSONArray();
                ctx.put(attachedMediaId.trim());
                params.put("contextImages", ctx);
                JSONObject qt = new JSONObject();
                qt.put("prefix", "");
                qt.put("suffix", "");
                params.put("qualityTag", qt);
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

            JSONObject createRes = sendGraphQL(payload, token, proxyStr);
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
            while (System.currentTimeMillis() - start < 300000) {
                Thread.sleep(2500);
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
                        result.errorMessage = "Task thất bại trên GPU PixAI (" + status + ")";
                        return result;
                    }
                }
            }

            result.success = false;
            result.errorMessage = "Quá thời gian chờ (Timeout) xử lý tác vụ trên GPU PixAI!";
            return result;

        } catch (Exception e) {
            result.success = false;
            result.errorMessage = "Lỗi Exception: " + e.getMessage();
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
        JSONObject upData = res1.optJSONObject("data");
        if (upData != null) upData = upData.optJSONObject("uploadMedia");
        if (upData == null || !upData.has("uploadUrl")) {
            throw new Exception("Không lấy được S3 Upload URL: " + res1.toString());
        }

        String upUrl = upData.getString("uploadUrl");

        // PUT lên S3
        URL s3Url = new URL(upUrl);
        HttpURLConnection s3Conn = (HttpURLConnection) s3Url.openConnection();
        s3Conn.setRequestMethod("PUT");
        s3Conn.setRequestProperty("Content-Type", "image/jpeg");
        s3Conn.setConnectTimeout(30000);
        s3Conn.setReadTimeout(45000);
        s3Conn.setDoOutput(true);
        try (OutputStream os = s3Conn.getOutputStream()) {
            os.write(imageBytes);
        }
        s3Conn.getResponseCode();

        // Confirm
        String extKey = upUrl.split("\\?")[0];
        String[] segs = extKey.split("/");
        String finalKey = segs[segs.length - 1];

        JSONObject vars2 = new JSONObject();
        JSONObject inp2 = new JSONObject();
        inp2.put("type", "IMAGE");
        inp2.put("provider", "S3");
        inp2.put("externalId", finalKey);
        inp2.put("imageType", "image/jpeg");
        vars2.put("input", inp2);

        JSONObject payload2 = new JSONObject();
        payload2.put("query", qUrl);
        payload2.put("variables", vars2);

        JSONObject res2 = sendGraphQL(payload2, token, proxyStr);
        JSONObject upData2 = res2.optJSONObject("data");
        if (upData2 != null) upData2 = upData2.optJSONObject("uploadMedia");
        if (upData2 != null && upData2.has("mediaId")) {
            return upData2.getString("mediaId");
        }
        throw new Exception("Không xác nhận được Media ID từ PixAI: " + res2.toString());
    }

    public static long getQuota(String token, String proxyStr) {
        try {
            JSONObject q = new JSONObject();
            q.put("query", "query { me { credit } }");
            JSONObject res = sendGraphQL(q, token, proxyStr);
            JSONObject me = res.optJSONObject("data");
            if (me != null) me = me.optJSONObject("me");
            if (me != null && me.has("credit")) {
                return me.optLong("credit", -1);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    public static boolean claimDaily(String token, String proxyStr) {
        try {
            URL url = new URL("https://api.pixai.art/v2/quest-v2/daily-claim");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static void claimAllQuests(String token, String proxyStr) {
        claimDaily(token, proxyStr);
        try {
            URL url1 = new URL("https://api.pixai.art/v2/quest-v2/featured-quests-claim");
            HttpURLConnection conn1 = (HttpURLConnection) url1.openConnection();
            conn1.setRequestMethod("POST");
            conn1.setRequestProperty("Authorization", "Bearer " + token);
            conn1.setRequestProperty("Content-Type", "application/json");
            conn1.setDoOutput(true);
            conn1.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            conn1.getResponseCode();
        } catch (Exception ignored) {}

        try {
            URL url2 = new URL("https://api.pixai.art/v2/quest-v2/social-quests-claim");
            HttpURLConnection conn2 = (HttpURLConnection) url2.openConnection();
            conn2.setRequestMethod("POST");
            conn2.setRequestProperty("Authorization", "Bearer " + token);
            conn2.setRequestProperty("Content-Type", "application/json");
            conn2.setDoOutput(true);
            conn2.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            conn2.getResponseCode();
        } catch (Exception ignored) {}
    }

    public static String httpRequestWithHeaders(String urlStr, String method, Map<String, String> headers, byte[] body, String proxyStr, int timeoutSec) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn;
            if (proxyStr != null && !proxyStr.trim().isEmpty()) {
                String clean = proxyStr.replace("http://", "").replace("https://", "").replace("socks5://", "");
                String[] parts = clean.split("@");
                String hostPort = parts.length > 1 ? parts[1] : parts[0];
                String[] hp = hostPort.split(":");
                Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }

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
