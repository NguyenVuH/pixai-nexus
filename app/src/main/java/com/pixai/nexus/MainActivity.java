package com.pixai.nexus;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private AccountStorage storage;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Navigation & Layouts
    private Button btnNavStudio, btnNavAccounts, btnNavHistory;
    private ScrollView layoutTabStudio;
    private LinearLayout layoutTabAccounts, layoutTabHistory;
    private TextView tvHeaderAccount, tvHeaderProxy;

    // Tab 1: Studio Controls
    private Button btnStyleAnime, btnStyleReal;
    private TextView tvLoraStatus;
    private Button btnProfileFlash, btnProfilePro, btnProfileUltra;
    private Spinner spRatio;
    private Button btnBatch1, btnBatch4;
    private Button btnPickImage, btnClearImage;
    private TextView tvAttachedImageName;
    private EditText etPrompt, etNegativePrompt;
    private TextView tvCostBadge, tvGenStatus;
    private Button btnGenerate;

    // Result Card
    private LinearLayout layoutResultCard;
    private ImageView ivResultPreview;
    private TextView tvResOriginalPrompt, tvResServerPrompt;
    private Button btnCopyOriginalPrompt, btnCopyServerPrompt;

    // Tab 2: Accounts
    private Button btnBatchClaimDaily, btnBatchClaimFull, btnAutoReg, btnSyncAllCredits;
    private ListView lvAccounts;
    private AccountAdapter accountAdapter;

    // Tab 3: History
    private ListView lvHistory;
    private HistoryAdapter historyAdapter;

    // State Variables
    private boolean isReal = true;
    private String currentProfile = "flash"; // flash, pro, ultra
    private int batchCount = 1;
    private String attachedMediaId = null;
    private int selectedWidth = 1104;
    private int selectedHeight = 1824;

    private final String[] RATIO_LABELS = {
            "9:16 (Dọc chuẩn - 1104x1824)",
            "1:1 (Vuông chuẩn - 1024x1024)",
            "16:9 (Ngang chuẩn - 1824x1104)",
            "3:4 (Chân dung - 896x1152)",
            "Compact (Tiết kiệm - 608x1024)"
    };
    private final int[][] RATIO_DIMS = {
            {1104, 1824},
            {1024, 1024},
            {1824, 1104},
            {896, 1152},
            {608, 1024}
    };

    private ActivityResultLauncher<String> imagePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new AccountStorage(this);
        SecurityShieldManager.initialize(this);

        initViews();
        setupNavigation();
        setupRatioSpinner();
        setupStyleButtons();
        setupProfileButtons();
        setupBatchButtons();
        setupImagePicker();
        setupGenerateAction();
        setupAccountManagementActions();
        setupHistoryActions();

        refreshHeader();
        updateCostBadge();
    }

    private void initViews() {
        btnNavStudio = findViewById(R.id.btnNavStudio);
        btnNavAccounts = findViewById(R.id.btnNavAccounts);
        btnNavHistory = findViewById(R.id.btnNavHistory);

        layoutTabStudio = findViewById(R.id.layoutTabStudio);
        layoutTabAccounts = findViewById(R.id.layoutTabAccounts);
        layoutTabHistory = findViewById(R.id.layoutTabHistory);

        tvHeaderAccount = findViewById(R.id.tvHeaderAccount);
        tvHeaderProxy = findViewById(R.id.tvHeaderProxy);

        btnStyleAnime = findViewById(R.id.btnStyleAnime);
        btnStyleReal = findViewById(R.id.btnStyleReal);
        tvLoraStatus = findViewById(R.id.tvLoraStatus);

        btnProfileFlash = findViewById(R.id.btnProfileFlash);
        btnProfilePro = findViewById(R.id.btnProfilePro);
        btnProfileUltra = findViewById(R.id.btnProfileUltra);

        spRatio = findViewById(R.id.spRatio);
        btnBatch1 = findViewById(R.id.btnBatch1);
        btnBatch4 = findViewById(R.id.btnBatch4);

        btnPickImage = findViewById(R.id.btnPickImage);
        btnClearImage = findViewById(R.id.btnClearImage);
        tvAttachedImageName = findViewById(R.id.tvAttachedImageName);

        etPrompt = findViewById(R.id.etPrompt);
        etNegativePrompt = findViewById(R.id.etNegativePrompt);
        tvCostBadge = findViewById(R.id.tvCostBadge);
        tvGenStatus = findViewById(R.id.tvGenStatus);
        btnGenerate = findViewById(R.id.btnGenerate);

        layoutResultCard = findViewById(R.id.layoutResultCard);
        ivResultPreview = findViewById(R.id.ivResultPreview);
        tvResOriginalPrompt = findViewById(R.id.tvResOriginalPrompt);
        tvResServerPrompt = findViewById(R.id.tvResServerPrompt);
        btnCopyOriginalPrompt = findViewById(R.id.btnCopyOriginalPrompt);
        btnCopyServerPrompt = findViewById(R.id.btnCopyServerPrompt);

        btnBatchClaimDaily = findViewById(R.id.btnBatchClaimDaily);
        btnBatchClaimFull = findViewById(R.id.btnBatchClaimFull);
        btnAutoReg = findViewById(R.id.btnAutoReg);
        btnSyncAllCredits = findViewById(R.id.btnSyncAllCredits);
        lvAccounts = findViewById(R.id.lvAccounts);

        lvHistory = findViewById(R.id.lvHistory);
    }

    private void setupNavigation() {
        btnNavStudio.setOnClickListener(v -> switchTab(1));
        btnNavAccounts.setOnClickListener(v -> switchTab(2));
        btnNavHistory.setOnClickListener(v -> switchTab(3));

        tvHeaderProxy.setOnClickListener(v -> {
            boolean cur = storage.isUseProxy();
            storage.setUseProxy(!cur);
            tvHeaderProxy.setText(!cur ? "[PROXY SHIELD: ON]" : "[PROXY SHIELD: OFF]");
            tvHeaderProxy.setTextColor(!cur ? 0xFF818CF8 : 0xFF94A3B8);
            Toast.makeText(this, !cur ? "Proxy Shield: BẬT" : "Proxy Shield: TẮT", Toast.LENGTH_SHORT).show();
        });
    }

    private void switchTab(int tabIndex) {
        layoutTabStudio.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        layoutTabAccounts.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);
        layoutTabHistory.setVisibility(tabIndex == 3 ? View.VISIBLE : View.GONE);

        btnNavStudio.setBackgroundResource(tabIndex == 1 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavStudio.setTextColor(tabIndex == 1 ? 0xFF38BDF8 : 0xFF94A3B8);

        btnNavAccounts.setBackgroundResource(tabIndex == 2 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavAccounts.setTextColor(tabIndex == 2 ? 0xFF38BDF8 : 0xFF94A3B8);

        btnNavHistory.setBackgroundResource(tabIndex == 3 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavHistory.setTextColor(tabIndex == 3 ? 0xFF38BDF8 : 0xFF94A3B8);

        if (tabIndex == 2) refreshAccountsList();
        if (tabIndex == 3) refreshHistoryList();
    }

    private void setupRatioSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RATIO_LABELS);
        spRatio.setAdapter(adapter);
        spRatio.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedWidth = RATIO_DIMS[position][0];
                selectedHeight = RATIO_DIMS[position][1];
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void setupStyleButtons() {
        btnStyleAnime.setOnClickListener(v -> {
            isReal = false;
            btnStyleAnime.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnStyleAnime.setTextColor(0xFF38BDF8);
            btnStyleReal.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
            btnStyleReal.setTextColor(0xFF94A3B8);
            tvLoraStatus.setText("• Anime Nguyên Bản (Không chèn LoRA)");
            tvLoraStatus.setTextColor(0xFF94A3B8);
        });

        btnStyleReal.setOnClickListener(v -> {
            isReal = true;
            btnStyleReal.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnStyleReal.setTextColor(0xFF38BDF8);
            btnStyleAnime.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
            btnStyleAnime.setTextColor(0xFF94A3B8);
            if (attachedMediaId != null) {
                tvLoraStatus.setText("• Đang đính kèm ảnh mẫu (Tự động bỏ LoRA để tương thích)");
                tvLoraStatus.setTextColor(0xFFF59E0B);
            } else {
                tvLoraStatus.setText("• Đã kích hoạt LoRA Tsubaki 3 Realism v1 (Trọng số 0.70)");
                tvLoraStatus.setTextColor(0xFF34D399);
            }
        });
    }

    private void setupProfileButtons() {
        btnProfileFlash.setOnClickListener(v -> selectProfile("flash"));
        btnProfilePro.setOnClickListener(v -> selectProfile("pro"));
        btnProfileUltra.setOnClickListener(v -> selectProfile("ultra"));
    }

    private void selectProfile(String profile) {
        currentProfile = profile;
        btnProfileFlash.setBackgroundResource("flash".equals(profile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_btn_secondary);
        btnProfileFlash.setTextColor("flash".equals(profile) ? 0xFF38BDF8 : 0xFF94A3B8);

        btnProfilePro.setBackgroundResource("pro".equals(profile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_btn_secondary);
        btnProfilePro.setTextColor("pro".equals(profile) ? 0xFF38BDF8 : 0xFF94A3B8);

        btnProfileUltra.setBackgroundResource("ultra".equals(profile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_btn_secondary);
        btnProfileUltra.setTextColor("ultra".equals(profile) ? 0xFF38BDF8 : 0xFF94A3B8);

        if (!"flash".equals(profile) && batchCount > 1) {
            batchCount = 1;
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch1.setTextColor(0xFF38BDF8);
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch4.setTextColor(0xFF94A3B8);
        }

        updateCostBadge();
    }

    private void setupBatchButtons() {
        btnBatch1.setOnClickListener(v -> {
            batchCount = 1;
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch1.setTextColor(0xFF38BDF8);
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch4.setTextColor(0xFF94A3B8);
            updateCostBadge();
        });

        btnBatch4.setOnClickListener(v -> {
            if (!"flash".equalsIgnoreCase(currentProfile)) {
                Toast.makeText(this, "Batch 4 ảnh chỉ khả dụng cho chế độ FLASH!", Toast.LENGTH_SHORT).show();
                return;
            }
            batchCount = 4;
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch4.setTextColor(0xFF38BDF8);
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch1.setTextColor(0xFF94A3B8);
            updateCostBadge();
        });
    }

    private void updateCostBadge() {
        long cost = calculateRequiredCost();
        tvCostBadge.setText("[Ước tính: " + String.format(Locale.US, "%,d", cost) + " Credits]");
    }

    private long calculateRequiredCost() {
        if ("ultra".equalsIgnoreCase(currentProfile)) return 5500;
        if ("pro".equalsIgnoreCase(currentProfile)) return 4400;
        return (batchCount == 4) ? 400 : 100;
    }

    private void setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadContextImage(uri);
                    }
                }
        );

        btnPickImage.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

        btnClearImage.setOnClickListener(v -> {
            attachedMediaId = null;
            btnClearImage.setVisibility(View.GONE);
            tvAttachedImageName.setText("Chưa đính kèm ảnh (Tạo từ Text Prompt)");
            tvAttachedImageName.setTextColor(0xFF64748B);
            if (isReal) {
                tvLoraStatus.setText("• Đã kích hoạt LoRA Tsubaki 3 Realism v1 (Trọng số 0.70)");
                tvLoraStatus.setTextColor(0xFF34D399);
            }
        });
    }

    private void uploadContextImage(Uri uri) {
        tvAttachedImageName.setText("Đang tải ảnh lên PixAI Cloud qua Proxy...");
        tvAttachedImageName.setTextColor(0xFFF59E0B);

        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, read);
                }
                is.close();
                byte[] imageBytes = bos.toByteArray();

                AccountModel active = storage.getActiveAccount();
                String token = active != null ? active.getToken() : "";
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;

                String mediaId = PixAiApiHelper.uploadImageToPixAi(imageBytes, token, proxy);

                mainHandler.post(() -> {
                    attachedMediaId = mediaId;
                    tvAttachedImageName.setText("[Đã đính kèm ảnh: " + mediaId + "]");
                    tvAttachedImageName.setTextColor(0xFF34D399);
                    btnClearImage.setVisibility(View.VISIBLE);
                    tvLoraStatus.setText("• Đang đính kèm ảnh mẫu (Tự động loại bỏ LoRA để tương thích)");
                    tvLoraStatus.setTextColor(0xFFF59E0B);
                    Toast.makeText(this, "Đính kèm ảnh thành công!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvAttachedImageName.setText("Lỗi tải ảnh: " + e.getMessage());
                    tvAttachedImageName.setTextColor(0xFFF43F5E);
                });
            }
        });
    }

    private void setupGenerateAction() {
        btnGenerate.setOnClickListener(v -> startGenerationTask());

        btnCopyOriginalPrompt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("Original Prompt", tvResOriginalPrompt.getText().toString());
            cm.setPrimaryClip(cd);
            Toast.makeText(this, "Đã sao chép Prompt Gốc!", Toast.LENGTH_SHORT).show();
        });

        btnCopyServerPrompt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("Server Prompt", tvResServerPrompt.getText().toString());
            cm.setPrimaryClip(cd);
            Toast.makeText(this, "Đã sao chép Prompt Server Sửa!", Toast.LENGTH_SHORT).show();
        });
    }

    private void startGenerationTask() {
        String prompt = etPrompt.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Prompt!", Toast.LENGTH_SHORT).show();
            return;
        }

        long requiredCost = calculateRequiredCost();
        AccountModel account = storage.findEligibleAccount(requiredCost);

        if (account == null) {
            Toast.makeText(this, "Tất cả tài khoản đều không đủ " + requiredCost + " Credits! Hãy nhấn [Reg Acc Mới] hoặc [Full Thưởng].", Toast.LENGTH_LONG).show();
            return;
        }

        // Tự động xoay tua nếu tài khoản hiện tại không đủ credit
        AccountModel currentActive = storage.getActiveAccount();
        if (currentActive == null || currentActive.getCredits() < requiredCost) {
            storage.setActiveEmail(account.getEmail());
            refreshHeader();
            Toast.makeText(this, "Tự động đổi sang tài khoản: " + account.getEmail(), Toast.LENGTH_SHORT).show();
        }

        String token = account.getToken();
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
        String neg = etNegativePrompt.getText().toString().trim();

        btnGenerate.setEnabled(false);
        tvGenStatus.setText("Đang gửi yêu cầu tạo ảnh tới PixAI Engine (" + currentProfile.toUpperCase() + ")...");
        tvGenStatus.setTextColor(0xFF38BDF8);

        executor.execute(() -> {
            PixAiApiHelper.TaskResult res = PixAiApiHelper.createAndPollGenerationTask(
                    token,
                    currentProfile,
                    isReal,
                    selectedWidth,
                    selectedHeight,
                    batchCount,
                    prompt,
                    neg,
                    attachedMediaId,
                    proxy
            );

            mainHandler.post(() -> {
                btnGenerate.setEnabled(true);
                if (res.success && !res.imageUrls.isEmpty()) {
                    String finalImgUrl = res.imageUrls.get(0);
                    tvGenStatus.setText("Khởi tạo thành công! Task ID: " + res.taskId);
                    tvGenStatus.setTextColor(0xFF34D399);

                    // Trừ credit nội bộ & cập nhật quota
                    account.setCredits(Math.max(0, account.getCredits() - requiredCost));
                    storage.addAccount(account);
                    refreshHeader();

                    // Lưu lịch sử
                    HistoryItem hist = new HistoryItem(
                            finalImgUrl,
                            res.originalPrompt,
                            res.serverPrompt,
                            "Tsubaki 3",
                            currentProfile,
                            isReal,
                            res.seed,
                            selectedWidth,
                            selectedHeight
                    );
                    storage.addHistoryItem(hist);

                    // Hiển thị kết quả
                    layoutResultCard.setVisibility(View.VISIBLE);
                    tvResOriginalPrompt.setText(res.originalPrompt);
                    tvResServerPrompt.setText(res.serverPrompt);

                    loadImagePreview(finalImgUrl);
                    Toast.makeText(this, "Tạo ảnh thành công!", Toast.LENGTH_SHORT).show();
                } else {
                    tvGenStatus.setText("Thất bại: " + res.errorMessage);
                    tvGenStatus.setTextColor(0xFFF43F5E);
                    Toast.makeText(this, "Lỗi tạo ảnh: " + res.errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void loadImagePreview(String urlString) {
        executor.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                InputStream is = conn.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(is);
                mainHandler.post(() -> {
                    if (bitmap != null) {
                        ivResultPreview.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    private void refreshHeader() {
        AccountModel active = storage.getActiveAccount();
        if (active != null) {
            String shortEmail = active.getEmail();
            if (shortEmail.length() > 22) shortEmail = shortEmail.substring(0, 19) + "...";
            tvHeaderAccount.setText("[" + shortEmail + " | " + String.format(Locale.US, "%,d", active.getCredits()) + " CR]");
        } else {
            tvHeaderAccount.setText("[Chưa có tài khoản]");
        }
    }

    // --- TAB 2: QUẢN LÝ TÀI KHOẢN ---
    private void setupAccountManagementActions() {
        btnBatchClaimDaily.setOnClickListener(v -> {
            btnBatchClaimDaily.setEnabled(false);
            Toast.makeText(this, "Đang nhận thưởng Daily cho toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel acc : list) {
                    if (acc.getToken() != null && !acc.getToken().isEmpty()) {
                        PixAiApiHelper.claimDaily(acc.getToken(), proxy);
                        long cur = PixAiApiHelper.getQuota(acc.getToken(), proxy);
                        if (cur >= 0) acc.setCredits(cur);
                    }
                }
                storage.saveAccounts(list);
                mainHandler.post(() -> {
                    btnBatchClaimDaily.setEnabled(true);
                    refreshAccountsList();
                    refreshHeader();
                    Toast.makeText(this, "Đã hoàn tất nhận Daily hàng loạt!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        btnBatchClaimFull.setOnClickListener(v -> {
            btnBatchClaimFull.setEnabled(false);
            Toast.makeText(this, "Đang nhận Full Thưởng (80K) cho toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel acc : list) {
                    if (acc.getToken() != null && !acc.getToken().isEmpty()) {
                        PixAiApiHelper.claimAllQuests(acc.getToken(), proxy);
                        long cur = PixAiApiHelper.getQuota(acc.getToken(), proxy);
                        if (cur >= 0) acc.setCredits(cur);
                    }
                }
                storage.saveAccounts(list);
                mainHandler.post(() -> {
                    btnBatchClaimFull.setEnabled(true);
                    refreshAccountsList();
                    refreshHeader();
                    Toast.makeText(this, "Đã hoàn tất nhận Full Thưởng hàng loạt!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        btnSyncAllCredits.setOnClickListener(v -> {
            btnSyncAllCredits.setEnabled(false);
            Toast.makeText(this, "Đang đồng bộ số dư Credits từ Server PixAI...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel acc : list) {
                    if (acc.getToken() != null && !acc.getToken().isEmpty()) {
                        long cur = PixAiApiHelper.getQuota(acc.getToken(), proxy);
                        if (cur >= 0) acc.setCredits(cur);
                    }
                }
                storage.saveAccounts(list);
                mainHandler.post(() -> {
                    btnSyncAllCredits.setEnabled(true);
                    refreshAccountsList();
                    refreshHeader();
                    Toast.makeText(this, "Đồng bộ số dư thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        btnAutoReg.setOnClickListener(v -> {
            Toast.makeText(this, "Đang khởi chạy tiến trình Reg Acc mới ngầm qua Proxy Shield...", Toast.LENGTH_LONG).show();
            String rnd = String.valueOf(System.currentTimeMillis()).substring(7);
            String newEmail = "pixai_nexus_" + rnd + "@temporarymail.net";
            String newPass = "PixAi@123456";

            Intent intent = new Intent(this, HeadlessRegService.class);
            intent.putExtra("email", newEmail);
            intent.putExtra("password", newPass);
            intent.putExtra("useProxy", storage.isUseProxy());
            intent.putExtra("proxyAddr", storage.getProxyAddress());
            intent.putExtra("receiver", new ResultReceiver(mainHandler) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode == HeadlessRegService.CODE_SUCCESS) {
                        String token = resultData.getString("token");
                        AccountModel newAcc = new AccountModel(newEmail, newPass, token, 80000);
                        storage.addAccount(newAcc);
                        storage.setActiveEmail(newEmail);
                        refreshAccountsList();
                        refreshHeader();
                        Toast.makeText(MainActivity.this, "Tạo tài khoản mới thành công: " + newEmail + " (+80,000 CR)!", Toast.LENGTH_LONG).show();
                    } else if (resultCode == HeadlessRegService.CODE_ERROR) {
                        String err = resultData.getString("error");
                        Toast.makeText(MainActivity.this, "Tạo tài khoản thất bại: " + err, Toast.LENGTH_LONG).show();
                    } else if (resultCode == HeadlessRegService.CODE_PROGRESS) {
                        String msg = resultData.getString("message");
                        tvHeaderAccount.setText("[Reg Acc: " + (msg.length() > 25 ? msg.substring(0, 22) + "..." : msg) + "]");
                    }
                }
            });

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        });
    }

    private void refreshAccountsList() {
        List<AccountModel> list = storage.getAccounts();
        if (accountAdapter == null) {
            accountAdapter = new AccountAdapter(this, list);
            lvAccounts.setAdapter(accountAdapter);
        } else {
            accountAdapter.clear();
            accountAdapter.addAll(list);
            accountAdapter.notifyDataSetChanged();
        }
    }

    // --- TAB 3: KHO LỊCH SỬ ---
    private void setupHistoryActions() {
        // Init history list
    }

    private void refreshHistoryList() {
        List<HistoryItem> list = storage.getHistory();
        if (historyAdapter == null) {
            historyAdapter = new HistoryAdapter(this, list);
            lvHistory.setAdapter(historyAdapter);
        } else {
            historyAdapter.clear();
            historyAdapter.addAll(list);
            historyAdapter.notifyDataSetChanged();
        }
    }

    // --- ADAPTERS ---
    private class AccountAdapter extends ArrayAdapter<AccountModel> {
        public AccountAdapter(Context ctx, List<AccountModel> items) {
            super(ctx, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
                convertView.setBackgroundResource(R.drawable.bg_cyber_card);
                convertView.setPadding(16, 12, 16, 12);
            }
            AccountModel item = getItem(position);
            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            AccountModel active = storage.getActiveAccount();
            boolean isActive = active != null && active.getEmail().equalsIgnoreCase(item.getEmail());

            text1.setText((isActive ? "[ĐANG DÙNG] " : "") + item.getEmail());
            text1.setTextColor(isActive ? 0xFF38BDF8 : 0xFFF8FAFC);
            text1.setTextSize(12);

            text2.setText("Số dư: " + String.format(Locale.US, "%,d", item.getCredits()) + " Credits | Token: " + (item.getToken() != null && item.getToken().length() > 15 ? item.getToken().substring(0, 15) + "..." : "Trống"));
            text2.setTextColor(0xFF34D399);
            text2.setTextSize(10);

            convertView.setOnClickListener(v -> {
                storage.setActiveEmail(item.getEmail());
                refreshAccountsList();
                refreshHeader();
                Toast.makeText(getContext(), "Đã chọn tài khoản: " + item.getEmail(), Toast.LENGTH_SHORT).show();
            });

            return convertView;
        }
    }

    private class HistoryAdapter extends ArrayAdapter<HistoryItem> {
        private final SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());

        public HistoryAdapter(Context ctx, List<HistoryItem> items) {
            super(ctx, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
                convertView.setBackgroundResource(R.drawable.bg_cyber_card);
                convertView.setPadding(16, 12, 16, 12);
            }
            HistoryItem item = getItem(position);
            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            text1.setText("[" + item.getProfile().toUpperCase() + " | " + (item.isReal() ? "Thực tế" : "Anime") + "] " + item.getOriginalPrompt());
            text1.setTextColor(0xFF38BDF8);
            text1.setTextSize(11);

            text2.setText("Thời gian: " + sdf.format(new Date(item.getTimestamp())) + " | Kích thước: " + item.getWidth() + "x" + item.getHeight() + "\nServer Sửa: " + item.getServerPrompt());
            text2.setTextColor(0xFF94A3B8);
            text2.setTextSize(9);

            convertView.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Server Prompt", item.getServerPrompt());
                cm.setPrimaryClip(cd);
                Toast.makeText(getContext(), "Đã sao chép Prompt do server sửa!", Toast.LENGTH_SHORT).show();
            });

            return convertView;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
