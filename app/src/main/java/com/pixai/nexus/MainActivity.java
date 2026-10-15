package com.pixai.nexus;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    // Navigation & Tabs
    private Button btnNavStudio, btnNavAccounts, btnNavHistory, btnNavSecurity, btnNavLogs;
    private ScrollView layoutTabStudio, layoutTabSecurity;
    private LinearLayout layoutTabAccounts, layoutTabHistory, layoutTabLogs;

    // Header Bar
    private TextView tvHeaderTotalCredits, tvHeaderAccount, tvHeaderProxy;

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
    private ProgressBar pbGenProgress;
    private Button btnGenerate;

    // Output & Carousel Controls
    private LinearLayout layoutResultCard, layoutCarouselBar;
    private ImageView ivResultPreview;
    private Button btnZoomImage, btnDownloadImage;
    private Button btnPrevImage, btnNextImage;
    private TextView tvCarouselIndicator;
    private TextView tvResOriginalPrompt, tvResServerPrompt;
    private Button btnCopyOriginalPrompt, btnCopyServerPrompt;

    // Tab 2: Quản Lý Tài Khoản
    private Button btnBatchClaimDaily, btnBatchClaimFull, btnSyncAllCredits, btnUnblurNsfw, btnAutoReg;
    private ListView lvAccounts;
    private AccountAdapter accountAdapter;

    // Tab 3: Kho Lịch Sử Mã Hóa
    private ListView lvHistory;
    private Button btnClearHistory;
    private HistoryAdapter historyAdapter;

    // Tab 4: Bảo Mật & Proxy
    private EditText etProxyAddress;
    private CheckBox cbUseProxy, cbFallbackDirect, cbAntiFingerprint;
    private Button btnSaveSecurity, btnTestProxy;
    private TextView tvProxyTestResult;

    // Tab 5: Nhật Ký Log
    private TextView tvLogConsole;
    private Button btnClearLogs;

    // State Variables
    private boolean isReal = true;
    private String currentProfile = "flash"; // flash, pro, ultra
    private int batchCount = 1;
    private String attachedMediaId = null;
    private int selectedWidth = 1104;
    private int selectedHeight = 1824;

    private final List<String> currentResultImages = new ArrayList<>();
    private int currentCarouselIdx = 0;
    private String lastOriginalPrompt = "";
    private String lastServerPrompt = "";
    private boolean isGenerating = false;

    private final String[] RATIO_LABELS = {
            "9:16 Dọc (1104 x 1824)",
            "1:1 Vuông (1024 x 1024)",
            "16:9 Ngang (1824 x 1104)",
            "3:4 Chân Dung (896 x 1152)",
            "Compact Nhỏ Gọn (608 x 1024)"
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
        setupCarouselActions();
        setupAccountManagementActions();
        setupHistoryActions();
        setupSecurityTabActions();
        setupLogActions();

        refreshHeader();
        updateCostBadge();
        logMessage("PixAI Nexus Studio v2.0 đã sẵn sàng.");
    }

    private void initViews() {
        btnNavStudio = findViewById(R.id.btnNavStudio);
        btnNavAccounts = findViewById(R.id.btnNavAccounts);
        btnNavHistory = findViewById(R.id.btnNavHistory);
        btnNavSecurity = findViewById(R.id.btnNavSecurity);
        btnNavLogs = findViewById(R.id.btnNavLogs);

        layoutTabStudio = findViewById(R.id.layoutTabStudio);
        layoutTabAccounts = findViewById(R.id.layoutTabAccounts);
        layoutTabHistory = findViewById(R.id.layoutTabHistory);
        layoutTabSecurity = findViewById(R.id.layoutTabSecurity);
        layoutTabLogs = findViewById(R.id.layoutTabLogs);

        tvHeaderTotalCredits = findViewById(R.id.tvHeaderTotalCredits);
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
        pbGenProgress = findViewById(R.id.pbGenProgress);
        tvGenStatus = findViewById(R.id.tvGenStatus);
        btnGenerate = findViewById(R.id.btnGenerate);

        layoutResultCard = findViewById(R.id.layoutResultCard);
        layoutCarouselBar = findViewById(R.id.layoutCarouselBar);
        ivResultPreview = findViewById(R.id.ivResultPreview);
        btnZoomImage = findViewById(R.id.btnZoomImage);
        btnDownloadImage = findViewById(R.id.btnDownloadImage);
        btnPrevImage = findViewById(R.id.btnPrevImage);
        btnNextImage = findViewById(R.id.btnNextImage);
        tvCarouselIndicator = findViewById(R.id.tvCarouselIndicator);

        tvResOriginalPrompt = findViewById(R.id.tvResOriginalPrompt);
        tvResServerPrompt = findViewById(R.id.tvResServerPrompt);
        btnCopyOriginalPrompt = findViewById(R.id.btnCopyOriginalPrompt);
        btnCopyServerPrompt = findViewById(R.id.btnCopyServerPrompt);

        btnBatchClaimDaily = findViewById(R.id.btnBatchClaimDaily);
        btnBatchClaimFull = findViewById(R.id.btnBatchClaimFull);
        btnSyncAllCredits = findViewById(R.id.btnSyncAllCredits);
        btnUnblurNsfw = findViewById(R.id.btnUnblurNsfw);
        btnAutoReg = findViewById(R.id.btnAutoReg);
        lvAccounts = findViewById(R.id.lvAccounts);

        lvHistory = findViewById(R.id.lvHistory);
        btnClearHistory = findViewById(R.id.btnClearHistory);

        etProxyAddress = findViewById(R.id.etProxyAddress);
        cbUseProxy = findViewById(R.id.cbUseProxy);
        cbFallbackDirect = findViewById(R.id.cbFallbackDirect);
        cbAntiFingerprint = findViewById(R.id.cbAntiFingerprint);
        btnSaveSecurity = findViewById(R.id.btnSaveSecurity);
        btnTestProxy = findViewById(R.id.btnTestProxy);
        tvProxyTestResult = findViewById(R.id.tvProxyTestResult);

        tvLogConsole = findViewById(R.id.tvLogConsole);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        etProxyAddress.setText(storage.getProxyAddress());
        cbUseProxy.setChecked(storage.isUseProxy());
    }

    private void setupNavigation() {
        btnNavStudio.setOnClickListener(v -> switchTab(1));
        btnNavAccounts.setOnClickListener(v -> switchTab(2));
        btnNavHistory.setOnClickListener(v -> switchTab(3));
        btnNavSecurity.setOnClickListener(v -> switchTab(4));
        btnNavLogs.setOnClickListener(v -> switchTab(5));

        tvHeaderProxy.setOnClickListener(v -> {
            boolean cur = storage.isUseProxy();
            storage.setUseProxy(!cur);
            tvHeaderProxy.setText(!cur ? "Proxy: BẬT" : "Proxy: TẮT");
            tvHeaderProxy.setTextColor(!cur ? 0xFF38BDF8 : 0xFF8C95A6);
            cbUseProxy.setChecked(!cur);
            Toast.makeText(this, !cur ? "Proxy Shield: BẬT" : "Proxy Shield: TẮT", Toast.LENGTH_SHORT).show();
            logMessage("Proxy Shield đã được: " + (!cur ? "BẬT" : "TẮT"));
        });
    }

    private void switchTab(int tabIndex) {
        layoutTabStudio.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        layoutTabAccounts.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);
        layoutTabHistory.setVisibility(tabIndex == 3 ? View.VISIBLE : View.GONE);
        layoutTabSecurity.setVisibility(tabIndex == 4 ? View.VISIBLE : View.GONE);
        layoutTabLogs.setVisibility(tabIndex == 5 ? View.VISIBLE : View.GONE);

        btnNavStudio.setBackgroundResource(tabIndex == 1 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavStudio.setTextColor(tabIndex == 1 ? 0xFF38BDF8 : 0xFF8C95A6);

        btnNavAccounts.setBackgroundResource(tabIndex == 2 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavAccounts.setTextColor(tabIndex == 2 ? 0xFF38BDF8 : 0xFF8C95A6);

        btnNavHistory.setBackgroundResource(tabIndex == 3 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavHistory.setTextColor(tabIndex == 3 ? 0xFF38BDF8 : 0xFF8C95A6);

        btnNavSecurity.setBackgroundResource(tabIndex == 4 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavSecurity.setTextColor(tabIndex == 4 ? 0xFF38BDF8 : 0xFF8C95A6);

        btnNavLogs.setBackgroundResource(tabIndex == 5 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnNavLogs.setTextColor(tabIndex == 5 ? 0xFF38BDF8 : 0xFF8C95A6);

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
            btnStyleReal.setTextColor(0xFF8C95A6);
            tvLoraStatus.setText("Anime Nguyên Bản (Không dùng LoRA)");
            tvLoraStatus.setTextColor(0xFF8C95A6);
        });

        btnStyleReal.setOnClickListener(v -> {
            isReal = true;
            btnStyleReal.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnStyleReal.setTextColor(0xFF38BDF8);
            btnStyleAnime.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
            btnStyleAnime.setTextColor(0xFF8C95A6);
            if (attachedMediaId != null) {
                tvLoraStatus.setText("Đang đính kèm ảnh mẫu (Tự động bỏ LoRA để tương thích)");
                tvLoraStatus.setTextColor(0xFFF59E0B);
            } else {
                tvLoraStatus.setText("LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)");
                tvLoraStatus.setTextColor(0xFF10B981);
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
        btnProfileFlash.setTextColor("flash".equals(profile) ? 0xFF38BDF8 : 0xFF8C95A6);

        btnProfilePro.setBackgroundResource("pro".equals(profile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_btn_secondary);
        btnProfilePro.setTextColor("pro".equals(profile) ? 0xFF38BDF8 : 0xFF8C95A6);

        btnProfileUltra.setBackgroundResource("ultra".equals(profile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_btn_secondary);
        btnProfileUltra.setTextColor("ultra".equals(profile) ? 0xFF38BDF8 : 0xFF8C95A6);

        if (!"flash".equals(profile) && batchCount > 1) {
            batchCount = 1;
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch1.setTextColor(0xFF38BDF8);
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch4.setTextColor(0xFF8C95A6);
        }

        updateCostBadge();
    }

    private void setupBatchButtons() {
        btnBatch1.setOnClickListener(v -> {
            batchCount = 1;
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch1.setTextColor(0xFF38BDF8);
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch4.setTextColor(0xFF8C95A6);
            updateCostBadge();
        });

        btnBatch4.setOnClickListener(v -> {
            if (!"flash".equalsIgnoreCase(currentProfile)) {
                Toast.makeText(this, "Số lượng 4 ảnh chỉ hỗ trợ chế độ Flash (Siêu Tốc)!", Toast.LENGTH_SHORT).show();
                return;
            }
            batchCount = 4;
            btnBatch4.setBackgroundResource(R.drawable.bg_cyber_chip_active);
            btnBatch4.setTextColor(0xFF38BDF8);
            btnBatch1.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
            btnBatch1.setTextColor(0xFF8C95A6);
            updateCostBadge();
        });
    }

    private void updateCostBadge() {
        long cost = calculateRequiredCost();
        tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " Credits (" + currentProfile.toUpperCase() + " - " + batchCount + " ảnh)");
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

        // GỠ ẢNH VÀ TỰ ĐỘNG PHỤC HỒI LORA THỰC TẾ
        btnClearImage.setOnClickListener(v -> {
            attachedMediaId = null;
            btnClearImage.setVisibility(View.GONE);
            tvAttachedImageName.setText("Chưa đính kèm ảnh (Tạo từ văn bản)");
            tvAttachedImageName.setTextColor(0xFF8C95A6);
            if (isReal) {
                tvLoraStatus.setText("LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)");
                tvLoraStatus.setTextColor(0xFF10B981);
                logMessage("Đã gỡ ảnh mẫu: Tự động phục hồi LoRA Thực Tế (0.70).");
            }
        });
    }

    private void uploadContextImage(Uri uri) {
        tvAttachedImageName.setText("Đang tải ảnh mẫu lên PixAI Cloud qua Proxy...");
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
                    tvAttachedImageName.setText("Đã đính kèm ảnh (ID: " + mediaId + ")");
                    tvAttachedImageName.setTextColor(0xFF10B981);
                    btnClearImage.setVisibility(View.VISIBLE);
                    tvLoraStatus.setText("Đang đính kèm ảnh mẫu (Tự động bỏ LoRA để tương thích)");
                    tvLoraStatus.setTextColor(0xFFF59E0B);
                    logMessage("Tải ảnh mẫu thành công! Media ID: " + mediaId);
                    Toast.makeText(this, "Đính kèm ảnh mẫu thành công!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvAttachedImageName.setText("Lỗi tải ảnh: " + e.getMessage());
                    tvAttachedImageName.setTextColor(0xFFEF4444);
                    logMessage("Lỗi tải ảnh mẫu: " + e.getMessage());
                });
            }
        });
    }

    private void setupGenerateAction() {
        btnGenerate.setOnClickListener(v -> startGenerationTask());

        btnCopyOriginalPrompt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("Original Prompt", lastOriginalPrompt);
            cm.setPrimaryClip(cd);
            Toast.makeText(this, "Đã sao chép Prompt Gốc!", Toast.LENGTH_SHORT).show();
        });

        btnCopyServerPrompt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("Server Prompt", lastServerPrompt);
            cm.setPrimaryClip(cd);
            Toast.makeText(this, "Đã sao chép Prompt Server Sửa!", Toast.LENGTH_SHORT).show();
        });

        btnZoomImage.setOnClickListener(v -> {
            if (!currentResultImages.isEmpty()) {
                openFullscreenZoom(currentResultImages.get(currentCarouselIdx));
            }
        });

        btnDownloadImage.setOnClickListener(v -> {
            if (!currentResultImages.isEmpty()) {
                downloadImageToGallery(currentResultImages.get(currentCarouselIdx));
            }
        });
    }

    private void setupCarouselActions() {
        btnPrevImage.setOnClickListener(v -> {
            if (currentCarouselIdx > 0) {
                currentCarouselIdx--;
                updateCarouselDisplay();
            }
        });

        btnNextImage.setOnClickListener(v -> {
            if (currentCarouselIdx < currentResultImages.size() - 1) {
                currentCarouselIdx++;
                updateCarouselDisplay();
            }
        });
    }

    private void updateCarouselDisplay() {
        if (currentResultImages.isEmpty()) {
            layoutCarouselBar.setVisibility(View.GONE);
            return;
        }

        int total = currentResultImages.size();
        if (total > 1) {
            layoutCarouselBar.setVisibility(View.VISIBLE);
            tvCarouselIndicator.setText("Ảnh " + (currentCarouselIdx + 1) + " / " + total);
            btnPrevImage.setEnabled(currentCarouselIdx > 0);
            btnNextImage.setEnabled(currentCarouselIdx < total - 1);
        } else {
            layoutCarouselBar.setVisibility(View.GONE);
        }

        loadImagePreview(currentResultImages.get(currentCarouselIdx));
    }

    private void startGenerationTask() {
        if (isGenerating) return;

        String prompt = etPrompt.getText().toString().trim();
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập câu lệnh Prompt!", Toast.LENGTH_SHORT).show();
            return;
        }

        long requiredCost = calculateRequiredCost();

        // TỰ ĐỘNG XOAY TUA TÀI KHOẢN NẾU THIẾU CREDIT
        AccountModel account = storage.findEligibleAccount(requiredCost);
        if (account == null) {
            Toast.makeText(this, "Tất cả tài khoản đều không đủ " + requiredCost + " Credits! Hãy nhận thưởng hoặc reg acc mới.", Toast.LENGTH_LONG).show();
            return;
        }

        AccountModel currentActive = storage.getActiveAccount();
        if (currentActive == null || currentActive.getCredits() < requiredCost) {
            storage.setActiveEmail(account.getEmail());
            refreshHeader();
            logMessage("Tự động xoay tua sang tài khoản: " + account.getEmail() + " (" + account.getCredits() + " CR)");
            Toast.makeText(this, "Tự động đổi tài khoản: " + account.getEmail(), Toast.LENGTH_SHORT).show();
        }

        isGenerating = true;
        btnGenerate.setEnabled(false);
        pbGenProgress.setVisibility(View.VISIBLE);
        tvGenStatus.setText("Bước 1/4: Đang kiểm tra phiên tài khoản & mở khóa 18+...");
        tvGenStatus.setTextColor(0xFF38BDF8);

        String token = account.getToken();
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
        String neg = etNegativePrompt.getText().toString().trim();

        executor.execute(() -> {
            try {
                // Tự động tắt làm mờ ảnh nhạy cảm & xác nhận 18+
                PixAiApiHelper.unblurNsfw(token, proxy);

                mainHandler.post(() -> tvGenStatus.setText("Bước 2/4: Đang gửi tác vụ khởi tạo tới PixAI Engine (" + currentProfile.toUpperCase() + ")..."));

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
                    isGenerating = false;
                    btnGenerate.setEnabled(true);
                    pbGenProgress.setVisibility(View.GONE);

                    if (res.success && !res.imageUrls.isEmpty()) {
                        tvGenStatus.setText("Bước 4/4: Khởi tạo thành công! (Task ID: " + res.taskId + ")");
                        tvGenStatus.setTextColor(0xFF10B981);

                        // Trừ credit nội bộ
                        account.setCredits(Math.max(0, account.getCredits() - requiredCost));
                        storage.addAccount(account);
                        refreshHeader();

                        lastOriginalPrompt = res.originalPrompt;
                        lastServerPrompt = res.serverPrompt;
                        currentResultImages.clear();
                        currentResultImages.addAll(res.imageUrls);
                        currentCarouselIdx = 0;

                        // Lưu vào Kho Lịch Sử Được Mã Hóa
                        for (String url : res.imageUrls) {
                            HistoryItem hist = new HistoryItem(
                                    url,
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
                        }

                        // Hiển thị kết quả & Carousel
                        layoutResultCard.setVisibility(View.VISIBLE);
                        tvResOriginalPrompt.setText(res.originalPrompt);
                        tvResServerPrompt.setText(res.serverPrompt);
                        updateCarouselDisplay();

                        logMessage("Khởi tạo thành công " + res.imageUrls.size() + " ảnh! Đã lưu vào kho lịch sử mã hóa.");
                        Toast.makeText(this, "Tạo ảnh thành công!", Toast.LENGTH_SHORT).show();
                    } else {
                        tvGenStatus.setText("Thất bại: " + res.errorMessage);
                        tvGenStatus.setTextColor(0xFFEF4444);
                        logMessage("Lỗi tạo ảnh: " + res.errorMessage);
                        Toast.makeText(this, "Lỗi tạo ảnh: " + res.errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isGenerating = false;
                    btnGenerate.setEnabled(true);
                    pbGenProgress.setVisibility(View.GONE);
                    tvGenStatus.setText("Lỗi: " + e.getMessage());
                    tvGenStatus.setTextColor(0xFFEF4444);
                    logMessage("Lỗi ngoại lệ: " + e.getMessage());
                });
            }
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

    private void openFullscreenZoom(String imageUrl) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.activity_main); // Dùng trực tiếp hoặc tạo view
        // Tạo view nhanh phóng to
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF0B0D12);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        container.addView(iv, lp);

        LinearLayout bBar = new LinearLayout(this);
        bBar.setOrientation(LinearLayout.HORIZONTAL);
        bBar.setPadding(20, 20, 20, 20);
        bBar.setBackgroundColor(0xFF161A22);

        Button btnClose = new Button(this);
        btnClose.setText("Đóng");
        btnClose.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
        btnClose.setTextColor(0xFFE2E8F0);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        bBar.addView(btnClose);

        Button btnDl = new Button(this);
        btnDl.setText("Tải Ảnh Về Máy");
        btnDl.setBackgroundResource(R.drawable.bg_cyber_btn_primary);
        btnDl.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(20);
        bBar.addView(btnDl, btnLp);
        btnDl.setOnClickListener(v -> downloadImageToGallery(imageUrl));

        container.addView(bBar);
        dialog.setContentView(container);

        executor.execute(() -> {
            try {
                URL url = new URL(imageUrl);
                InputStream is = url.openStream();
                Bitmap bm = BitmapFactory.decodeStream(is);
                mainHandler.post(() -> iv.setImageBitmap(bm));
            } catch (Exception ignored) {}
        });

        dialog.show();
    }

    private void downloadImageToGallery(String imageUrl) {
        Toast.makeText(this, "Đang tải ảnh về máy...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL(imageUrl);
                InputStream is = url.openStream();
                Bitmap bm = BitmapFactory.decodeStream(is);
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File outDir = new File(picturesDir, "PixAI_Nexus");
                outDir.mkdirs();
                String fname = "PixAI_" + System.currentTimeMillis() + ".jpg";
                File outFile = new File(outDir, fname);
                FileOutputStream fos = new FileOutputStream(outFile);
                bm.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();

                mainHandler.post(() -> {
                    Toast.makeText(this, "Đã lưu ảnh vào Thư Viện: Pictures/PixAI_Nexus/" + fname, Toast.LENGTH_LONG).show();
                    logMessage("Đã lưu ảnh thành công: " + outFile.getAbsolutePath());
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Lỗi tải ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void refreshHeader() {
        long totalCredits = 0;
        List<AccountModel> list = storage.getAccounts();
        for (AccountModel a : list) {
            totalCredits += a.getCredits();
        }
        tvHeaderTotalCredits.setText("Tổng: " + String.format(Locale.US, "%,d", totalCredits) + " CR");

        AccountModel active = storage.getActiveAccount();
        if (active != null) {
            String shortEmail = active.getEmail();
            if (shortEmail.length() > 20) shortEmail = shortEmail.substring(0, 18) + "...";
            tvHeaderAccount.setText(shortEmail + " (" + String.format(Locale.US, "%,d", active.getCredits()) + " CR)");
        } else {
            tvHeaderAccount.setText("Chưa có tài khoản");
        }

        boolean pOn = storage.isUseProxy();
        tvHeaderProxy.setText(pOn ? "Proxy: BẬT" : "Proxy: TẮT");
        tvHeaderProxy.setTextColor(pOn ? 0xFF38BDF8 : 0xFF8C95A6);
    }

    // --- TAB 2: QUẢN LÝ TÀI KHOẢN ---
    private void setupAccountManagementActions() {
        btnBatchClaimDaily.setOnClickListener(v -> {
            btnBatchClaimDaily.setEnabled(false);
            Toast.makeText(this, "Đang nhận Daily (+10K) cho toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
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
                    logMessage("Hoàn tất nhận Daily (+10K) hàng loạt!");
                    Toast.makeText(this, "Đã nhận Daily hàng loạt thành công!", Toast.LENGTH_SHORT).show();
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
                    logMessage("Hoàn tất nhận Full Thưởng (+80K) hàng loạt!");
                    Toast.makeText(this, "Đã nhận Full Thưởng hàng loạt thành công!", Toast.LENGTH_SHORT).show();
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
                    logMessage("Đồng bộ số dư tài khoản thành công!");
                    Toast.makeText(this, "Đồng bộ số dư thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        btnUnblurNsfw.setOnClickListener(v -> {
            btnUnblurNsfw.setEnabled(false);
            Toast.makeText(this, "Đang xác nhận 18+ và tắt làm mờ nhạy cảm cho toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel acc : list) {
                    if (acc.getToken() != null && !acc.getToken().isEmpty()) {
                        PixAiApiHelper.unblurNsfw(acc.getToken(), proxy);
                    }
                }
                mainHandler.post(() -> {
                    btnUnblurNsfw.setEnabled(true);
                    logMessage("Đã hoàn tất xác nhận 18+ và gỡ blur NSFW cho toàn bộ tài khoản!");
                    Toast.makeText(this, "Đã mở khóa 18+ & tắt mờ nhạy cảm toàn bộ tài khoản!", Toast.LENGTH_SHORT).show();
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
                        logMessage("Tạo tài khoản mới thành công: " + newEmail + " (+80,000 CR)!");
                        Toast.makeText(MainActivity.this, "Tạo tài khoản mới thành công: " + newEmail + " (+80,000 CR)!", Toast.LENGTH_LONG).show();
                    } else if (resultCode == HeadlessRegService.CODE_ERROR) {
                        String err = resultData.getString("error");
                        logMessage("Tạo tài khoản thất bại: " + err);
                        Toast.makeText(MainActivity.this, "Tạo tài khoản thất bại: " + err, Toast.LENGTH_LONG).show();
                    } else if (resultCode == HeadlessRegService.CODE_PROGRESS) {
                        String msg = resultData.getString("message");
                        tvGenStatus.setText("Reg Acc: " + msg);
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

    // --- TAB 3: KHO LỊCH SỬ MÃ HÓA ---
    private void setupHistoryActions() {
        btnClearHistory.setOnClickListener(v -> {
            storage.clearHistory();
            refreshHistoryList();
            Toast.makeText(this, "Đã xóa toàn bộ kho lịch sử mã hóa!", Toast.LENGTH_SHORT).show();
            logMessage("Đã xóa toàn bộ kho lịch sử mã hóa.");
        });
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

    // --- TAB 4: BẢO MẬT & PROXY ---
    private void setupSecurityTabActions() {
        btnSaveSecurity.setOnClickListener(v -> {
            String addr = etProxyAddress.getText().toString().trim();
            boolean useP = cbUseProxy.isChecked();
            storage.setProxyAddress(addr);
            storage.setUseProxy(useP);
            refreshHeader();
            Toast.makeText(this, "Đã lưu cấu hình Bảo Mật & Proxy Shield!", Toast.LENGTH_SHORT).show();
            logMessage("Đã cập nhật cấu hình Proxy: " + addr + " (Trạng thái: " + (useP ? "BẬT" : "TẮT") + ")");
        });

        btnTestProxy.setOnClickListener(v -> {
            String proxy = etProxyAddress.getText().toString().trim();
            tvProxyTestResult.setText("Đang kiểm tra kết nối Proxy...");
            tvProxyTestResult.setTextColor(0xFF38BDF8);

            executor.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    URL url = new URL("https://ipwho.is/");
                    HttpURLConnection conn;
                    if (!proxy.isEmpty()) {
                        String clean = proxy.replace("http://", "").replace("https://", "");
                        String[] parts = clean.split("@");
                        String hostPort = parts.length > 1 ? parts[1] : parts[0];
                        String[] hp = hostPort.split(":");
                        Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                        conn = (HttpURLConnection) url.openConnection(p);
                    } else {
                        conn = (HttpURLConnection) url.openConnection();
                    }
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(12000);
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                    is.close();
                    long ping = System.currentTimeMillis() - t0;
                    JSONObject json = new JSONObject(bos.toString("UTF-8"));
                    String ip = json.optString("ip", "Unknown");
                    String country = json.optString("country", "Unknown");

                    mainHandler.post(() -> {
                        tvProxyTestResult.setText("KẾT NỐI THÀNH CÔNG! IP: " + ip + " | Vùng: " + country + " | Ping: " + ping + "ms");
                        tvProxyTestResult.setTextColor(0xFF10B981);
                        logMessage("Kiểm tra Proxy thành công: IP " + ip + " (" + country + ") - " + ping + "ms");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        tvProxyTestResult.setText("KẾT NỐI THẤT BẠI: " + e.getMessage());
                        tvProxyTestResult.setTextColor(0xFFEF4444);
                        logMessage("Kiểm tra Proxy thất bại: " + e.getMessage());
                    });
                }
            });
        });
    }

    // --- TAB 5: NHẬT KÝ LOG ---
    private void setupLogActions() {
        btnClearLogs.setOnClickListener(v -> tvLogConsole.setText(""));
    }

    private void logMessage(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        mainHandler.post(() -> {
            if (tvLogConsole != null) {
                tvLogConsole.append("[" + t + "] " + msg + "\n");
            }
        });
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

            text1.setText((isActive ? "ĐANG DÙNG - " : "") + item.getEmail());
            text1.setTextColor(isActive ? 0xFF38BDF8 : 0xFFE2E8F0);
            text1.setTextSize(11);

            text2.setText("Số dư: " + String.format(Locale.US, "%,d", item.getCredits()) + " Credits | Token: " + (item.getToken() != null && item.getToken().length() > 15 ? item.getToken().substring(0, 15) + "..." : "Trống"));
            text2.setTextColor(0xFF10B981);
            text2.setTextSize(9);

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

            text1.setText((item.isReal() ? "Thực Tế" : "Anime") + " - " + item.getProfile().toUpperCase() + " | " + item.getOriginalPrompt());
            text1.setTextColor(0xFF38BDF8);
            text1.setTextSize(10);

            text2.setText("Thời gian: " + sdf.format(new Date(item.getTimestamp())) + " | Kích thước: " + item.getWidth() + "x" + item.getHeight() + "\nServer Sửa: " + item.getServerPrompt());
            text2.setTextColor(0xFF8C95A6);
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
