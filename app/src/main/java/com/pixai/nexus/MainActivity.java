package com.pixai.nexus;

import android.app.AlertDialog;
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
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

    // Size Preset Chips & Custom & Auto
    private Button btnSizeXL, btnSizeL, btnSizeM, btnSizeCustom, btnSizeAuto;
    private LinearLayout layoutCustomDimensions;
    private EditText etCustomWidth, etCustomHeight;

    // Aspect Ratio Chips
    private Button btnRatio9_16, btnRatio16_9, btnRatio1_1, btnRatio3_4, btnRatio4_3;
    private Button btnRatio2_3, btnRatio3_2, btnRatio3_5, btnRatio5_3, btnRatio7_9;
    private Button btnRatio9_7, btnRatio1_3, btnRatio3_1;

    // Visual Preview Box
    private FrameLayout flRatioPreviewContainer;
    private View vRatioPreviewBox;
    private TextView tvRatioInfo, tvDimensionInfo;

    private Button btnBatch1, btnBatch4;

    // Multi-Image Context Gallery
    private Button btnPickImage, btnClearImage;
    private HorizontalScrollView hsvAttachedImages;
    private LinearLayout layoutAttachedThumbnails;
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
    private Button btnAddAccountDirect, btnAutoRegAcc;
    private Button btnImportText, btnImportJson, btnExportJson;
    private Button btnSyncAllCredits, btnBatchClaimDaily, btnBatchClaimFull;
    private Button btnUnblurNsfw, btnClearAllAccounts;
    private ListView lvAccounts;
    private AccountAdapter accountAdapter;

    // Tab 3: History Views & PIN Security
    private Button btnHistorySecurity, btnClearHistory;
    private LinearLayout layoutHistoryLocked, layoutHistoryContent;
    private EditText etHistoryUnlockPin;
    private Button btnUnlockHistory, btnLoadMoreHistory;
    private ListView lvHistory;
    private HistoryAdapter historyAdapter;
    private int historyPageLimit = 20;
    private boolean isHistoryUnlocked = false;

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
    private String currentSizeTier = "xl"; // xl, l, m, custom, auto
    private String currentRatio = "9:16";
    private int batchCount = 1;
    private int selectedWidth = 1104;
    private int selectedHeight = 1824;

    // Multi-image state
    private final List<Uri> attachedImageUris = new ArrayList<>();
    private final List<String> attachedMediaIds = new ArrayList<>();
    private Bitmap firstAttachedBitmap = null;

    private final List<String> currentResultImages = new ArrayList<>();
    private int currentCarouselIdx = 0;
    private String lastOriginalPrompt = "";
    private String lastServerPrompt = "";
    private boolean isGenerating = false;

    private ActivityResultLauncher<String> imagePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new AccountStorage(this);

        initViews();
        restoreSavedState();
        setupNavigation();
        setupStyleButtons();
        setupProfileButtons();
        setupSizeButtons();
        setupRatioButtons();
        setupBatchButtons();
        setupImagePicker();
        setupGenerateAction();
        setupCarouselActions();
        setupAccountManagementActions();
        setupHistoryActions();
        setupSecurityTabActions();
        setupLogActions();

        refreshHeader();
        updateDimensionsAndPreview();
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

        btnSizeXL = findViewById(R.id.btnSizeXL);
        btnSizeL = findViewById(R.id.btnSizeL);
        btnSizeM = findViewById(R.id.btnSizeM);
        btnSizeCustom = findViewById(R.id.btnSizeCustom);
        btnSizeAuto = findViewById(R.id.btnSizeAuto);
        layoutCustomDimensions = findViewById(R.id.layoutCustomDimensions);
        etCustomWidth = findViewById(R.id.etCustomWidth);
        etCustomHeight = findViewById(R.id.etCustomHeight);

        btnRatio9_16 = findViewById(R.id.btnRatio9_16);
        btnRatio16_9 = findViewById(R.id.btnRatio16_9);
        btnRatio1_1 = findViewById(R.id.btnRatio1_1);
        btnRatio3_4 = findViewById(R.id.btnRatio3_4);
        btnRatio4_3 = findViewById(R.id.btnRatio4_3);
        btnRatio2_3 = findViewById(R.id.btnRatio2_3);
        btnRatio3_2 = findViewById(R.id.btnRatio3_2);
        btnRatio3_5 = findViewById(R.id.btnRatio3_5);
        btnRatio5_3 = findViewById(R.id.btnRatio5_3);
        btnRatio7_9 = findViewById(R.id.btnRatio7_9);
        btnRatio9_7 = findViewById(R.id.btnRatio9_7);
        btnRatio1_3 = findViewById(R.id.btnRatio1_3);
        btnRatio3_1 = findViewById(R.id.btnRatio3_1);

        flRatioPreviewContainer = findViewById(R.id.flRatioPreviewContainer);
        vRatioPreviewBox = findViewById(R.id.vRatioPreviewBox);
        tvRatioInfo = findViewById(R.id.tvRatioInfo);
        tvDimensionInfo = findViewById(R.id.tvDimensionInfo);

        btnBatch1 = findViewById(R.id.btnBatch1);
        btnBatch4 = findViewById(R.id.btnBatch4);

        btnPickImage = findViewById(R.id.btnPickImage);
        btnClearImage = findViewById(R.id.btnClearImage);
        hsvAttachedImages = findViewById(R.id.hsvAttachedImages);
        layoutAttachedThumbnails = findViewById(R.id.layoutAttachedThumbnails);
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
        btnAutoRegAcc = findViewById(R.id.btnAutoRegAcc);
        btnImportText = findViewById(R.id.btnImportText);
        btnImportJson = findViewById(R.id.btnImportJson);
        btnExportJson = findViewById(R.id.btnExportJson);
        btnSyncAllCredits = findViewById(R.id.btnSyncAllCredits);
        btnBatchClaimDaily = findViewById(R.id.btnBatchClaimDaily);
        btnBatchClaimFull = findViewById(R.id.btnBatchClaimFull);
        btnUnblurNsfw = findViewById(R.id.btnUnblurNsfw);
        btnClearAllAccounts = findViewById(R.id.btnClearAllAccounts);
        lvAccounts = findViewById(R.id.lvAccounts);

        btnHistorySecurity = findViewById(R.id.btnHistorySecurity);
        btnClearHistory = findViewById(R.id.btnClearHistory);
        layoutHistoryLocked = findViewById(R.id.layoutHistoryLocked);
        layoutHistoryContent = findViewById(R.id.layoutHistoryContent);
        etHistoryUnlockPin = findViewById(R.id.etHistoryUnlockPin);
        btnUnlockHistory = findViewById(R.id.btnUnlockHistory);
        btnLoadMoreHistory = findViewById(R.id.btnLoadMoreHistory);
        lvHistory = findViewById(R.id.lvHistory);

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

        isReal = storage.isSavedReal();
        updateStyleButtonsDisplay();

        currentProfile = storage.getSavedProfile();
        updateProfileButtonsDisplay();

        currentSizeTier = storage.getSavedSizeTier();
        updateSizeButtonsDisplay();

        int savedCustomW = storage.getSavedCustomWidth();
        int savedCustomH = storage.getSavedCustomHeight();
        etCustomWidth.setText(String.valueOf(savedCustomW));
        etCustomHeight.setText(String.valueOf(savedCustomH));

        TextWatcher customDimWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if ("custom".equals(currentSizeTier)) {
                    updateDimensionsAndPreview();
                }
            }
        };
        etCustomWidth.addTextChangedListener(customDimWatcher);
        etCustomHeight.addTextChangedListener(customDimWatcher);

        batchCount = storage.getSavedBatch();
        updateBatchButtonsDisplay();

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
            if (v == btnNavHistory) checkHistoryLockAndRefresh();
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

        if (!attachedMediaIds.isEmpty()) {
            tvLoraStatus.setText("Đang đính kèm " + attachedMediaIds.size() + " ảnh mẫu: Tự động bỏ LoRA để tương thích Img2Img");
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

    private void setupSizeButtons() {
        btnSizeXL.setOnClickListener(v -> selectSizeTier("xl"));
        btnSizeL.setOnClickListener(v -> selectSizeTier("l"));
        btnSizeM.setOnClickListener(v -> selectSizeTier("m"));
        btnSizeCustom.setOnClickListener(v -> selectSizeTier("custom"));
        btnSizeAuto.setOnClickListener(v -> selectSizeTier("auto"));
    }

    private void selectSizeTier(String tier) {
        currentSizeTier = tier;
        storage.setSavedSizeTier(tier);
        updateSizeButtonsDisplay();
        updateDimensionsAndPreview();
    }

    private void updateSizeButtonsDisplay() {
        btnSizeXL.setBackgroundResource("xl".equals(currentSizeTier) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnSizeXL.setTextColor("xl".equals(currentSizeTier) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnSizeL.setBackgroundResource("l".equals(currentSizeTier) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnSizeL.setTextColor("l".equals(currentSizeTier) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnSizeM.setBackgroundResource("m".equals(currentSizeTier) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnSizeM.setTextColor("m".equals(currentSizeTier) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnSizeCustom.setBackgroundResource("custom".equals(currentSizeTier) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnSizeCustom.setTextColor("custom".equals(currentSizeTier) ? 0xFFFFFFFF : 0xFF9E9E9E);

        btnSizeAuto.setBackgroundResource("auto".equals(currentSizeTier) ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btnSizeAuto.setTextColor("auto".equals(currentSizeTier) ? 0xFFFFFFFF : 0xFF9E9E9E);

        layoutCustomDimensions.setVisibility("custom".equals(currentSizeTier) ? View.VISIBLE : View.GONE);
        btnSizeAuto.setVisibility(!attachedMediaIds.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void setupRatioButtons() {
        View.OnClickListener ratioListener = v -> {
            if (v == btnRatio9_16) currentRatio = "9:16";
            else if (v == btnRatio16_9) currentRatio = "16:9";
            else if (v == btnRatio1_1) currentRatio = "1:1";
            else if (v == btnRatio3_4) currentRatio = "3:4";
            else if (v == btnRatio4_3) currentRatio = "4:3";
            else if (v == btnRatio2_3) currentRatio = "2:3";
            else if (v == btnRatio3_2) currentRatio = "3:2";
            else if (v == btnRatio3_5) currentRatio = "3:5";
            else if (v == btnRatio5_3) currentRatio = "5:3";
            else if (v == btnRatio7_9) currentRatio = "7:9";
            else if (v == btnRatio9_7) currentRatio = "9:7";
            else if (v == btnRatio1_3) currentRatio = "1:3";
            else if (v == btnRatio3_1) currentRatio = "3:1";

            updateRatioButtonsDisplay();
            updateDimensionsAndPreview();
        };

        btnRatio9_16.setOnClickListener(ratioListener);
        btnRatio16_9.setOnClickListener(ratioListener);
        btnRatio1_1.setOnClickListener(ratioListener);
        btnRatio3_4.setOnClickListener(ratioListener);
        btnRatio4_3.setOnClickListener(ratioListener);
        btnRatio2_3.setOnClickListener(ratioListener);
        btnRatio3_2.setOnClickListener(ratioListener);
        btnRatio3_5.setOnClickListener(ratioListener);
        btnRatio5_3.setOnClickListener(ratioListener);
        btnRatio7_9.setOnClickListener(ratioListener);
        btnRatio9_7.setOnClickListener(ratioListener);
        btnRatio1_3.setOnClickListener(ratioListener);
        btnRatio3_1.setOnClickListener(ratioListener);

        updateRatioButtonsDisplay();
    }

    private void updateRatioButtonsDisplay() {
        setChipState(btnRatio9_16, "9:16".equals(currentRatio));
        setChipState(btnRatio16_9, "16:9".equals(currentRatio));
        setChipState(btnRatio1_1, "1:1".equals(currentRatio));
        setChipState(btnRatio3_4, "3:4".equals(currentRatio));
        setChipState(btnRatio4_3, "4:3".equals(currentRatio));
        setChipState(btnRatio2_3, "2:3".equals(currentRatio));
        setChipState(btnRatio3_2, "3:2".equals(currentRatio));
        setChipState(btnRatio3_5, "3:5".equals(currentRatio));
        setChipState(btnRatio5_3, "5:3".equals(currentRatio));
        setChipState(btnRatio7_9, "7:9".equals(currentRatio));
        setChipState(btnRatio9_7, "9:7".equals(currentRatio));
        setChipState(btnRatio1_3, "1:3".equals(currentRatio));
        setChipState(btnRatio3_1, "3:1".equals(currentRatio));
    }

    private void setChipState(Button btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundResource(active ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
        btn.setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
    }

    private void updateDimensionsAndPreview() {
        if ("custom".equals(currentSizeTier)) {
            int cw = 1024, ch = 1024;
            try {
                cw = Integer.parseInt(etCustomWidth.getText().toString().trim());
                ch = Integer.parseInt(etCustomHeight.getText().toString().trim());
            } catch (Exception ignored) {}
            cw = Math.max(256, (cw / 16) * 16);
            ch = Math.max(256, (ch / 16) * 16);
            selectedWidth = cw;
            selectedHeight = ch;
            storage.setSavedCustomDimensions(cw, ch);
            tvRatioInfo.setText("Tùy Chỉnh Size (" + cw + " x " + ch + ")");
            tvDimensionInfo.setText("Kích thước tự do: " + cw + " x " + ch + " px");
        } else if ("auto".equals(currentSizeTier) && firstAttachedBitmap != null) {
            int bw = firstAttachedBitmap.getWidth();
            int bh = firstAttachedBitmap.getHeight();
            // Scale to reasonable bounds while preserving aspect ratio
            double scale = Math.sqrt((1024.0 * 1024.0) / (double)(bw * bh));
            int aw = (int) (bw * scale);
            int ah = (int) (bh * scale);
            aw = Math.max(384, (aw / 16) * 16);
            ah = Math.max(384, (ah / 16) * 16);
            selectedWidth = aw;
            selectedHeight = ah;
            tvRatioInfo.setText("Tự Động Theo Ảnh Mẫu (" + bw + " x " + bh + ")");
            tvDimensionInfo.setText("Kích thước tính toán: " + aw + " x " + ah + " px");
        } else {
            // Standard presets: xl, l, m
            int[] dims = calculatePresetDimensions(currentRatio, currentSizeTier);
            selectedWidth = dims[0];
            selectedHeight = dims[1];
            tvRatioInfo.setText("Tỷ lệ: " + currentRatio);
            tvDimensionInfo.setText("Kích thước: " + selectedWidth + " x " + selectedHeight + " px (" + currentSizeTier.toUpperCase() + ")");
        }

        // CẬP NHẬT MINH HỌA HÌNH HỌC TRỰC QUAN CHUẨN XÁC
        renderGeometricPreviewBox(selectedWidth, selectedHeight);
    }

    private int[] calculatePresetDimensions(String ratio, String tier) {
        int w = 1104, h = 1824;
        boolean isXL = "xl".equalsIgnoreCase(tier);
        boolean isL = "l".equalsIgnoreCase(tier);

        switch (ratio) {
            case "9:16":
                w = isXL ? 1104 : (isL ? 944 : 768);
                h = isXL ? 1824 : (isL ? 1584 : 1280);
                break;
            case "16:9":
                w = isXL ? 1824 : (isL ? 1584 : 1280);
                h = isXL ? 1104 : (isL ? 944 : 768);
                break;
            case "1:1":
                w = isXL ? 1440 : (isL ? 1216 : 1024);
                h = isXL ? 1440 : (isL ? 1216 : 1024);
                break;
            case "3:4":
                w = isXL ? 1248 : (isL ? 1056 : 896);
                h = isXL ? 1664 : (isL ? 1408 : 1152);
                break;
            case "4:3":
                w = isXL ? 1664 : (isL ? 1408 : 1152);
                h = isXL ? 1248 : (isL ? 1056 : 896);
                break;
            case "2:3":
                w = isXL ? 1184 : (isL ? 1008 : 832);
                h = isXL ? 1776 : (isL ? 1520 : 1248);
                break;
            case "3:2":
                w = isXL ? 1776 : (isL ? 1520 : 1248);
                h = isXL ? 1184 : (isL ? 1008 : 832);
                break;
            case "3:5":
                w = isXL ? 1104 : (isL ? 936 : 768);
                h = isXL ? 1840 : (isL ? 1568 : 1280);
                break;
            case "5:3":
                w = isXL ? 1840 : (isL ? 1568 : 1280);
                h = isXL ? 1104 : (isL ? 936 : 768);
                break;
            case "7:9":
                w = isXL ? 1280 : (isL ? 1088 : 928);
                h = isXL ? 1664 : (isL ? 1408 : 1184);
                break;
            case "9:7":
                w = isXL ? 1664 : (isL ? 1408 : 1184);
                h = isXL ? 1280 : (isL ? 1088 : 928);
                break;
            case "1:3":
                w = isXL ? 720 : (isL ? 608 : 512);
                h = isXL ? 2160 : (isL ? 1824 : 1536);
                break;
            case "3:1":
                w = isXL ? 2160 : (isL ? 1824 : 1536);
                h = isXL ? 720 : (isL ? 608 : 512);
                break;
            default:
                w = 1104;
                h = 1824;
                break;
        }
        return new int[]{w, h};
    }

    private void renderGeometricPreviewBox(int width, int height) {
        if (vRatioPreviewBox == null) return;
        float density = getResources().getDisplayMetrics().density;
        float maxDp = 42.0f;
        float boxWDp, boxHDp;

        if (width >= height) {
            boxWDp = maxDp;
            boxHDp = Math.max(10.0f, maxDp * ((float) height / (float) width));
        } else {
            boxHDp = maxDp;
            boxWDp = Math.max(10.0f, maxDp * ((float) width / (float) height));
        }

        int pxW = (int) (boxWDp * density);
        int pxH = (int) (boxHDp * density);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pxW, pxH);
        lp.gravity = Gravity.CENTER;
        vRatioPreviewBox.setLayoutParams(lp);
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
                Toast.makeText(this, "Số lượng 4 ảnh chỉ hỗ trợ ở chế độ Flash!", Toast.LENGTH_SHORT).show();
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
        AccountModel eligible = storage.findEligibleAccount(cost);

        if (eligible != null) {
            tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " CR | Khả dụng: " + eligible.getEmail() + " (" + String.format(Locale.US, "%,d", eligible.getCredits()) + " CR) | Đủ điều kiện");
            tvCostBadge.setTextColor(0xFF81C784);
            btnGenerate.setEnabled(true);
        } else {
            tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " CR | Cảnh báo: Không có tài khoản nào đủ " + cost + " CR!");
            tvCostBadge.setTextColor(0xFFE57373);
        }
    }

    private long calculateRequiredCost() {
        if ("ultra".equalsIgnoreCase(currentProfile)) return 5500;
        if ("pro".equalsIgnoreCase(currentProfile)) return 4400;
        return (batchCount == 4) ? 400 : 100;
    }

    // --- MULTI-IMAGE PICKER & GALLERY ---
    private void setupImagePicker() {
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        uploadReferenceImage(uri);
                    }
                }
        );

        btnPickImage.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

        btnClearImage.setOnClickListener(v -> {
            attachedImageUris.clear();
            attachedMediaIds.clear();
            firstAttachedBitmap = null;
            layoutAttachedThumbnails.removeAllViews();
            hsvAttachedImages.setVisibility(View.GONE);
            btnClearImage.setVisibility(View.GONE);
            tvAttachedImageName.setText("Chưa đính kèm ảnh (Tạo từ văn bản, bật LoRA Thực Tế)");
            tvAttachedImageName.setTextColor(0xFF9E9E9E);
            updateStyleButtonsDisplay();
            updateSizeButtonsDisplay();
            updateDimensionsAndPreview();
            logMessage("Đã gỡ toàn bộ ảnh mẫu: Tự động khôi phục LoRA Thực Tế.");
        });
    }

    private void uploadReferenceImage(Uri uri) {
        tvAttachedImageName.setText("Đang tải ảnh mẫu lên PixAI Cloud...");
        tvAttachedImageName.setTextColor(0xFFE0E0E0);

        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) bos.write(buffer, 0, read);
                is.close();
                byte[] imageBytes = bos.toByteArray();

                Bitmap bm = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                AccountModel active = storage.getActiveAccount();
                String token = active != null ? active.getToken() : "";
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;

                String mediaId = PixAiApiHelper.uploadImageToPixAi(imageBytes, token, proxy);

                mainHandler.post(() -> {
                    attachedImageUris.add(uri);
                    attachedMediaIds.add(mediaId);
                    if (firstAttachedBitmap == null) firstAttachedBitmap = bm;

                    addAttachedThumbnailCard(uri, mediaId, bm);

                    hsvAttachedImages.setVisibility(View.VISIBLE);
                    btnClearImage.setVisibility(View.VISIBLE);
                    tvAttachedImageName.setText("Đã đính kèm " + attachedMediaIds.size() + " ảnh mẫu");
                    tvAttachedImageName.setTextColor(0xFF81C784);

                    updateStyleButtonsDisplay();
                    updateSizeButtonsDisplay();
                    updateDimensionsAndPreview();
                    logMessage("Tải ảnh mẫu lên PixAI thành công. Media ID: " + mediaId);
                    Toast.makeText(this, "Đã thêm ảnh tham chiếu!", Toast.LENGTH_SHORT).show();
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

    private void addAttachedThumbnailCard(Uri uri, String mediaId, Bitmap bm) {
        float density = getResources().getDisplayMetrics().density;

        FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams((int) (64 * density), (int) (64 * density));
        cardLp.setMarginEnd((int) (8 * density));
        card.setLayoutParams(cardLp);
        card.setBackgroundResource(R.drawable.bg_cyber_card);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (bm != null) iv.setImageBitmap(bm);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        card.addView(iv, ivLp);

        // Nút xóa 'X'
        Button btnDel = new Button(this);
        btnDel.setText("X");
        btnDel.setTextColor(0xFFFFFFFF);
        btnDel.setTextSize(10);
        btnDel.setBackgroundColor(0xCC000000);
        FrameLayout.LayoutParams delLp = new FrameLayout.LayoutParams((int) (22 * density), (int) (22 * density));
        delLp.gravity = Gravity.TOP | Gravity.END;
        btnDel.setLayoutParams(delLp);
        btnDel.setPadding(0, 0, 0, 0);

        btnDel.setOnClickListener(v -> {
            attachedMediaIds.remove(mediaId);
            attachedImageUris.remove(uri);
            layoutAttachedThumbnails.removeView(card);

            if (attachedMediaIds.isEmpty()) {
                firstAttachedBitmap = null;
                hsvAttachedImages.setVisibility(View.GONE);
                btnClearImage.setVisibility(View.GONE);
                tvAttachedImageName.setText("Chưa đính kèm ảnh (Tạo từ văn bản, bật LoRA Thực Tế)");
                tvAttachedImageName.setTextColor(0xFF9E9E9E);
                if ("auto".equals(currentSizeTier)) {
                    selectSizeTier("xl");
                }
            } else {
                tvAttachedImageName.setText("Đã đính kèm " + attachedMediaIds.size() + " ảnh mẫu");
            }
            updateStyleButtonsDisplay();
            updateSizeButtonsDisplay();
            updateDimensionsAndPreview();
            logMessage("Đã xóa 1 ảnh tham chiếu. Còn lại: " + attachedMediaIds.size() + " ảnh.");
        });

        card.addView(btnDel);
        layoutAttachedThumbnails.addView(card);
    }

    // --- GENERATE ACTION ---
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

        AccountModel account = storage.findEligibleAccount(requiredCost);
        if (account == null) {
            Toast.makeText(this, "Tất cả tài khoản đều không đủ " + requiredCost + " Credits! Hãy nhận thưởng hoặc đăng ký tài khoản mới.", Toast.LENGTH_LONG).show();
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

        List<String> contextList = new ArrayList<>(attachedMediaIds);

        executor.execute(() -> executeGenerationWithRetry(account, prompt, neg, proxy, requiredCost, contextList));
    }

    private void executeGenerationWithRetry(AccountModel account, String prompt, String neg, String proxy, long requiredCost, List<String> contextList) {
        try {
            // Mở khóa 18+ với bằng chứng GraphQL
            StringBuilder proof = new StringBuilder();
            PixAiApiHelper.unblurNsfw(account.getToken(), proxy, proof);
            mainHandler.post(() -> logMessage("Xác thực 18+ cho " + account.getEmail() + ": " + proof.toString()));

            mainHandler.post(() -> tvGenStatus.setText("Bước 2/4: Đang gửi lệnh khởi tạo tới GPU PixAI (" + currentProfile.toUpperCase() + ")..."));

            PixAiApiHelper.TaskResult res = PixAiApiHelper.createAndPollGenerationTask(
                    account.getToken(),
                    currentProfile,
                    isReal,
                    "standard",
                    selectedWidth,
                    selectedHeight,
                    batchCount,
                    prompt,
                    neg,
                    contextList,
                    proxy,
                    (msg, sec) -> mainHandler.post(() -> tvGenStatus.setText("Bước 3/4: " + msg))
            );

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
                    executeGenerationWithRetry(nextAcc, prompt, neg, proxy, requiredCost, contextList);
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

                    account.setCredits(Math.max(0, account.getCredits() - requiredCost));
                    storage.addAccount(account);
                    refreshHeader();
                    updateCostBadge();

                    lastOriginalPrompt = res.originalPrompt;
                    lastServerPrompt = res.serverPrompt;
                    currentResultImages.clear();
                    currentResultImages.addAll(res.imageUrls);
                    currentCarouselIdx = 0;

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
                    if (bitmap != null) ivResultPreview.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {}
        });
    }

    private void openFullscreenZoom(String imageUrl) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF0A0A0A);
        container.setPadding(16, 16, 16, 16);

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

        btnAddAccountDirect.setOnClickListener(v -> {
            String input = etNewAccountToken.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập Bearer Token!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (input.startsWith("Bearer ")) input = input.substring(7).trim();
            final String token = input;

            Toast.makeText(this, "Đang kiểm tra và thêm tài khoản...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                long quota = PixAiApiHelper.getQuota(token, proxy);
                String email = "acc_" + (System.currentTimeMillis() % 100000) + "@nexus.com";
                AccountModel acc = new AccountModel(email, token, quota);
                storage.addAccount(acc);

                mainHandler.post(() -> {
                    etNewAccountToken.setText("");
                    refreshAccountsList();
                    refreshHeader();
                    updateCostBadge();
                    logMessage("Đã thêm tài khoản: " + email + " với " + quota + " CR");
                    Toast.makeText(this, "Thêm tài khoản thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // TỰ ĐĂNG KÝ TÀI KHOẢN MỚI TỰ ĐỘNG
        btnAutoRegAcc.setOnClickListener(v -> {
            Toast.makeText(this, "Đang khởi tạo trình đăng ký PixAI tự động...", Toast.LENGTH_SHORT).show();
            logMessage("Bắt đầu quy trình tự đăng ký tài khoản mới...");

            String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
            String randomEmail = "user" + System.currentTimeMillis() + "@gmail.com";
            String randomPass = "Nexus@" + (System.currentTimeMillis() % 1000000) + "X!";

            HeadlessAccountRegistrar registrar = new HeadlessAccountRegistrar(this);
            registrar.startHeadlessRegistration(randomEmail, randomPass, new HeadlessAccountRegistrar.RegistrationCallback() {
                @Override
                public void onProgress(String message, String color) {
                    logMessage(message);
                }

                @Override
                public void onSuccess(String email, String password, String token) {
                    logMessage("Đăng ký thành công: " + email + ". Đang tự nhận thưởng và mở khóa 18+...");
                    executor.execute(() -> {
                        PixAiApiHelper.claimDailyCredits(token, proxy);
                        PixAiApiHelper.claimSocialAndVisitQuests(token, proxy);
                        PixAiApiHelper.unblurNsfw(token, proxy);
                        long quota = PixAiApiHelper.getQuota(token, proxy);
                        AccountModel acc = new AccountModel(email, password, token, quota);
                        storage.addAccount(acc);

                        mainHandler.post(() -> {
                            refreshAccountsList();
                            refreshHeader();
                            updateCostBadge();
                            logMessage("Tự đăng ký hoàn tất tài khoản: " + email + " với " + quota + " CR.");
                            Toast.makeText(MainActivity.this, "Tự đăng ký thành công: " + email + " (" + quota + " CR)", Toast.LENGTH_LONG).show();
                        });
                    });
                }

                @Override
                public void onError(String error) {
                    logMessage("Đăng ký thất bại: " + error);
                    Toast.makeText(MainActivity.this, "Lỗi đăng ký: " + error, Toast.LENGTH_LONG).show();
                }
            });
        });

        // NHẬP TEXT LIST HÀNG LOẠT
        btnImportText.setOnClickListener(v -> showImportTextDialog());

        // NHẬP JSON
        btnImportJson.setOnClickListener(v -> showImportJsonDialog());

        // XUẤT JSON
        btnExportJson.setOnClickListener(v -> exportAccountsJson());

        // Đồng bộ số dư
        btnSyncAllCredits.setOnClickListener(v -> {
            Toast.makeText(this, "Đang đồng bộ số dư toàn bộ tài khoản...", Toast.LENGTH_SHORT).show();
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
                    updateCostBadge();
                    logMessage("Đồng bộ số dư toàn bộ tài khoản hoàn tất.");
                    Toast.makeText(this, "Đồng bộ số dư thành công!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Nhận thưởng Daily
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
                    updateCostBadge();
                    logMessage("Nhận Daily hoàn tất: Thành công " + totalSucc + " tài khoản.");
                    Toast.makeText(this, "Nhận Daily hoàn tất cho " + totalSucc + " tài khoản!", Toast.LENGTH_SHORT).show();
                });
            });
        });

        // Nhận All Thưởng Quests
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
                    updateCostBadge();
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
            updateCostBadge();
            logMessage("Đã xóa toàn bộ tài khoản khỏi ứng dụng.");
            Toast.makeText(this, "Đã xóa toàn bộ tài khoản!", Toast.LENGTH_SHORT).show();
        });
    }

    private void showImportTextDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Nhập Token Hàng Loạt (Text)");

        final EditText input = new EditText(this);
        input.setHint("Dán mỗi dòng 1 token hoặc email:token...");
        input.setMinLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        builder.setView(input);

        builder.setPositiveButton("Nhập", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            int count = storage.importAccountsFromTextLines(text);
            refreshAccountsList();
            refreshHeader();
            updateCostBadge();
            logMessage("Đã nhập hàng loạt " + count + " tài khoản từ text.");
            Toast.makeText(this, "Đã nhập thành công " + count + " tài khoản!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showImportJsonDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Nhập Danh Sách Tài Khoản (JSON)");

        final EditText input = new EditText(this);
        input.setHint("Dán nội dung JSON đã sao lưu vào đây...");
        input.setMinLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        builder.setView(input);

        builder.setPositiveButton("Nhập JSON", (dialog, which) -> {
            String jsonStr = input.getText().toString().trim();
            if (jsonStr.isEmpty()) return;
            int count = storage.importAccountsFromJsonString(jsonStr);
            refreshAccountsList();
            refreshHeader();
            updateCostBadge();
            logMessage("Đã nhập " + count + " tài khoản từ backup JSON.");
            Toast.makeText(this, "Đã nhập thành công " + count + " tài khoản!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void exportAccountsJson() {
        String json = storage.exportAccountsToJsonString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText("Nexus Accounts Backup", json);
        cm.setPrimaryClip(cd);

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File backupFile = new File(downloads, "nexus_accounts_backup.json");
            FileOutputStream fos = new FileOutputStream(backupFile);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();
            logMessage("Đã xuất JSON ra file: " + backupFile.getAbsolutePath());
            Toast.makeText(this, "Đã lưu vào Downloads/nexus_accounts_backup.json & Copy vào Clipboard!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            logMessage("Đã sao chép JSON vào Clipboard: " + e.getMessage());
            Toast.makeText(this, "Đã sao chép danh sách JSON vào Clipboard!", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshAccountsList() {
        if (accountAdapter != null) {
            accountAdapter.clear();
            accountAdapter.addAll(storage.getAccounts());
            accountAdapter.notifyDataSetChanged();
        }
    }

    // --- TAB 3: KHO LỊCH SỬ MÃ HÓA & BẢO MẬT PIN ---
    private void setupHistoryActions() {
        historyAdapter = new HistoryAdapter(this, new ArrayList<>());
        lvHistory.setAdapter(historyAdapter);

        btnHistorySecurity.setOnClickListener(v -> showHistorySecurityDialog());

        btnUnlockHistory.setOnClickListener(v -> {
            String entered = etHistoryUnlockPin.getText().toString().trim();
            if (storage.checkHistoryPassword(entered)) {
                isHistoryUnlocked = true;
                etHistoryUnlockPin.setText("");
                layoutHistoryLocked.setVisibility(View.GONE);
                layoutHistoryContent.setVisibility(View.VISIBLE);
                refreshHistoryList();
                Toast.makeText(this, "Mở khóa kho lịch sử thành công!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Mật khẩu / PIN không chính xác!", Toast.LENGTH_SHORT).show();
            }
        });

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

    private void checkHistoryLockAndRefresh() {
        if (storage.isHistoryPasswordEnabled() && !isHistoryUnlocked) {
            layoutHistoryLocked.setVisibility(View.VISIBLE);
            layoutHistoryContent.setVisibility(View.GONE);
        } else {
            layoutHistoryLocked.setVisibility(View.GONE);
            layoutHistoryContent.setVisibility(View.VISIBLE);
            refreshHistoryList();
        }
    }

    private void showHistorySecurityDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Thiết Lập Bảo Mật Kho Lịch Sử");

        if (storage.isHistoryPasswordEnabled()) {
            builder.setMessage("Kho lịch sử đang được bảo vệ bằng mật khẩu.\nBạn muốn gỡ bỏ hay đổi mật khẩu?");
            builder.setPositiveButton("Gỡ Mật Khẩu", (d, w) -> {
                storage.removeHistoryPassword();
                isHistoryUnlocked = true;
                checkHistoryLockAndRefresh();
                Toast.makeText(this, "Đã gỡ bảo mật mật khẩu!", Toast.LENGTH_SHORT).show();
            });
            builder.setNegativeButton("Đóng", (d, w) -> d.cancel());
        } else {
            final EditText input = new EditText(this);
            input.setHint("Nhập mật khẩu hoặc mã PIN bảo vệ");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            builder.setView(input);

            builder.setPositiveButton("Đặt Mật Khẩu", (d, w) -> {
                String pass = input.getText().toString().trim();
                if (!pass.isEmpty()) {
                    storage.setHistoryPassword(pass);
                    isHistoryUnlocked = false;
                    checkHistoryLockAndRefresh();
                    Toast.makeText(this, "Đã kích hoạt mật khẩu bảo vệ kho lịch sử!", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Hủy", (d, w) -> d.cancel());
        }
        builder.show();
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
            updateCostBadge();
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

    // --- ADAPTERS ---
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
                updateCostBadge();
                Toast.makeText(getContext(), "Đã chọn tài khoản: " + item.getEmail(), Toast.LENGTH_SHORT).show();
            });

            btnDelete.setOnClickListener(v -> {
                storage.deleteAccount(item.getEmail());
                refreshAccountsList();
                refreshHeader();
                updateCostBadge();
                Toast.makeText(getContext(), "Đã xóa tài khoản!", Toast.LENGTH_SHORT).show();
            });

            return convertView;
        }
    }

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
