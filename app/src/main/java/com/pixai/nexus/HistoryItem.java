package com.pixai.nexus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HistoryItem {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private String id;
    private String taskId;
    private String status; // PENDING, COMPLETED, FAILED
    private long timestamp;
    private String imageUrl;
    private List<String> imageUrls = new ArrayList<>();
    private String originalPrompt;
    private String serverPrompt;
    private String modelName;
    private String profile;
    private boolean isReal;
    private long seed;
    private int width;
    private int height;
    private String errorMessage;

    public HistoryItem(String taskId, String originalPrompt, String modelName, String profile, boolean isReal, int width, int height) {
        this.id = (taskId != null && !taskId.isEmpty()) ? taskId : (System.currentTimeMillis() + "_" + (int)(Math.random() * 1000));
        this.taskId = (taskId != null) ? taskId : this.id;
        this.status = STATUS_PENDING;
        this.timestamp = System.currentTimeMillis();
        this.imageUrl = "";
        this.imageUrls = new ArrayList<>();
        this.originalPrompt = originalPrompt;
        this.serverPrompt = "Đang kết xuất trên GPU PixAI...";
        this.modelName = modelName;
        this.profile = profile;
        this.isReal = isReal;
        this.seed = 0;
        this.width = width;
        this.height = height;
        this.errorMessage = "";
    }

    public HistoryItem(String imageUrl, String originalPrompt, String serverPrompt, String modelName, String profile, boolean isReal, long seed, int width, int height) {
        this.id = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 1000);
        this.taskId = this.id;
        this.status = STATUS_COMPLETED;
        this.timestamp = System.currentTimeMillis();
        this.imageUrl = imageUrl;
        if (imageUrl != null && !imageUrl.isEmpty()) {
            this.imageUrls.add(imageUrl);
        }
        this.originalPrompt = originalPrompt;
        this.serverPrompt = (serverPrompt != null && !serverPrompt.trim().isEmpty()) ? serverPrompt : originalPrompt;
        this.modelName = modelName;
        this.profile = profile;
        this.isReal = isReal;
        this.seed = seed;
        this.width = width;
        this.height = height;
        this.errorMessage = "";
    }

    public HistoryItem(JSONObject obj) {
        this.id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
        this.taskId = obj.optString("taskId", this.id);
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
        this.errorMessage = obj.optString("errorMessage", "");

        String rawStatus = obj.optString("status", "");
        if (rawStatus.isEmpty()) {
            this.status = (imageUrl != null && !imageUrl.isEmpty()) ? STATUS_COMPLETED : STATUS_PENDING;
        } else {
            this.status = rawStatus;
        }

        JSONArray arr = obj.optJSONArray("imageUrls");
        this.imageUrls = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (!u.isEmpty()) this.imageUrls.add(u);
            }
        }
        if (this.imageUrls.isEmpty() && this.imageUrl != null && !this.imageUrl.isEmpty()) {
            this.imageUrls.add(this.imageUrl);
        }
    }

    public JSONObject toJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("taskId", taskId);
            obj.put("status", status);
            obj.put("timestamp", timestamp);
            obj.put("imageUrl", imageUrl);
            JSONArray arr = new JSONArray();
            for (String u : imageUrls) arr.put(u);
            obj.put("imageUrls", arr);
            obj.put("originalPrompt", originalPrompt);
            obj.put("serverPrompt", serverPrompt);
            obj.put("modelName", modelName);
            obj.put("profile", profile);
            obj.put("isReal", isReal);
            obj.put("seed", seed);
            obj.put("width", width);
            obj.put("height", height);
            obj.put("errorMessage", errorMessage);
        } catch (JSONException ignored) {}
        return obj;
    }

    public boolean isPending() { return STATUS_PENDING.equalsIgnoreCase(status); }
    public boolean isCompleted() { return STATUS_COMPLETED.equalsIgnoreCase(status); }
    public boolean isFailed() { return STATUS_FAILED.equalsIgnoreCase(status); }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTimestamp() { return timestamp; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        if (imageUrl != null && !imageUrl.isEmpty() && !this.imageUrls.contains(imageUrl)) {
            this.imageUrls.add(imageUrl);
        }
    }

    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> urls) {
        this.imageUrls = (urls != null) ? new ArrayList<>(urls) : new ArrayList<>();
        if (!this.imageUrls.isEmpty() && (this.imageUrl == null || this.imageUrl.isEmpty())) {
            this.imageUrl = this.imageUrls.get(0);
        }
    }

    public String getOriginalPrompt() { return originalPrompt; }
    public String getServerPrompt() { return serverPrompt; }
    public void setServerPrompt(String serverPrompt) { this.serverPrompt = serverPrompt; }

    public String getModelName() { return modelName; }
    public String getProfile() { return profile; }
    public boolean isReal() { return isReal; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
