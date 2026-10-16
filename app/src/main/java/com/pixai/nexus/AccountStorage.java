package com.pixai.nexus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class AccountStorage {
    private static final String PREF_NAME = "pixai_nexus_prefs";

    private static final String KEY_ACTIVE_EMAIL = "active_email";
    private static final String KEY_USE_PROXY = "use_proxy";
    private static final String KEY_PROXY_ADDR = "proxy_address";
    private static final String KEY_FALLBACK_DIRECT = "fallback_direct";
    private static final String KEY_AUTO_ROTATE = "auto_rotate_accounts";

    private static final String KEY_SAVED_PROMPT = "saved_prompt";
    private static final String KEY_SAVED_NEGATIVE = "saved_negative";
    private static final String KEY_SAVED_PROFILE = "saved_profile";
    private static final String KEY_SAVED_REAL = "saved_real";
    private static final String KEY_SAVED_BATCH = "saved_batch";
    private static final String KEY_SAVED_RATIO_NAME = "saved_ratio_name";
    private static final String KEY_SAVED_SIZE_TIER = "saved_size_tier";
    private static final String KEY_SAVED_CUSTOM_W = "saved_custom_w";
    private static final String KEY_SAVED_CUSTOM_H = "saved_custom_h";
    private static final String KEY_HISTORY_PIN_HASH = "history_pin_hash";

    private static final String ACCOUNTS_FILE = "accounts_nexus.json";
    private static final String HISTORY_FILE = "nexus_history.enc";

    private static final byte[] CIPHER_KEY = "PixAi_Nexus_Security_Master_Key_2026_Secure".getBytes(StandardCharsets.UTF_8);

    private final Context context;
    private final SharedPreferences prefs;

    public AccountStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static byte[] cipherBytes(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ CIPHER_KEY[i % CIPHER_KEY.length]);
        }
        return out;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    // --- HISTORY PASSWORD / PIN LOCK ---
    public boolean isHistoryPasswordEnabled() {
        return prefs.contains(KEY_HISTORY_PIN_HASH) && !prefs.getString(KEY_HISTORY_PIN_HASH, "").isEmpty();
    }

    public void setHistoryPassword(String pin) {
        if (pin == null || pin.trim().isEmpty()) {
            prefs.edit().remove(KEY_HISTORY_PIN_HASH).apply();
        } else {
            prefs.edit().putString(KEY_HISTORY_PIN_HASH, sha256(pin.trim())).apply();
        }
    }

    public boolean checkHistoryPassword(String pin) {
        if (!isHistoryPasswordEnabled()) return true;
        if (pin == null) return false;
        String hash = prefs.getString(KEY_HISTORY_PIN_HASH, "");
        return hash.equals(sha256(pin.trim()));
    }

    public void removeHistoryPassword() {
        prefs.edit().remove(KEY_HISTORY_PIN_HASH).apply();
    }

    // --- ACCOUNTS MANAGEMENT ---
    public synchronized List<AccountModel> getAccounts() {
        List<AccountModel> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), ACCOUNTS_FILE);
        if (!file.exists()) return list;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString().trim());
            for (int i = 0; i < arr.length(); i++) {
                list.add(new AccountModel(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized void saveAccounts(List<AccountModel> accounts) {
        File file = new File(context.getFilesDir(), ACCOUNTS_FILE);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            JSONArray arr = new JSONArray();
            for (AccountModel a : accounts) {
                arr.put(a.toJsonObject());
            }
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public synchronized void addAccount(AccountModel acc) {
        List<AccountModel> list = getAccounts();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getEmail().equalsIgnoreCase(acc.getEmail())) {
                list.set(i, acc);
                saveAccounts(list);
                return;
            }
        }
        list.add(0, acc);
        saveAccounts(list);
    }

    public synchronized void addAccountsBatch(List<AccountModel> newAccounts) {
        List<AccountModel> list = getAccounts();
        for (AccountModel acc : newAccounts) {
            boolean found = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getEmail().equalsIgnoreCase(acc.getEmail())) {
                    list.set(i, acc);
                    found = true;
                    break;
                }
            }
            if (!found) {
                list.add(acc);
            }
        }
        saveAccounts(list);
    }

    public synchronized void deleteAccount(String email) {
        List<AccountModel> list = getAccounts();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getEmail().equalsIgnoreCase(email)) {
                list.remove(i);
                break;
            }
        }
        saveAccounts(list);
    }

    public synchronized void clearAllAccounts() {
        saveAccounts(new ArrayList<>());
    }

    public synchronized void updateAccountCredits(String email, long credits) {
        List<AccountModel> list = getAccounts();
        for (AccountModel a : list) {
            if (a.getEmail().equalsIgnoreCase(email)) {
                a.setCredits(credits);
                saveAccounts(list);
                return;
            }
        }
    }

    public synchronized long getTotalCredits() {
        long sum = 0;
        for (AccountModel a : getAccounts()) {
            sum += a.getCredits();
        }
        return sum;
    }

    public synchronized AccountModel getActiveAccount() {
        List<AccountModel> list = getAccounts();
        if (list.isEmpty()) return null;
        String email = prefs.getString(KEY_ACTIVE_EMAIL, "");
        for (AccountModel a : list) {
            if (a.getEmail().equalsIgnoreCase(email)) return a;
        }
        return list.get(0);
    }

    public void setActiveEmail(String email) {
        prefs.edit().putString(KEY_ACTIVE_EMAIL, email).apply();
    }

    public synchronized AccountModel findEligibleAccount(long requiredCredits) {
        List<AccountModel> list = getAccounts();
        if (list.isEmpty()) return null;

        // Ưu tiên tài khoản đang chọn
        AccountModel active = getActiveAccount();
        if (active != null && active.getCredits() >= requiredCredits && active.getToken() != null && !active.getToken().isEmpty()) {
            return active;
        }

        // Tự động tìm tài khoản tiếp theo có đủ số dư
        for (AccountModel a : list) {
            if (a.getCredits() >= requiredCredits && a.getToken() != null && !a.getToken().isEmpty()) {
                return a;
            }
        }
        return null;
    }

    // --- IMPORT / EXPORT DATA ACCOUNTS ---
    public synchronized String exportAccountsToJsonString() {
        List<AccountModel> list = getAccounts();
        JSONArray arr = new JSONArray();
        for (AccountModel a : list) {
            arr.put(a.toJsonObject());
        }
        try {
            return arr.toString(2);
        } catch (Exception e) {
            return arr.toString();
        }
    }

    public synchronized int importAccountsFromJsonString(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr.trim());
            List<AccountModel> imported = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                imported.add(new AccountModel(arr.getJSONObject(i)));
            }
            addAccountsBatch(imported);
            return imported.size();
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized int importAccountsFromTextLines(String text) {
        int count = 0;
        String[] lines = text.split("\n");
        List<AccountModel> imported = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            String email = "";
            String token = "";
            String password = "";

            if (line.startsWith("Bearer ")) {
                token = line.substring(7).trim();
            } else if (line.contains(":") && !line.startsWith("http")) {
                String[] parts = line.split(":");
                if (parts.length >= 3) {
                    email = parts[0].trim();
                    password = parts[1].trim();
                    token = parts[2].trim();
                } else if (parts.length == 2) {
                    email = parts[0].trim();
                    token = parts[1].trim();
                }
            } else {
                token = line;
            }

            if (token.length() >= 20) {
                if (email.isEmpty()) {
                    email = "pixai_acc_" + (System.currentTimeMillis() % 1000000) + "_" + (int)(Math.random() * 1000) + "@nexus.com";
                }
                imported.add(new AccountModel(email, password, token, 0));
                count++;
            }
        }
        if (!imported.isEmpty()) {
            addAccountsBatch(imported);
        }
        return count;
    }

    // --- HISTORY MANAGEMENT ---
    public synchronized List<HistoryItem> getHistory() {
        List<HistoryItem> list = new ArrayList<>();
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        if (!file.exists()) return list;

        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] encData = new byte[(int) file.length()];
            fis.read(encData);
            fis.close();
            byte[] rawData = cipherBytes(encData);
            String jsonStr = new String(rawData, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(jsonStr.trim());
            for (int i = 0; i < arr.length(); i++) {
                list.add(new HistoryItem(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public synchronized void saveHistory(List<HistoryItem> history) {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        try {
            JSONArray arr = new JSONArray();
            for (HistoryItem h : history) {
                arr.put(h.toJsonObject());
            }
            byte[] raw = arr.toString().getBytes(StandardCharsets.UTF_8);
            byte[] enc = cipherBytes(raw);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(enc);
            fos.close();
        } catch (Exception ignored) {}
    }

    public synchronized void addHistoryItem(HistoryItem item) {
        List<HistoryItem> list = getHistory();
        list.add(0, item);
        saveHistory(list);
    }

    public synchronized void updateHistoryItem(HistoryItem item) {
        if (item == null) return;
        List<HistoryItem> list = getHistory();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            HistoryItem cur = list.get(i);
            if ((cur.getId() != null && cur.getId().equals(item.getId())) ||
                (cur.getTaskId() != null && cur.getTaskId().equals(item.getTaskId()))) {
                list.set(i, item);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(0, item);
        }
        saveHistory(list);
    }

    public synchronized boolean deleteHistoryItem(String id) {
        if (id == null) return false;
        List<HistoryItem> list = getHistory();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            HistoryItem cur = list.get(i);
            if (id.equals(cur.getId()) || id.equals(cur.getTaskId())) {
                list.remove(i);
                found = true;
                break;
            }
        }
        if (found) {
            saveHistory(list);
        }
        return found;
    }

    public synchronized List<HistoryItem> getPendingHistoryItems() {
        List<HistoryItem> pending = new ArrayList<>();
        List<HistoryItem> all = getHistory();
        for (HistoryItem item : all) {
            if (item.isPending()) {
                pending.add(item);
            }
        }
        return pending;
    }

    public synchronized void clearHistory() {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        if (file.exists()) file.delete();
        File cacheDir = new File(context.getFilesDir(), "nexus_cache");
        if (cacheDir.exists() && cacheDir.isDirectory()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
    }

    // --- ENCRYPTED IMAGE CACHE ---
    public void saveEncryptedImageCache(String keyId, byte[] imageBytes) {
        try {
            File cacheDir = new File(context.getFilesDir(), "nexus_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File f = new File(cacheDir, keyId + ".enc");
            byte[] enc = cipherBytes(imageBytes);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(enc);
            fos.close();
        } catch (Exception ignored) {}
    }

    public Bitmap getDecryptedImageCache(String keyId) {
        try {
            File cacheDir = new File(context.getFilesDir(), "nexus_cache");
            File f = new File(cacheDir, keyId + ".enc");
            if (!f.exists()) return null;
            FileInputStream fis = new FileInputStream(f);
            byte[] enc = new byte[(int) f.length()];
            fis.read(enc);
            fis.close();
            byte[] raw = cipherBytes(enc);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    // --- PREFERENCES / SETTINGS PERSISTENCE ---
    public boolean isUseProxy() {
        return prefs.getBoolean(KEY_USE_PROXY, false);
    }

    public void setUseProxy(boolean use) {
        prefs.edit().putBoolean(KEY_USE_PROXY, use).apply();
    }

    public String getProxyAddress() {
        return prefs.getString(KEY_PROXY_ADDR, "");
    }

    public void setProxyAddress(String addr) {
        prefs.edit().putString(KEY_PROXY_ADDR, addr).apply();
    }

    public boolean isFallbackDirect() {
        return prefs.getBoolean(KEY_FALLBACK_DIRECT, true);
    }

    public void setFallbackDirect(boolean fb) {
        prefs.edit().putBoolean(KEY_FALLBACK_DIRECT, fb).apply();
    }

    public boolean isAutoRotateAccounts() {
        return prefs.getBoolean(KEY_AUTO_ROTATE, true);
    }

    public void setAutoRotateAccounts(boolean rotate) {
        prefs.edit().putBoolean(KEY_AUTO_ROTATE, rotate).apply();
    }

    public String getSavedPrompt() {
        return prefs.getString(KEY_SAVED_PROMPT, "1girl, solo, masterpiece, beautiful anime aesthetic, detailed eyes");
    }

    public void setSavedPrompt(String p) {
        prefs.edit().putString(KEY_SAVED_PROMPT, p).apply();
    }

    public String getSavedNegativePrompt() {
        return prefs.getString(KEY_SAVED_NEGATIVE, PixAiApiHelper.DEFAULT_NEGATIVE);
    }

    public void setSavedNegativePrompt(String neg) {
        prefs.edit().putString(KEY_SAVED_NEGATIVE, neg).apply();
    }

    public String getSavedProfile() {
        return prefs.getString(KEY_SAVED_PROFILE, "flash");
    }

    public void setSavedProfile(String prof) {
        prefs.edit().putString(KEY_SAVED_PROFILE, prof).apply();
    }

    public boolean isSavedReal() {
        return prefs.getBoolean(KEY_SAVED_REAL, false);
    }

    public void setSavedReal(boolean real) {
        prefs.edit().putBoolean(KEY_SAVED_REAL, real).apply();
    }

    public int getSavedBatch() {
        return prefs.getInt(KEY_SAVED_BATCH, 1);
    }

    public void setSavedBatch(int b) {
        prefs.edit().putInt(KEY_SAVED_BATCH, b).apply();
    }

    public String getSavedRatioName() {
        return prefs.getString(KEY_SAVED_RATIO_NAME, "9:16");
    }

    public void setSavedRatioName(String name) {
        prefs.edit().putString(KEY_SAVED_RATIO_NAME, name).apply();
    }

    public String getSavedSizeTier() {
        return prefs.getString(KEY_SAVED_SIZE_TIER, "XL");
    }

    public void setSavedSizeTier(String tier) {
        prefs.edit().putString(KEY_SAVED_SIZE_TIER, tier).apply();
    }

    public int getSavedCustomWidth() {
        return prefs.getInt(KEY_SAVED_CUSTOM_W, 1104);
    }

    public void setSavedCustomWidth(int w) {
        prefs.edit().putInt(KEY_SAVED_CUSTOM_W, w).apply();
    }

    public int getSavedCustomHeight() {
        return prefs.getInt(KEY_SAVED_CUSTOM_H, 1824);
    }

    public void setSavedCustomHeight(int h) {
        prefs.edit().putInt(KEY_SAVED_CUSTOM_H, h).apply();
    }

    public synchronized void setSavedCustomDimensions(int w, int h) {
        prefs.edit().putInt(KEY_SAVED_CUSTOM_W, w).putInt(KEY_SAVED_CUSTOM_H, h).apply();
    }

    public synchronized void addAccount(String email, String token, long quota) {
        addAccount(new AccountModel(email, token, quota));
    }

    public boolean isProxyWebUiEnabled() { return prefs.getBoolean("security_proxy_web_ui", true); }
    public boolean isAutoRotateProxyEnabled() { return prefs.getBoolean("security_auto_rotate_proxy", false); }
    public List<String> getParsedProxyPool() {
        List<String> list = new ArrayList<>();
        String raw = getProxyAddress();
        if (raw != null && !raw.trim().isEmpty()) {
            list.add(raw.trim());
        }
        return list;
    }
    public boolean isDohEnabled() { return prefs.getBoolean("security_doh_enabled", true); }
    public boolean isAntiDpiEnabled() { return prefs.getBoolean("security_anti_dpi_enabled", true); }
    public boolean isStrictKillSwitchEnabled() { return prefs.getBoolean("security_kill_switch_enabled", false); }
    public boolean isBlockWebRtcEnabled() { return prefs.getBoolean("security_block_webrtc", true); }
    public boolean isSpoofFingerprintEnabled() { return prefs.getBoolean("security_spoof_fp", true); }
    public boolean isBlockTrackersEnabled() { return prefs.getBoolean("security_block_trackers", true); }
    public int getFingerprintSeed() { return (int) prefs.getLong("security_fp_seed", 0L); }
}
