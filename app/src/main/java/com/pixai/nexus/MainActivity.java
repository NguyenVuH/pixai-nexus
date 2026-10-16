package com.pixai.nexus;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
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

    // Aspect Ratio Cards with individual visual geometric shapes
    private static class RatioCardItem {
        View card;
        View shape;
        TextView label;
        String ratio;
        RatioCardItem(View card, View shape, TextView label, String ratio) {
            this.card = card;
            this.shape = shape;
            this.label = label;
            this.ratio = ratio;
        }
    }
    private final List<RatioCardItem> ratioCards = new ArrayList<>();
    private View cardRatioAuto;
    private View shapeRatioAuto;
    private TextView tvLabelRatioAuto;
    private TextView tvRatioInfo;
    private TextView tvTotalAccountsSummary, tvTotalCreditsSummary;

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
    private LinearLayout layoutActiveTasksQueue;
    private LinearLayout containerActiveTasks;

    // Prompt Copy within Prompt Card
    private View btnCopyPrompt, btnCopyNegative;

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
        setupAccountManagementActions();
        setupHistoryActions();
        setupSecurityTabActions();
        setupLogActions();

        refreshHeader();
        updateDimensionsAndPreview();
        updateCostBadge();
        resumePendingTasks();
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

        ratioCards.clear();
        cardRatioAuto = findViewById(R.id.cardRatioAuto);
        shapeRatioAuto = findViewById(R.id.shapeRatioAuto);
        tvLabelRatioAuto = findViewById(R.id.tvLabelRatioAuto);

        bindRatioCard(R.id.cardRatio9_16, R.id.shapeRatio9_16, R.id.tvLabelRatio9_16, "9:16");
        bindRatioCard(R.id.cardRatio16_9, R.id.shapeRatio16_9, R.id.tvLabelRatio16_9, "16:9");
        bindRatioCard(R.id.cardRatio1_1, R.id.shapeRatio1_1, R.id.tvLabelRatio1_1, "1:1");
        bindRatioCard(R.id.cardRatio3_4, R.id.shapeRatio3_4, R.id.tvLabelRatio3_4, "3:4");
        bindRatioCard(R.id.cardRatio4_3, R.id.shapeRatio4_3, R.id.tvLabelRatio4_3, "4:3");
        bindRatioCard(R.id.cardRatio2_3, R.id.shapeRatio2_3, R.id.tvLabelRatio2_3, "2:3");
        bindRatioCard(R.id.cardRatio3_2, R.id.shapeRatio3_2, R.id.tvLabelRatio3_2, "3:2");
        bindRatioCard(R.id.cardRatio3_5, R.id.shapeRatio3_5, R.id.tvLabelRatio3_5, "3:5");
        bindRatioCard(R.id.cardRatio5_3, R.id.shapeRatio5_3, R.id.tvLabelRatio5_3, "5:3");
        bindRatioCard(R.id.cardRatio7_9, R.id.shapeRatio7_9, R.id.tvLabelRatio7_9, "7:9");
        bindRatioCard(R.id.cardRatio9_7, R.id.shapeRatio9_7, R.id.tvLabelRatio9_7, "9:7");
        bindRatioCard(R.id.cardRatio1_3, R.id.shapeRatio1_3, R.id.tvLabelRatio1_3, "1:3");
        bindRatioCard(R.id.cardRatio3_1, R.id.shapeRatio3_1, R.id.tvLabelRatio3_1, "3:1");

        tvRatioInfo = findViewById(R.id.tvRatioInfo);
        tvTotalAccountsSummary = findViewById(R.id.tvTotalAccountsSummary);
        tvTotalCreditsSummary = findViewById(R.id.tvTotalCreditsSummary);

        btnBatch1 = findViewById(R.id.btnBatch1);
        btnBatch4 = findViewById(R.id.btnBatch4);

        btnPickImage = findViewById(R.id.btnPickImage);
        btnClearImage = findViewById(R.id.btnClearImage);
        hsvAttachedImages = findViewById(R.id.hsvAttachedImages);
        layoutAttachedThumbnails = findViewById(R.id.layoutAttachedThumbnails);
        tvAttachedImageName = findViewById(R.id.tvAttachedImageName);

        etPrompt = findViewById(R.id.etPrompt);
        etNegativePrompt = findViewById(R.id.etNegativePrompt);
        btnCopyPrompt = findViewById(R.id.btnCopyPrompt);
        btnCopyNegative = findViewById(R.id.btnCopyNegative);
        tvCostBadge = findViewById(R.id.tvCostBadge);
        pbGenProgress = findViewById(R.id.pbGenProgress);
        tvGenStatus = findViewById(R.id.tvGenStatus);
        btnGenerate = findViewById(R.id.btnGenerate);
        layoutActiveTasksQueue = findViewById(R.id.layoutActiveTasksQueue);
        containerActiveTasks = findViewById(R.id.containerActiveTasks);

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
            updateProfileButtonsDisplay();
            updateCostBadge();
        });

        btnProfileUltra.setOnClickListener(v -> {
            currentProfile = "ultra";
            storage.setSavedProfile("ultra");
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

    private void bindRatioCard(int cardId, int shapeId, int labelId, String ratio) {
        View card = findViewById(cardId);
        View shape = findViewById(shapeId);
        TextView label = findViewById(labelId);
        if (card != null) {
            ratioCards.add(new RatioCardItem(card, shape, label, ratio));
        }
    }

    private void setupRatioButtons() {
        for (RatioCardItem item : ratioCards) {
            item.card.setOnClickListener(v -> {
                currentRatio = item.ratio;
                if ("auto".equals(currentSizeTier)) {
                    currentSizeTier = "xl";
                    storage.setSavedSizeTier("xl");
                    updateSizeButtonsDisplay();
                }
                updateRatioButtonsDisplay();
                updateDimensionsAndPreview();
                updateCostBadge();
            });
        }
        if (cardRatioAuto != null) {
            cardRatioAuto.setOnClickListener(v -> selectSizeTier("auto"));
        }
        updateRatioButtonsDisplay();
    }

    private void updateRatioButtonsDisplay() {
        for (RatioCardItem item : ratioCards) {
            boolean active = item.ratio.equals(currentRatio) && !"auto".equals(currentSizeTier);
            item.card.setBackgroundResource(active ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            if (item.shape != null) {
                item.shape.setBackgroundResource(active ? R.drawable.bg_ratio_box_active : R.drawable.bg_ratio_box_normal);
            }
            if (item.label != null) {
                item.label.setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
            }
        }

        if (cardRatioAuto != null) {
            boolean autoActive = "auto".equals(currentSizeTier);
            cardRatioAuto.setVisibility(!attachedMediaIds.isEmpty() ? View.VISIBLE : View.GONE);
            cardRatioAuto.setBackgroundResource(autoActive ? R.drawable.bg_cyber_chip_active : R.drawable.bg_cyber_chip_normal);
            if (shapeRatioAuto != null) {
                shapeRatioAuto.setBackgroundResource(autoActive ? R.drawable.bg_ratio_box_active : R.drawable.bg_ratio_box_normal);
            }
            if (tvLabelRatioAuto != null) {
                tvLabelRatioAuto.setTextColor(autoActive ? 0xFFFFFFFF : 0xFF9E9E9E);
            }
        }
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
            if (tvRatioInfo != null) {
                tvRatioInfo.setText("Tùy Chỉnh Size • Kích thước: " + cw + " x " + ch + " px");
            }
        } else if ("auto".equals(currentSizeTier) && firstAttachedBitmap != null) {
            int bw = firstAttachedBitmap.getWidth();
            int bh = firstAttachedBitmap.getHeight();
            double scale = Math.sqrt((1024.0 * 1024.0) / (double)(bw * bh));
            int aw = (int) (bw * scale);
            int ah = (int) (bh * scale);
            aw = Math.max(384, (aw / 16) * 16);
            ah = Math.max(384, (ah / 16) * 16);
            selectedWidth = aw;
            selectedHeight = ah;
            if (tvRatioInfo != null) {
                tvRatioInfo.setText("Auto Theo Ảnh Mẫu • Kích thước: " + aw + " x " + ah + " px (Gốc: " + bw + "x" + bh + ")");
            }
        } else {
            int[] dims = calculatePresetDimensions(currentRatio, currentSizeTier);
            selectedWidth = dims[0];
            selectedHeight = dims[1];
            if (tvRatioInfo != null) {
                tvRatioInfo.setText("Tỷ lệ: " + currentRatio + " • Kích thước: " + selectedWidth + " x " + selectedHeight + " px (" + currentSizeTier.toUpperCase() + ")");
            }
        }
        updateCostBadge();
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

    private void setupBatchButtons() {
        btnBatch1.setOnClickListener(v -> {
            batchCount = 1;
            storage.setSavedBatch(1);
            updateBatchButtonsDisplay();
            updateCostBadge();
        });

        btnBatch4.setOnClickListener(v -> {
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

    private long calculateRequiredCost() {
        return PixAiApiHelper.estimateTaskCost(currentProfile, currentSizeTier, selectedWidth, selectedHeight, batchCount, isReal && attachedMediaIds.isEmpty());
    }

    private void updateCostBadge() {
        long cost = calculateRequiredCost();
        AccountModel active = storage.getActiveAccount();
        if (active != null && active.getCredits() < cost && storage.isAutoRotateAccounts()) {
            AccountModel eligible = storage.findEligibleAccount(cost);
            if (eligible != null && !eligible.getId().equals(active.getId())) {
                storage.setActiveEmail(eligible.getEmail());
                refreshHeader();
                logMessage("Tự động xoay sang tài khoản " + eligible.getEmail() + " có " + eligible.getCredits() + " CR (đủ cho chi phí " + cost + " CR).");
                active = eligible;
            }
        }

        AccountModel currentActive = storage.getActiveAccount();
        if (currentActive != null && currentActive.getCredits() >= cost) {
            tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " CR | Sẵn sàng: " + currentActive.getEmail() + " (" + String.format(Locale.US, "%,d", currentActive.getCredits()) + " CR)");
            tvCostBadge.setTextColor(0xFF81C784);
            btnGenerate.setEnabled(true);
        } else {
            AccountModel eligibleAny = storage.findEligibleAccount(cost);
            if (eligibleAny != null) {
                tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " CR | Hiện tại thiếu CR, khả dụng: " + eligibleAny.getEmail() + " (" + String.format(Locale.US, "%,d", eligibleAny.getCredits()) + " CR)");
                tvCostBadge.setTextColor(0xFFFFB74D);
                btnGenerate.setEnabled(true);
            } else {
                tvCostBadge.setText("Chi phí: " + String.format(Locale.US, "%,d", cost) + " CR | Cảnh báo: Không có tài khoản nào đủ " + String.format(Locale.US, "%,d", cost) + " CR");
                tvCostBadge.setTextColor(0xFFE57373);
                btnGenerate.setEnabled(false);
            }
        }
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

        if (btnCopyPrompt != null) {
            btnCopyPrompt.setOnClickListener(v -> {
                String text = etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    Toast.makeText(this, "Prompt đang trống!", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Prompt", text);
                cm.setPrimaryClip(cd);
                Toast.makeText(this, "Đã sao chép Prompt!", Toast.LENGTH_SHORT).show();
            });
        }

        if (btnCopyNegative != null) {
            btnCopyNegative.setOnClickListener(v -> {
                String text = etNegativePrompt.getText() != null ? etNegativePrompt.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    Toast.makeText(this, "Negative Prompt đang trống!", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Negative Prompt", text);
                cm.setPrimaryClip(cd);
                Toast.makeText(this, "Đã sao chép Negative Prompt!", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void startGenerationTask() {
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

        String neg = etNegativePrompt.getText().toString().trim();
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
        List<String> contextList = new ArrayList<>(attachedMediaIds);

        // Nút Tạo Ảnh không bị khóa, cho phép bấm tạo tiếp các tiến trình song song!
        tvGenStatus.setText("Đang khởi tạo tác vụ mới trên GPU PixAI (" + currentProfile.toUpperCase() + ")...");
        tvGenStatus.setTextColor(0xFFFFFFFF);

        executor.execute(() -> launchTaskPipeline(account, prompt, neg, proxy, requiredCost, contextList));
    }

    private void launchTaskPipeline(AccountModel account, String prompt, String neg, String proxy, long requiredCost, List<String> contextList) {
        try {
            // Mở khóa 18+ ngầm
            StringBuilder proof = new StringBuilder();
            PixAiApiHelper.unblurNsfw(account.getToken(), proxy, proof);
            mainHandler.post(() -> logMessage("Xác thực 18+ cho " + account.getEmail() + ": " + proof.toString()));

            // Gửi GraphQL tạo tác vụ với priority 1000
            PixAiApiHelper.CreateTaskResult cr = PixAiApiHelper.createGenerationTask(
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
                    proxy
            );

            if (cr.insufficientBalance) {
                mainHandler.post(() -> logMessage("Tài khoản " + account.getEmail() + " không đủ credit trên máy chủ PixAI! Đang tự động xoay tài khoản..."));
                account.setCredits(0);
                storage.addAccount(account);

                AccountModel nextAcc = storage.findEligibleAccount(requiredCost);
                if (nextAcc != null && !nextAcc.getEmail().equalsIgnoreCase(account.getEmail())) {
                    storage.setActiveEmail(nextAcc.getEmail());
                    mainHandler.post(() -> {
                        refreshHeader();
                        updateCostBadge();
                    });
                    launchTaskPipeline(nextAcc, prompt, neg, proxy, requiredCost, contextList);
                    return;
                } else {
                    mainHandler.post(() -> {
                        tvGenStatus.setText("Lỗi: Toàn bộ tài khoản đã cạn kiệt Credit!");
                        tvGenStatus.setTextColor(0xFFE57373);
                        Toast.makeText(this, "Tất cả tài khoản đều đã hết Credit!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }

            if (!cr.success || cr.taskId == null || cr.taskId.isEmpty()) {
                mainHandler.post(() -> {
                    tvGenStatus.setText("Thất bại: " + cr.errorMessage);
                    tvGenStatus.setTextColor(0xFFE57373);
                    logMessage("Khởi tạo thất bại: " + cr.errorMessage);
                    Toast.makeText(this, "Lỗi tạo task: " + cr.errorMessage, Toast.LENGTH_LONG).show();
                });
                return;
            }

            // Trừ credit đã sử dụng và cập nhật UI
            long deducted = (cr.paidCredit > 0) ? cr.paidCredit : requiredCost;
            account.setCredits(Math.max(0, account.getCredits() - deducted));
            storage.addAccount(account);
            mainHandler.post(() -> {
                refreshHeader();
                updateCostBadge();
            });

            // LƯU NGAY VÀO KHO LỊCH SỬ MÃ HÓA VỚI TRẠNG THÁI PENDING (KHÔNG SỢ MẤT)
            final String taskId = cr.taskId;
            HistoryItem pendingItem = new HistoryItem(taskId, prompt, "Tsubaki 3", currentProfile, isReal, selectedWidth, selectedHeight);
            storage.addHistoryItem(pendingItem);

            mainHandler.post(() -> {
                refreshHistoryList();
                logMessage("Đã khởi tạo thành công Task #" + taskId + " (" + currentProfile.toUpperCase() + "). Đã lưu vào lịch sử.");
                tvGenStatus.setText("Task #" + taskId.substring(Math.max(0, taskId.length() - 6)) + " đã vào hàng đợi GPU.");
                tvGenStatus.setTextColor(0xFF81C784);

                // Thêm thẻ tiến trình vào danh sách (Cũ ở trên, Mới ở dưới)
                attachTaskCardToQueue(pendingItem, account.getToken(), proxy);
            });

        } catch (Exception e) {
            mainHandler.post(() -> {
                tvGenStatus.setText("Lỗi kết nối: " + e.getMessage());
                tvGenStatus.setTextColor(0xFFE57373);
                logMessage("Lỗi ngoại lệ: " + e.getMessage());
            });
        }
    }

    private void attachTaskCardToQueue(HistoryItem item, String token, String proxy) {
        layoutActiveTasksQueue.setVisibility(View.VISIBLE);

        View card = LayoutInflater.from(this).inflate(R.layout.item_active_task, containerActiveTasks, false);
        View layoutHeaderClick = card.findViewById(R.id.layoutTaskHeaderClickable);
        TextView tvHeader = card.findViewById(R.id.tvTaskHeader);
        TextView tvBadge = card.findViewById(R.id.tvTaskStatusBadge);
        TextView tvPrompt = card.findViewById(R.id.tvTaskPrompt);
        TextView tvExpandText = card.findViewById(R.id.tvTaskExpandText);
        ImageView ivExpandToggle = card.findViewById(R.id.ivTaskExpandToggle);
        View layoutExpanded = card.findViewById(R.id.layoutTaskExpandedDetails);
        TextView tvFullOriginal = card.findViewById(R.id.tvTaskFullOriginal);
        TextView tvFullServer = card.findViewById(R.id.tvTaskFullServer);
        View btnCopyOrig = card.findViewById(R.id.btnTaskCopyOriginal);
        View btnCopyServ = card.findViewById(R.id.btnTaskCopyServer);
        ProgressBar pb = card.findViewById(R.id.pbTaskProgress);
        LinearLayout layoutTaskImagesFeed = card.findViewById(R.id.layoutTaskImagesFeed);

        String tidShort = item.getTaskId();
        if (tidShort.length() > 8) tidShort = tidShort.substring(tidShort.length() - 8);
        tvHeader.setText("Task #" + tidShort + " • " + item.getProfile().toUpperCase() + " • " + item.getWidth() + "x" + item.getHeight());
        tvPrompt.setText(item.getOriginalPrompt());
        if (tvFullOriginal != null) tvFullOriginal.setText(item.getOriginalPrompt());

        // Xử lý đóng / mở rộng chi tiết của phiên tiến trình
        final boolean[] isExp = {false};
        View.OnClickListener toggleClick = v -> {
            isExp[0] = !isExp[0];
            if (layoutExpanded != null) layoutExpanded.setVisibility(isExp[0] ? View.VISIBLE : View.GONE);
            if (tvExpandText != null) tvExpandText.setText(isExp[0] ? "Thu Gọn" : "Mở Rộng");
            if (ivExpandToggle != null) ivExpandToggle.setImageResource(isExp[0] ? R.drawable.ic_collapse : R.drawable.ic_expand);
        };
        if (layoutHeaderClick != null) layoutHeaderClick.setOnClickListener(toggleClick);

        // Nút sao chép Prompt Gốc
        if (btnCopyOrig != null) {
            btnCopyOrig.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Original Prompt", item.getOriginalPrompt());
                cm.setPrimaryClip(cd);
                Toast.makeText(this, "Đã sao chép Prompt Gốc!", Toast.LENGTH_SHORT).show();
            });
        }

        // Nút sao chép Prompt Tối Ưu
        if (btnCopyServ != null) {
            btnCopyServ.setOnClickListener(v -> {
                String sp = item.getServerPrompt();
                if (sp == null || sp.isEmpty()) sp = item.getOriginalPrompt();
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("Server Prompt", sp);
                cm.setPrimaryClip(cd);
                Toast.makeText(this, "Đã sao chép Prompt Máy Chủ Tối Ưu!", Toast.LENGTH_SHORT).show();
            });
        }

        // Thêm vào đầu container để tiến trình mới tạo luôn hiện ở TRÊN CÙNG
        containerActiveTasks.addView(card, 0);

        // Khởi chạy vòng lặp Polling ngầm riêng biệt cho task này (Không timeout chặn)
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            boolean finished = false;

            while (!finished) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {}

                int elapsed = (int) ((System.currentTimeMillis() - startTime) / 1000);
                PixAiApiHelper.PollStatusResult pr = PixAiApiHelper.pollTaskMedia(item.getTaskId(), token, proxy);

                mainHandler.post(() -> {
                    if ("running".equalsIgnoreCase(pr.status)) {
                        tvBadge.setText("Đang render (" + elapsed + "s)...");
                        tvBadge.setTextColor(0xFF64B5F6);
                    } else if ("waiting".equalsIgnoreCase(pr.status)) {
                        tvBadge.setText("Đang chờ GPU (" + elapsed + "s)...");
                        tvBadge.setTextColor(0xFFFFA726);
                    }
                });

                if (pr.completed && !pr.imageUrls.isEmpty()) {
                    finished = true;
                    item.setStatus(HistoryItem.STATUS_COMPLETED);
                    item.setImageUrls(pr.imageUrls);
                    item.setServerPrompt(pr.serverPrompt.isEmpty() ? item.getOriginalPrompt() : pr.serverPrompt);
                    item.setSeed(pr.seed);
                    storage.updateHistoryItem(item);

                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        tvBadge.setText("Hoàn tất (" + elapsed + "s)");
                        tvBadge.setTextColor(0xFF81C784);
                        if (tvFullServer != null) {
                            tvFullServer.setText(item.getServerPrompt());
                            tvFullServer.setTextColor(0xFF81C784);
                        }

                        // Tự động mở rộng phiên để hiển thị đầy đủ hình ảnh kết quả ngay tại phiên
                        isExp[0] = true;
                        if (layoutExpanded != null) layoutExpanded.setVisibility(View.VISIBLE);
                        if (tvExpandText != null) tvExpandText.setText("Thu Gọn");
                        if (ivExpandToggle != null) ivExpandToggle.setImageResource(R.drawable.ic_collapse);

                        // Hiển thị danh sách ảnh đầy đủ ngay trong thẻ phiên (layoutTaskImagesFeed)
                        if (layoutTaskImagesFeed != null) {
                            layoutTaskImagesFeed.removeAllViews();
                            layoutTaskImagesFeed.setVisibility(View.VISIBLE);
                            List<String> urls = pr.imageUrls;
                            int total = urls.size();
                            for (int i = 0; i < total; i++) {
                                final int idx = i;
                                final String u = urls.get(i);

                                LinearLayout imgCard = new LinearLayout(MainActivity.this);
                                imgCard.setOrientation(LinearLayout.VERTICAL);
                                imgCard.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                                int p = (int) (8 * getResources().getDisplayMetrics().density);
                                imgCard.setPadding(p, p, p, p);
                                imgCard.setClipChildren(true);
                                imgCard.setClipToPadding(true);
                                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                cardLp.setMargins(0, 0, 0, (int) (10 * getResources().getDisplayMetrics().density));
                                imgCard.setLayoutParams(cardLp);

                                TextView tvNum = new TextView(MainActivity.this);
                                tvNum.setText("Ảnh " + (idx + 1) + " / " + total + " • " + item.getWidth() + "x" + item.getHeight());
                                tvNum.setTextColor(0xFFCCCCCC);
                                tvNum.setTextSize(11);
                                tvNum.setTypeface(null, android.graphics.Typeface.BOLD);
                                imgCard.addView(tvNum);

                                PinchZoomImageView iv = new PinchZoomImageView(MainActivity.this);
                                int hPx = (int) (280 * getResources().getDisplayMetrics().density);
                                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx);
                                ivLp.setMargins(0, (int) (6 * getResources().getDisplayMetrics().density), 0, (int) (6 * getResources().getDisplayMetrics().density));
                                iv.setLayoutParams(ivLp);
                                iv.setBackgroundColor(0xFF141414);
                                loadImageIntoView(u, iv, item.getId() + "_task_" + idx);
                                imgCard.addView(iv);

                                // Thanh tác vụ tối giản (Hạn chế button, dùng chip tối giản)
                                LinearLayout actRow = new LinearLayout(MainActivity.this);
                                actRow.setOrientation(LinearLayout.HORIZONTAL);
                                actRow.setGravity(Gravity.CENTER_VERTICAL);
                                actRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                                TextView tvHint = new TextView(MainActivity.this);
                                tvHint.setText("Kéo 2 ngón tay để zoom • Chạm 2 lần");
                                tvHint.setTextColor(0xFF757575);
                                tvHint.setTextSize(10);
                                LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                                tvHint.setLayoutParams(thLp);
                                actRow.addView(tvHint);

                                // Chip Phóng To Toàn Màn
                                LinearLayout chipZ = new LinearLayout(MainActivity.this);
                                chipZ.setOrientation(LinearLayout.HORIZONTAL);
                                chipZ.setGravity(Gravity.CENTER);
                                chipZ.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                                chipZ.setClickable(true);
                                chipZ.setFocusable(true);
                                int pPadH = (int) (10 * getResources().getDisplayMetrics().density);
                                int pPadV = (int) (6 * getResources().getDisplayMetrics().density);
                                chipZ.setPadding(pPadH, pPadV, pPadH, pPadV);
                                LinearLayout.LayoutParams czLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                czLp.setMarginEnd((int) (6 * getResources().getDisplayMetrics().density));
                                chipZ.setLayoutParams(czLp);

                                ImageView ivZ = new ImageView(MainActivity.this);
                                ivZ.setLayoutParams(new LinearLayout.LayoutParams((int) (14 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density)));
                                ivZ.setImageResource(R.drawable.ic_zoom_in);
                                chipZ.addView(ivZ);

                                TextView tvZ = new TextView(MainActivity.this);
                                tvZ.setText(" Toàn Màn");
                                tvZ.setTextColor(0xFFFFFFFF);
                                tvZ.setTextSize(11);
                                chipZ.addView(tvZ);
                                chipZ.setOnClickListener(v -> openFullscreenZoom(u));
                                actRow.addView(chipZ);

                                // Chip Tải Về Máy
                                LinearLayout chipD = new LinearLayout(MainActivity.this);
                                chipD.setOrientation(LinearLayout.HORIZONTAL);
                                chipD.setGravity(Gravity.CENTER);
                                chipD.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                                chipD.setClickable(true);
                                chipD.setFocusable(true);
                                chipD.setPadding(pPadH, pPadV, pPadH, pPadV);

                                ImageView ivD = new ImageView(MainActivity.this);
                                ivD.setLayoutParams(new LinearLayout.LayoutParams((int) (14 * getResources().getDisplayMetrics().density), (int) (14 * getResources().getDisplayMetrics().density)));
                                ivD.setImageResource(R.drawable.ic_download);
                                chipD.addView(ivD);

                                TextView tvD = new TextView(MainActivity.this);
                                tvD.setText(" Tải Về");
                                tvD.setTextColor(0xFF81C784);
                                tvD.setTextSize(11);
                                chipD.addView(tvD);
                                chipD.setOnClickListener(v -> downloadImageToGallery(u));
                                actRow.addView(chipD);

                                imgCard.addView(actRow);
                                layoutTaskImagesFeed.addView(imgCard);
                            }
                        }

                        refreshHistoryList();
                        logMessage("Task #" + item.getTaskId() + " hoàn thành 100%! Đã nhận " + pr.imageUrls.size() + " ảnh.");
                        Toast.makeText(MainActivity.this, "Tạo ảnh hoàn tất (" + elapsed + "s)!", Toast.LENGTH_SHORT).show();
                    });
                } else if (pr.failed) {
                    finished = true;
                    item.setStatus(HistoryItem.STATUS_FAILED);
                    item.setErrorMessage(pr.errorMessage);
                    storage.updateHistoryItem(item);

                    mainHandler.post(() -> {
                        pb.setVisibility(View.GONE);
                        tvBadge.setText("Thất bại");
                        tvBadge.setTextColor(0xFFE57373);
                        logMessage("Task #" + item.getTaskId() + " thất bại: " + pr.errorMessage);
                    });
                } else if (elapsed > 1800) { // 30 phút tối đa để dọn tài nguyên
                    finished = true;
                }
            }
        });
    }

    private HttpURLConnection openConnectionWithAppProxy(URL url) throws Exception {
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
        return PixAiApiHelper.openConnectionWithProxy(url, proxy);
    }

    private void loadImageIntoView(String urlString, ImageView targetView, String cacheKey) {
        Bitmap cached = storage.getDecryptedImageCache(cacheKey);
        if (cached != null) {
            targetView.setImageBitmap(cached);
            return;
        }

        executor.execute(() -> {
            try {
                URL u = new URL(urlString);
                HttpURLConnection conn = openConnectionWithAppProxy(u);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                        byte[] rawBytes = bos.toByteArray();
                        storage.saveEncryptedImageCache(cacheKey, rawBytes);
                        Bitmap bm = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
                        mainHandler.post(() -> {
                            if (bm != null && targetView != null) {
                                targetView.setImageBitmap(bm);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void openFullscreenZoom(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            Toast.makeText(this, "Không có đường dẫn ảnh hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF0A0A0A);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        // Top bar cân đối 2 bên
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, (int) (12 * getResources().getDisplayMetrics().density));

        LinearLayout chipClose = new LinearLayout(this);
        chipClose.setOrientation(LinearLayout.HORIZONTAL);
        chipClose.setGravity(Gravity.CENTER);
        chipClose.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
        chipClose.setClickable(true);
        chipClose.setFocusable(true);
        int chipPadH = (int) (12 * getResources().getDisplayMetrics().density);
        int chipPadV = (int) (8 * getResources().getDisplayMetrics().density);
        chipClose.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
        ImageView ivClose = new ImageView(this);
        ivClose.setLayoutParams(new LinearLayout.LayoutParams((int) (16 * getResources().getDisplayMetrics().density), (int) (16 * getResources().getDisplayMetrics().density)));
        ivClose.setImageResource(R.drawable.ic_close);
        chipClose.addView(ivClose);
        TextView tvClose = new TextView(this);
        tvClose.setText(" Đóng");
        tvClose.setTextColor(0xFFE0E0E0);
        tvClose.setTextSize(11);
        chipClose.addView(tvClose);
        chipClose.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(chipClose);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("PHÓNG TO ẢNH");
        tvTitle.setTextColor(0xFFCCCCCC);
        tvTitle.setTextSize(12);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvTitleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(tvTitleLp);
        topBar.addView(tvTitle);

        LinearLayout chipSave = new LinearLayout(this);
        chipSave.setOrientation(LinearLayout.HORIZONTAL);
        chipSave.setGravity(Gravity.CENTER);
        chipSave.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
        chipSave.setClickable(true);
        chipSave.setFocusable(true);
        chipSave.setPadding(chipPadH, chipPadV, chipPadH, chipPadV);
        ImageView ivSave = new ImageView(this);
        ivSave.setLayoutParams(new LinearLayout.LayoutParams((int) (16 * getResources().getDisplayMetrics().density), (int) (16 * getResources().getDisplayMetrics().density)));
        ivSave.setImageResource(R.drawable.ic_download);
        chipSave.addView(ivSave);
        TextView tvSave = new TextView(this);
        tvSave.setText(" Tải Về");
        tvSave.setTextColor(0xFF81C784);
        tvSave.setTextSize(11);
        chipSave.addView(tvSave);
        chipSave.setOnClickListener(v -> downloadImageToGallery(imageUrl));
        topBar.addView(chipSave);

        container.addView(topBar);

        // FrameLayout chứa ảnh và ProgressBar tải ảnh
        FrameLayout flImage = new FrameLayout(this);
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        flImage.setLayoutParams(flLp);

        ProgressBar pbLoading = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        pbLoading.setLayoutParams(pbLp);
        flImage.addView(pbLoading);

        // Sử dụng PinchZoomImageView hỗ trợ phóng to bằng 2 ngón tay & double tap
        PinchZoomImageView iv = new PinchZoomImageView(this);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        iv.setLayoutParams(ivLp);
        flImage.addView(iv);

        container.addView(flImage);

        dialog.setContentView(container);
        dialog.show();

        executor.execute(() -> {
            try {
                URL u = new URL(imageUrl);
                HttpURLConnection conn = openConnectionWithAppProxy(u);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                InputStream is = conn.getInputStream();
                Bitmap bm = BitmapFactory.decodeStream(is);
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    if (bm != null) {
                        iv.setImageBitmap(bm);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(this, "Lỗi tải ảnh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void downloadImageToGallery(String imageUrl) {
        Toast.makeText(this, "Đang tải ảnh về máy...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = openConnectionWithAppProxy(url);
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
        btnAutoRegAcc.setOnClickListener(v -> startAutoRegisterPixAiHeadless(0));

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
                        totalQ += PixAiApiHelper.claimFeaturedQuests(a.getToken(), proxy);
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

        // Mở khóa 18+ toàn bộ kèm bằng chứng từ server
        btnUnblurNsfw.setOnClickListener(v -> {
            Toast.makeText(this, "Đang mở khóa 18+ và thu thập bằng chứng từ máy chủ PixAI...", Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                List<AccountModel> list = storage.getAccounts();
                String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
                StringBuilder allProofs = new StringBuilder();
                int successCount = 0;
                for (AccountModel a : list) {
                    if (a.getToken() != null && !a.getToken().isEmpty()) {
                        StringBuilder proof = new StringBuilder();
                        boolean ok = PixAiApiHelper.unblurNsfw(a.getToken(), proxy, proof);
                        if (ok) successCount++;
                        allProofs.append("=== TÀI KHOẢN: ").append(a.getEmail()).append(" ===\n")
                                 .append(proof.toString()).append("\n\n");
                    }
                }
                final String finalProofText = allProofs.toString();
                final int finalSucc = successCount;
                mainHandler.post(() -> {
                    logMessage("Đã mở khóa 18+ thành công cho " + finalSucc + "/" + list.size() + " tài khoản.");
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setTitle("BẰNG CHỨNG XÁC THỰC 18+ TỪ MÁY CHỦ PIXAI");
                    ScrollView sv = new ScrollView(MainActivity.this);
                    TextView tv = new TextView(MainActivity.this);
                    tv.setPadding(24, 24, 24, 24);
                    tv.setText(finalProofText.isEmpty() ? "Không có tài khoản nào được cấu hình." : finalProofText);
                    tv.setTextIsSelectable(true);
                    tv.setTextSize(11);
                    tv.setTextColor(0xFFE0E0E0);
                    sv.addView(tv);
                    builder.setView(sv);
                    builder.setPositiveButton("Đóng", (d, w) -> d.dismiss());
                    builder.show();
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

    private int pixaiMailProviderOffset = 0;

    private void startAutoRegisterPixAiHeadless(final int retryCount) {
        Toast.makeText(this, "Đang khởi tạo tiến trình đăng ký tài khoản tự động...", Toast.LENGTH_SHORT).show();
        logMessage("Bắt đầu tự động tạo tài khoản PixAI mới (lần " + (retryCount + 1) + "/3)...");

        executor.execute(() -> {
            try {
                final String proxy = storage.isUseProxy() ? storage.getProxyAddress() : "";
                final int mailOffset = (retryCount == 0) ? 0 : (pixaiMailProviderOffset++);

                logMessage("Đang cấp phát hòm thư Temp-Mail tương thích chuẩn PixAI...");
                final PixAiApiHelper.Mailbox mailbox = PixAiApiHelper.createMailboxMultiProvider(
                        proxy,
                        false,
                        mailOffset,
                        (msg, col) -> logMessage(msg)
                );
                if (mailbox == null || mailbox.address == null || mailbox.address.isEmpty()) {
                    mainHandler.post(() -> {
                        logMessage("Không thể cấp phát hòm thư Temp-Mail!");
                        Toast.makeText(MainActivity.this, "Lỗi cấp phát hòm thư Temp-Mail!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                final String regEmail = mailbox.address;
                final String regPass = PixAiApiHelper.makeSafePassword();
                logMessage("Đã cấp phát hòm thư chuẩn: " + regEmail + " (" + mailbox.provider + ")");
                logMessage("Khởi chạy tiến trình :registrar độc lập tạo tài khoản (Mật khẩu: " + regPass + ")...");

                android.os.ResultReceiver regReceiver = new android.os.ResultReceiver(mainHandler) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultData == null) return;
                        if (resultCode == HeadlessRegService.CODE_PROGRESS) {
                            String msg = resultData.getString("message", "");
                            logMessage(msg);
                        } else if (resultCode == HeadlessRegService.CODE_SUCCESS) {
                            String email = resultData.getString("email", regEmail);
                            String password = resultData.getString("password", regPass);
                            String token = resultData.getString("token", "");

                            executor.execute(() -> {
                                logMessage("Đã tạo tài khoản và trích xuất Token thành công từ tiến trình độc lập!");
                                try { Thread.sleep(800); } catch (InterruptedException ignored) {}

                                logMessage("Gọi API GraphQL gửi mã xác thực email...");
                                PixAiApiHelper.sendVerificationEmail(email, token, proxy, (msg, col) -> logMessage(msg));

                                logMessage("Đang lắng nghe mã OTP từ hòm thư " + mailbox.provider + "...");
                                String otpCode = PixAiApiHelper.waitForPixAICode(mailbox, email, token, proxy, 65, (msg, col) -> logMessage(msg));

                                if (otpCode != null && !otpCode.isEmpty()) {
                                    logMessage("Đã nhận mã OTP: " + otpCode);
                                    boolean verified = PixAiApiHelper.verifyEmailCode(token, otpCode, proxy);
                                    if (verified) {
                                        logMessage("Xác thực email PixAI thành công!");
                                    }
                                } else {
                                    logMessage("Tiếp tục nhận thưởng mà không cần chờ thêm OTP...");
                                }

                                PixAiApiHelper.claimFeaturedQuests(token, proxy);
                                PixAiApiHelper.claimDailyCredits(token, proxy);
                                PixAiApiHelper.claimSocialAndVisitQuests(token, proxy);
                                StringBuilder proof = new StringBuilder();
                                PixAiApiHelper.unblurNsfw(token, proxy, proof);
                                long quota = PixAiApiHelper.getQuota(token, proxy);

                                AccountModel acc = new AccountModel(email, password, token, quota);
                                storage.addAccount(acc);

                                mainHandler.post(() -> {
                                    refreshAccountsList();
                                    refreshHeader();
                                    updateCostBadge();
                                    logMessage("ĐĂNG KÝ HOÀN TẤT: " + email + " • Khởi tạo: " + String.format(Locale.US, "%,d", quota) + " Credits");
                                    Toast.makeText(MainActivity.this, "Đăng ký thành công: " + email + " (" + quota + " CR)!", Toast.LENGTH_LONG).show();
                                });
                            });
                        } else if (resultCode == HeadlessRegService.CODE_ERROR) {
                            String error = resultData.getString("error", "Lỗi không xác định");
                            if (retryCount < 2 && (error.contains("429") || error.contains("TOO_MANY_REQUESTS") || error.contains("INVALID_EMAIL") || error.contains("400"))) {
                                logMessage("Phát hiện giới hạn mạng (" + error + "), đang tự động thử lại lần " + (retryCount + 2) + "/3...");
                                startAutoRegisterPixAiHeadless(retryCount + 1);
                            } else {
                                logMessage("Lỗi đăng ký: " + error);
                                Toast.makeText(MainActivity.this, "Lỗi đăng ký: " + error, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                };

                Intent serviceIntent = new Intent(MainActivity.this, HeadlessRegService.class);
                serviceIntent.putExtra("targetSite", "pixai");
                serviceIntent.putExtra("email", regEmail);
                serviceIntent.putExtra("password", regPass);
                serviceIntent.putExtra("useProxy", storage.isUseProxy());
                serviceIntent.putExtra("proxyAddr", storage.getProxyAddress());
                serviceIntent.putExtra("receiver", regReceiver);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } catch (Exception e) {
                logMessage("Lỗi ngoại lệ tiến trình đăng ký: " + e.getMessage());
            }
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
        List<AccountModel> accounts = storage.getAccounts();
        if (accountAdapter != null) {
            accountAdapter.clear();
            accountAdapter.addAll(accounts);
            accountAdapter.notifyDataSetChanged();
        }
        if (tvTotalAccountsSummary != null) {
            tvTotalAccountsSummary.setText("Tổng Số Tài Khoản: " + accounts.size());
        }
        if (tvTotalCreditsSummary != null) {
            long totalCredits = 0;
            for (AccountModel a : accounts) totalCredits += a.getCredits();
            tvTotalCreditsSummary.setText("Tổng Số Dư: " + String.format(Locale.US, "%,d", totalCredits) + " CR");
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

            if (all.size() > historyPageLimit) {
                btnLoadMoreHistory.setVisibility(View.VISIBLE);
                btnLoadMoreHistory.setText("Xem Thêm 20 Phiên Tiếp Theo (Còn lại " + (all.size() - historyPageLimit) + " phiên)");
            } else {
                btnLoadMoreHistory.setVisibility(View.GONE);
            }
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

    private void resumePendingTasks() {
        List<HistoryItem> pending = storage.getPendingHistoryItems();
        if (pending.isEmpty()) return;
        AccountModel active = storage.getActiveAccount();
        if (active == null || active.getToken() == null) return;
        String proxy = storage.isUseProxy() ? storage.getProxyAddress() : null;
        for (HistoryItem item : pending) {
            attachTaskCardToQueue(item, active.getToken(), proxy);
        }
    }

    private class HistoryAdapter extends ArrayAdapter<HistoryItem> {
        private final java.util.Set<String> expandedIds = new java.util.HashSet<>();

        public HistoryAdapter(Context ctx, List<HistoryItem> items) {
            super(ctx, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_history, parent, false);
            }
            HistoryItem item = getItem(position);
            View headerView = convertView.findViewById(R.id.layoutItemHeader);
            ImageView ivThumb = convertView.findViewById(R.id.ivHistoryThumb);
            TextView tvMeta = convertView.findViewById(R.id.tvHistoryMeta);
            TextView tvPrompt = convertView.findViewById(R.id.tvHistoryPrompt);
            TextView tvDate = convertView.findViewById(R.id.tvHistoryDate);
            ImageView ivExpandIndicator = convertView.findViewById(R.id.ivExpandIndicator);

            View layoutDetails = convertView.findViewById(R.id.layoutExpandedDetails);
            LinearLayout layoutHistoryImagesFeed = convertView.findViewById(R.id.layoutHistoryImagesFeed);
            TextView tvFullOriginal = convertView.findViewById(R.id.tvHistoryFullOriginalPrompt);
            TextView tvFullServer = convertView.findViewById(R.id.tvHistoryFullServerPrompt);

            tvPrompt.setText(item.getOriginalPrompt());
            tvDate.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(item.getTimestamp())));

            boolean isExpanded = expandedIds.contains(item.getId());
            layoutDetails.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            if (ivExpandIndicator != null) {
                ivExpandIndicator.setImageResource(isExpanded ? R.drawable.ic_collapse : R.drawable.ic_expand);
            }

            headerView.setOnClickListener(v -> {
                if (expandedIds.contains(item.getId())) {
                    expandedIds.remove(item.getId());
                } else {
                    expandedIds.add(item.getId());
                }
                notifyDataSetChanged();
            });

            if (item.isPending()) {
                tvMeta.setText(item.getModelName() + " " + item.getProfile().toUpperCase() + " • ĐANG TẠO TRÊN GPU PIXAI");
                tvMeta.setTextColor(0xFFFFA726);
                ivThumb.setImageResource(R.drawable.ic_sync);
            } else if (item.isFailed()) {
                tvMeta.setText(item.getModelName() + " " + item.getProfile().toUpperCase() + " • THẤT BẠI");
                tvMeta.setTextColor(0xFFE57373);
                ivThumb.setImageResource(R.drawable.ic_close);
            } else {
                tvMeta.setText(item.getModelName() + " " + item.getProfile().toUpperCase() + " • " + item.getWidth() + "x" + item.getHeight());
                tvMeta.setTextColor(0xFF81C784);

                loadImageIntoView(item.getImageUrl(), ivThumb, item.getId());
            }

            if (isExpanded) {
                tvFullOriginal.setText(item.getOriginalPrompt());
                String sPrompt = item.getServerPrompt();
                if (sPrompt == null || sPrompt.trim().isEmpty() || sPrompt.equals(item.getOriginalPrompt())) {
                    tvFullServer.setText("Trùng khớp với Prompt gốc, máy chủ không sửa đổi.");
                    tvFullServer.setTextColor(0xFF9E9E9E);
                } else {
                    tvFullServer.setText(sPrompt);
                    tvFullServer.setTextColor(0xFF81C784);
                }

                // Hiển thị danh sách từng ảnh riêng biệt trong lịch sử
                if (layoutHistoryImagesFeed != null) {
                    layoutHistoryImagesFeed.removeAllViews();
                    List<String> urls = item.getImageUrls();
                    if (urls == null || urls.isEmpty()) {
                        if (item.getImageUrl() != null && !item.getImageUrl().isEmpty()) {
                            urls = new ArrayList<>();
                            urls.add(item.getImageUrl());
                        }
                    }

                    if (urls != null && !urls.isEmpty()) {
                        int total = urls.size();
                        for (int i = 0; i < total; i++) {
                            final int idx = i;
                            final String u = urls.get(i);

                            LinearLayout imgCard = new LinearLayout(getContext());
                            imgCard.setOrientation(LinearLayout.VERTICAL);
                            imgCard.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                            int p = (int) (8 * getContext().getResources().getDisplayMetrics().density);
                            imgCard.setPadding(p, p, p, p);
                            imgCard.setClipChildren(true);
                            imgCard.setClipToPadding(true);
                            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            cardLp.setMargins(0, 0, 0, (int) (10 * getContext().getResources().getDisplayMetrics().density));
                            imgCard.setLayoutParams(cardLp);

                            TextView tvNum = new TextView(getContext());
                            tvNum.setText("Ảnh " + (idx + 1) + " / " + total);
                            tvNum.setTextColor(0xFFCCCCCC);
                            tvNum.setTextSize(11);
                            tvNum.setTypeface(null, android.graphics.Typeface.BOLD);
                            imgCard.addView(tvNum);

                            PinchZoomImageView iv = new PinchZoomImageView(getContext());
                            int hPx = (int) (280 * getContext().getResources().getDisplayMetrics().density);
                            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx);
                            ivLp.setMargins(0, (int) (6 * getContext().getResources().getDisplayMetrics().density), 0, (int) (6 * getContext().getResources().getDisplayMetrics().density));
                            iv.setLayoutParams(ivLp);
                            iv.setBackgroundColor(0xFF141414);
                            loadImageIntoView(u, iv, item.getId() + "_" + idx);
                            imgCard.addView(iv);

                            // Thanh tác vụ tinh gọn (Hạn chế button, dùng chip tối giản cân đối)
                            LinearLayout actRow = new LinearLayout(getContext());
                            actRow.setOrientation(LinearLayout.HORIZONTAL);
                            actRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
                            actRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                            TextView tvHint = new TextView(getContext());
                            tvHint.setText("Kéo 2 ngón tay để zoom • Chạm 2 lần");
                            tvHint.setTextColor(0xFF757575);
                            tvHint.setTextSize(10);
                            LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                            tvHint.setLayoutParams(thLp);
                            actRow.addView(tvHint);

                            // Chip Phóng To
                            LinearLayout chipZ = new LinearLayout(getContext());
                            chipZ.setOrientation(LinearLayout.HORIZONTAL);
                            chipZ.setGravity(android.view.Gravity.CENTER);
                            chipZ.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                            chipZ.setClickable(true);
                            chipZ.setFocusable(true);
                            int pPadH = (int) (10 * getContext().getResources().getDisplayMetrics().density);
                            int pPadV = (int) (6 * getContext().getResources().getDisplayMetrics().density);
                            chipZ.setPadding(pPadH, pPadV, pPadH, pPadV);
                            LinearLayout.LayoutParams czLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                            czLp.setMarginEnd((int) (6 * getContext().getResources().getDisplayMetrics().density));
                            chipZ.setLayoutParams(czLp);

                            ImageView ivZ = new ImageView(getContext());
                            ivZ.setLayoutParams(new LinearLayout.LayoutParams((int) (14 * getContext().getResources().getDisplayMetrics().density), (int) (14 * getContext().getResources().getDisplayMetrics().density)));
                            ivZ.setImageResource(R.drawable.ic_zoom_in);
                            chipZ.addView(ivZ);

                            TextView tvZ = new TextView(getContext());
                            tvZ.setText(" Toàn Màn");
                            tvZ.setTextColor(0xFFFFFFFF);
                            tvZ.setTextSize(11);
                            chipZ.addView(tvZ);
                            chipZ.setOnClickListener(v -> openFullscreenZoom(u));
                            actRow.addView(chipZ);

                            // Chip Tải Về Máy
                            LinearLayout chipD = new LinearLayout(getContext());
                            chipD.setOrientation(LinearLayout.HORIZONTAL);
                            chipD.setGravity(android.view.Gravity.CENTER);
                            chipD.setBackgroundResource(R.drawable.bg_cyber_chip_normal);
                            chipD.setClickable(true);
                            chipD.setFocusable(true);
                            chipD.setPadding(pPadH, pPadV, pPadH, pPadV);

                            ImageView ivD = new ImageView(getContext());
                            ivD.setLayoutParams(new LinearLayout.LayoutParams((int) (14 * getContext().getResources().getDisplayMetrics().density), (int) (14 * getContext().getResources().getDisplayMetrics().density)));
                            ivD.setImageResource(R.drawable.ic_download);
                            chipD.addView(ivD);

                            TextView tvD = new TextView(getContext());
                            tvD.setText(" Tải Về");
                            tvD.setTextColor(0xFF81C784);
                            tvD.setTextSize(11);
                            chipD.addView(tvD);
                            chipD.setOnClickListener(v -> downloadImageToGallery(u));
                            actRow.addView(chipD);

                            imgCard.addView(actRow);
                            layoutHistoryImagesFeed.addView(imgCard);
                        }
                    }
                }

                View btnCopyOrig = convertView.findViewById(R.id.btnHistoryCopyOriginal);
                View btnCopyServ = convertView.findViewById(R.id.btnHistoryCopyServer);

                if (btnCopyOrig != null) {
                    btnCopyOrig.setOnClickListener(v -> {
                        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("PixAI Original Prompt", item.getOriginalPrompt()));
                        Toast.makeText(getContext(), "Đã sao chép Prompt Gốc!", Toast.LENGTH_SHORT).show();
                    });
                }
                if (btnCopyServ != null) {
                    btnCopyServ.setOnClickListener(v -> {
                        String p = item.getServerPrompt();
                        if (p == null || p.isEmpty()) p = item.getOriginalPrompt();
                        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("PixAI Server Prompt", p));
                        Toast.makeText(getContext(), "Đã sao chép Prompt Máy Chủ Tối Ưu!", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            return convertView;
        }
    }
}
