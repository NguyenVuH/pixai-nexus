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
    private static final String KEY_SAVED_RATIO_IDX = "saved_ratio_index";
    private static final String KEY_SAVED_QUALITY = "saved_quality";

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

    public synchronized void clearHistory() {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        if (file.exists()) file.delete();
        // Xóa luôn cache ảnh mã hóa
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

    public int getSavedRatioIndex() {
        return prefs.getInt(KEY_SAVED_RATIO_IDX, 0);
    }

    public void setSavedRatioIndex(int idx) {
        prefs.edit().putInt(KEY_SAVED_RATIO_IDX, idx).apply();
    }

    public String getSavedQuality() {
        return prefs.getString(KEY_SAVED_QUALITY, "standard");
    }

    public void setSavedQuality(String q) {
        prefs.edit().putString(KEY_SAVED_QUALITY, q).apply();
    }

    public boolean isStrictKillSwitchEnabled() {
        return false;
    }

    public boolean isProxyWebUiEnabled() {
        return false;
    }

    public boolean isAutoRotateProxyEnabled() {
        return false;
    }

    public boolean isDohEnabled() {
        return true;
    }

    public boolean isAntiDpiEnabled() {
        return false;
    }

    public boolean isBlockWebRtcEnabled() {
        return true;
    }

    public boolean isSpoofFingerprintEnabled() {
        return true;
    }

    public boolean isBlockTrackersEnabled() {
        return true;
    }

    public int getFingerprintSeed() {
        return 12345;
    }

    public List<String> getParsedProxyPool() {
        List<String> list = new ArrayList<>();
        String p = getProxyAddress();
        if (!p.isEmpty()) list.add(p);
        return list;
    }
}
