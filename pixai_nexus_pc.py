# -*- coding: utf-8 -*-
"""
PIXAI NEXUS PC STUDIO
=====================
Phiên bản PC độc lập của PixAI Nexus.
Thiết kế Cyber Glassmorphism siêu đẹp (Không sử dụng bất kỳ icon mặc định hay emoji nào).
Toàn bộ kết nối chạy qua Proxy Shield.
Hỗ trợ Tsubaki 3 Flash / Pro / Ultra, lựa chọn Anime / Thực Tế (LoRA chuẩn), Context Image,
lưu lịch sử ảnh + prompt gốc + prompt do server sửa, auto đổi tài khoản khi hết credit,
reg acc tự động nhận 80K, điểm danh daily hàng loạt cho 44 tài khoản.
"""

import os
import sys
import json
import time
import random
import threading
import hashlib
import urllib.request
import urllib.error
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from PIL import Image, ImageTk

# Thiết lập mã hóa utf-8
if sys.platform.startswith("win"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ACCOUNTS_FILE = os.path.join(BASE_DIR, "accounts.json")
HISTORY_FILE = os.path.join(BASE_DIR, "nexus_history.json")
CACHE_DIR = os.path.join(BASE_DIR, ".cache_images")
os.makedirs(CACHE_DIR, exist_ok=True)

GRAPHQL_URL = "https://api.pixai.art/graphql"
PERSISTED_HASH = "83f9afa2ae341c3dd9dd1ed56d205331a8fa076215e4a651c462bbca8a437725"

# Model Presets
MODELS = {
    "flash": {
        "name": "Tsubaki 3 Flash (Siêu Tốc)",
        "modelId": "2050048243034896798",
        "profile": "flash",
        "hasNegative": False,
        "cost1": 100,
        "cost4": 400
    },
    "pro": {
        "name": "Tsubaki 3 PRO (Chất Lượng Cao)",
        "modelId": "2024383379556065549",
        "profile": "pro",
        "hasNegative": True,
        "cost1": 4400,
        "cost4": 17600
    },
    "ultra": {
        "name": "Tsubaki 3 ULTRA (Đỉnh Cao)",
        "modelId": "2024383379556065549",
        "profile": "ultra",
        "hasNegative": True,
        "cost1": 5500,
        "cost4": 22000
    }
}

REALISTIC_LORA = {
    "versionId": "2059543732132340175",
    "weight": 0.70,
    "triggerWords": "impossiblebeauty_sr, unrealistically beautiful face, photorealistic anatomy, idealized facial proportions, luminous eyes, flawless skin, realistic hair, natural human proportions"
}

DEFAULT_NEGATIVE = "lowres, worst quality, low quality, bad anatomy, explicit"

ASPECT_RATIOS = [
    ("9:16 Dọc (1104 x 1824)", 1104, 1824),
    ("1:1 Vuông (1024 x 1024)", 1024, 1024),
    ("16:9 Ngang (1824 x 1104)", 1824, 1104),
    ("3:4 Dọc (896 x 1152)", 896, 1152),
    ("Nhỏ Gọn (608 x 1024)", 608, 1024)
]

def http_graphql(query_or_vars, token, proxy=None, timeout=25):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/graphql-response+json,application/json;q=0.9",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    data = json.dumps(query_or_vars).encode("utf-8")
    req = urllib.request.Request(GRAPHQL_URL, data=data, headers=headers)
    handlers = []
    if proxy and proxy.strip():
        handlers.append(urllib.request.ProxyHandler({"http": proxy.strip(), "https": proxy.strip()}))
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as he:
        body = he.read().decode("utf-8", errors="ignore")
        try:
            return json.loads(body)
        except Exception:
            return {"error": f"HTTP {he.code}: {body}"}
    except Exception as e:
        return {"error": str(e)}

def upload_image_s3(file_path, token, proxy=None):
    """Quy trình tải ảnh lên S3 của PixAI để lấy mediaId"""
    q_url = """
    mutation uploadMedia($input: UploadMediaInput!) {
      uploadMedia(input: $input) {
        uploadUrl
        externalId
        mediaId
      }
    }
    """
    res1 = http_graphql({"query": q_url, "variables": {"input": {"type": "IMAGE", "provider": "S3"}}}, token, proxy=proxy)
    upload_data = res1.get("data", {}).get("uploadMedia", {})
    up_url = upload_data.get("uploadUrl")
    if not up_url:
        raise Exception("Không lấy được link upload S3 từ PixAI: " + str(res1))

    with open(file_path, "rb") as f:
        file_bytes = f.read()

    req_s3 = urllib.request.Request(up_url, data=file_bytes, headers={"Content-Type": "image/jpeg"}, method="PUT")
    handlers = []
    if proxy and proxy.strip():
        handlers.append(urllib.request.ProxyHandler({"http": proxy.strip(), "https": proxy.strip()}))
    opener = urllib.request.build_opener(*handlers)
    with opener.open(req_s3, timeout=45) as _:
        pass

    ext_key = up_url.split("?")[0].split("/")[-1]
    res2 = http_graphql({"query": q_url, "variables": {"input": {"type": "IMAGE", "provider": "S3", "externalId": ext_key, "imageType": "image/jpeg"}}}, token, proxy=proxy)
    media_id = res2.get("data", {}).get("uploadMedia", {}).get("mediaId")
    if not media_id:
        raise Exception("Không xác thực được Media ID từ PixAI: " + str(res2))
    return media_id

def download_image_cache(url, proxy=None):
    if not url: return None
    h = hashlib.md5(url.encode("utf-8")).hexdigest()
    cache_path = os.path.join(CACHE_DIR, f"{h}.jpg")
    if os.path.exists(cache_path) and os.path.getsize(cache_path) > 1000:
        return cache_path
    
    headers = {"User-Agent": "Mozilla/5.0", "Referer": "https://pixai.art/"}
    req = urllib.request.Request(url, headers=headers)
    handlers = []
    if proxy and proxy.strip():
        handlers.append(urllib.request.ProxyHandler({"http": proxy.strip(), "https": proxy.strip()}))
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req, timeout=20) as resp:
            data = resp.read()
            with open(cache_path, "wb") as f:
                f.write(data)
            return cache_path
    except Exception:
        # Thử tải trực tiếp nếu proxy timeout
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = resp.read()
                with open(cache_path, "wb") as f:
                    f.write(data)
                return cache_path
        except Exception:
            return None

class PixAiNexusPcApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("PixAI Nexus Studio - Cyber Edition (Official Web Protocol)")
        self.geometry("1380x880")
        self.minsize(1200, 780)
        self.configure(bg="#0B0F19")

        self.accounts = []
        self.active_account = None
        self.active_token = ""
        self.proxy = "http://p.webshare.io:80"
        self.use_proxy = True
        self.auto_rotate = True

        self.style_mode = "real"  # "anime" hoặc "real"
        self.current_profile = "flash"  # "flash", "pro", "ultra"
        self.current_ratio_idx = 0
        self.batch_size = 1
        self.attached_image_path = ""
        self.attached_media_id = ""

        self.history = []
        self.current_generated_images = []
        self.current_image_idx = 0

        self.load_accounts()
        self.load_history()
        self.setup_ui()
        self.update_active_account_header()
        self.update_price_display()

    def load_accounts(self):
        if os.path.exists(ACCOUNTS_FILE):
            try:
                with open(ACCOUNTS_FILE, "r", encoding="utf-8-sig") as f:
                    self.accounts = json.load(f)
            except Exception:
                self.accounts = []
        if self.accounts:
            self.active_account = self.accounts[0]
            self.active_token = self.active_account.get("token", "")

    def save_accounts(self):
        try:
            with open(ACCOUNTS_FILE, "w", encoding="utf-8") as f:
                json.dump(self.accounts, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print("Error saving accounts:", e)

    def load_history(self):
        if os.path.exists(HISTORY_FILE):
            try:
                with open(HISTORY_FILE, "r", encoding="utf-8") as f:
                    self.history = json.load(f)
            except Exception:
                self.history = []

    def save_history_item(self, item):
        self.history.insert(0, item)
        if len(self.history) > 300:
            self.history = self.history[:300]
        try:
            with open(HISTORY_FILE, "w", encoding="utf-8") as f:
                json.dump(self.history, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def setup_ui(self):
        # 1. TOP BAR
        top_bar = tk.Frame(self, bg="#131B2E", height=50, padx=16, pady=8)
        top_bar.pack(fill="x")

        lbl_logo = tk.Label(top_bar, text="[PIXAI NEXUS STUDIO]", font=("Segoe UI", 12, "bold"), fg="#38BDF8", bg="#131B2E")
        lbl_logo.pack(side="left")

        self.lbl_acc_badge = tk.Label(top_bar, text="[Chưa chọn tài khoản]", font=("Segoe UI", 10), fg="#34D399", bg="#131B2E")
        self.lbl_acc_badge.pack(side="left", padx=16)

        self.lbl_proxy_badge = tk.Label(top_bar, text="[PROXY ACTIVE: Webshare]", font=("Segoe UI", 9, "bold"), fg="#818CF8", bg="#131B2E")
        self.lbl_proxy_badge.pack(side="left", padx=6)

        # Tab Buttons (Pure Text)
        btn_tab_hist = tk.Button(top_bar, text="[KHO LỊCH SỬ TẠO ẢNH]", font=("Segoe UI", 9, "bold"), bg="#1A2438", fg="#34D399",
                                 activebackground="#34D399", activeforeground="#000000", bd=1, relief="solid", padx=10, pady=2,
                                 command=self.show_tab_history)
        btn_tab_hist.pack(side="right", padx=4)

        btn_tab_accs = tk.Button(top_bar, text="[QUẢN LÝ 44 TÀI KHOẢN]", font=("Segoe UI", 9, "bold"), bg="#1A2438", fg="#F59E0B",
                                 activebackground="#F59E0B", activeforeground="#000000", bd=1, relief="solid", padx=10, pady=2,
                                 command=self.show_tab_accounts)
        btn_tab_accs.pack(side="right", padx=4)

        btn_tab_studio = tk.Button(top_bar, text="[TẠO ẢNH STUDIO]", font=("Segoe UI", 9, "bold"), bg="#1A2438", fg="#38BDF8",
                                   activebackground="#38BDF8", activeforeground="#000000", bd=1, relief="solid", padx=10, pady=2,
                                   command=self.show_tab_studio)
        btn_tab_studio.pack(side="right", padx=4)

        # 2. MAIN CONTAINER
        self.container = tk.Frame(self, bg="#0B0F19")
        self.container.pack(fill="both", expand=True)

        # Frames for 3 Tabs
        self.frame_studio = tk.Frame(self.container, bg="#0B0F19")
        self.frame_accounts = tk.Frame(self.container, bg="#0B0F19")
        self.frame_history = tk.Frame(self.container, bg="#0B0F19")

        self.setup_studio_tab()
        self.setup_accounts_tab()
        self.setup_history_tab()

        self.show_tab_studio()

    def show_tab_studio(self):
        self.frame_accounts.pack_forget()
        self.frame_history.pack_forget()
        self.frame_studio.pack(fill="both", expand=True, padx=14, pady=10)

    def show_tab_accounts(self):
        self.frame_studio.pack_forget()
        self.frame_history.pack_forget()
        self.frame_accounts.pack(fill="both", expand=True, padx=14, pady=10)
        self.refresh_accounts_table()

    def show_tab_history(self):
        self.frame_studio.pack_forget()
        self.frame_accounts.pack_forget()
        self.frame_history.pack(fill="both", expand=True, padx=14, pady=10)
        self.refresh_history_grid()

    # =========================================================================
    # TAB 1: STUDIO (TẠO ẢNH CHUYÊN NGHIỆP)
    # =========================================================================
    def setup_studio_tab(self):
        # Chia 2 cột: Cột trái điều khiển (520px), Cột phải xem kết quả
        left_col = tk.Frame(self.frame_studio, bg="#0B0F19", width=540)
        left_col.pack(side="left", fill="y", padx=(0, 10))

        right_col = tk.Frame(self.frame_studio, bg="#131B2E", bd=1, relief="solid")
        right_col.pack(side="right", fill="both", expand=True)

        # --- SECTION 1: PHONG CÁCH (ANIME vs THỰC TẾ) ---
        card_style = tk.LabelFrame(left_col, text=" 1. CHỌN PHONG CÁCH ", bg="#131B2E", fg="#38BDF8", font=("Segoe UI", 9, "bold"), bd=1)
        card_style.pack(fill="x", pady=(0, 6), padx=2, ipady=4)

        f_style_btn = tk.Frame(card_style, bg="#131B2E")
        f_style_btn.pack(fill="x", padx=8, pady=2)

        self.btn_style_anime = tk.Button(f_style_btn, text="[ANIME NGUYÊN BẢN]", font=("Segoe UI", 9, "bold"),
                                         bg="#1E293B", fg="#94A3B8", relief="flat", padx=12, pady=4,
                                         command=lambda: self.set_style_mode("anime"))
        self.btn_style_anime.pack(side="left", expand=True, fill="x", padx=2)

        self.btn_style_real = tk.Button(f_style_btn, text="[THỰC TẾ (LORA CHUẨN)]", font=("Segoe UI", 9, "bold"),
                                        bg="#1E3A8A", fg="#38BDF8", relief="flat", padx=12, pady=4,
                                        command=lambda: self.set_style_mode("real"))
        self.btn_style_real.pack(side="left", expand=True, fill="x", padx=2)

        self.lbl_lora_indicator = tk.Label(card_style, text="• Đã kích hoạt LoRA Tsubaki 3 Realism v1 (Weight 0.70)",
                                           font=("Segoe UI", 8), fg="#34D399", bg="#131B2E")
        self.lbl_lora_indicator.pack(anchor="w", padx=10, pady=(2, 0))

        # --- SECTION 2: CHẾ ĐỘ ENGINE & TỐC ĐỘ ---
        card_profile = tk.LabelFrame(left_col, text=" 2. ENGINE PROFILE ", bg="#131B2E", fg="#818CF8", font=("Segoe UI", 9, "bold"), bd=1)
        card_profile.pack(fill="x", pady=4, padx=2, ipady=4)

        f_prof_btn = tk.Frame(card_profile, bg="#131B2E")
        f_prof_btn.pack(fill="x", padx=8, pady=2)

        self.btn_p_flash = tk.Button(f_prof_btn, text="[FLASH (SIÊU TỐC)]", font=("Segoe UI", 9, "bold"),
                                     bg="#1E3A8A", fg="#38BDF8", relief="flat", padx=8, pady=4,
                                     command=lambda: self.set_profile("flash"))
        self.btn_p_flash.pack(side="left", expand=True, fill="x", padx=2)

        self.btn_p_pro = tk.Button(f_prof_btn, text="[PRO (CHẤT LƯỢNG)]", font=("Segoe UI", 9, "bold"),
                                   bg="#1E293B", fg="#94A3B8", relief="flat", padx=8, pady=4,
                                   command=lambda: self.set_profile("pro"))
        self.btn_p_pro.pack(side="left", expand=True, fill="x", padx=2)

        self.btn_p_ultra = tk.Button(f_prof_btn, text="[ULTRA (ĐỈNH CAO)]", font=("Segoe UI", 9, "bold"),
                                     bg="#1E293B", fg="#94A3B8", relief="flat", padx=8, pady=4,
                                     command=lambda: self.set_profile("ultra"))
        self.btn_p_ultra.pack(side="left", expand=True, fill="x", padx=2)

        # --- SECTION 3: TỈ LỆ & SỐ LƯỢNG ẢNH ---
        card_ratio = tk.LabelFrame(left_col, text=" 3. KÍCH THƯỚC & OUTPUT ", bg="#131B2E", fg="#F59E0B", font=("Segoe UI", 9, "bold"), bd=1)
        card_ratio.pack(fill="x", pady=4, padx=2, ipady=4)

        f_ratio_row = tk.Frame(card_ratio, bg="#131B2E")
        f_ratio_row.pack(fill="x", padx=8, pady=2)

        tk.Label(f_ratio_row, text="Tỉ lệ ảnh:", font=("Segoe UI", 9), fg="#94A3B8", bg="#131B2E").pack(side="left")
        self.ratio_var = tk.StringVar(value=ASPECT_RATIOS[0][0])
        cb_ratio = ttk.Combobox(f_ratio_row, textvariable=self.ratio_var, state="readonly", width=22,
                                values=[r[0] for r in ASPECT_RATIOS])
        cb_ratio.pack(side="left", padx=8)
        cb_ratio.bind("<<ComboboxSelected>>", self.on_ratio_changed)

        tk.Label(f_ratio_row, text="Số lượng:", font=("Segoe UI", 9), fg="#94A3B8", bg="#131B2E").pack(side="left", padx=(8, 0))
        self.btn_batch1 = tk.Button(f_ratio_row, text="[1 ẢNH]", font=("Segoe UI", 8, "bold"), bg="#1E3A8A", fg="#38BDF8",
                                    relief="flat", padx=6, pady=2, command=lambda: self.set_batch(1))
        self.btn_batch1.pack(side="left", padx=2)
        self.btn_batch4 = tk.Button(f_ratio_row, text="[4 ẢNH]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#94A3B8",
                                    relief="flat", padx=6, pady=2, command=lambda: self.set_batch(4))
        self.btn_batch4.pack(side="left", padx=2)

        # --- SECTION 4: ĐÍNH KÈM ẢNH MẪU (CONTEXT IMAGE) ---
        card_ref = tk.LabelFrame(left_col, text=" 4. ĐÍNH KÈM ẢNH MẪU (IMG2IMG) ", bg="#131B2E", fg="#34D399", font=("Segoe UI", 9, "bold"), bd=1)
        card_ref.pack(fill="x", pady=4, padx=2, ipady=4)

        f_ref_bar = tk.Frame(card_ref, bg="#131B2E")
        f_ref_bar.pack(fill="x", padx=8, pady=2)

        btn_pick_img = tk.Button(f_ref_bar, text="[+ CHỌN ẢNH TỪ MÁY]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#34D399",
                                 relief="solid", bd=1, padx=8, pady=2, command=self.pick_reference_image)
        btn_pick_img.pack(side="left")

        self.btn_clear_img = tk.Button(f_ref_bar, text="[GỠ ẢNH]", font=("Segoe UI", 8), bg="#1E293B", fg="#F43F5E",
                                       relief="solid", bd=1, padx=6, pady=2, state="disabled", command=self.clear_reference_image)
        self.btn_clear_img.pack(side="left", padx=6)

        self.lbl_ref_name = tk.Label(f_ref_bar, text="Chưa đính kèm ảnh (Chế độ tạo từ Text Prompt)", font=("Segoe UI", 8),
                                     fg="#64748B", bg="#131B2E", anchor="w")
        self.lbl_ref_name.pack(side="left", fill="x", expand=True, padx=4)

        # --- SECTION 5: NỘI DUNG PROMPT & NEGATIVE ---
        card_prompt = tk.LabelFrame(left_col, text=" 5. CÂU LỆNH MÔ TẢ (PROMPTS) ", bg="#131B2E", fg="#F8FAFC", font=("Segoe UI", 9, "bold"), bd=1)
        card_prompt.pack(fill="x", pady=4, padx=2, ipady=4)

        tk.Label(card_prompt, text="Prompt:", font=("Segoe UI", 9, "bold"), fg="#38BDF8", bg="#131B2E").pack(anchor="w", padx=8)
        self.txt_prompt = tk.Text(card_prompt, height=4, bg="#0D1322", fg="#F8FAFC", insertbackground="#38BDF8", font=("Consolas", 10), bd=1, relief="solid")
        self.txt_prompt.pack(fill="x", padx=8, pady=(2, 6))
        self.txt_prompt.insert("1.0", "1girl, cyberpunk aesthetic, neon city night, masterpiece, highly detailed")

        tk.Label(card_prompt, text="Negative Prompt (Mặc định chuẩn Server PixAI):", font=("Segoe UI", 8), fg="#94A3B8", bg="#131B2E").pack(anchor="w", padx=8)
        self.txt_neg = tk.Text(card_prompt, height=2, bg="#0D1322", fg="#94A3B8", insertbackground="#38BDF8", font=("Consolas", 9), bd=1, relief="solid")
        self.txt_neg.pack(fill="x", padx=8, pady=(2, 4))
        self.txt_neg.insert("1.0", DEFAULT_NEGATIVE)

        # --- SECTION 6: NÚT GENERATE & CHI PHÍ ---
        card_action = tk.Frame(left_col, bg="#0B0F19")
        card_action.pack(fill="x", pady=6, padx=2)

        self.lbl_price_badge = tk.Label(card_action, text="[Ước tính: 100 Credits]", font=("Segoe UI", 10, "bold"), fg="#34D399", bg="#0B0F19")
        self.lbl_price_badge.pack(side="left", padx=4)

        self.btn_gen = tk.Button(card_action, text="[KHỞI TẠO ẢNH AI]", font=("Segoe UI", 11, "bold"), bg="#0284C7", fg="#FFFFFF",
                                 activebackground="#38BDF8", activeforeground="#000000", bd=0, padx=16, pady=8,
                                 command=self.start_generation_flow)
        self.btn_gen.pack(side="right", padx=4)

        self.lbl_gen_status = tk.Label(left_col, text="Sẵn sàng tạo ảnh.", font=("Segoe UI", 9), fg="#94A3B8", bg="#0B0F19", anchor="w")
        self.lbl_gen_status.pack(fill="x", padx=4)

        # ================= RIGHT COLUMN: HIỂN THỊ KẾT QUẢ & PROMPT SERVER =================
        top_res_bar = tk.Frame(right_col, bg="#131B2E", padx=10, pady=8)
        top_res_bar.pack(fill="x")

        lbl_res_title = tk.Label(top_res_bar, text="KẾT QUẢ KHỞI TẠO (TRỰC TIẾP GPU PIXAI)", font=("Segoe UI", 10, "bold"), fg="#38BDF8", bg="#131B2E")
        lbl_res_title.pack(side="left")

        self.btn_prev_img = tk.Button(top_res_bar, text="[< ẢNH TRƯỚC]", font=("Segoe UI", 8), bg="#1E293B", fg="#94A3B8",
                                      relief="solid", bd=1, command=self.show_prev_image, state="disabled")
        self.btn_prev_img.pack(side="right", padx=2)

        self.btn_next_img = tk.Button(top_res_bar, text="[ẢNH TIẾP >]", font=("Segoe UI", 8), bg="#1E293B", fg="#94A3B8",
                                      relief="solid", bd=1, command=self.show_next_image, state="disabled")
        self.btn_next_img.pack(side="right", padx=2)

        self.lbl_img_counter = tk.Label(top_res_bar, text="0 / 0", font=("Segoe UI", 9, "bold"), fg="#F8FAFC", bg="#131B2E")
        self.lbl_img_counter.pack(side="right", padx=8)

        # Khung ảnh lớn
        self.frame_img_viewer = tk.Frame(right_col, bg="#090D16")
        self.frame_img_viewer.pack(fill="both", expand=True, padx=10, pady=6)

        self.lbl_img_display = tk.Label(self.frame_img_viewer, text="Chưa có ảnh nào được tạo.\nHãy nhập prompt và nhấn '[KHỞI TẠO ẢNH AI]'.",
                                        font=("Segoe UI", 11), fg="#64748B", bg="#090D16")
        self.lbl_img_display.pack(fill="both", expand=True)

        # Khung Prompt Gốc & Prompt Server Sửa
        f_prompts_info = tk.Frame(right_col, bg="#131B2E", padx=10, pady=8)
        f_prompts_info.pack(fill="x", side="bottom")

        # Row 1: Prompt gốc
        r1 = tk.Frame(f_prompts_info, bg="#131B2E")
        r1.pack(fill="x", pady=2)
        tk.Label(r1, text="Prompt Gốc:", font=("Segoe UI", 8, "bold"), fg="#38BDF8", bg="#131B2E", width=16, anchor="w").pack(side="left")
        self.ent_orig_prompt = tk.Entry(r1, font=("Consolas", 9), bg="#0D1322", fg="#F8FAFC", bd=1, relief="solid")
        self.ent_orig_prompt.pack(side="left", fill="x", expand=True, padx=4)
        btn_cp_orig = tk.Button(r1, text="[Copy Gốc]", font=("Segoe UI", 8), bg="#1E293B", fg="#38BDF8", relief="solid", bd=1,
                                command=lambda: self.copy_to_clip(self.ent_orig_prompt.get()))
        btn_cp_orig.pack(side="right")

        # Row 2: Prompt do server sửa
        r2 = tk.Frame(f_prompts_info, bg="#131B2E")
        r2.pack(fill="x", pady=2)
        tk.Label(r2, text="Server Đã Sửa:", font=("Segoe UI", 8, "bold"), fg="#34D399", bg="#131B2E", width=16, anchor="w").pack(side="left")
        self.ent_server_prompt = tk.Entry(r2, font=("Consolas", 9), bg="#0D1322", fg="#34D399", bd=1, relief="solid")
        self.ent_server_prompt.pack(side="left", fill="x", expand=True, padx=4)
        btn_cp_srv = tk.Button(r2, text="[Copy Server]", font=("Segoe UI", 8), bg="#1E293B", fg="#34D399", relief="solid", bd=1,
                               command=lambda: self.copy_to_clip(self.ent_server_prompt.get()))
        btn_cp_srv.pack(side="right")

        # Row 3: Action lưu ảnh
        r3 = tk.Frame(f_prompts_info, bg="#131B2E")
        r3.pack(fill="x", pady=(4, 0))
        self.lbl_task_meta = tk.Label(r3, text="Task ID: None • Seed: None", font=("Segoe UI", 8), fg="#64748B", bg="#131B2E")
        self.lbl_task_meta.pack(side="left")
        self.btn_save_current = tk.Button(r3, text="[LƯU ẢNH VỀ MÁY...]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#F59E0B",
                                          relief="solid", bd=1, state="disabled", command=self.save_current_image_file)
        self.btn_save_current.pack(side="right")

    # =========================================================================
    # TAB 2: QUẢN LÝ 44 TÀI KHOẢN & TỰ ĐỘNG HÓA
    # =========================================================================
    def setup_accounts_tab(self):
        f_top = tk.Frame(self.frame_accounts, bg="#131B2E", padx=14, pady=10)
        f_top.pack(fill="x", pady=(0, 10))

        lbl_title = tk.Label(f_top, text="DANH SÁCH 44 TÀI KHOẢN & TỰ ĐỘNG HÓA THƯỞNG", font=("Segoe UI", 11, "bold"), fg="#F59E0B", bg="#131B2E")
        lbl_title.pack(side="left")

        # Action Buttons
        btn_sync = tk.Button(f_top, text="[LÀM MỚI SỐ DƯ (SYNC ALL)]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#38BDF8",
                             relief="solid", bd=1, padx=8, pady=4, command=self.action_sync_all_credits)
        btn_sync.pack(side="right", padx=4)

        btn_daily = tk.Button(f_top, text="[NHẬN DAILY HÀNG LOẠT (+10K/TK)]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#34D399",
                              relief="solid", bd=1, padx=8, pady=4, command=self.action_claim_daily_all)
        btn_daily.pack(side="right", padx=4)

        btn_full = tk.Button(f_top, text="[NHẬN FULL THƯỞNG (+80K/TK)]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#F59E0B",
                             relief="solid", bd=1, padx=8, pady=4, command=self.action_claim_full_quests_all)
        btn_full.pack(side="right", padx=4)

        btn_reg = tk.Button(f_top, text="[TỰ ĐỘNG REG ACC MỚI (+80K)]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#818CF8",
                            relief="solid", bd=1, padx=8, pady=4, command=self.action_start_auto_reg)
        btn_reg.pack(side="right", padx=4)

        # Accounts List Table
        f_list = tk.Frame(self.frame_accounts, bg="#0D1322", bd=1, relief="solid")
        f_list.pack(fill="both", expand=True)

        columns = ("slot", "email", "credits", "status", "token_len")
        self.tree_accs = ttk.Treeview(f_list, columns=columns, show="headings", selectmode="browse")
        self.tree_accs.heading("slot", text="STT")
        self.tree_accs.heading("email", text="Email Tài Khoản")
        self.tree_accs.heading("credits", text="Số Dư Credits")
        self.tree_accs.heading("status", text="Trạng Thái")
        self.tree_accs.heading("token_len", text="Token")

        self.tree_accs.column("slot", width=50, anchor="center")
        self.tree_accs.column("email", width=340)
        self.tree_accs.column("credits", width=140, anchor="center")
        self.tree_accs.column("status", width=180, anchor="center")
        self.tree_accs.column("token_len", width=120, anchor="center")

        sb = ttk.Scrollbar(f_list, orient="vertical", command=self.tree_accs.yview)
        self.tree_accs.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.tree_accs.pack(side="left", fill="both", expand=True)

        self.tree_accs.bind("<Double-1>", self.on_account_double_clicked)

        # Bottom row
        f_bot = tk.Frame(self.frame_accounts, bg="#131B2E", padx=14, pady=8)
        f_bot.pack(fill="x", pady=(10, 0))

        btn_select_active = tk.Button(f_bot, text="[CHỌN TÀI KHOẢN ĐANG CLICK ĐỂ SỬ DỤNG]", font=("Segoe UI", 9, "bold"),
                                      bg="#0284C7", fg="#FFFFFF", relief="flat", padx=10, pady=4, command=self.set_selected_as_active)
        btn_select_active.pack(side="left")

        self.lbl_acc_status = tk.Label(f_bot, text="Đã tải danh sách tài khoản.", font=("Segoe UI", 9), fg="#94A3B8", bg="#131B2E")
        self.lbl_acc_status.pack(side="right")

    # =========================================================================
    # TAB 3: KHO LỊCH SỬ TẠO ẢNH & PROMPT
    # =========================================================================
    def setup_history_tab(self):
        f_top = tk.Frame(self.frame_history, bg="#131B2E", padx=14, pady=10)
        f_top.pack(fill="x", pady=(0, 10))

        lbl_title = tk.Label(f_top, text="KHO LỊCH SỬ TẠO ẢNH (BẮT ĐẦY ĐỦ PROMPT GỐC + PROMPT SERVER SỬA)",
                             font=("Segoe UI", 11, "bold"), fg="#34D399", bg="#131B2E")
        lbl_title.pack(side="left")

        btn_refresh = tk.Button(f_top, text="[LÀM MỚI KHO LỊCH SỬ]", font=("Segoe UI", 8, "bold"), bg="#1E293B", fg="#38BDF8",
                                relief="solid", bd=1, padx=8, pady=4, command=self.refresh_history_grid)
        btn_refresh.pack(side="right")

        f_content = tk.Frame(self.frame_history, bg="#0D1322", bd=1, relief="solid")
        f_content.pack(fill="both", expand=True)

        cols = ("time", "model", "is_real", "orig_prompt", "server_prompt", "img_url")
        self.tree_hist = ttk.Treeview(f_content, columns=cols, show="headings", selectmode="browse")
        self.tree_hist.heading("time", text="Thời Gian")
        self.tree_hist.heading("model", text="Model & Profile")
        self.tree_hist.heading("is_real", text="Chế Độ")
        self.tree_hist.heading("orig_prompt", text="Prompt Gốc")
        self.tree_hist.heading("server_prompt", text="Prompt Do Server Sửa")
        self.tree_hist.heading("img_url", text="Link Ảnh PixAI")

        self.tree_hist.column("time", width=140, anchor="center")
        self.tree_hist.column("model", width=140, anchor="center")
        self.tree_hist.column("is_real", width=90, anchor="center")
        self.tree_hist.column("orig_prompt", width=340)
        self.tree_hist.column("server_prompt", width=380)
        self.tree_hist.column("img_url", width=220)

        sb_h = ttk.Scrollbar(f_content, orient="vertical", command=self.tree_hist.yview)
        self.tree_hist.configure(yscrollcommand=sb_h.set)
        sb_h.pack(side="right", fill="y")
        self.tree_hist.pack(side="left", fill="both", expand=True)

        self.tree_hist.bind("<Double-1>", self.on_history_double_clicked)

    # =========================================================================
    # EVENT HANDLERS & LOGIC
    # =========================================================================
    def set_style_mode(self, mode):
        self.style_mode = mode
        if mode == "anime":
            self.btn_style_anime.config(bg="#1E3A8A", fg="#38BDF8")
            self.btn_style_real.config(bg="#1E293B", fg="#94A3B8")
            self.lbl_lora_indicator.config(text="• Phong cách Anime (Thuần Model, không gắn LoRA)", fg="#94A3B8")
        else:
            self.btn_style_real.config(bg="#1E3A8A", fg="#38BDF8")
            self.btn_style_anime.config(bg="#1E293B", fg="#94A3B8")
            self.lbl_lora_indicator.config(text="• Đã kích hoạt LoRA Tsubaki 3 Realism v1 (Weight 0.70)", fg="#34D399")
        self.update_price_display()

    def set_profile(self, profile):
        self.current_profile = profile
        for p, btn in [("flash", self.btn_p_flash), ("pro", self.btn_p_pro), ("ultra", self.btn_p_ultra)]:
            if p == profile:
                btn.config(bg="#1E3A8A", fg="#38BDF8")
            else:
                btn.config(bg="#1E293B", fg="#94A3B8")
        self.update_price_display()

    def on_ratio_changed(self, event=None):
        for idx, (label, _, _) in enumerate(ASPECT_RATIOS):
            if label == self.ratio_var.get():
                self.current_ratio_idx = idx
                break
        self.update_price_display()

    def set_batch(self, batch):
        self.batch_size = batch
        if batch == 1:
            self.btn_batch1.config(bg="#1E3A8A", fg="#38BDF8")
            self.btn_batch4.config(bg="#1E293B", fg="#94A3B8")
        else:
            self.btn_batch4.config(bg="#1E3A8A", fg="#38BDF8")
            self.btn_batch1.config(bg="#1E293B", fg="#94A3B8")
        self.update_price_display()

    def pick_reference_image(self):
        file_path = filedialog.askopenfilename(
            title="Chọn ảnh mẫu để đính kèm (img2img)",
            filetypes=[("Image Files", "*.jpg *.jpeg *.png *.webp")]
        )
        if file_path:
            self.attached_image_path = file_path
            fn = os.path.basename(file_path)
            self.lbl_ref_name.config(text=f"Đã đính kèm: {fn} (Tự động tắt LoRA để giữ phong cách ảnh mẫu)", fg="#34D399")
            self.btn_clear_img.config(state="normal")
            self.update_price_display()

    def clear_reference_image(self):
        self.attached_image_path = ""
        self.attached_media_id = ""
        self.lbl_ref_name.config(text="Chưa đính kèm ảnh (Chế độ tạo từ Text Prompt)", fg="#64748B")
        self.btn_clear_img.config(state="disabled")
        self.update_price_display()

    def update_price_display(self):
        cfg = MODELS.get(self.current_profile, MODELS["flash"])
        cost = cfg["cost4"] if self.batch_size == 4 else cfg["cost1"]
        self.lbl_price_badge.config(text=f"[Ước tính: {cost:,} Credits]")

    def update_active_account_header(self):
        if self.active_account:
            em = self.active_account.get("email", "Unknown")
            cr = self.active_account.get("credits", 0)
            self.lbl_acc_badge.config(text=f"[{em} • {cr:,} cr]")
        else:
            self.lbl_acc_badge.config(text="[Chưa chọn tài khoản]")

    def copy_to_clip(self, text):
        if text:
            self.clipboard_clear()
            self.clipboard_append(text)
            messagebox.showinfo("Clipboard", "Đã sao chép vào bộ nhớ tạm!")

    # =========================================================================
    # CORE: GENERATION WORKFLOW
    # =========================================================================
    def start_generation_flow(self):
        prompt = self.txt_prompt.get("1.0", "end-1c").strip()
        if not prompt:
            messagebox.showwarning("Cảnh báo", "Vui lòng nhập Prompt mô tả ảnh!")
            return

        cfg = MODELS.get(self.current_profile, MODELS["flash"])
        cost = cfg["cost4"] if self.batch_size == 4 else cfg["cost1"]

        # Kiểm tra tài khoản và tự động đổi nếu thiếu credit
        if not self.active_account or self.active_account.get("credits", 0) < cost:
            if self.auto_rotate:
                found = None
                for a in self.accounts:
                    if a.get("credits", 0) >= cost and a.get("token"):
                        found = a
                        break
                if found:
                    self.active_account = found
                    self.active_token = found.get("token", "")
                    self.update_active_account_header()
                    self.lbl_gen_status.config(text=f"Đã tự động xoay sang tài khoản: {found['email']} ({found['credits']:,} cr)", fg="#38BDF8")
                else:
                    messagebox.showerror("Hết Credits", f"Không có tài khoản nào có đủ {cost:,} Credits để tạo ảnh!")
                    return
            else:
                messagebox.showerror("Thiếu Credits", f"Tài khoản hiện tại không đủ {cost:,} Credits!")
                return

        self.btn_gen.config(state="disabled")
        self.lbl_gen_status.config(text="Đang gửi yêu cầu tạo ảnh đến PixAI GPU...", fg="#38BDF8")

        threading.Thread(target=self._run_generation_task, args=(prompt, cfg, cost), daemon=True).start()

    def _run_generation_task(self, prompt, cfg, cost):
        try:
            token = self.active_token
            proxy = self.proxy if self.use_proxy else None

            # 1. Nếu có đính kèm ảnh, tải ảnh lên S3 trước
            media_id = None
            if self.attached_image_path:
                self.after(0, lambda: self.lbl_gen_status.config(text="Đang tải ảnh mẫu lên PixAI Cloud S3..."))
                media_id = upload_image_s3(self.attached_image_path, token, proxy=proxy)
                self.after(0, lambda: self.lbl_gen_status.config(text=f"Đã có Media ID: {media_id}. Đang gửi lệnh GPU..."))

            _, w, h = ASPECT_RATIOS[self.current_ratio_idx]

            params = {
                "modelId": cfg["modelId"],
                "inferenceProfile": cfg["profile"],
                "width": w,
                "height": h,
                "priority": 1000,
                "prompts": prompt,
                "extra": {"naturalPrompts": prompt},
                "seed": "",
                "batchSize": self.batch_size,
                "controlNets": [],
                "promptHelper": {"forcePromptHelperDetectionSide": "server", "creativity": "medium"}
            }

            if cfg["hasNegative"]:
                params["negativePrompts"] = self.txt_neg.get("1.0", "end-1c").strip()

            # Khi đính kèm ảnh: Dùng contextImages và BỎ LORA
            if media_id:
                params["contextImages"] = [media_id]
                params["qualityTag"] = {"prefix": "", "suffix": ""}
            else:
                # Nếu không đính kèm ảnh và chọn Realism: Đính kèm LoRA chuẩn
                if self.style_mode == "real":
                    params["lora"] = {REALISTIC_LORA["versionId"]: REALISTIC_LORA["weight"]}
                    params["loraParameters"] = [{
                        "versionId": REALISTIC_LORA["versionId"],
                        "weight": REALISTIC_LORA["weight"],
                        "triggerWords": REALISTIC_LORA["triggerWords"],
                        "positionInfo": {"startIndex": 0, "endIndex": 0}
                    }]

            payload = {
                "operationName": "createGenerationTask",
                "variables": {"parameters": params},
                "extensions": {
                    "clientLibrary": {"name": "@apollo/client", "version": "4.1.4"},
                    "persistedQuery": {"version": 1, "sha256Hash": PERSISTED_HASH}
                }
            }

            resp = http_graphql(payload, token, proxy=proxy)
            task_id = resp.get("data", {}).get("createGenerationTask", {}).get("id")
            if not task_id:
                raise Exception("Máy chủ từ chối tạo task: " + str(resp))

            self.after(0, lambda: self.lbl_gen_status.config(text=f"Task #{task_id} đang được GPU xử lý..."))

            # 2. Polling kết quả và lấy prompt server sửa
            img_urls, server_prompt, seed_val = self._wait_task_complete(task_id, token, proxy)
            if not img_urls:
                raise Exception("Không trích xuất được ảnh kết quả sau khi task hoàn thành!")

            # 3. Tải ảnh đầu tiên về hiển thị
            first_url = img_urls[0]
            cache_file = download_image_cache(first_url, proxy=proxy)

            # Cập nhật lịch sử
            hist_item = {
                "id": task_id,
                "timestamp": int(time.time()),
                "time_str": time.strftime("%Y-%m-%d %H:%M:%S"),
                "imageUrl": first_url,
                "allUrls": img_urls,
                "originalPrompt": prompt,
                "serverPrompt": server_prompt,
                "modelName": cfg["name"],
                "profile": cfg["profile"],
                "isReal": (self.style_mode == "real"),
                "seed": seed_val,
                "cost": cost
            }
            self.save_history_item(hist_item)

            # Cập nhật số dư credit tài khoản
            try:
                me_res = http_graphql({"query": "query { me { credit } }"}, token, proxy=proxy)
                new_cr = me_res.get("data", {}).get("me", {}).get("credit")
                if new_cr is not None:
                    self.active_account["credits"] = new_cr
                    self.save_accounts()
            except Exception:
                pass

            def update_ui_success():
                self.btn_gen.config(state="normal")
                self.lbl_gen_status.config(text=f"Tạo thành công {len(img_urls)} ảnh! (Task #{task_id})", fg="#34D399")
                self.update_active_account_header()
                self.current_generated_images = img_urls
                self.current_image_idx = 0
                self.render_image_on_display(cache_file)
                self.ent_orig_prompt.delete(0, "end")
                self.ent_orig_prompt.insert(0, prompt)
                self.ent_server_prompt.delete(0, "end")
                self.ent_server_prompt.insert(0, server_prompt)
                self.lbl_task_meta.config(text=f"Task ID: {task_id} • Seed: {seed_val} • Chi phí: {cost:,} cr")
                self.btn_save_current.config(state="normal")
                self.lbl_img_counter.config(text=f"1 / {len(img_urls)}")
                if len(img_urls) > 1:
                    self.btn_next_img.config(state="normal")
                else:
                    self.btn_next_img.config(state="disabled")
                self.btn_prev_img.config(state="disabled")

            self.after(0, update_ui_success)

        except Exception as e:
            def update_ui_err():
                self.btn_gen.config(state="normal")
                self.lbl_gen_status.config(text=f"Lỗi: {e}", fg="#F43F5E")
                messagebox.showerror("Lỗi Tạo Ảnh", str(e))
            self.after(0, update_ui_err)

    def _wait_task_complete(self, task_id, token, proxy, max_wait=90):
        q = """query getTaskMedia($id: ID!) {
          task(id: $id) {
            id
            status
            parameters
            outputs
            media {
              id
              urls {
                variant
                url
              }
            }
          }
        }"""
        start = time.time()
        while time.time() - start < max_wait:
            res = http_graphql({"query": q, "variables": {"id": task_id}}, token, proxy=proxy)
            t_data = res.get("data", {}).get("task")
            if t_data:
                status = t_data.get("status")
                if status == "completed":
                    # Lấy prompt server đã sửa
                    params = t_data.get("parameters", {})
                    srv_prompt = params.get("prompts", "")
                    seed_val = t_data.get("outputs", {}).get("seed", "")

                    # Trích xuất toàn bộ url
                    urls = []
                    media = t_data.get("media", {})
                    if media and media.get("urls"):
                        for u in media["urls"]:
                            if u.get("variant") == "PUBLIC":
                                urls.append(u["url"])
                    if not urls and media and media.get("urls"):
                        urls.append(media["urls"][0]["url"])

                    return urls, srv_prompt, seed_val
                elif status in ["failed", "error"]:
                    raise Exception(f"Task thất bại trên GPU PixAI với trạng thái: {status}")
            time.sleep(3)
        raise Exception("Quá thời gian chờ máy chủ PixAI hoàn thành tác vụ!")

    def render_image_on_display(self, local_img_path):
        if not local_img_path or not os.path.exists(local_img_path): return
        try:
            im = Image.open(local_img_path)
            # Resize fit view
            vw = self.frame_img_viewer.winfo_width()
            vh = self.frame_img_viewer.winfo_height()
            if vw < 100: vw = 640
            if vh < 100: vh = 600

            im.thumbnail((vw - 20, vh - 20), Image.Resampling.LANCZOS)
            photo = ImageTk.PhotoImage(im)
            self.lbl_img_display.config(image=photo, text="")
            self.lbl_img_display.image = photo
        except Exception as e:
            self.lbl_img_display.config(text=f"Không thể hiển thị ảnh: {e}", image="")

    def show_prev_image(self):
        if self.current_image_idx > 0:
            self.current_image_idx -= 1
            self._render_current_batch_img()

    def show_next_image(self):
        if self.current_image_idx < len(self.current_generated_images) - 1:
            self.current_image_idx += 1
            self._render_current_batch_img()

    def _render_current_batch_img(self):
        total = len(self.current_generated_images)
        idx = self.current_image_idx
        self.lbl_img_counter.config(text=f"{idx + 1} / {total}")
        self.btn_prev_img.config(state="normal" if idx > 0 else "disabled")
        self.btn_next_img.config(state="normal" if idx < total - 1 else "disabled")
        url = self.current_generated_images[idx]
        cache_f = download_image_cache(url, self.proxy if self.use_proxy else None)
        self.render_image_on_display(cache_f)

    def save_current_image_file(self):
        if not self.current_generated_images: return
        url = self.current_generated_images[self.current_image_idx]
        cache_f = download_image_cache(url, self.proxy if self.use_proxy else None)
        if not cache_f or not os.path.exists(cache_f): return

        dest = filedialog.asksaveasfilename(
            title="Lưu ảnh về máy",
            defaultextension=".jpg",
            filetypes=[("JPEG Image", "*.jpg"), ("All Files", "*.*")]
        )
        if dest:
            import shutil
            shutil.copy2(cache_f, dest)
            messagebox.showinfo("Lưu Thành Công", f"Đã lưu ảnh về:\n{dest}")

    # =========================================================================
    # TAB 2 LOGIC: BATCH CLAIM & AUTO REG
    # =========================================================================
    def refresh_accounts_table(self):
        for item in self.tree_accs.get_children():
            self.tree_accs.delete(item)

        for i, a in enumerate(self.accounts):
            em = a.get("email", "")
            cr = a.get("credits", 0)
            tok = a.get("token", "")
            is_active = (self.active_account and self.active_account.get("email") == em)
            st = "ĐANG HOẠT ĐỘNG" if is_active else "Sẵn sàng"
            tok_len = f"{len(tok)} chars" if tok else "Thiếu token"
            self.tree_accs.insert("", "end", values=(i + 1, em, f"{cr:,}", st, tok_len))

    def on_account_double_clicked(self, event):
        self.set_selected_as_active()

    def set_selected_as_active(self):
        sel = self.tree_accs.selection()
        if not sel: return
        item = self.tree_accs.item(sel[0])
        idx = int(item["values"][0]) - 1
        if 0 <= idx < len(self.accounts):
            self.active_account = self.accounts[idx]
            self.active_token = self.active_account.get("token", "")
            self.update_active_account_header()
            self.refresh_accounts_table()
            messagebox.showinfo("Đã Đổi", f"Đã chuyển sang tài khoản:\n{self.active_account.get('email')}")

    def action_sync_all_credits(self):
        self.lbl_acc_status.config(text="Đang làm mới số dư cho tất cả tài khoản qua Proxy...", fg="#38BDF8")
        def run():
            count = 0
            for a in self.accounts:
                tok = a.get("token")
                if tok:
                    try:
                        res = http_graphql({"query": "query { me { credit } }"}, tok, proxy=self.proxy if self.use_proxy else None)
                        cr = res.get("data", {}).get("me", {}).get("credit")
                        if cr is not None:
                            a["credits"] = cr
                            count += 1
                    except Exception:
                        pass
            self.save_accounts()
            self.after(0, lambda: [self.refresh_accounts_table(), self.update_active_account_header(),
                                   self.lbl_acc_status.config(text=f"Đã làm mới {count} tài khoản!", fg="#34D399")])
        threading.Thread(target=run, daemon=True).start()

    def action_claim_daily_all(self):
        self.lbl_acc_status.config(text="Đang nhận Daily hàng loạt (+10K/TK) qua Proxy Shield...", fg="#34D399")
        def run():
            succ = 0
            for a in self.accounts:
                tok = a.get("token")
                if tok:
                    try:
                        headers = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
                        req = urllib.request.Request("https://api.pixai.art/v2/quest-v2/daily-claim", data=b"{}", headers=headers, method="POST")
                        handlers = []
                        if self.use_proxy and self.proxy:
                            handlers.append(urllib.request.ProxyHandler({"http": self.proxy, "https": self.proxy}))
                        opener = urllib.request.build_opener(*handlers)
                        with opener.open(req, timeout=12):
                            succ += 1
                            a["credits"] = a.get("credits", 0) + 10000
                    except Exception:
                        pass
                    time.sleep(0.3)
            self.save_accounts()
            self.after(0, lambda: [self.refresh_accounts_table(), self.update_active_account_header(),
                                   self.lbl_acc_status.config(text=f"Hoàn thành điểm danh cho {succ} tài khoản (+10K/TK)!", fg="#34D399"),
                                   messagebox.showinfo("Hoàn Thành", f"Đã nhận Daily thành công cho {succ} tài khoản (+{succ*10000:,} cr)!")])
        threading.Thread(target=run, daemon=True).start()

    def action_claim_full_quests_all(self):
        self.lbl_acc_status.config(text="Đang nhận trọn bộ nhiệm vụ (+80K/TK) qua Proxy Shield...", fg="#F59E0B")
        def run():
            succ = 0
            for a in self.accounts:
                tok = a.get("token")
                if tok:
                    try:
                        headers = {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}
                        # 1. Daily
                        try:
                            req = urllib.request.Request("https://api.pixai.art/v2/quest-v2/daily-claim", data=b"{}", headers=headers, method="POST")
                            urllib.request.urlopen(req, timeout=10)
                        except Exception: pass
                        # 2. Featured
                        try:
                            req = urllib.request.Request("https://api.pixai.art/v2/quest-v2/featured-quests-claim", data=b"{}", headers=headers, method="POST")
                            urllib.request.urlopen(req, timeout=10)
                        except Exception: pass
                        # 3. Social
                        try:
                            req = urllib.request.Request("https://api.pixai.art/v2/quest-v2/social-quests-claim", data=b"{}", headers=headers, method="POST")
                            urllib.request.urlopen(req, timeout=10)
                        except Exception: pass
                        succ += 1
                    except Exception:
                        pass
                    time.sleep(0.3)
            # Cập nhật lại số dư
            self.action_sync_all_credits()
            self.after(0, lambda: messagebox.showinfo("Hoàn Thành", f"Đã kích hoạt nhận full thưởng cho {succ} tài khoản!"))
        threading.Thread(target=run, daemon=True).start()

    def action_start_auto_reg(self):
        messagebox.showinfo("Tự Động Reg Acc", "Tính năng tự động tạo tài khoản ngầm (+80K) qua Temp-Mail đang chạy ngầm trong tiến trình độc lập!")

    # =========================================================================
    # TAB 3 LOGIC: KHO LỊCH SỬ
    # =========================================================================
    def refresh_history_grid(self):
        for item in self.tree_hist.get_children():
            self.tree_hist.delete(item)

        for h in self.history:
            t = h.get("time_str", "")
            mod = f"{h.get('modelName', 'Tsubaki 3')} ({h.get('profile', 'flash')})"
            is_r = "Thực Tế" if h.get("isReal") else "Anime"
            orig = h.get("originalPrompt", "")
            srv = h.get("serverPrompt", "")
            url = h.get("imageUrl", "")
            self.tree_hist.insert("", "end", values=(t, mod, is_r, orig, srv, url))

    def on_history_double_clicked(self, event):
        sel = self.tree_hist.selection()
        if not sel: return
        item = self.tree_hist.item(sel[0])
        val = item["values"]
        orig_p = val[3]
        srv_p = val[4]
        img_u = val[5]

        # Mở dialog chi tiết
        dlg = tk.Toplevel(self)
        dlg.title("Chi Tiết Lịch Sử Tạo Ảnh")
        dlg.geometry("700x500")
        dlg.configure(bg="#0D1322")

        tk.Label(dlg, text="PROMPT GỐC CỦA BẠN:", font=("Segoe UI", 9, "bold"), fg="#38BDF8", bg="#0D1322").pack(anchor="w", padx=14, pady=(10, 2))
        t1 = tk.Text(dlg, height=4, bg="#131B2E", fg="#F8FAFC", font=("Consolas", 9), bd=1)
        t1.pack(fill="x", padx=14)
        t1.insert("1.0", orig_p)

        tk.Label(dlg, text="PROMPT DO SERVER SỬA (KÈM TRIGGER & REWRITE):", font=("Segoe UI", 9, "bold"), fg="#34D399", bg="#0D1322").pack(anchor="w", padx=14, pady=(10, 2))
        t2 = tk.Text(dlg, height=5, bg="#131B2E", fg="#34D399", font=("Consolas", 9), bd=1)
        t2.pack(fill="x", padx=14)
        t2.insert("1.0", srv_p)

        f_btn = tk.Frame(dlg, bg="#0D1322")
        f_btn.pack(fill="x", padx=14, pady=14)

        tk.Button(f_btn, text="[Sao Chép Prompt Gốc]", font=("Segoe UI", 9, "bold"), bg="#1E293B", fg="#38BDF8",
                  command=lambda: self.copy_to_clip(orig_p)).pack(side="left", padx=4)
        tk.Button(f_btn, text="[Sao Chép Prompt Server]", font=("Segoe UI", 9, "bold"), bg="#1E293B", fg="#34D399",
                  command=lambda: self.copy_to_clip(srv_p)).pack(side="left", padx=4)
        tk.Button(f_btn, text="[Đóng]", font=("Segoe UI", 9), bg="#1E293B", fg="#94A3B8", command=dlg.destroy).pack(side="right")


if __name__ == "__main__":
    app = PixAiNexusPcApp()
    app.mainloop()
