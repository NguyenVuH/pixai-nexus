package com.pixai.nexus;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Locale;

public class AccountStorage {
    private static final String PREF_NAME = "pixai_nexus_prefs";
    private static final String KEY_ACTIVE_EMAIL = "active_email";
    private static final String KEY_USE_PROXY = "use_proxy";
    private static final String KEY_PROXY_ADDR = "proxy_address";
    private static final String KEY_AUTO_ROTATE = "auto_rotate_accounts";
    private static final String KEY_KILL_SWITCH = "kill_switch_enabled";

    private static final String ACCOUNTS_FILE = "accounts_nexus.json";
    private static final String HISTORY_FILE = "nexus_history.enc";

    private final Context context;
    private final SharedPreferences prefs;

    public AccountStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        initDefaultAccountsIfNeeded();
    }

    private void initDefaultAccountsIfNeeded() {
        File file = new File(context.getFilesDir(), ACCOUNTS_FILE);
        if (!file.exists() || file.length() < 10) {
            List<AccountModel> list = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("accounts.json"), StandardCharsets.UTF_8))) {
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
            saveAccounts(list);
        }
    }

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

        AccountModel active = getActiveAccount();
        if (active != null && active.getCredits() >= requiredCredits && active.getToken() != null && !active.getToken().isEmpty()) {
            return active;
        }

        // Find next eligible
        for (AccountModel a : list) {
            if (a.getCredits() >= requiredCredits && a.getToken() != null && !a.getToken().isEmpty()) {
                return a;
            }
        }
        return null;
    }

    private static final byte[] CIPHER_KEY = "PixAi_Nexus_Security_Master_Key_2026_Secure".getBytes(StandardCharsets.UTF_8);

    private static byte[] cipherBytes(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ CIPHER_KEY[i % CIPHER_KEY.length]);
        }
        return out;
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

    public synchronized void addHistoryItem(HistoryItem item) {
        List<HistoryItem> list = getHistory();
        list.add(0, item);
        if (list.size() > 200) {
            list = list.subList(0, 200);
        }
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            JSONArray arr = new JSONArray();
            for (HistoryItem h : list) {
                arr.put(h.toJsonObject());
            }
            byte[] raw = arr.toString().getBytes(StandardCharsets.UTF_8);
            fos.write(cipherBytes(raw));
        } catch (Exception ignored) {}
    }

    public synchronized void clearHistory() {
        File file = new File(context.getFilesDir(), HISTORY_FILE);
        if (file.exists()) file.delete();
    }

    // --- PROXY & PREFERENCES ---
    public boolean isUseProxy() {
        return prefs.getBoolean(KEY_USE_PROXY, true);
    }

    public void setUseProxy(boolean use) {
        prefs.edit().putBoolean(KEY_USE_PROXY, use).apply();
    }

    public String getProxyAddress() {
        return prefs.getString(KEY_PROXY_ADDR, "http://p.webshare.io:80");
    }

    public void setProxyAddress(String addr) {
        prefs.edit().putString(KEY_PROXY_ADDR, addr).apply();
    }

    public boolean isAutoRotateAccounts() {
        return prefs.getBoolean(KEY_AUTO_ROTATE, true);
    }

    public void setAutoRotateAccounts(boolean rotate) {
        prefs.edit().putBoolean(KEY_AUTO_ROTATE, rotate).apply();
    }

    public boolean isKillSwitchEnabled() {
        return prefs.getBoolean(KEY_KILL_SWITCH, true);
    }

    public void setKillSwitchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH, enabled).apply();
    }

    public boolean isProxyWebUiEnabled() {
        return prefs.getBoolean("proxy_web_ui", true);
    }

    public boolean isAutoRotateProxyEnabled() {
        return prefs.getBoolean("auto_rotate_proxy", true);
    }

    public List<String> getParsedProxyPool() {
        List<String> list = new ArrayList<>();
        list.add(getProxyAddress());
        return list;
    }

    public boolean isDohEnabled() {
        return prefs.getBoolean("doh_enabled", true);
    }

    public boolean isAntiDpiEnabled() {
        return prefs.getBoolean("anti_dpi_enabled", false);
    }

    public boolean isStrictKillSwitchEnabled() {
        return isKillSwitchEnabled();
    }

    public boolean isBlockWebRtcEnabled() {
        return prefs.getBoolean("block_webrtc", true);
    }

    public boolean isSpoofFingerprintEnabled() {
        return prefs.getBoolean("spoof_fingerprint", true);
    }

    public boolean isBlockTrackersEnabled() {
        return prefs.getBoolean("block_trackers", true);
    }

    public int getFingerprintSeed() {
        return prefs.getInt("fingerprint_seed", 12345);
    }
}
