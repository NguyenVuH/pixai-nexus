package com.pixai.nexus;

import org.json.JSONException;
import org.json.JSONObject;

public class AccountModel {
    private String id;
    private String email;
    private String password;
    private String token;
    private long credits;
    private long createdAt;
    private String proxy;

    public AccountModel(String email, String token, long credits) {
        this(email, "", token, credits);
    }

    public AccountModel(String email, String password, String token, long credits) {
        this.id = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 1000);
        this.email = email;
        this.password = password;
        this.token = token;
        this.credits = credits;
        this.createdAt = System.currentTimeMillis();
        this.proxy = "";
    }

    public AccountModel(JSONObject obj) {
        this.id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
        this.email = obj.optString("email", "");
        this.password = obj.optString("password", "");
        this.token = obj.optString("token", "");
        this.credits = obj.optLong("credits", 0);
        this.createdAt = obj.optLong("createdAt", System.currentTimeMillis());
        this.proxy = obj.optString("proxy", "");
    }

    public JSONObject toJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("email", email);
            obj.put("password", password);
            obj.put("token", token);
            obj.put("credits", credits);
            obj.put("createdAt", createdAt);
            obj.put("proxy", proxy);
        } catch (JSONException ignored) {}
        return obj;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getCredits() { return credits; }
    public void setCredits(long credits) { this.credits = credits; }
    public long getCreatedAt() { return createdAt; }
    public String getProxy() { return proxy; }
    public void setProxy(String proxy) { this.proxy = proxy; }
}
