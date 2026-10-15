package com.pixai.nexus;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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

    // Header Views
    private TextView tvTotalCreditsHeader;
    private TextView tvActiveAccountHeader;

    // Navigation Buttons
    private Button btnNavStudio, btnNavAccounts, btnNavHistory, btnNavSecurity, btnNavLogs;
    private ScrollView layoutTabStudio;
    private LinearLayout layoutTabAccounts;
    private LinearLayout layoutTabHistory;
    private ScrollView layoutTabSecurity;
    private LinearLayout layoutTabLogs;

    // Tab 1: Studio Views
    private Button btnStyleAnime, btnStyleReal;
    private TextView tvLoraStatus;

    private Button btnProfileFlash, btnProfilePro, btnProfileUltra;
    private Button btnQualityStandard, btnQualityHigh, btnQualityUltra;

    private FrameLayout flRatioPreviewContainer;
    private View vRatioPreviewBox;
    private Spinner spAspectRatio;
    private Button btnBatch1, btnBatch4;

    private Button btnPickImage, btnClearImage;
    private TextView tvAttachedImageName;

    private EditText etPrompt, etNegativePrompt;
    private TextView tvCostBadge;
    private ProgressBar pbGenProgress;
    private TextView tvGenStatus;
    private Button btnGenerate;

    private LinearLayout layoutResultCard;
    private LinearLayout layoutCarouselBar;
    private ImageView ivResultPreview;
    private Button btnZoomImage, btnDownloadImage;
    private Button btnPrevImage, btnNextImage;
    private TextView tvCarouselIndicator;
    private TextView tvResOriginalPrompt, tvResServerPrompt;
    private Button btnCopyOriginalPrompt, btnCopyServerPrompt;

    // Tab 2: Accounts Views
    private EditText etNewAccountToken;
    private Button btnAddAccountDirect;
    private Button btnSyncAllCredits, btnBatchClaimDaily, btnBatchClaimFull;
    private Button btnUnblurNsfw, btnClearAllAccounts;
    private ListView lvAccounts;
    private AccountAdapter accountAdapter;

    // Tab 3: History Views
    private ListView lvHistory;
    private Button btnClearHistory;
    private Button btnLoadMoreHistory;
    private HistoryAdapter historyAdapter;
    private int historyPageLimit = 20;

    // Tab 4: Security & Proxy Views
    private EditText etProxyAddress;
    private CheckBox cbUseProxy, cbFallbackDirect, cbAutoRotateAccounts;
    private Button btnSaveSecurity, btnTestProxy;
    private TextView tvProxyTestResult;

    // Tab 5: Logs Views
    private ScrollView svLogConsole;
    private TextView tvLogConsole;
    private Button btnClearLogs;

    // State Variables
    private boolean isReal = false;
    private String currentProfile = "flash"; // flash, pro, ultra
    private String currentQuality = "standard"; // standard, high, ultra
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

        initViews();
        restoreSavedState();
        setupNavigation();
        setupRatioSpinner();
        setupStyleButtons();
        setupProfileButtons();
        setupQualityButtons();
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
        logMessage("Khởi động PixAI Nexus Studio phiên bản Monochrome Dark thành công.");
    }

    private void initViews() {
        tvTotalCreditsHeader = findViewById(R.id.tvTotalCreditsHeader);
        tvActiveAccountHeader = findViewById(R.id.tvActiveAccountHeader);

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

        btnStyleAnime = findViewById(R.id.btnStyleAnime);
        btnStyleReal = findViewById(R.id.btnStyleReal);
        tvLoraStatus = findViewById(R.id.tvLoraStatus);

        btnProfileFlash = findViewById(R.id.btnProfileFlash);
        btnProfilePro = findViewById(R.id.btnProfilePro);
        btnProfileUltra = findViewById(R.id.btnProfileUltra);

        btnQualityStandard = findViewById(R.id.btnQualityStandard);
        btnQualityHigh = findViewById(R.id.btnQualityHigh);
        btnQualityUltra = findViewById(R.id.btnQualityUltra);

        flRatioPreviewContainer = findViewById(R.id.flRatioPreviewContainer);
        vRatioPreviewBox = findViewById(R.id.vRatioPreviewBox);
        spAspectRatio = findViewById(R.id.spAspectRatio);
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

        etNewAccountToken = findViewById(R.id.etNewAccountToken);
        btnAddAccountDirect = findViewById(R.id.btnAddAccountDirect);
        btnSyncAllCredits = findViewById(R.id.btnSyncAllCredits);
        btnBatchClaimDaily = findViewById(R.id.btnBatchClaimDaily);
        btnBatchClaimFull = findViewById(R.id.btnBatchClaimFull);
        btnUnblurNsfw = findViewById(R.id.btnUnblurNsfw);
        btnClearAllAccounts = findViewById(R.id.btnClearAllAccounts);
        lvAccounts = findViewById(R.id.lvAccounts);

        lvHistory = findViewById(R.id.lvHistory);
        btnClearHistory = findViewById(R.id.btnClearHistory);
        btnLoadMoreHistory = findViewById(R.id.btnLoadMoreHistory);

        etProxyAddress = findViewById(R.id.etProxyAddress);
        cbUseProxy = findViewById(R.id.cbUseProxy);
        cbFallbackDirect = findViewById(R.id.cbFallbackDirect);
        cbAutoRotateAccounts = findViewById(R.id.cbAutoRotateAccounts);
        btnSaveSecurity = findViewById(R.id.btnSaveSecurity);
        btnTestProxy = findViewById(R.id.btnTestProxy);
        tvProxyTestResult = findViewById(R.id.tvProxyTestResult);

        svLogConsole = findViewById(R.id.svLogConsole);
        tvLogConsole = findViewById(R.id.tvLogConsole);
        btnClearLogs = findViewById(R.id.btnClearLogs);
    }

    private void restoreSavedState() {
        // Khôi phục Prompt & Negative
        etPrompt.setText(storage.getSavedPrompt());
        etNegativePrompt.setText(storage.getSavedNegativePrompt());

        etPrompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                storage.setSavedPrompt(s.toString());
            }
        });

        etNegativePrompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                storage.setSavedNegativePrompt(s.toString());
            }
        });

        // Khôi phục Style
        isReal = storage.isSavedReal();
        updateStyleButtonsDisplay();

        // Khôi phục Profile
        currentProfile = storage.getSavedProfile();
        updateProfileButtonsDisplay();

        // Khôi phục Quality
        currentQuality = storage.getSavedQuality();
        updateQualityButtonsDisplay();

        // Khôi phục Batch
        batchCount = storage.getSavedBatch();
        updateBatchButtonsDisplay();

        // Khôi phục Proxy & Security
        etProxyAddress.setText(storage.getProxyAddress());
        cbUseProxy.setChecked(storage.isUseProxy());
        cbFallbackDirect.setChecked(storage.isFallbackDirect());
        cbAutoRotateAccounts.setChecked(storage.isAutoRotateAccounts());
    }

    private void setupNavigation() {
        View.OnClickListener navClick = v -> {
            layoutTabStudio.setVisibility(v == btnNavStudio ? View.VISIBLE : View.GONE);
            layoutTabAccounts.setVisibility(v == btnNavAccounts ? View.VISIBLE : View.GONE);
            layoutTabHistory.setVisibility(v == btnNavHistory ? View.VISIBLE : View.GONE);
            layoutTabSecurity.setVisibility(v == btnNavSecurity ? View.VISIBLE : View.GONE);
            layoutTabLogs.setVisibility(v == btnNavLogs ? View.VISIBLE : View.GONE);

            btnNavStudio.setBackgroundResource(v == btnNavStudio ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            btnNavStudio.setTextColor(v == btnNavStudio ? 0xFFFFFFFF : 0xFF9E9E9E);

            btnNavAccounts.setBackgroundResource(v == btnNavAccounts ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            btnNavAccounts.setTextColor(v == btnNavAccounts ? 0xFFFFFFFF : 0xFF9E9E9E);

            btnNavHistory.setBackgroundResource(v == btnNavHistory ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            btnNavHistory.setTextColor(v == btnNavHistory ? 0xFFFFFFFF : 0xFF9E9E9E);

            btnNavSecurity.setBackgroundResource(v == btnNavSecurity ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            btnNavSecurity.setTextColor(v == btnNavSecurity ? 0xFFFFFFFF : 0xFF9E9E9E);

            btnNavLogs.setBackgroundResource(v == btnNavLogs ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            btnNavLogs.setTextColor(v == btnNavLogs ? 0xFFFFFFFF : 0xFF9E9E9E);

            if (v == btnNavAccounts) refreshAccountsList();
            if (v == btnNavHistory) refreshHistoryList();
        };

        btnNavStudio.setOnClickListener(navClick);
        btnNavAccounts.setOnClickListener(navClick);
        btnNavHistory.setOnClickListener(navClick);
        btnNavSecurity.setOnClickListener(navClick);
        btnNavLogs.setOnClickListener(navClick);
    }

    private void refreshHeader() {
        long total = storage.getTotalCredits();
        tvTotalCreditsHeader.setText("Tổng: " + String.format(Locale.US, "%,d", total) + " CR");

        AccountModel active = storage.getActiveAccount();
        if (active != null) {
            tvActiveAccountHeader.setText("Tài khoản: " + active.getEmail() + " | Số dư: " + String.format(Locale.US, "%,d", active.getCredits()) + " CR");
        } else {
            tvActiveAccountHeader.setText("Tài khoản: Chưa kích hoạt | Số dư: 0 CR");
        }
    }

    // --- TAB 1: STUDIO CONTROLS ---
    private void setupRatioSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, RATIO_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAspectRatio.setAdapter(adapter);

        int savedIdx = storage.getSavedRatioIndex();
        if (savedIdx >= 0 && savedIdx < RATIO_LABELS.length) {
            spAspectRatio.setSelection(savedIdx);
            selectedWidth = RATIO_DIMS[savedIdx][0];
            selectedHeight = RATIO_DIMS[savedIdx][1];
            updateVisualRatioBox(savedIdx);
        }

        spAspectRatio.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedWidth = RATIO_DIMS[position][0];
                selectedHeight = RATIO_DIMS[position][1];
                storage.setSavedRatioIndex(position);
                updateVisualRatioBox(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateVisualRatioBox(int index) {
        float density = getResources().getDisplayMetrics().density;
        int wDp = 20, hDp = 36;
        switch (index) {
            case 0: // 9:16
                wDp = 20; hDp = 36;
                break;
            case 1: // 1:1
                wDp = 30; hDp = 30;
                break;
            case 2: // 16:9
                wDp = 36; hDp = 20;
                break;
            case 3: // 3:4
                wDp = 24; hDp = 32;
                break;
            case 4: // Compact
                wDp = 21; hDp = 36;
                break;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (wDp * density), (int) (hDp * density));
        lp.gravity = android.view.Gravity.CENTER;
        vRatioPreviewBox.setLayoutParams(lp);
    }

    private void setupStyleButtons() {
        btnStyleAnime.setOnClickListener(v -> {
            isReal = false;
            storage.setSavedReal(false);
            updateStyleButtonsDisplay();
        });

        btnStyleReal.setOnClickListener(v -> {
            isReal = true;
            storage.setSavedReal(true);
            updateStyleButtonsDisplay();
        });
    }

    private void updateStyleButtonsDisplay() {
        btnStyleAnime.setBackgroundResource(!isReal ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnStyleAnime.setTextColor(!isReal ? 0xFFFFFFFF : 0xFF9E9E9E);
        btnStyleReal.setBackgroundResource(isReal ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnStyleReal.setTextColor(isReal ? 0xFFFFFFFF : 0xFF9E9E9E);

        if (attachedMediaId != null) {
            tvLoraStatus.setText("Đang đính kèm ảnh mẫu: Tự động bỏ LoRA để tương thích");
            tvLoraStatus.setTextColor(0xFFE0E0E0);
        } else if (isReal) {
            tvLoraStatus.setText("LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)");
            tvLoraStatus.setTextColor(0xFF81C784);
        } else {
            tvLoraStatus.setText("Chế độ Anime nguyên bản, không dùng LoRA");
            tvLoraStatus.setTextColor(0xFF9E9E9E);
        }
    }

    private void setupProfileButtons() {
        btnProfileFlash.setOnClickListener(v -> {
            currentProfile = "flash";
            storage.setSavedProfile("flash");
            updateProfileButtonsDisplay();
            updateCostBadge();
        });

        btnProfilePro.setOnClickListener(v -> {
            currentProfile = "pro";
            storage.setSavedProfile("pro");
            if (batchCount > 1) {
                batchCount = 1;
                storage.setSavedBatch(1);
                updateBatchButtonsDisplay();
            }
            updateProfileButtonsDisplay();
            updateCostBadge();
        });

        btnProfileUltra.setOnClickListener(v -> {
            currentProfile = "ultra";
            storage.setSavedProfile("ultra");
            if (batchCount > 1) {
                batchCount = 1;
                storage.setSavedBatch(1);
                updateBatchButtonsDisplay();
            }
            updateProfileButtonsDisplay();
            updateCostBadge();
        });
    }

    private void updateProfileButtonsDisplay() {
        btnProfileFlash.setBackgroundResource("flash".equals(currentProfile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnProfileFlash.setTextColor("flash".equals(currentProfile) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnProfilePro.setBackgroundResource("pro".equals(currentProfile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnProfilePro.setTextColor("pro".equals(currentProfile) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnProfileUltra.setBackgroundResource("ultra".equals(currentProfile) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnProfileUltra.setTextColor("ultra".equals(currentProfile) ? 0xFFFFFFFF : 0xFF9E9E9E);
    }

    private void setupQualityButtons() {
        btnQualityStandard.setOnClickListener(v -> {
            currentQuality = "standard";
            storage.setSavedQuality("standard");
            updateQualityButtonsDisplay();
        });

        btnQualityHigh.setOnClickListener(v -> {
            currentQuality = "high";
            storage.setSavedQuality("high");
            updateQualityButtonsDisplay();
        });

        btnQualityUltra.setOnClickListener(v -> {
            currentQuality = "ultra";
            storage.setSavedQuality("ultra");
            updateQualityButtonsDisplay();
        });
    }

    private void updateQualityButtonsDisplay() {
        btnQualityStandard.setBackgroundResource("standard".equals(currentQuality) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnQualityStandard.setTextColor("standard".equals(currentQuality) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnQualityHigh.setBackgroundResource("high".equals(currentQuality) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnQualityHigh.setTextColor("high".equals(currentQuality) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnQualityUltra.setBackgroundResource("ultra".equals(currentQuality) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnQualityUltra.setTextColor("ultra".equals(currentQuality) ? 0xFFFFFFFF : 0xFF9E9E9E);
    }

    private void setupBatchButtons() {
        btnBatch1.setOnClickListener(v -> {
            batchCount = 1;
            storage.setSavedBatch(1);
            updateBatchButtonsDisplay();
            updateCostBadge();
        });

        btnBatch4.setOnClickListener(v -> {
            if (!"flash".equalsIgnoreCase(currentProfile)) {
                Toast.makeText(this, "Số lượng 4 ảnh chỉ hỗ trợ ở chế độ Flash (Siêu Tốc)!", Toast.LENGTH_SHORT).show();
                return;
            }
            batchCount = 4;
            storage.setSavedBatch(4);
            updateBatchButtonsDisplay();
            updateCostBadge();
        });
    }

    private void updateBatchButtonsDisplay() {
        btnBatch1.setBackgroundResource(batchCount == 1 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnBatch1.setTextColor(batchCount == 1 ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnBatch4.setBackgroundResource(batchCount == 4 ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnBatch4.setTextColor(batchCount == 4 ? 0xFFFFFFFF : 0xFF9E9E9E);
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
            tvAttachedImageName.setTextColor(0xFF9E9E9E);
            updateStyleButtonsDisplay();
            logMessage("Đã gỡ ảnh mẫu: Tự động phục hồi LoRA Thực Tế.");
        });
    }

    private void uploadContextImage(Uri uri) {
        tvAttachedImageName.setText("Đang tải ảnh mẫu lên PixAI S3 Cloud...");
        tvAttachedImageName.setTextColor(0xFFE0E0E0);

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
                    tvAttachedImageName.setTextColor(0xFF81C784);
                    btnClearImage.setVisibility(View.VISIBLE);
                    updateStyleButtonsDisplay();
                    logMessage("Tải ảnh mẫu lên PixAI thành công. Media ID: " + mediaId);
                    Toast.makeText(this, "Đính kèm ảnh mẫu thành công!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvAttachedImageName.setText("Lỗi tải ảnh: " + e.getMessage());
                    tvAttachedImageName.setTextColor(0xFFE57373);
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
            Toast.makeText(this, "Tất cả tài khoản đều không đủ " + requiredCost + " Credits! Hãy nhận thưởng hoặc thêm tài khoản mới.", Toast.LENGTH_LONG).show();
            return;
        }

        AccountModel currentActive = storage.getActiveAccount();
        if (currentActive == null || !currentActive.getEmail().equalsIgnoreCase(account.getEmail())) {
            storage.setActiveEmail(account.getEmail());
            refreshHeader();
            logMessage("Tự động chuyển sang tài khoản đủ số dư: " + account.getEmail() + " (" + account.getCredits() + " CR)");
        }

        isGenerating = true;
        btnGenerate.setEnabled(false);
        pbGenProgress.setVisibility(View.VISIBLE);
        tvGenStatus.setText("Bước 1/4: Đang chuẩn bị phiên làm việc & Mở khóa 18+ (NSFW)...");
        tvGenStatus.setTextColor(0xFFFFFFFF);

        String neg = etNegativePrompt.getText().toString().trim();
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;

        executor.execute(() -> executeGenerationWithRetry(account, prompt, neg, proxy, requiredCost));
    }

    private void executeGenerationWithRetry(AccountModel account, String prompt, String neg, String proxy, long requiredCost) {
        try {
            // Tự động tắt làm mờ ảnh nhạy cảm & xác nhận 18+
            PixAiApiHelper.unblurNsfw(account.getToken(), proxy);

            mainHandler.post(() -> tvGenStatus.setText("Bước 2/4: Đang gửi lệnh khởi tạo tới GPU PixAI (" + currentProfile.toUpperCase() + ")..."));

            PixAiApiHelper.TaskResult res = PixAiApiHelper.createAndPollGenerationTask(
                    account.getToken(),
                    currentProfile,
                    isReal,
                    currentQuality,
                    selectedWidth,
                    selectedHeight,
                    batchCount,
                    prompt,
                    neg,
                    attachedMediaId,
                    proxy,
                    (msg, sec) -> mainHandler.post(() -> tvGenStatus.setText("Bước 3/4: " + msg))
            );

            // Xử lý khi tài khoản bị báo hết Credit từ Server
            if (res.insufficientBalance) {
                mainHandler.post(() -> logMessage("Tài khoản " + account.getEmail() + " hết Credit trên server! Tự động xoay sang tài khoản khác..."));
                account.setCredits(0);
                storage.addAccount(account);

                AccountModel nextAcc = storage.findEligibleAccount(requiredCost);
                if (nextAcc != null && !nextAcc.getEmail().equalsIgnoreCase(account.getEmail())) {
                    storage.setActiveEmail(nextAcc.getEmail());
                    mainHandler.post(() -> {
                        refreshHeader();
                        tvGenStatus.setText("Đang tự động đổi sang: " + nextAcc.getEmail() + "...");
                    });
                    executeGenerationWithRetry(nextAcc, prompt, neg, proxy, requiredCost);
                    return;
                } else {
                    mainHandler.post(() -> {
                        isGenerating = false;
                        btnGenerate.setEnabled(true);
                        pbGenProgress.setVisibility(View.GONE);
                        tvGenStatus.setText("Thất bại: Toàn bộ tài khoản đã cạn kiệt Credit!");
                        tvGenStatus.setTextColor(0xFFE57373);
                        Toast.makeText(this, "Tất cả tài khoản đều đã hết Credit!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }

            mainHandler.post(() -> {
                isGenerating = false;
                btnGenerate.setEnabled(true);
                pbGenProgress.setVisibility(View.GONE);

                if (res.success && !res.imageUrls.isEmpty()) {
                    tvGenStatus.setText("Bước 4/4: Khởi tạo hoàn tất 100%! Tác vụ thành công.");
                    tvGenStatus.setTextColor(0xFF81C784);

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
                    tvGenStatus.setTextColor(0xFFE57373);
                    logMessage("Lỗi tạo ảnh: " + res.errorMessage);
                    Toast.makeText(this, "Lỗi: " + res.errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            mainHandler.post(() -> {
                isGenerating = false;
                btnGenerate.setEnabled(true);
                pbGenProgress.setVisibility(View.GONE);
                tvGenStatus.setText("Lỗi Exception: " + e.getMessage());
                tvGenStatus.setTextColor(0xFFE57373);
                logMessage("Lỗi ngoại lệ: " + e.getMessage());
            });
        }
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

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF121212);
        container.setPadding(16, 16, 16, 16);

        // Header controls
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(0, 0, 0, 16);

        Button btnClose = new Button(this);
        btnClose.setText("Đóng");
        btnClose.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(btnClose);

        Button btnSave = new Button(this);
        btnSave.setText("Lưu Vào Máy");
        btnSave.setBackgroundResource(R.drawable.bg_cyber_btn_secondary);
        btnSave.setTextColor(0xFF81C784);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(24);
        btnSave.setLayoutParams(btnLp);
        btnSave.setOnClickListener(v -> downloadImageToGallery(imageUrl));
        topBar.addView(btnSave);

        container.addView(topBar);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        container.addView(iv, lp);

        dialog.setContentView(container);
        dialog.show();

        executor.execute(() -> {
            try {
                URL u = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                InputStream is = conn.getInputStream();
                Bitmap bm = BitmapFactory.decodeStream(is);
                mainHandler.post(() -> {
                    if (bm != null) iv.setImageBitmap(bm);
                });
            } catch (Exception ignored) {}
        });
    }

    private void downloadImageToGallery(String imageUrl) {
        Toast.makeText(this, "Đang tải ảnh về máy...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                byte[] bytes = bos.toByteArray();

                String filename = "PixAI_Nexus_" + System.currentTimeMillis() + ".png";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PixAI_Nexus");

                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            os.write(bytes);
                            os.close();
                        }
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixAI_Nexus");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, filename);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(bytes);
                    fos.close();
                }

                mainHandler.post(() -> {
                    logMessage("Đã tải ảnh về thư viện Pictures/PixAI_Nexus: " + filename);
                    Toast.makeText(this, "Đã lưu ảnh vào Pictures/PixAI_Nexus!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    logMessage("Lỗi lưu ảnh: " + e.getMessage());
                    Toast.makeText(this, "Lỗi lưu ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // --- TAB 2: QUẢN LÝ TÀI KHOẢN ---
    private void setupAccountManagementActions() {
        accountAdapter = new AccountAdapter(this, new ArrayList<>());
        lvAccounts.setAdapter(accountAdapter);

        // Thêm tài khoản thủ công hoặc danh sách
        btnAddAccountDirect.setOnClickListener(v -> {
            String input = etNewAccountToken.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập hoặc dán token!", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] lines = input.split("\n");
            Toast.makeText(this, "Đang kiểm tra và thêm " + lines.length + " tài khoản...", Toast.LENGTH_SHORT).show();

            executor.execute(() -> {
                int added = 0;
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;

                for (String line : lines) {
                    String t = line.trim();
                    if (t.startsWith("Bearer ")) t = t.substring(7).trim();
                    if (t.length() < 20) continue;

                    long quota = PixAiApiHelper.getQuota(t, proxy);
                    String email = "pixai_acc_" + (System.currentTimeMillis() % 100000) + "@nexus.com";
                    AccountModel acc = new AccountModel(email, t, quota);
                    storage.addAccount(acc);
                    added++;
                }

                final int count = added;
                mainHandler.post(() -> {
                    etNewAccountToken.setText("");
                    refreshAccountsList();
                    refreshHeader();
                    logMessage("Đã thêm thành công " + count + " tài khoản vào danh sách.");
                    Toast.makeText(this, "Đã thêm " + count + " tài khoản thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Đồng bộ số dư toàn bộ tài khoản
        btnSyncAllCredits.setOnClickListener(v -> {
            Toast.makeText(this, "Đang đồng bộ số dư toàn bộ tài khoản từ PixAI Server...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel a : list) {
                    if (a.getToken() != null && !a.getToken().isEmpty()) {
                        long q = PixAiApiHelper.getQuota(a.getToken(), proxy);
                        a.setCredits(q);
                    }
                }
                storage.saveAccounts(list);
                mainHandler.post(() -> {
                    refreshAccountsList();
                    refreshHeader();
                    logMessage("Đồng bộ số dư toàn bộ tài khoản hoàn tất.");
                    Toast.makeText(this, "Đồng bộ số dư thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Nhận thưởng Daily toàn bộ
        btnBatchClaimDaily.setOnClickListener(v -> {
            Toast.makeText(this, "Đang nhận thưởng Daily cho toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                int succ = 0;
                for (AccountModel a : list) {
                    if (a.getToken() != null && !a.getToken().isEmpty()) {
                        if (PixAiApiHelper.claimDailyCredits(a.getToken(), proxy)) succ++;
                        long q = PixAiApiHelper.getQuota(a.getToken(), proxy);
                        a.setCredits(q);
                    }
                }
                storage.saveAccounts(list);
                final int totalSucc = succ;
                mainHandler.post(() -> {
                    refreshAccountsList();
                    refreshHeader();
                    logMessage("Nhận Daily hoàn tất: Thành công " + totalSucc + " tài khoản.");
                    Toast.makeText(this, "Nhận Daily hoàn tất cho " + totalSucc + " tài khoản!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Nhận All Thưởng Quests toàn bộ
        btnBatchClaimFull.setOnClickListener(v -> {
            Toast.makeText(this, "Đang nhận toàn bộ Social & Visit Quests...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                int totalQ = 0;
                for (AccountModel a : list) {
                    if (a.getToken() != null && !a.getToken().isEmpty()) {
                        totalQ += PixAiApiHelper.claimSocialAndVisitQuests(a.getToken(), proxy);
                        long q = PixAiApiHelper.getQuota(a.getToken(), proxy);
                        a.setCredits(q);
                    }
                }
                storage.saveAccounts(list);
                final int count = totalQ;
                mainHandler.post(() -> {
                    refreshAccountsList();
                    refreshHeader();
                    logMessage("Nhận toàn bộ nhiệm vụ hoàn tất: " + count + " lượt thành công.");
                    Toast.makeText(this, "Nhận thưởng hoàn tất: " + count + " nhiệm vụ!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Mở khóa 18+ toàn bộ
        btnUnblurNsfw.setOnClickListener(v -> {
            Toast.makeText(this, "Đang tự động mở khóa 18+ và bỏ làm mờ nhạy cảm...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                for (AccountModel a : list) {
                    if (a.getToken() != null && !a.getToken().isEmpty()) {
                        PixAiApiHelper.unblurNsfw(a.getToken(), proxy);
                    }
                }
                mainHandler.post(() -> {
                    logMessage("Đã gửi yêu cầu mở khóa 18+ và bỏ làm mờ cho toàn bộ tài khoản.");
                    Toast.makeText(this, "Mở khóa 18+ toàn bộ tài khoản thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Xóa toàn bộ tài khoản
        btnClearAllAccounts.setOnClickListener(v -> {
            storage.clearAllAccounts();
            refreshAccountsList();
            refreshHeader();
            logMessage("Đã xóa toàn bộ tài khoản khỏi ứng dụng.");
            Toast.makeText(this, "Đã xóa toàn bộ tài khoản!", Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshAccountsList() {
        if (accountAdapter != null) {
            accountAdapter.clear();
            accountAdapter.addAll(storage.getAccounts());
            accountAdapter.notifyDataSetChanged();
        }
    }

    // --- TAB 3: KHO LỊCH SỬ MÃ HÓA ---
    private void setupHistoryActions() {
        historyAdapter = new HistoryAdapter(this, new ArrayList<>());
        lvHistory.setAdapter(historyAdapter);

        btnClearHistory.setOnClickListener(v -> {
            storage.clearHistory();
            refreshHistoryList();
            logMessage("Đã xóa toàn bộ kho lịch sử mã hóa và cache ảnh.");
            Toast.makeText(this, "Đã xóa toàn bộ lịch sử!", Toast.LENGTH_SHORT).show();
        });

        btnLoadMoreHistory.setOnClickListener(v -> {
            historyPageLimit += 20;
            refreshHistoryList();
            Toast.makeText(this, "Đã mở rộng hiển thị " + historyPageLimit + " phiên lịch sử.", Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshHistoryList() {
        if (historyAdapter != null) {
            historyAdapter.clear();
            List<HistoryItem> all = storage.getHistory();
            int limit = Math.min(all.size(), historyPageLimit);
            historyAdapter.addAll(all.subList(0, limit));
            historyAdapter.notifyDataSetChanged();

            btnLoadMoreHistory.setVisibility(all.size() > historyPageLimit ? View.VISIBLE : View.GONE);
        }
    }

    // --- TAB 4: BẢO MẬT & PROXY SHIELD ---
    private void setupSecurityTabActions() {
        btnSaveSecurity.setOnClickListener(v -> {
            String addr = etProxyAddress.getText().toString().trim();
            boolean useP = cbUseProxy.isChecked();
            boolean fb = cbFallbackDirect.isChecked();
            boolean autoR = cbAutoRotateAccounts.isChecked();

            storage.setProxyAddress(addr);
            storage.setUseProxy(useP);
            storage.setFallbackDirect(fb);
            storage.setAutoRotateAccounts(autoR);

            refreshHeader();
            Toast.makeText(this, "Đã lưu cấu hình Bảo Mật & Proxy Shield!", Toast.LENGTH_SHORT).show();
            logMessage("Đã cập nhật cấu hình: Proxy=" + addr + " (Shield=" + (useP ? "BẬT" : "TẮT") + ", Fallback=" + (fb ? "BẬT" : "TẮT") + ")");
        });

        btnTestProxy.setOnClickListener(v -> {
            String proxy = etProxyAddress.getText().toString().trim();
            tvProxyTestResult.setText("Đang kiểm tra kết nối Proxy...");
            tvProxyTestResult.setTextColor(0xFFFFFFFF);

            executor.execute(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    URL url = new URL("https://ipwho.is/");
                    HttpURLConnection conn = PixAiApiHelper.openConnectionWithProxy(url, proxy);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
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
                        tvProxyTestResult.setText("KẾT NỐI THÀNH CÔNG: IP " + ip + " | Quốc gia: " + country + " | Ping: " + ping + "ms");
                        tvProxyTestResult.setTextColor(0xFF81C784);
                        logMessage("Kiểm tra Proxy thành công: IP " + ip + " (" + country + ") - Ping " + ping + "ms");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        tvProxyTestResult.setText("KẾT NỐI THẤT BẠI: " + e.getMessage());
                        tvProxyTestResult.setTextColor(0xFFE57373);
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
                tvLogConsole.append(t + " " + msg + "\n");
                if (svLogConsole != null) {
                    svLogConsole.post(() -> svLogConsole.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    // --- ADAPTER CHO TÀI KHOẢN ---
    private class AccountAdapter extends ArrayAdapter<AccountModel> {
        public AccountAdapter(Context ctx, List<AccountModel> items) {
            super(ctx, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_account, parent, false);
            }
            AccountModel item = getItem(position);
            TextView tvEmail = convertView.findViewById(R.id.tvAccEmail);
            TextView tvCredits = convertView.findViewById(R.id.tvAccCredits);
            TextView tvStatus = convertView.findViewById(R.id.tvAccStatus);
            Button btnDelete = convertView.findViewById(R.id.btnDeleteAccount);

            AccountModel active = storage.getActiveAccount();
            boolean isActive = active != null && active.getEmail().equalsIgnoreCase(item.getEmail());

            tvEmail.setText((isActive ? "ĐANG DÙNG: " : "") + item.getEmail());
            tvEmail.setTextColor(isActive ? 0xFFFFFFFF : 0xFFE0E0E0);

            tvCredits.setText("Số dư: " + String.format(Locale.US, "%,d", item.getCredits()) + " Credits");
            tvCredits.setTextColor(item.getCredits() > 0 ? 0xFF81C784 : 0xFF9E9E9E);

            tvStatus.setText(isActive ? "Tài khoản đang được kích hoạt làm việc" : "Chạm để chọn làm tài khoản chính");

            convertView.setOnClickListener(v -> {
                storage.setActiveEmail(item.getEmail());
                refreshAccountsList();
                refreshHeader();
                Toast.makeText(getContext(), "Đã chọn tài khoản: " + item.getEmail(), Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v -> {
                storage.deleteAccount(item.getEmail());
                refreshAccountsList();
                refreshHeader();
                Toast.makeText(getContext(), "Đã xóa tài khoản!", Toast.LENGTH_SHORT).show();
            });

            return convertView;
        }
    }

    // --- ADAPTER CHO KHO LỊCH SỬ MÃ HÓA CÓ THUMBNAIL ---
    private class HistoryAdapter extends ArrayAdapter<HistoryItem> {
        public HistoryAdapter(Context ctx, List<HistoryItem> items) {
            super(ctx, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_history, parent, false);
            }
            HistoryItem item = getItem(position);
            ImageView ivThumb = convertView.findViewById(R.id.ivHistoryThumb);
            TextView tvMeta = convertView.findViewById(R.id.tvHistoryMeta);
            TextView tvPrompt = convertView.findViewById(R.id.tvHistoryPrompt);
            TextView tvDate = convertView.findViewById(R.id.tvHistoryDate);

            tvMeta.setText(item.getModelName() + " " + item.getProfile().toUpperCase() + " - " + item.getWidth() + "x" + item.getHeight());
            tvPrompt.setText(item.getOriginalPrompt());
            tvDate.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(item.getTimestamp())));

            // Load decrypted thumbnail từ memory/cache
            Bitmap cached = storage.getDecryptedImageCache(item.getId());
            if (cached != null) {
                ivThumb.setImageBitmap(cached);
            } else {
                ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
                executor.execute(() -> {
                    try {
                        URL u = new URL(item.getImageUrl());
                        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                        InputStream is = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                        byte[] rawBytes = bos.toByteArray();

                        storage.saveEncryptedImageCache(item.getId(), rawBytes);
                        Bitmap bm = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);

                        mainHandler.post(() -> {
                            if (bm != null) ivThumb.setImageBitmap(bm);
                        });
                    } catch (Exception ignored) {}
                });
            }

            convertView.setOnClickListener(v -> openFullscreenZoom(item.getImageUrl()));

            return convertView;
        }
    }
}
