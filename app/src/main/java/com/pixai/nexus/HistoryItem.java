package com.pixai.nexus;

import org.json.JSONException;
import org.json.JSONObject;

public class HistoryItem {
    private String id;
    private long timestamp;
    private String imageUrl;
    private String originalPrompt;
    private String serverPrompt;
    private String modelName;
    private String profile;
    private boolean isReal;
    private long seed;
    private int width;
    private int height;

    public HistoryItem(String imageUrl, String originalPrompt, String serverPrompt, String modelName, String profile, boolean isReal, long seed, int width, int height) {
        this.id = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 1000);
        this.timestamp = System.currentTimeMillis();
        this.imageUrl = imageUrl;
        this.originalPrompt = originalPrompt;
        this.serverPrompt = (serverPrompt != null && !serverPrompt.trim().isEmpty()) ? serverPrompt : originalPrompt;
        this.modelName = modelName;
        this.profile = profile;
        this.isReal = isReal;
        this.seed = seed;
        this.width = width;
        this.height = height;
    }

    public HistoryItem(JSONObject obj) {
        this.id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
        this.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
        this.imageUrl = obj.optString("imageUrl", "");
        this.originalPrompt = obj.optString("originalPrompt", "");
        this.serverPrompt = obj.optString("serverPrompt", "");
        this.modelName = obj.optString("modelName", "Tsubaki 3");
        this.profile = obj.optString("profile", "flash");
        this.isReal = obj.optBoolean("isReal", false);
        this.seed = obj.optLong("seed", 0);
        this.width = obj.optInt("width", 1104);
        this.height = obj.optInt("height", 1824);
    }

    public JSONObject toJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("timestamp", timestamp);
            obj.put("imageUrl", imageUrl);
            obj.put("originalPrompt", originalPrompt);
            obj.put("serverPrompt", serverPrompt);
            obj.put("modelName", modelName);
            obj.put("profile", profile);
            obj.put("isReal", isReal);
            obj.put("seed", seed);
            obj.put("width", width);
            obj.put("height", height);
        } catch (JSONException ignored) {}
        return obj;
    }

    public String getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public String getImageUrl() { return imageUrl; }
    public String getOriginalPrompt() { return originalPrompt; }
    public String getServerPrompt() { return serverPrompt; }
    public String getModelName() { return modelName; }
    public String getProfile() { return profile; }
    public boolean isReal() { return isReal; }
    public long getSeed() { return seed; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
