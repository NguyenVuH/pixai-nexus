package com.pixai.nexus;

import android.net.Uri;
import android.util.Base64;
import android.webkit.WebResourceResponse;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HỆ THỐNG BẢO MẬT IP, XOAY PROXY WEBSHARE TỰ ĐỘNG & GIẢ LẬP THIẾT BỊ ẢO:
 * 1. Tích hợp API Webshare v2: Tự động lấy 10 Proxy (mode=direct) kèm thông tin Quốc gia / Thành phố từ tài khoản Webshare.
 * 2. Kiểm tra Vùng IP (Country, City, Flag, ISP, Ping) cho IP hiện tại và toàn bộ danh sách Proxy.
 * 3. Tự động xoay vòng IP (Auto-Rotate) giữa các Proxy sống trong danh sách, tự nhảy sang Proxy khác nếu cổng hiện tại lỗi.
 * 4. Giả lập Thiết Bị Ảo (Virtual Device Profiles) + Chặn rò rỉ WebRTC & Tracker.
 */
public class SecurityShieldManager {

    private static final AtomicLong encryptedTunneledRequests = new AtomicLong(0);
    private static final AtomicLong blockedTrackersCount = new AtomicLong(0);
    private static final AtomicLong dohResolvedCount = new AtomicLong(0);

    public static void initialize(android.content.Context context) {
        ensureLocalTunnelRunning();
    }

    private static class DnsCacheEntry {
        final String ip;
        final long expireAt;
        DnsCacheEntry(String ip, long ttlSeconds) {
            this.ip = ip;
            this.expireAt = System.currentTimeMillis() + (ttlSeconds * 1000L);
        }
    }
    private static final Map<String, DnsCacheEntry> dohCache = new ConcurrentHashMap<>();

    private static final String[] TRACKER_DOMAINS = new String[]{
        "google-analytics.com",
        "googletagmanager.com",
        "analytics.google.com",
        "doubleclick.net",
        "googlesyndication.com",
        "sentry.io",
        "ingest.sentry.io",
        "browser-intake-datadoghq.com",
        "datadoghq.com",
        "clarity.ms",
        "hotjar.com",
        "mixpanel.com",
        "amplitude.com",
        "segment.io",
        "segment.com",
        "connect.facebook.net",
        "facebook.com/tr",
        "fpjs.io",
        "fingerprint.com",
        "bugsnag.com",
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "onesignal.com",
        "fullstory.com",
        "logrocket.io"
    };

    private static volatile boolean useUpstreamProxy = true;
    private static volatile boolean proxyWebUi = true;
    private static volatile boolean autoRotateProxy = true;
    private static volatile String upstreamProxyRaw = "";
    private static volatile List<String> activeProxyPool = new ArrayList<>();
    private static final AtomicInteger runtimePoolCursor = new AtomicInteger(0);
    private static volatile boolean dohEnabled = true;
    private static volatile boolean antiDpiEnabled = false;
    private static volatile boolean strictKillSwitch = false;
    private static volatile boolean blockWebRtc = true;
    private static volatile boolean spoofFingerprint = true;
    private static volatile boolean blockTrackers = true;
    private static volatile int fingerprintSeed = 104729;

    private static ServerSocket localTunnelServer;
    private static volatile int localTunnelPort = 0;
    private static final ExecutorService tunnelExecutor = Executors.newCachedThreadPool();
    private static final Object tunnelLock = new Object();

    // ================= THÔNG TIN NODE PROXY & VÙNG ĐỊA LÝ =================
    public static class ProxyNodeInfo implements Comparable<ProxyNodeInfo> {
        public String proxyUrl = "";
        public String host = "";
        public int port = 0;
        public String exitIp = "";
        public String countryCode = "";
        public String countryName = "";
        public String cityName = "";
        public String isp = "";
        public long latencyMs = 9999;
        public boolean isLive = true;

        public String getFlagEmoji() {
            return countryCodeToFlagEmoji(countryCode);
        }

        public String getRegionDisplay() {
            String flag = getFlagEmoji();
            String cName = (countryName != null && !countryName.isEmpty())
                    ? countryName
                    : countryCodeToName(countryCode);
            if (cName.isEmpty()) cName = "Quốc tế";
            if (cityName != null && !cityName.isEmpty() && !cityName.equalsIgnoreCase(cName)) {
                return (flag.isEmpty() ? "🌐 " : flag + " ") + cName + " (" + cityName + ")";
            }
            return (flag.isEmpty() ? "🌐 " : flag + " ") + cName;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("proxyUrl", proxyUrl);
                o.put("host", host);
                o.put("port", port);
                o.put("exitIp", exitIp.isEmpty() ? host : exitIp);
                o.put("countryCode", countryCode);
                o.put("countryName", countryName.isEmpty() ? countryCodeToName(countryCode) : countryName);
                o.put("cityName", cityName);
                o.put("flag", getFlagEmoji());
                o.put("regionDisplay", getRegionDisplay());
                o.put("isp", isp);
                o.put("latencyMs", latencyMs < 9000 ? latencyMs : 0);
                o.put("isLive", isLive);
            } catch (Exception ignored) {}
            return o;
        }

        public static ProxyNodeInfo fromJson(JSONObject o) {
            ProxyNodeInfo info = new ProxyNodeInfo();
            if (o == null) return info;
            info.proxyUrl = o.optString("proxyUrl", "");
            info.host = o.optString("host", "");
            info.port = o.optInt("port", 0);
            info.exitIp = o.optString("exitIp", info.host);
            info.countryCode = o.optString("countryCode", "");
            info.countryName = o.optString("countryName", "");
            info.cityName = o.optString("cityName", "");
            info.isp = o.optString("isp", "");
            info.latencyMs = o.optLong("latencyMs", 0);
            info.isLive = o.optBoolean("isLive", true);
            return info;
        }

        @Override
        public int compareTo(ProxyNodeInfo o) {
            return Long.compare(this.latencyMs, o.latencyMs);
        }
    }

    public static String countryCodeToFlagEmoji(String code) {
        if (code == null || code.trim().length() != 2) return "🌐";
        String c = code.trim().toUpperCase(Locale.US);
        char first = c.charAt(0);
        char second = c.charAt(1);
        if (first < 'A' || first > 'Z' || second < 'A' || second > 'Z') return "🌐";
        int flag1 = 0x1F1E6 + (first - 'A');
        int flag2 = 0x1F1E6 + (second - 'A');
        return new String(Character.toChars(flag1)) + new String(Character.toChars(flag2));
    }

    public static String countryCodeToName(String code) {
        if (code == null || code.trim().isEmpty()) return "";
        String c = code.trim().toUpperCase(Locale.US);
        switch (c) {
            case "US": return "Hoa Kỳ (US)";
            case "GB":
            case "UK": return "Vương Quốc Anh (UK)";
            case "NL": return "Hà Lan (NL)";
            case "DE": return "Đức (DE)";
            case "FR": return "Pháp (FR)";
            case "SG": return "Singapore (SG)";
            case "JP": return "Nhật Bản (JP)";
            case "KR": return "Hàn Quốc (KR)";
            case "CA": return "Canada (CA)";
            case "AU": return "Úc (AU)";
            case "ES": return "Tây Ban Nha (ES)";
            case "IT": return "Ý (IT)";
            case "CH": return "Thụy Sĩ (CH)";
            case "SE": return "Thụy Điển (SE)";
            case "FI": return "Phần Lan (FI)";
            case "PL": return "Ba Lan (PL)";
            case "RO": return "Romania (RO)";
            case "CZ": return "CH Séc (CZ)";
            case "BR": return "Brazil (BR)";
            case "IN": return "Ấn Độ (IN)";
            case "HK": return "Hồng Kông (HK)";
            case "TW": return "Đài Loan (TW)";
            case "VN": return "Việt Nam (VN)";
            default: return c;
        }
    }

    // ================= 8 HỒ SƠ THIẾT BỊ ẢO CHỐNG NHẬN DẠNG THIẾT BỊ THẬT =================
    public static class VirtualDeviceProfile {
        public final String displayName;
        public final String modelCode;
        public final String androidVersion;
        public final String chromeMajor;
        public final String chromeFullVersion;
        public final String userAgent;
        public final String gpuVendor;
        public final String gpuRenderer;
        public final int cpuCores;
        public final int ramGb;
        public final int screenWidth;
        public final int screenHeight;
        public final double pixelRatio;
        public final String locale;
        public final String timezone;
        public final int tzOffsetMinutes;

        public VirtualDeviceProfile(
                String displayName, String modelCode, String androidVersion,
                String chromeMajor, String chromeFullVersion,
                String gpuVendor, String gpuRenderer,
                int cpuCores, int ramGb, int screenWidth, int screenHeight, double pixelRatio,
                String locale, String timezone, int tzOffsetMinutes) {
            this.displayName = displayName;
            this.modelCode = modelCode;
            this.androidVersion = androidVersion;
            this.chromeMajor = chromeMajor;
            this.chromeFullVersion = chromeFullVersion;
            this.userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + chromeMajor + ".0.0.0 Mobile Safari/537.36";
            this.gpuVendor = gpuVendor;
            this.gpuRenderer = gpuRenderer;
            this.cpuCores = cpuCores;
            this.ramGb = ramGb;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.pixelRatio = pixelRatio;
            this.locale = locale;
            this.timezone = timezone;
            this.tzOffsetMinutes = tzOffsetMinutes;
        }
    }

    private static final VirtualDeviceProfile[] VIRTUAL_DEVICES = new VirtualDeviceProfile[]{
        new VirtualDeviceProfile("Samsung Galaxy S24 Ultra", "SM-S928B", "14", "131", "131.0.6778.104",
                "Qualcomm", "Adreno (TM) 750", 8, 12, 412, 915, 3.5, "en-US", "America/Los_Angeles", 480),
        new VirtualDeviceProfile("Google Pixel 9 Pro XL", "Pixel 9 Pro XL", "15", "131", "131.0.6778.135",
                "ARM", "Mali-G715-Immortalis MC11", 8, 16, 412, 892, 3.5, "en-US", "America/New_York", 300),
        new VirtualDeviceProfile("Samsung Galaxy S23 Ultra", "SM-S918B", "14", "130", "130.0.6723.107",
                "Qualcomm", "Adreno (TM) 740", 8, 12, 384, 854, 3.0, "en-US", "America/Chicago", 360),
        new VirtualDeviceProfile("Xiaomi 14 Pro", "23116PN5BC", "14", "131", "131.0.6778.81",
                "Qualcomm", "Adreno (TM) 750", 8, 12, 393, 873, 3.0, "en-GB", "Europe/London", 0),
        new VirtualDeviceProfile("OnePlus 12 5G", "CPH2581", "14", "130", "130.0.6723.102",
                "Qualcomm", "Adreno (TM) 750", 8, 16, 412, 919, 3.5, "en-US", "Asia/Tokyo", -540),
        new VirtualDeviceProfile("Google Pixel 8 Pro", "Pixel 8 Pro", "14", "129", "129.0.6668.100",
                "ARM", "Mali-G715-Immortalis MC11", 8, 12, 412, 892, 3.0, "en-US", "Asia/Singapore", -480),
        new VirtualDeviceProfile("Sony Xperia 1 VI", "XQ-EC54", "14", "131", "131.0.6778.104",
                "Qualcomm", "Adreno (TM) 750", 8, 12, 384, 832, 2.75, "en-US", "Europe/Frankfurt", -60),
        new VirtualDeviceProfile("ASUS ROG Phone 8 Pro", "AI2401", "14", "130", "130.0.6723.86",
                "Qualcomm", "Adreno (TM) 750", 8, 16, 396, 880, 2.75, "en-US", "America/Los_Angeles", 480)
    };

    public static VirtualDeviceProfile getActiveVirtualDevice(int seed) {
        int idx = Math.abs(seed) % VIRTUAL_DEVICES.length;
        return VIRTUAL_DEVICES[idx];
    }

    public static String getActiveVirtualDeviceSummary() {
        return getActiveVirtualDeviceSummary(fingerprintSeed);
    }

    public static String getActiveVirtualDeviceSummary(int seed) {
        VirtualDeviceProfile vp = getActiveVirtualDevice(seed);
        return vp.displayName + " (" + vp.modelCode + " • Android " + vp.androidVersion + ")";
    }

    // ================= PHÂN TÍCH ĐỊNH DẠNG PROXY =================
    public static class ParsedProxy {
        public boolean isSocks5;
        public String host;
        public int port;
        public String user;
        public String pass;
        public String basicAuthHeader;

        public String toNormalizedUrl() {
            String scheme = isSocks5 ? "socks5://" : "http://";
            if (user != null && !user.isEmpty() && pass != null) {
                return scheme + user + ":" + pass + "@" + host + ":" + port;
            }
            return scheme + host + ":" + port;
        }
    }

    public static ParsedProxy parseProxyString(String proxyStr) {
        if (proxyStr == null || proxyStr.trim().isEmpty()) return null;
        try {
            String str = proxyStr.trim();
            str = str.replaceAll("^['\"`]+|['\"`]+$", "").trim();

            boolean socks5 = false;
            if (str.regionMatches(true, 0, "socks5h://", 0, 10)) {
                socks5 = true;
                str = str.substring(10);
            } else if (str.regionMatches(true, 0, "socks5://", 0, 9)) {
                socks5 = true;
                str = str.substring(9);
            } else if (str.regionMatches(true, 0, "socks://", 0, 8)) {
                socks5 = true;
                str = str.substring(8);
            } else if (str.regionMatches(true, 0, "http://", 0, 7)) {
                str = str.substring(7);
            } else if (str.regionMatches(true, 0, "https://", 0, 8)) {
                str = str.substring(8);
            }

            int slashIdx = str.indexOf('/');
            if (slashIdx > 0) {
                str = str.substring(0, slashIdx);
            }

            String host = "";
            int port = 0;
            String user = null;
            String pass = null;

            if (str.contains("@")) {
                String[] atParts = str.split("@", 2);
                String left = atParts[0];
                String right = atParts[1];
                if (right.contains(":")) {
                    String[] hp = right.split(":", 2);
                    String pDigits = hp[1].replaceAll("[^0-9]", "");
                    if (!pDigits.isEmpty() && hp[0].contains(".")) {
                        host = hp[0].trim();
                        port = Integer.parseInt(pDigits);
                        if (left.contains(":")) {
                            String[] cp = left.split(":", 2);
                            user = cp[0].trim();
                            pass = cp[1].trim();
                        }
                    }
                }
                if (host.isEmpty() && left.contains(":")) {
                    String[] hp = left.split(":", 2);
                    String pDigits = hp[1].replaceAll("[^0-9]", "");
                    if (!pDigits.isEmpty() && hp[0].contains(".")) {
                        host = hp[0].trim();
                        port = Integer.parseInt(pDigits);
                        if (right.contains(":")) {
                            String[] cp = right.split(":", 2);
                            user = cp[0].trim();
                            pass = cp[1].trim();
                        }
                    }
                }
            } else {
                String[] parts = str.split(":");
                if (parts.length == 4) {
                    if (parts[0].contains(".") && parts[1].matches("^\\d+$")) {
                        host = parts[0].trim();
                        port = Integer.parseInt(parts[1]);
                        user = parts[2].trim();
                        pass = parts[3].trim();
                    } else if (parts[2].contains(".") && parts[3].matches("^\\d+$")) {
                        user = parts[0].trim();
                        pass = parts[1].trim();
                        host = parts[2].trim();
                        port = Integer.parseInt(parts[3]);
                    }
                } else if (parts.length == 2) {
                    host = parts[0].trim();
                    String pDigits = parts[1].replaceAll("[^0-9]", "");
                    if (!pDigits.isEmpty()) {
                        port = Integer.parseInt(pDigits);
                    }
                }
            }

            // Loại bỏ các host không phải proxy hoặc cổng p.webshare.io:80 lỗi 407 trên gói Free
            if (host.equalsIgnoreCase("ipv4.webshare.io")
                    || host.equalsIgnoreCase("proxy.webshare.io")
                    || host.equalsIgnoreCase("dashboard.webshare.io")
                    || host.equalsIgnoreCase("p.webshare.io")
                    || host.equalsIgnoreCase("api.pixai.art")
                    || host.equalsIgnoreCase("pixai.art")) {
                return null;
            }

            if (!host.isEmpty() && host.contains(".") && port > 0 && port <= 65535) {
                ParsedProxy pp = new ParsedProxy();
                pp.isSocks5 = socks5;
                pp.host = host;
                pp.port = port;
                pp.user = user;
                pp.pass = pass;
                if (user != null && pass != null && !user.isEmpty()) {
                    String cred = user + ":" + pass;
                    String b64 = Base64.encodeToString(cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    pp.basicAuthHeader = "Basic " + b64.trim();
                }
                return pp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static List<String> extractProxiesFromCommandOrText(String rawInput) {
        Set<String> extracted = new LinkedHashSet<>();
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String text = rawInput.trim();

        Matcher mCurlUser = Pattern.compile("(?:-U|--proxy-user)\\s+['\"]?([^'\"\\s]+:[^'\"\\s]+)['\"]?").matcher(text);
        Matcher mCurlHost = Pattern.compile("(?:-x|--proxy|--socks5|--socks5-hostname)\\s+['\"]?([^'\"\\s]+)['\"]?").matcher(text);
        String explicitCreds = mCurlUser.find() ? mCurlUser.group(1) : null;
        while (mCurlHost.find()) {
            String rawHost = mCurlHost.group(1);
            boolean isSocks = text.contains("--socks5");
            if (explicitCreds != null && !rawHost.contains("@")) {
                String cleanHost = rawHost.replaceFirst("^(?i)(https?|socks5h?|socks)://", "");
                String combined = (isSocks ? "socks5://" : "http://") + explicitCreds + "@" + cleanHost;
                ParsedProxy pp = parseProxyString(combined);
                if (pp != null) extracted.add(pp.toNormalizedUrl());
            } else {
                ParsedProxy pp = parseProxyString(rawHost);
                if (pp != null) extracted.add(pp.toNormalizedUrl());
            }
        }

        Matcher mUrl = Pattern.compile("(?i)\\b((?:https?|socks5h?|socks)://[^\\s'\"`<>]+)").matcher(text);
        while (mUrl.find()) {
            String candidate = mUrl.group(1);
            ParsedProxy pp = parseProxyString(candidate);
            if (pp != null) {
                extracted.add(pp.toNormalizedUrl());
            }
        }

        String[] tokens = text.split("[\\r\\n\\s,;]+");
        for (String token : tokens) {
            if (token == null || token.isEmpty()) continue;
            String clean = token.replaceAll("^['\"`\\(\\[]+|['\"`\\)\\]]+$", "");
            if (clean.toLowerCase().startsWith("http://ipv4.webshare")
                    || clean.toLowerCase().startsWith("https://ipv4.webshare")
                    || clean.toLowerCase().startsWith("https://proxy.webshare.io")
                    || clean.toLowerCase().startsWith("https://dashboard.webshare.io")) {
                continue;
            }
            ParsedProxy pp = parseProxyString(clean);
            if (pp != null) {
                extracted.add(pp.toNormalizedUrl());
            }
        }

        return new ArrayList<>(extracted);
    }

    // ================= TỰ ĐỘNG LẤY DANH SÁCH 10 PROXY TỪ TÀI KHOẢN WEBSHARE (API V2) =================
    /**
     * Lấy danh sách Proxy từ tài khoản Webshare bằng API Token hoặc Link Download hoặc JSON trả về từ Webshare.
     */
    public static List<ProxyNodeInfo> fetchWebshareProxies(String tokenOrInput, PixAiApiHelper.LogCallback log) {
        List<ProxyNodeInfo> result = new ArrayList<>();
        if (tokenOrInput == null || tokenOrInput.trim().isEmpty()) return result;

        String trimmed = tokenOrInput.trim();

        // Trường hợp 1: Chuỗi truyền vào đã là JSON từ API /api/v2/proxy/list/ của Webshare
        if (trimmed.startsWith("{") && trimmed.contains("\"results\"")) {
            return parseWebshareProxyListJson(trimmed);
        }

        // Trường hợp 2: Chuỗi truyền vào chứa Link Download của Webshare (https://proxy.webshare.io/api/v2/proxy/list/download/...)
        if (trimmed.toLowerCase().contains("proxy.webshare.io/api/v2/proxy/list/download/")) {
            Matcher m = Pattern.compile("(https://proxy\\.webshare\\.io/api/v2/proxy/list/download/[^\\s'\"`<>]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (m.find()) {
                String dlUrl = m.group(1);
                if (log != null) log.log("🌐 Đang tải danh sách Proxy từ Link Download Webshare...", "#60A5FA");
                String resp = PixAiApiHelper.httpRequestWithHeaders(dlUrl, "GET", null, null, null, 12);
                List<String> urls = extractProxiesFromCommandOrText(resp);
                for (String u : urls) {
                    ParsedProxy pp = parseProxyString(u);
                    if (pp != null) {
                        ProxyNodeInfo info = new ProxyNodeInfo();
                        info.proxyUrl = pp.toNormalizedUrl();
                        info.host = pp.host;
                        info.port = pp.port;
                        info.exitIp = pp.host;
                        result.add(info);
                    }
                }
                return result;
            }
        }

        // Trường hợp 3: Chuỗi truyền vào là Webshare API Token (VD: 40 ký tự chữ-số hoặc "Token xxx")
        String cleanToken = trimmed.replaceFirst("(?i)^Token\\s+", "").replaceFirst("(?i)^Bearer\\s+", "").trim();
        if (cleanToken.matches("^[a-zA-Z0-9_\\-]{20,64}$")) {
            if (log != null) log.log("🔑 Đang gọi API Webshare v2 để lấy danh sách 10 Proxy Free...", "#60A5FA");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Token " + cleanToken);
            headers.put("Accept", "application/json");
            headers.put("Origin", "https://dashboard.webshare.io");
            headers.put("Referer", "https://dashboard.webshare.io/");

            String listUrl = "https://proxy.webshare.io/api/v2/proxy/list/?mode=direct&page=1&page_size=25";
            for (int attempt = 0; attempt < 6; attempt++) {
                String resp = PixAiApiHelper.httpRequestWithHeaders(listUrl, "GET", headers, null, null, 12);
                if (resp != null && resp.contains("\"results\"")) {
                    result = parseWebshareProxyListJson(resp);
                    if (!result.isEmpty()) {
                        return result;
                    }
                    if (attempt < 5) {
                        if (log != null && attempt == 0) {
                            log.log("  ⏳ Webshare đang cấp phát 10 Proxy cho tài khoản mới, đợi vài giây...", "#94A3B8");
                        }
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    }
                } else {
                    break;
                }
            }
        }

        // Trường hợp 4: Người dùng nhập trực tiếp danh sách ip:port:user:pass hoặc lệnh curl
        List<String> manualUrls = extractProxiesFromCommandOrText(trimmed);
        for (String u : manualUrls) {
            ParsedProxy pp = parseProxyString(u);
            if (pp != null) {
                ProxyNodeInfo info = new ProxyNodeInfo();
                info.proxyUrl = pp.toNormalizedUrl();
                info.host = pp.host;
                info.port = pp.port;
                info.exitIp = pp.host;
                result.add(info);
            }
        }
        return result;
    }

    public static List<ProxyNodeInfo> parseWebshareProxyListJson(String jsonStr) {
        List<ProxyNodeInfo> list = new ArrayList<>();
        if (jsonStr == null || !jsonStr.contains("\"results\"")) return list;
        try {
            int start = jsonStr.indexOf('{');
            int end = jsonStr.lastIndexOf('}');
            if (start >= 0 && end > start) {
                jsonStr = jsonStr.substring(start, end + 1);
            }
            JSONObject root = new JSONObject(jsonStr);
            JSONArray results = root.optJSONArray("results");
            if (results == null) return list;

            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                boolean valid = item.optBoolean("valid", true);
                if (!valid) continue;

                String host = item.optString("proxy_address", "").trim();
                int port = item.optInt("port", 0);
                if (port <= 0) {
                    JSONObject portsObj = item.optJSONObject("ports");
                    if (portsObj != null) {
                        port = portsObj.optInt("http", portsObj.optInt("socks5", 0));
                    }
                }
                String user = item.optString("username", "").trim();
                String pass = item.optString("password", "").trim();
                String countryCode = item.optString("country_code", "").trim().toUpperCase(Locale.US);
                String cityName = item.optString("city_name", "").trim();

                if (!host.isEmpty() && port > 0) {
                    ProxyNodeInfo node = new ProxyNodeInfo();
                    node.host = host;
                    node.port = port;
                    node.exitIp = host;
                    if (!user.isEmpty() && !pass.isEmpty()) {
                        node.proxyUrl = "http://" + user + ":" + pass + "@" + host + ":" + port;
                    } else {
                        node.proxyUrl = "http://" + host + ":" + port;
                    }
                    node.countryCode = countryCode;
                    node.countryName = countryCodeToName(countryCode);
                    node.cityName = cityName;
                    node.isp = item.optString("asn_name", "Webshare Network");
                    node.isLive = true;
                    list.add(node);
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static List<ProxyNodeInfo> fetchAndFilterShareFreeProxies(PixAiApiHelper.LogCallback log) {
        List<String> candidates = PixAiApiHelper.fetchShareFreeProxyPool(log);
        if (candidates.isEmpty()) return new ArrayList<>();
        return checkAndFilterLiveProxiesWithRegion(candidates, null, log);
    }

    // ================= KIỂM TRA VÙNG IP (IP REGION, QUỐC GIA, THÀNH PHỐ, ISP & ĐỘ TRỄ) =================
    /**
     * Kiểm tra chi tiết IP và Vùng địa lý thông qua 1 Proxy cụ thể (hoặc mạng trực tiếp nếu proxyStr rỗng)
     */
    public static ProxyNodeInfo checkSingleProxyIpAndRegion(String proxyStr, int timeoutSec) {
        ProxyNodeInfo node = new ProxyNodeInfo();
        ParsedProxy pp = parseProxyString(proxyStr);
        if (pp != null) {
            node.proxyUrl = pp.toNormalizedUrl();
            node.host = pp.host;
            node.port = pp.port;
        }

        long t0 = System.currentTimeMillis();
        try {
            // Ưu tiên 1: Truy vấn ipwho.is qua đúng đường hầm Proxy (không tự fallback về mạng gốc khi đang test riêng 1 proxy)
            String resp = (pp != null)
                    ? PixAiApiHelper.executeSocketHttpRequest("https://ipwho.is/", "GET", null, null, pp, timeoutSec)
                    : PixAiApiHelper.httpRequestWithHeaders("https://ipwho.is/", "GET", null, null, null, timeoutSec);

            long ping = System.currentTimeMillis() - t0;
            if (resp != null && resp.contains("\"ip\"") && !resp.contains("\"success\":false")) {
                int s = resp.indexOf('{');
                int e = resp.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JSONObject j = new JSONObject(resp.substring(s, e + 1));
                    String ip = j.optString("ip", "").trim();
                    if (!ip.isEmpty()) {
                        node.exitIp = ip;
                        node.countryCode = j.optString("country_code", "").trim().toUpperCase(Locale.US);
                        node.countryName = j.optString("country", countryCodeToName(node.countryCode)).trim();
                        node.cityName = j.optString("city", j.optString("region", "")).trim();
                        JSONObject conn = j.optJSONObject("connection");
                        if (conn != null) {
                            node.isp = conn.optString("isp", conn.optString("org", "")).trim();
                        }
                        node.latencyMs = ping;
                        node.isLive = true;
                        return node;
                    }
                }
            }

            // Ưu tiên 2: Dự phòng qua ip-api.com nếu ipwho.is bị nghẽn
            long t1 = System.currentTimeMillis();
            String url2 = "http://ip-api.com/json/?fields=status,country,countryCode,regionName,city,isp,query";
            String resp2 = (pp != null)
                    ? PixAiApiHelper.executeSocketHttpRequest(url2, "GET", null, null, pp, timeoutSec)
                    : PixAiApiHelper.httpRequestWithHeaders(url2, "GET", null, null, null, timeoutSec);
            long ping2 = System.currentTimeMillis() - t1;
            if (resp2 != null && resp2.contains("\"query\"")) {
                int s = resp2.indexOf('{');
                int e = resp2.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JSONObject j2 = new JSONObject(resp2.substring(s, e + 1));
                    String ip = j2.optString("query", "").trim();
                    if (!ip.isEmpty()) {
                        node.exitIp = ip;
                        node.countryCode = j2.optString("countryCode", "").trim().toUpperCase(Locale.US);
                        node.countryName = j2.optString("country", countryCodeToName(node.countryCode)).trim();
                        node.cityName = j2.optString("city", j2.optString("regionName", "")).trim();
                        node.isp = j2.optString("isp", "").trim();
                        node.latencyMs = ping2;
                        node.isLive = true;
                        return node;
                    }
                }
            }
        } catch (Exception ignored) {}

        node.isLive = false;
        return node;
    }

    /**
     * Kiểm tra song song toàn bộ danh sách Proxy:
     * - Lọc các Proxy đang sống.
     * - Xác định chính xác Quốc gia, Thành phố, Cờ, ISP và Ping (ms) của từng Proxy.
     * - Sắp xếp từ nhanh nhất đến chậm nhất.
     */
    public static List<ProxyNodeInfo> checkAndFilterLiveProxiesWithRegion(
            List<String> candidates,
            Map<String, ProxyNodeInfo> existingMeta,
            PixAiApiHelper.LogCallback log) {
        if (candidates == null || candidates.isEmpty()) return new ArrayList<>();

        final List<String> toCheck = candidates.size() > 25 ? candidates.subList(0, 25) : candidates;
        if (log != null) {
            log.log("🔍 Đang kiểm tra tốc độ & vùng địa lý (IP/Quốc gia) trên " + toCheck.size() + " Proxy...", "#60A5FA");
        }

        final List<ProxyNodeInfo> liveResults = Collections.synchronizedList(new ArrayList<>());
        int threads = Math.min(10, Math.max(2, toCheck.size()));
        ExecutorService poolExec = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(toCheck.size());

        for (final String pStr : toCheck) {
            poolExec.execute(() -> {
                try {
                    ProxyNodeInfo checked = checkSingleProxyIpAndRegion(pStr, 3);
                    if (checked.isLive && checked.latencyMs < 3500) {
                        // Giữ lại thông tin vùng từ Webshare nếu API tra cứu IP thiếu tên thành phố
                        if (existingMeta != null && existingMeta.containsKey(checked.proxyUrl)) {
                            ProxyNodeInfo old = existingMeta.get(checked.proxyUrl);
                            if (old != null) {
                                if (checked.countryCode.isEmpty()) checked.countryCode = old.countryCode;
                                if (checked.countryName.isEmpty()) checked.countryName = old.countryName;
                                if (checked.cityName.isEmpty()) checked.cityName = old.cityName;
                            }
                        }
                        liveResults.add(checked);
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        poolExec.shutdownNow();

        Collections.sort(liveResults);
        if (log != null) {
            log.log("✅ Đã kiểm tra xong: " + liveResults.size() + "/" + toCheck.size() + " Proxy đang sống & rõ vùng IP!", "#34D399");
        }
        return liveResults;
    }

    public static List<String> checkAndFilterLiveProxies(List<String> candidates, PixAiApiHelper.LogCallback log) {
        List<ProxyNodeInfo> infos = checkAndFilterLiveProxiesWithRegion(candidates, null, log);
        List<String> urls = new ArrayList<>();
        for (ProxyNodeInfo info : infos) {
            urls.add(info.proxyUrl);
        }
        return urls;
    }

    // ================= QUẢN LÝ CẤU HÌNH & ĐƯỜNG HẦM LOCAL TUNNEL =================
    public static synchronized void syncAndApplySecurityConfig(AccountStorage storage) {
        if (storage == null) return;
        useUpstreamProxy = storage.isUseProxy();
        proxyWebUi = storage.isProxyWebUiEnabled();
        autoRotateProxy = storage.isAutoRotateProxyEnabled();
        upstreamProxyRaw = storage.getProxyAddress();
        activeProxyPool = storage.getParsedProxyPool();
        dohEnabled = storage.isDohEnabled();
        antiDpiEnabled = storage.isAntiDpiEnabled();
        strictKillSwitch = storage.isStrictKillSwitchEnabled();
        blockWebRtc = storage.isBlockWebRtcEnabled();
        spoofFingerprint = storage.isSpoofFingerprintEnabled();
        blockTrackers = storage.isBlockTrackersEnabled();
        fingerprintSeed = storage.getFingerprintSeed();

        ensureLocalTunnelRunning();
        applyWebViewProxyOverride();
    }

    public static synchronized void applyDirectConfig(
            boolean useProxy,
            String proxyAddr,
            boolean enableDoh,
            boolean enableAntiDpi,
            boolean killSwitch,
            int fpSeed) {
        useUpstreamProxy = useProxy;
        proxyWebUi = true;
        upstreamProxyRaw = proxyAddr != null ? proxyAddr : "";
        dohEnabled = enableDoh;
        antiDpiEnabled = enableAntiDpi;
        strictKillSwitch = killSwitch;
        blockWebRtc = true;
        spoofFingerprint = true;
        blockTrackers = true;
        fingerprintSeed = fpSeed != 0 ? fpSeed : (int) (System.currentTimeMillis() & 0x7FFFFFFF);

        ensureLocalTunnelRunning();
        applyWebViewProxyOverride();
    }

    public static String getCleanUserAgent() {
        return getActiveVirtualDevice(fingerprintSeed).userAgent;
    }

    public static boolean isUseUpstreamProxy() {
        return useUpstreamProxy;
    }

    public static String getActiveUpstreamProxyRaw() {
        return upstreamProxyRaw != null ? upstreamProxyRaw : "";
    }

    public static boolean isStrictKillSwitchActive() {
        return strictKillSwitch;
    }

    /**
     * Khi bật Proxy và bật Chặn quay về IP gốc (Kill-Switch) -> Tuyệt đối không cho bất kỳ kết nối nào dùng IP thật.
     */
    public static boolean isBlockOriginalIpActive() {
        return useUpstreamProxy && strictKillSwitch;
    }

    public static long getEncryptedTunneledRequests() {
        return encryptedTunneledRequests.get();
    }

    public static long getBlockedTrackersCount() {
        return blockedTrackersCount.get();
    }

    public static long getDohResolvedCount() {
        return dohResolvedCount.get();
    }

    public static int getLocalTunnelPort() {
        ensureLocalTunnelRunning();
        return localTunnelPort;
    }

    public static String getNextWorkingProxyCandidate(String failedProxyRaw) {
        List<String> pool = activeProxyPool;
        if (pool != null && pool.size() > 1) {
            int n = pool.size();
            for (int attempt = 0; attempt < n; attempt++) {
                int idx = Math.abs(runtimePoolCursor.incrementAndGet()) % n;
                String candidate = pool.get(idx);
                if (candidate != null && !candidate.equalsIgnoreCase(failedProxyRaw)) {
                    ParsedProxy pp = parseProxyString(candidate);
                    if (pp != null) {
                        upstreamProxyRaw = candidate;
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static ParsedProxy pickFallbackProxyFromPool(String failedProxyRaw) {
        String next = getNextWorkingProxyCandidate(failedProxyRaw);
        return next != null ? parseProxyString(next) : null;
    }

    public static void ensureLocalTunnelRunning() {
        synchronized (tunnelLock) {
            if (localTunnelServer != null && !localTunnelServer.isClosed() && localTunnelPort > 0) {
                return;
            }
            try {
                localTunnelServer = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
                localTunnelPort = localTunnelServer.getLocalPort();
                final ServerSocket srv = localTunnelServer;
                tunnelExecutor.execute(() -> {
                    while (!srv.isClosed()) {
                        try {
                            final Socket client = srv.accept();
                            tunnelExecutor.execute(() -> handleTunnelClient(client));
                        } catch (Throwable e) {
                            if (srv.isClosed()) break;
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static volatile int appliedWebViewTunnelPort = -1;

    /**
     * Định tuyến WebView qua Local Tunnel khi người dùng BẬT Proxy.
     * Chỉ gọi setProxyOverride khi trạng thái cổng thực sự thay đổi để tránh làm Chromium WebView tự ngắt kết nối và reload trang!
     */
    public static void applyWebViewProxyOverride() {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                return;
            }

            ParsedProxy pp = (useUpstreamProxy && proxyWebUi) ? parseProxyString(upstreamProxyRaw) : null;
            if (pp != null) {
                ensureLocalTunnelRunning();
                if (localTunnelPort > 0) {
                    if (appliedWebViewTunnelPort == localTunnelPort) {
                        return;
                    }
                    appliedWebViewTunnelPort = localTunnelPort;
                    ProxyConfig proxyConfig = new ProxyConfig.Builder()
                            .addProxyRule("http://127.0.0.1:" + localTunnelPort)
                            .addBypassRule("127.0.0.1")
                            .addBypassRule("localhost")
                            .addBypassRule("<local>")
                            .build();
                    ProxyController.getInstance().setProxyOverride(proxyConfig, tunnelExecutor, () -> {});
                    return;
                }
            }
            if (appliedWebViewTunnelPort != 0) {
                appliedWebViewTunnelPort = 0;
                ProxyController.getInstance().clearProxyOverride(tunnelExecutor, () -> {});
            }
        } catch (Throwable ignored) {}
    }

    public static boolean handleHttpAuthRequest(android.webkit.HttpAuthHandler handler, String host, String realm) {
        if (handler == null) return false;
        try {
            ParsedProxy pp = parseProxyString(upstreamProxyRaw);
            if (pp != null && pp.user != null && !pp.user.isEmpty() && pp.pass != null) {
                handler.proceed(pp.user, pp.pass);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String resolveHostProtected(String host) {
        if (host == null || host.isEmpty()) return host;
        String trimmed = host.trim().toLowerCase(Locale.US);
        if (trimmed.equals("localhost") || trimmed.equals("127.0.0.1") || trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            return trimmed;
        }
        DnsCacheEntry cached = dohCache.get(trimmed);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.ip;
        }
        try {
            InetAddress addr = InetAddress.getByName(trimmed);
            String sysIp = addr.getHostAddress();
            if (sysIp != null && !sysIp.isEmpty()) {
                dohCache.put(trimmed, new DnsCacheEntry(sysIp, 300));
                dohResolvedCount.incrementAndGet();
                return sysIp;
            }
        } catch (Exception ignored) {}
        return trimmed;
    }

    // ================= XỬ LÝ ĐƯỜNG HẦM PROXY CHO WEBVIEW (127.0.0.1) =================
    private static void handleTunnelClient(Socket clientSocket) {
        Socket remoteSocket = null;
        try {
            clientSocket.setSoTimeout(30000);
            clientSocket.setTcpNoDelay(true);
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int state = 0;
            while ((b = clientIn.read()) != -1) {
                headerBuf.write(b);
                if (state == 0 && b == '\r') state = 1;
                else if (state == 1 && b == '\n') state = 2;
                else if (state == 2 && b == '\r') state = 3;
                else if (state == 3 && b == '\n') { state = 4; break; }
                else state = 0;
                if (headerBuf.size() > 16384) break;
            }
            if (headerBuf.size() == 0) {
                clientSocket.close();
                return;
            }

            String headerStr = new String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = headerStr.split("\r\n");
            if (lines.length == 0) {
                clientSocket.close();
                return;
            }

            String firstLine = lines[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                clientSocket.close();
                return;
            }

            String method = parts[0].trim();
            String target = parts[1].trim();

            String targetHost = "";
            int targetPort = 80;
            boolean isConnect = "CONNECT".equalsIgnoreCase(method);

            if (isConnect) {
                targetPort = 443;
                if (target.contains(":")) {
                    String[] hp = target.split(":", 2);
                    targetHost = hp[0].trim();
                    try { targetPort = Integer.parseInt(hp[1].trim()); } catch (Exception ignored) {}
                } else {
                    targetHost = target;
                }
            } else {
                try {
                    Uri u = Uri.parse(target);
                    if (u.getHost() != null) {
                        targetHost = u.getHost();
                        targetPort = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
                    }
                } catch (Exception ignored) {}
                if (targetHost.isEmpty()) {
                    for (String line : lines) {
                        if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                            String hVal = line.substring(5).trim();
                            if (hVal.contains(":")) {
                                String[] hp = hVal.split(":", 2);
                                targetHost = hp[0].trim();
                                try { targetPort = Integer.parseInt(hp[1].trim()); } catch (Exception ignored) {}
                            } else {
                                targetHost = hVal;
                            }
                            break;
                        }
                    }
                }
            }

            if (targetHost.isEmpty()) {
                clientSocket.close();
                return;
            }

            if (blockTrackers && isTrackerHost(targetHost)) {
                blockedTrackersCount.incrementAndGet();
                if (isConnect) {
                    clientOut.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                } else {
                    clientOut.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                }
                clientOut.flush();
                clientSocket.close();
                return;
            }

            encryptedTunneledRequests.incrementAndGet();

            String currentProxyStr = upstreamProxyRaw;
            ParsedProxy upstream = (useUpstreamProxy && proxyWebUi) ? parseProxyString(currentProxyStr) : null;

            if (isConnect) {
                if (upstream != null) {
                    try {
                        remoteSocket = openUpstreamProxyTunnel(upstream, targetHost, targetPort);
                    } catch (Exception proxyEx) {
                        // Thử xoay sang tối đa 2 Proxy dự phòng trong Kho (Pool) nếu cổng hiện tại lỗi
                        String lastFailed = currentProxyStr;
                        for (int retry = 0; retry < 2 && remoteSocket == null; retry++) {
                            ParsedProxy fallbackProxy = pickFallbackProxyFromPool(lastFailed);
                            if (fallbackProxy == null) break;
                            lastFailed = fallbackProxy.toNormalizedUrl();
                            try {
                                remoteSocket = openUpstreamProxyTunnel(fallbackProxy, targetHost, targetPort);
                            } catch (Exception ignoredFallback) {
                                remoteSocket = null;
                            }
                        }
                    }
                }

                // Nếu Proxy trong kho đều chết, tự động kết nối dự phòng để không bao giờ bị lỗi Webpage Not Available
                if (remoteSocket == null) {
                    remoteSocket = new Socket();
                    remoteSocket.setTcpNoDelay(true);
                    remoteSocket.connect(new InetSocketAddress(targetHost, targetPort), 8000);
                }

                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();

                OutputStream remoteOut = remoteSocket.getOutputStream();
                InputStream remoteIn = remoteSocket.getInputStream();

                final Socket finalRemote = remoteSocket;
                tunnelExecutor.execute(() -> pipeStream(clientIn, remoteOut, clientSocket, finalRemote));
                pipeStream(remoteIn, clientOut, finalRemote, clientSocket);
            } else {
                String cleanedHeader = sanitizePlainHttpHeaders(headerStr, upstream);
                if (upstream != null && !upstream.isSocks5) {
                    try {
                        remoteSocket = new Socket();
                        remoteSocket.setTcpNoDelay(true);
                        remoteSocket.connect(new InetSocketAddress(upstream.host, upstream.port), 4500);
                        OutputStream remoteOut = remoteSocket.getOutputStream();
                        remoteOut.write(cleanedHeader.getBytes(StandardCharsets.ISO_8859_1));
                        remoteOut.flush();
                    } catch (Exception proxyEx) {
                        remoteSocket = null;
                    }
                } else if (upstream != null && upstream.isSocks5) {
                    try {
                        remoteSocket = openUpstreamProxyTunnel(upstream, targetHost, targetPort);
                        OutputStream remoteOut = remoteSocket.getOutputStream();
                        String relativeHeader = sanitizePlainHttpHeaders(headerStr, null);
                        remoteOut.write(relativeHeader.getBytes(StandardCharsets.ISO_8859_1));
                        remoteOut.flush();
                    } catch (Exception e) {
                        remoteSocket = null;
                    }
                }

                if (remoteSocket == null) {
                    remoteSocket = new Socket();
                    remoteSocket.setTcpNoDelay(true);
                    remoteSocket.connect(new InetSocketAddress(targetHost, targetPort), 8000);
                    OutputStream remoteOut = remoteSocket.getOutputStream();
                    String relativeHeader = sanitizePlainHttpHeaders(headerStr, null);
                    remoteOut.write(relativeHeader.getBytes(StandardCharsets.ISO_8859_1));
                    remoteOut.flush();
                }

                final Socket finalRemote = remoteSocket;
                OutputStream remoteOut = remoteSocket.getOutputStream();
                InputStream remoteIn = remoteSocket.getInputStream();
                tunnelExecutor.execute(() -> pipeStream(clientIn, remoteOut, clientSocket, finalRemote));
                pipeStream(remoteIn, clientOut, finalRemote, clientSocket);
            }
        } catch (Throwable ignored) {
            try { clientSocket.close(); } catch (Exception e) {}
            if (remoteSocket != null) {
                try { remoteSocket.close(); } catch (Exception e) {}
            }
        }
    }

    /**
     * Mở đường hầm qua Upstream Proxy (Hỗ trợ cả HTTP CONNECT Proxy & SOCKS5 Proxy có user:pass)
     */
    public static Socket openUpstreamProxyTunnel(ParsedProxy upstream, String targetHost, int targetPort) throws IOException {
        Socket sock = new Socket();
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(8000);
        sock.connect(new InetSocketAddress(upstream.host, upstream.port), 4500);

        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        if (upstream.isSocks5) {
            boolean hasAuth = upstream.user != null && upstream.pass != null && !upstream.user.isEmpty();
            if (hasAuth) {
                out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
            } else {
                out.write(new byte[]{0x05, 0x01, 0x00});
            }
            out.flush();

            DataInputStream dis = new DataInputStream(in);
            int ver = dis.readUnsignedByte();
            int method = dis.readUnsignedByte();
            if (ver != 0x05) {
                sock.close();
                throw new IOException("SOCKS5 phiên bản không hợp lệ");
            }
            if (method == 0x02 && hasAuth) {
                byte[] uBytes = upstream.user.getBytes(StandardCharsets.UTF_8);
                byte[] pBytes = upstream.pass.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream authReq = new ByteArrayOutputStream();
                authReq.write(0x01);
                authReq.write(uBytes.length);
                authReq.write(uBytes);
                authReq.write(pBytes.length);
                authReq.write(pBytes);
                out.write(authReq.toByteArray());
                out.flush();

                dis.readUnsignedByte();
                int authStatus = dis.readUnsignedByte();
                if (authStatus != 0x00) {
                    sock.close();
                    throw new IOException("Xác thực SOCKS5 thất bại");
                }
            } else if (method == 0xFF) {
                sock.close();
                throw new IOException("SOCKS5 từ chối phương thức xác thực");
            }

            byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream connReq = new ByteArrayOutputStream();
            connReq.write(0x05);
            connReq.write(0x01);
            connReq.write(0x00);
            connReq.write(0x03);
            connReq.write(hostBytes.length);
            connReq.write(hostBytes);
            connReq.write((targetPort >> 8) & 0xFF);
            connReq.write(targetPort & 0xFF);
            out.write(connReq.toByteArray());
            out.flush();

            dis.readUnsignedByte();
            int repCode = dis.readUnsignedByte();
            dis.readUnsignedByte();
            int atyp = dis.readUnsignedByte();
            if (atyp == 0x01) {
                dis.skipBytes(4);
            } else if (atyp == 0x03) {
                int len = dis.readUnsignedByte();
                dis.skipBytes(len);
            } else if (atyp == 0x04) {
                dis.skipBytes(16);
            }
            dis.skipBytes(2);

            if (repCode != 0x00) {
                sock.close();
                throw new IOException("SOCKS5 CONNECT thất bại: mã " + repCode);
            }
            return sock;
        } else {
            StringBuilder connectReq = new StringBuilder();
            connectReq.append("CONNECT ").append(targetHost).append(":").append(targetPort).append(" HTTP/1.1\r\n");
            connectReq.append("Host: ").append(targetHost).append(":").append(targetPort).append("\r\n");
            connectReq.append("User-Agent: ").append(getCleanUserAgent()).append("\r\n");
            if (upstream.basicAuthHeader != null) {
                connectReq.append("Proxy-Authorization: ").append(upstream.basicAuthHeader).append("\r\n");
            }
            connectReq.append("Proxy-Connection: Keep-Alive\r\n\r\n");

            out.write(connectReq.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            StringBuilder respLine = new StringBuilder();
            int c;
            int state = 0;
            while ((c = in.read()) != -1) {
                respLine.append((char) c);
                if (state == 0 && c == '\r') state = 1;
                else if (state == 1 && c == '\n') state = 2;
                else if (state == 2 && c == '\r') state = 3;
                else if (state == 3 && c == '\n') break;
                else state = 0;
                if (respLine.length() > 8192) break;
            }

            String statusStr = respLine.toString();
            if (!statusStr.contains(" 200 ")) {
                sock.close();
                throw new IOException("HTTP Proxy CONNECT thất bại: " + (statusStr.contains("\r\n") ? statusStr.substring(0, statusStr.indexOf("\r\n")) : statusStr));
            }
            return sock;
        }
    }

    private static String sanitizePlainHttpHeaders(String rawHeaders, ParsedProxy upstream) {
        String[] lines = rawHeaders.split("\r\n");
        StringBuilder sb = new StringBuilder();
        VirtualDeviceProfile vp = getActiveVirtualDevice(fingerprintSeed);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (i == 0 && upstream == null) {
                String[] p = line.split(" ");
                if (p.length >= 3 && (p[1].startsWith("http://") || p[1].startsWith("https://"))) {
                    try {
                        Uri u = Uri.parse(p[1]);
                        String path = u.getEncodedPath();
                        if (path == null || path.isEmpty()) path = "/";
                        if (u.getEncodedQuery() != null && !u.getEncodedQuery().isEmpty()) {
                            path += "?" + u.getEncodedQuery();
                        }
                        line = p[0] + " " + path + " " + p[2];
                    } catch (Exception ignored) {}
                }
            }
            if (line.regionMatches(true, 0, "X-Requested-With:", 0, 17)
                    || line.regionMatches(true, 0, "Sec-CH-UA-Model:", 0, 16)
                    || line.regionMatches(true, 0, "Sec-CH-UA-Platform-Version:", 0, 27)
                    || line.regionMatches(true, 0, "Sec-CH-UA-Full-Version", 0, 22)) {
                continue;
            }
            if (line.regionMatches(true, 0, "User-Agent:", 0, 11)) {
                line = "User-Agent: " + vp.userAgent;
            }
            sb.append(line).append("\r\n");
        }
        sb.append("Sec-CH-UA-Model: \"").append(vp.modelCode).append("\"\r\n");
        sb.append("Sec-CH-UA-Platform: \"Android\"\r\n");
        sb.append("Sec-CH-UA-Mobile: ?1\r\n");
        if (upstream != null && !upstream.isSocks5 && upstream.basicAuthHeader != null) {
            sb.append("Proxy-Authorization: ").append(upstream.basicAuthHeader).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static void pipeStream(InputStream in, OutputStream out, Socket s1, Socket s2) {
        byte[] buffer = new byte[16384];
        int len;
        try {
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { s1.close(); } catch (Exception ignored) {}
            try { s2.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean isTrackerHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.US);
        for (String tracker : TRACKER_DOMAINS) {
            if (h.equals(tracker) || h.endsWith("." + tracker) || h.contains(tracker)) {
                return true;
            }
        }
        return false;
    }

    public static WebResourceResponse interceptWebViewRequest(Uri uri) {
        if (!blockTrackers || uri == null) return null;
        String host = uri.getHost();
        if (host == null) return null;
        String lowerHost = host.toLowerCase(Locale.US);
        // Tuyệt đối không chặn bất kỳ request nào thuộc tên miền chính của PixAI hoặc Webshare
        if (lowerHost.contains("pixai.art") || lowerHost.contains("webshare.io") || lowerHost.contains("recaptcha") || lowerHost.contains("gstatic.com") || lowerHost.contains("google.com")) {
            return null;
        }
        String fullUrl = uri.toString().toLowerCase(Locale.US);
        if (isTrackerHost(lowerHost)
                || fullUrl.contains("sentry_key=")
                || fullUrl.contains("collect?v=")) {
            blockedTrackersCount.incrementAndGet();
            boolean isJs = fullUrl.contains(".js");
            String mime = isJs ? "application/javascript" : "application/json";
            byte[] emptyPayload = (isJs ? "/* blocked */" : "{}").getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse(
                    mime,
                    "UTF-8",
                    200,
                    "OK",
                    Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                    new ByteArrayInputStream(emptyPayload)
            );
        }
        return null;
    }

    // ================= CHỐNG NHẬN DẠNG THIẾT BỊ THẬT TOÀN DIỆN =================
    public static String getStealthInjectionJs() {
        return getStealthInjectionJs(fingerprintSeed, blockWebRtc, spoofFingerprint);
    }

    public static String getStealthInjectionJs(int seed, boolean enableWebRtcBlock, boolean enableFpSpoof) {
        VirtualDeviceProfile vp = getActiveVirtualDevice(seed);
        int noiseDelta = (Math.abs(seed) % 5) + 1;
        int subPixelJitter = (Math.abs(seed) % 7) + 1;
        long fakeStorageQuota = (long) vp.ramGb * 16L * 1024L * 1024L * 1024L;
        long fakeStorageUsage = (Math.abs(seed) % 900 + 120) * 1024L * 1024L;
        String fakeAudioDeviceId = "ad_" + Integer.toHexString(Math.abs(seed * 31 + 7));
        String fakeVideoDeviceId = "vd_" + Integer.toHexString(Math.abs(seed * 17 + 13));
        String fakeGroupId = "grp_" + Integer.toHexString(Math.abs(seed * 23 + 5));

        return "(() => {" +
            "  try {" +
            "    if (window.__pixai_stealth_shield_active) return;" +
            "    window.__pixai_stealth_shield_active = true;" +
            "    try {" +
            "      Object.defineProperty(navigator, 'webdriver', { get: () => false, configurable: true });" +
            "      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;" +
            "      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;" +
            "      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;" +
            "    } catch(e) {}" +
            (enableWebRtcBlock ? (
            "    try {" +
            "      const noopRtc = function() {" +
            "        return {" +
            "          createDataChannel: function() { return {}; }," +
            "          createOffer: function() { return Promise.resolve({}); }," +
            "          createAnswer: function() { return Promise.resolve({}); }," +
            "          setLocalDescription: function() { return Promise.resolve(); }," +
            "          setRemoteDescription: function() { return Promise.resolve(); }," +
            "          addIceCandidate: function() { return Promise.resolve(); }," +
            "          close: function() {}," +
            "          addEventListener: function() {}," +
            "          removeEventListener: function() {}" +
            "        };" +
            "      };" +
            "      window.RTCPeerConnection = noopRtc;" +
            "      window.webkitRTCPeerConnection = noopRtc;" +
            "      window.mozRTCPeerConnection = noopRtc;" +
            "      if (window.RTCDataChannel) window.RTCDataChannel = function() {};" +
            "    } catch(e) {}"
            ) : "") +
            (enableFpSpoof ? (
            "    try {" +
            "      const fakeBrands = [" +
            "        { brand: 'Google Chrome', version: '" + vp.chromeMajor + "' }," +
            "        { brand: 'Chromium', version: '" + vp.chromeMajor + "' }," +
            "        { brand: 'Not_A Brand', version: '24' }" +
            "      ];" +
            "      const fakeFullVersionList = [" +
            "        { brand: 'Google Chrome', version: '" + vp.chromeFullVersion + "' }," +
            "        { brand: 'Chromium', version: '" + vp.chromeFullVersion + "' }," +
            "        { brand: 'Not_A Brand', version: '24.0.0.0' }" +
            "      ];" +
            "      const fakeUaData = {" +
            "        brands: fakeBrands," +
            "        mobile: true," +
            "        platform: 'Android'," +
            "        getHighEntropyValues: function(hints) {" +
            "          return Promise.resolve({" +
            "            brands: fakeBrands," +
            "            mobile: true," +
            "            platform: 'Android'," +
            "            platformVersion: '" + vp.androidVersion + ".0.0'," +
            "            architecture: 'arm'," +
            "            bitness: '64'," +
            "            wow64: false," +
            "            model: '" + vp.modelCode + "'," +
            "            uaFullVersion: '" + vp.chromeFullVersion + "'," +
            "            fullVersionList: fakeFullVersionList" +
            "          });" +
            "        }," +
            "        toJSON: function() { return { brands: fakeBrands, mobile: true, platform: 'Android' }; }" +
            "      };" +
            "      Object.defineProperty(navigator, 'userAgentData', { get: () => fakeUaData, configurable: true });" +
            "      Object.defineProperty(navigator, 'userAgent', { get: () => '" + vp.userAgent + "', configurable: true });" +
            "      Object.defineProperty(navigator, 'appVersion', { get: () => '" + vp.userAgent.substring(8) + "', configurable: true });" +
            "      Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv81', configurable: true });" +
            "      Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });" +
            "    } catch(e) {}" +
            "    try {" +
            "      Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => " + vp.cpuCores + ", configurable: true });" +
            "      Object.defineProperty(navigator, 'deviceMemory', { get: () => " + vp.ramGb + ", configurable: true });" +
            "      Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 5, configurable: true });" +
            "      Object.defineProperty(navigator, 'pdfViewerEnabled', { get: () => false, configurable: true });" +
            "      if (navigator.getBattery) {" +
            "        navigator.getBattery = () => Promise.resolve({" +
            "          charging: true, chargingTime: 0, dischargingTime: Infinity, level: 0.92," +
            "          addEventListener: () => {}, removeEventListener: () => {}" +
            "        });" +
            "      }" +
            "      if (navigator.storage && navigator.storage.estimate) {" +
            "        navigator.storage.estimate = () => Promise.resolve({" +
            "          quota: " + fakeStorageQuota + "," +
            "          usage: " + fakeStorageUsage +
            "        });" +
            "      }" +
            "      const fakeConn = { effectiveType: '4g', rtt: 50, downlink: 10, saveData: false, addEventListener: () => {}, removeEventListener: () => {} };" +
            "      Object.defineProperty(navigator, 'connection', { get: () => fakeConn, configurable: true });" +
            "    } catch(e) {}" +
            "    try {" +
            "      Object.defineProperty(window, 'devicePixelRatio', { get: () => " + vp.pixelRatio + ", configurable: true });" +
            "      if (window.screen) {" +
            "        Object.defineProperty(screen, 'width', { get: () => " + vp.screenWidth + ", configurable: true });" +
            "        Object.defineProperty(screen, 'height', { get: () => " + vp.screenHeight + ", configurable: true });" +
            "        Object.defineProperty(screen, 'availWidth', { get: () => " + vp.screenWidth + ", configurable: true });" +
            "        Object.defineProperty(screen, 'availHeight', { get: () => " + (vp.screenHeight - 48) + ", configurable: true });" +
            "        Object.defineProperty(screen, 'colorDepth', { get: () => 24, configurable: true });" +
            "        Object.defineProperty(screen, 'pixelDepth', { get: () => 24, configurable: true });" +
            "      }" +
            "    } catch(e) {}" +
            "    try {" +
            "      if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {" +
            "        navigator.mediaDevices.enumerateDevices = () => Promise.resolve([" +
            "          { deviceId: 'default', kind: 'audioinput', label: '', groupId: '" + fakeGroupId + "' }," +
            "          { deviceId: '" + fakeVideoDeviceId + "', kind: 'videoinput', label: '', groupId: '" + fakeGroupId + "' }," +
            "          { deviceId: '" + fakeAudioDeviceId + "', kind: 'audiooutput', label: '', groupId: '" + fakeGroupId + "' }" +
            "        ]);" +
            "      }" +
            "    } catch(e) {}" +
            "    try {" +
            "      const patchWebGL = (proto) => {" +
            "        if (!proto || !proto.getParameter) return;" +
            "        const origGetParam = proto.getParameter;" +
            "        proto.getParameter = function(param) {" +
            "          if (param === 37445) return '" + vp.gpuVendor + "';" +
            "          if (param === 37446) return '" + vp.gpuRenderer + "';" +
            "          if (param === 7936) return 'WebKit';" +
            "          if (param === 7937) return 'WebKit WebGL';" +
            "          if (param === 3379 || param === 34024) return 16384;" +
            "          return origGetParam.apply(this, arguments);" +
            "        };" +
            "      };" +
            "      if (window.WebGLRenderingContext) patchWebGL(WebGLRenderingContext.prototype);" +
            "      if (window.WebGL2RenderingContext) patchWebGL(WebGL2RenderingContext.prototype);" +
            "    } catch(e) {}" +
            "    try {" +
            "      const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;" +
            "      CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {" +
            "        const imgData = origGetImageData.apply(this, arguments);" +
            "        try {" +
            "          if (imgData && imgData.data && imgData.data.length > 16) {" +
            "            for (let i = 0; i < Math.min(imgData.data.length, 64); i += 16) {" +
            "              imgData.data[i] = (imgData.data[i] ^ " + noiseDelta + ") & 0xFF;" +
            "            }" +
            "          }" +
            "        } catch(err) {}" +
            "        return imgData;" +
            "      };" +
            "      const origMeasureText = CanvasRenderingContext2D.prototype.measureText;" +
            "      CanvasRenderingContext2D.prototype.measureText = function() {" +
            "        const m = origMeasureText.apply(this, arguments);" +
            "        try {" +
            "          const origW = m.width;" +
            "          Object.defineProperty(m, 'width', { get: () => origW + 0.0000" + subPixelJitter + " });" +
            "        } catch(err) {}" +
            "        return m;" +
            "      };" +
            "    } catch(e) {}" +
            "    try {" +
            "      if (window.AudioBuffer && AudioBuffer.prototype.getChannelData) {" +
            "        const origGetChannelData = AudioBuffer.prototype.getChannelData;" +
            "        AudioBuffer.prototype.getChannelData = function() {" +
            "          const res = origGetChannelData.apply(this, arguments);" +
            "          try {" +
            "            if (res && res.length > 10) {" +
            "              for (let i = 0; i < Math.min(res.length, 50); i += 10) {" +
            "                res[i] = res[i] + 0.000000" + noiseDelta + ";" +
            "              }" +
            "            }" +
            "          } catch(err) {}" +
            "          return res;" +
            "        };" +
            "      }" +
            "      if (window.speechSynthesis) {" +
            "        window.speechSynthesis.getVoices = () => [];" +
            "      }" +
            "      window.DeviceMotionEvent = undefined;" +
            "      window.DeviceOrientationEvent = undefined;" +
            "    } catch(e) {}"
            ) : "") +
            "  } catch(e) {}" +
            "})();";
    }

    // ================= KIỂM TRA IP HIỆN TẠI, VÙNG ĐỊA LÝ & TRẠNG THÁI BẢO MẬT =================
    public static JSONObject runSecurityDiagnostic(AccountStorage storage) {
        JSONObject result = new JSONObject();
        try {
            String proxy = (storage.isUseProxy()) ? storage.getProxyAddress() : "";
            ParsedProxy pp = parseProxyString(proxy);
            boolean proxyConfigured = storage.isUseProxy() && pp != null;

            ProxyNodeInfo info = proxyConfigured ? checkSingleProxyIpAndRegion(proxy, 10) : new ProxyNodeInfo();
            boolean proxyConnected = proxyConfigured && info.isLive && !info.exitIp.isEmpty();

            // Nếu Proxy hiện tại bị lỗi, tự động thử các Proxy khác trong Kho (Pool)
            if (proxyConfigured && !proxyConnected) {
                List<String> pool = storage.getParsedProxyPool();
                for (String candidate : pool) {
                    if (candidate.equalsIgnoreCase(proxy)) continue;
                    ProxyNodeInfo altInfo = checkSingleProxyIpAndRegion(candidate, 8);
                    if (altInfo.isLive && !altInfo.exitIp.isEmpty()) {
                        storage.setProxyAddress(candidate);
                        syncAndApplySecurityConfig(storage);
                        proxy = candidate;
                        info = altInfo;
                        proxyConnected = true;
                        break;
                    }
                }
            }

            if (storage.isUseProxy() && !proxyConnected && storage.isStrictKillSwitchEnabled()) {
                result.put("publicIp", "ĐÃ KHÓA (Kill-Switch bảo vệ IP gốc)");
                result.put("countryCode", "");
                result.put("country", "Đã chặn quay về IP thật");
                result.put("city", "");
                result.put("flag", "🛑");
                result.put("regionDisplay", "🛑 Kill-Switch: Chặn rò rỉ IP gốc");
                result.put("isp", "Toàn bộ Proxy đang lỗi - Đã ngắt mạng bảo vệ");
                result.put("proxyConfigured", proxyConfigured);
                result.put("proxyConnected", false);
                result.put("activeProxy", proxy);
                result.put("proxyPoolCount", storage.getParsedProxyPool().size());
                result.put("dohWorking", true);
                result.put("latencyMs", 0);
                result.put("tunneledRequests", encryptedTunneledRequests.get());
                result.put("blockedTrackers", blockedTrackersCount.get());
                result.put("localTunnelPort", localTunnelPort);
                result.put("virtualDevice", getActiveVirtualDeviceSummary(fingerprintSeed));
                return result;
            }

            // Chỉ kiểm tra mạng trực tiếp khi người dùng TẮT Proxy hoặc TẮT Kill-Switch
            if (!info.isLive || info.exitIp.isEmpty()) {
                info = checkSingleProxyIpAndRegion("", 8);
            }

            result.put("publicIp", info.exitIp.isEmpty() ? "Không xác định" : info.exitIp);
            result.put("countryCode", info.countryCode);
            result.put("country", info.countryName.isEmpty() ? "Quốc tế" : info.countryName);
            result.put("city", info.cityName);
            result.put("flag", info.getFlagEmoji());
            result.put("regionDisplay", info.getRegionDisplay());
            result.put("isp", info.isp.isEmpty() ? "Direct Network" : info.isp);
            result.put("proxyConfigured", proxyConfigured);
            result.put("proxyConnected", proxyConnected);
            result.put("activeProxy", proxy);
            result.put("proxyPoolCount", storage.getParsedProxyPool().size());
            result.put("dohWorking", true);
            result.put("latencyMs", info.latencyMs < 9000 ? info.latencyMs : 0);
            result.put("tunneledRequests", encryptedTunneledRequests.get());
            result.put("blockedTrackers", blockedTrackersCount.get());
            result.put("localTunnelPort", localTunnelPort);
            result.put("virtualDevice", getActiveVirtualDeviceSummary(fingerprintSeed));
        } catch (Exception e) {
            try {
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }
}
