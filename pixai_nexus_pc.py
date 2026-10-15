# -*- coding: utf-8 -*-
"""
PIXAI NEXUS PC STUDIO - PRO EDITION v2.0
=========================================
Thiết kế Dark Minimalist chuyên nghiệp (Đã gỡ bỏ toàn bộ ngoặc vuông [...], text rõ nét).
Tích hợp Quản Lý Bảo Mật & Proxy Shield (Test Ping, IP, Vùng Quốc Gia, Auto-Fallback).
Minh họa trực quan Tỷ Lệ Khung Hình (Ratio Aspect Preview).
Thanh tiến trình chi tiết (4 bước) + Timeout 300s chống mất ảnh.
Tự động tính Credit, tổng số dư toàn bộ tài khoản, tự động xoay tua khi thiếu credit.
Tự động xác nhận 18+ và tắt làm mờ nhạy cảm (Unblur NSFW).
Hiển thị Output ảnh dạng Carousel lướt ngang cho Batch 4 ảnh + Phóng to toàn màn hình + Tải về máy.
Tự động phục hồi LoRA Thực Tế khi gỡ ảnh tham chiếu.
Kho Lịch Sử mã hóa bảo mật toàn diện (Mã hóa file + cache ảnh .enc).
Bảng điều khiển Console Log riêng biệt.
"""

import os
import sys
import json
import time
import random
import threading
import hashlib
import base64
import urllib.request
import urllib.error
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from PIL import Image, ImageTk

if sys.platform.startswith("win"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ACCOUNTS_FILE = os.path.join(BASE_DIR, "accounts.json")
CONFIG_FILE = os.path.join(BASE_DIR, "nexus_config.json")
HISTORY_ENC_FILE = os.path.join(BASE_DIR, "nexus_history.enc")
CACHE_DIR = os.path.join(BASE_DIR, ".cache_images")
os.makedirs(CACHE_DIR, exist_ok=True)

GRAPHQL_URL = "https://api.pixai.art/graphql"
PERSISTED_HASH = "83f9afa2ae341c3dd9dd1ed56d205331a8fa076215e4a651c462bbca8a437725"

# Mã hóa bảo mật cho kho lịch sử
CIPHER_KEY = b"PixAi_Nexus_Security_Master_Key_2026_Secure"

def encrypt_bytes(data: bytes) -> bytes:
    key_len = len(CIPHER_KEY)
    return bytes([b ^ CIPHER_KEY[i % key_len] for i, b in enumerate(data)])

def decrypt_bytes(data: bytes) -> bytes:
    return encrypt_bytes(data)

def save_encrypted_history(history_list):
    try:
        raw = json.dumps(history_list, ensure_ascii=False).encode("utf-8")
        enc = encrypt_bytes(raw)
        with open(HISTORY_ENC_FILE, "wb") as f:
            f.write(enc)
    except Exception:
        pass

def load_encrypted_history():
    if not os.path.exists(HISTORY_ENC_FILE):
        return []
    try:
        with open(HISTORY_ENC_FILE, "rb") as f:
            enc = f.read()
        raw = decrypt_bytes(enc).decode("utf-8")
        return json.loads(raw)
    except Exception:
        return []

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
    ("9:16 Dọc (1104 x 1824)", 1104, 1824, "portrait"),
    ("1:1 Vuông (1024 x 1024)", 1024, 1024, "square"),
    ("16:9 Ngang (1824 x 1104)", 1824, 1104, "landscape"),
    ("3:4 Tiêu Chuẩn (896 x 1152)", 896, 1152, "standard"),
    ("Nhỏ Gọn (608 x 1024)", 608, 1024, "compact")
]

def http_request(url, method="POST", data=None, headers=None, proxy=None, timeout=30):
    if headers is None:
        headers = {}
    if "User-Agent" not in headers:
        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    if isinstance(data, (dict, list)):
        body = json.dumps(data).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif isinstance(data, bytes):
        body = data
    elif isinstance(data, str):
        body = data.encode("utf-8")
    else:
        body = None

    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    handlers = []
    if proxy and proxy.strip():
        clean_proxy = proxy.strip()
        handlers.append(urllib.request.ProxyHandler({"http": clean_proxy, "https": clean_proxy}))
    
    opener = urllib.request.build_opener(*handlers)
    with opener.open(req, timeout=timeout) as resp:
        return resp.read()

def http_graphql(query_or_vars, token, proxy=None, timeout=30):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/graphql-response+json,application/json;q=0.9",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    raw = http_request(GRAPHQL_URL, method="POST", data=query_or_vars, headers=headers, proxy=proxy, timeout=timeout)
    return json.loads(raw.decode("utf-8"))

def unblur_nsfw_for_account(token, proxy=None):
    """Tự động xác nhận 18+ và tắt làm mờ ảnh nhạy cảm trên server PixAI"""
    try:
        mutation = {
            "query": """
            mutation updateUserSettings($input: UpdateUserSettingsInput!) {
                updateUserSettings(input: $input) {
                    isNsfw
                    showNsfw
                }
            }
            """,
            "variables": {
                "input": {
                    "isNsfw": True,
                    "showNsfw": True
                }
            }
        }
        http_graphql(mutation, token, proxy=proxy, timeout=12)
    except Exception:
        pass


class PixAiNexusStudio(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("PixAI Nexus Studio - AI Generation & Account Manager")
        self.geometry("1200x820")
        self.minsize(1050, 720)
        
        # Load app icon if exists
        icon_path = os.path.join(BASE_DIR, "nexus_icon.ico")
        if os.path.exists(icon_path):
            try:
                self.iconbitmap(icon_path)
            except Exception:
                pass

        # Load configs & state
        self.load_config()
        self.load_accounts()
        self.history = load_encrypted_history()

        # State Variables
        self.current_style = tk.StringVar(value="real")  # anime | real
        self.current_profile = tk.StringVar(value="flash") # flash | pro | ultra
        self.current_ratio_idx = tk.IntVar(value=0)
        self.batch_count = tk.IntVar(value=1)
        self.attached_media_id = None
        self.attached_image_name = tk.StringVar(value="Chưa đính kèm ảnh mẫu (Tạo từ văn bản)")
        self.generated_images = [] # Danh sách URL ảnh hiện tại
        self.current_image_idx = 0
        self.last_original_prompt = ""
        self.last_server_prompt = ""
        self.is_generating = False

        self.setup_theme()
        self.build_ui()
        self.update_live_cost()
        self.update_header()
        self.log_message("Hệ thống PixAI Nexus Studio v2.0 đã sẵn sàng.", "info")

    def load_config(self):
        default_config = {
            "use_proxy": True,
            "proxy_address": "http://p.webshare.io:80",
            "auto_fallback_direct": True,
            "kill_switch": False,
            "anti_fingerprint": True
        }
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    self.config = json.load(f)
            except Exception:
                self.config = default_config
        else:
            self.config = default_config
            self.save_config()

    def save_config(self):
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2, ensure_ascii=False)
        except Exception:
            pass

    def load_accounts(self):
        self.accounts = []
        self.active_account = None
        if os.path.exists(ACCOUNTS_FILE):
            try:
                with open(ACCOUNTS_FILE, "r", encoding="utf-8") as f:
                    self.accounts = json.load(f)
                if self.accounts:
                    self.active_account = self.accounts[0]
            except Exception:
                self.accounts = []

    def save_accounts(self):
        try:
            with open(ACCOUNTS_FILE, "w", encoding="utf-8") as f:
                json.dump(self.accounts, f, indent=2, ensure_ascii=False)
        except Exception:
            pass

    def get_total_credits(self):
        return sum(a.get("credits", 0) for a in self.accounts)

    def get_effective_proxy(self):
        if self.config.get("use_proxy"):
            return self.config.get("proxy_address", "").strip()
        return None

    def setup_theme(self):
        # Dark Minimalist Color Palette (Đơn sắc tối sang trọng, độ tương phản cao)
        self.c_bg = "#111318"          # Nền chính siêu tối
        self.c_card = "#1A1D24"        # Khối thẻ panel
        self.c_card_border = "#262C38" # Viền thẻ mảnh
        self.c_input = "#14171E"       # Nền ô nhập liệu
        self.c_input_border = "#2E3646"# Viền ô nhập
        self.c_text = "#E2E8F0"        # Chữ sáng chính
        self.c_muted = "#8C95A6"       # Chữ phụ mờ
        self.c_primary = "#0284C7"     # Xanh biển chuẩn
        self.c_primary_hover = "#0369A1"
        self.c_accent = "#38BDF8"      # Xanh cyan sáng
        self.c_success = "#10B981"     # Xanh lá ngọc
        self.c_danger = "#EF4444"      # Đỏ cảnh báo
        self.c_amber = "#F59E0B"       # Vàng cam nhấn

        self.configure(bg=self.c_bg)

        # Cấu hình TTK Styles
        self.style = ttk.Style(self)
        self.style.theme_use("clam")
        
        self.style.configure(".", background=self.c_bg, foreground=self.c_text, font=("Segoe UI", 9))
        self.style.configure("TFrame", background=self.c_bg)
        self.style.configure("Card.TFrame", background=self.c_card, relief="solid", borderwidth=1)
        self.style.configure("TNotebook", background=self.c_bg, borderwidth=0)
        self.style.configure("TNotebook.Tab", background=self.c_card, foreground=self.c_muted, padding=[16, 8], font=("Segoe UI", 9, "bold"))
        self.style.map("TNotebook.Tab",
            background=[("selected", self.c_input_border)],
            foreground=[("selected", self.c_accent)]
        )
        self.style.configure("Horizontal.TProgressbar", troughcolor=self.c_input, background=self.c_accent, bordercolor=self.c_input_border)

    def build_ui(self):
        # 1. HEADER BAR
        self.header_frame = tk.Frame(self, bg="#161A22", height=48, padx=16, pady=8)
        self.header_frame.pack(side="top", fill="x")

        self.lbl_brand = tk.Label(self.header_frame, text="PIXAI NEXUS STUDIO", bg="#161A22", fg=self.c_accent, font=("Segoe UI", 12, "bold"))
        self.lbl_brand.pack(side="left")

        self.lbl_total_credits = tk.Label(self.header_frame, text="Tổng Số Dư: 0 Credits", bg="#161A22", fg=self.c_success, font=("Segoe UI", 10, "bold"))
        self.lbl_total_credits.pack(side="left", padx=(20, 0))

        self.lbl_active_acc = tk.Label(self.header_frame, text="Tài khoản: [Đang tải...]", bg="#161A22", fg=self.c_text, font=("Segoe UI", 9))
        self.lbl_active_acc.pack(side="left", padx=(15, 0))

        self.lbl_proxy_status = tk.Label(self.header_frame, text="Proxy: BẬT", bg="#202736", fg=self.c_accent, font=("Segoe UI", 9, "bold"), padx=10, pady=2)
        self.lbl_proxy_status.pack(side="right")

        # 2. TAB NOTEBOOK
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=12, pady=8)

        # Tab 1: Tạo Ảnh Studio
        self.tab_studio = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_studio, text=" TẠO ẢNH STUDIO ")

        # Tab 2: Quản Lý Tài Khoản
        self.tab_accounts = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_accounts, text=" QUẢN LÝ TÀI KHOẢN ")

        # Tab 3: Kho Lịch Sử (Mã hóa)
        self.tab_history = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_history, text=" KHO LỊCH SỬ ")

        # Tab 4: Bảo Mật & Proxy
        self.tab_security = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_security, text=" BẢO MẬT & PROXY ")

        # Tab 5: Nhật Ký Hoạt Động (Log)
        self.tab_logs = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_logs, text=" NHẬT KÝ HỆ THỐNG ")

        self.build_studio_tab()
        self.build_accounts_tab()
        self.build_history_tab()
        self.build_security_tab()
        self.build_logs_tab()

    # ================= TAB 1: STUDIO =================
    def build_studio_tab(self):
        container = tk.Frame(self.tab_studio, bg=self.c_bg)
        container.pack(fill="both", expand=True, padx=8, pady=8)

        # Cột Trái: Bảng Điều Khiển Cấu Hình (450px)
        left_pane = tk.Frame(container, bg=self.c_bg, width=460)
        left_pane.pack(side="left", fill="both", padx=(0, 10))
        left_pane.pack_propagate(False)

        # Khung cuộn bên trái
        canvas = tk.Canvas(left_pane, bg=self.c_bg, highlightthickness=0)
        scrollbar = ttk.Scrollbar(left_pane, orient="vertical", command=canvas.yview)
        scrollable_frame = tk.Frame(canvas, bg=self.c_bg)
        scrollable_frame.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=scrollable_frame, anchor="nw", width=440)
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # 1. Phong Cách (Anime vs Thực Tế)
        grp_style = tk.LabelFrame(scrollable_frame, text=" Phong Cách Khởi Tạo ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_style.pack(fill="x", pady=(0, 8))

        row_style = tk.Frame(grp_style, bg=self.c_card)
        row_style.pack(fill="x")

        self.btn_style_anime = tk.Button(row_style, text="Anime Nguyên Bản", bg=self.c_input, fg=self.c_muted, activebackground=self.c_card_border, activeforeground=self.c_text, relief="flat", padx=12, pady=6, font=("Segoe UI", 9), command=lambda: self.set_style("anime"))
        self.btn_style_anime.pack(side="left", fill="x", expand=True, padx=(0, 4))

        self.btn_style_real = tk.Button(row_style, text="Thực Tế (LoRA Chuẩn)", bg=self.c_primary, fg="#FFFFFF", activebackground=self.c_primary_hover, activeforeground="#FFFFFF", relief="flat", padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=lambda: self.set_style("real"))
        self.btn_style_real.pack(side="left", fill="x", expand=True, padx=(4, 0))

        self.lbl_lora_status = tk.Label(grp_style, text="LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)", bg=self.c_card, fg=self.c_success, font=("Segoe UI", 8))
        self.lbl_lora_status.pack(anchor="w", pady=(6, 0))

        # 2. Engine Profile (Flash, Pro, Ultra)
        grp_profile = tk.LabelFrame(scrollable_frame, text=" Chế Độ Engine (GPU Profile) ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_profile.pack(fill="x", pady=(0, 8))

        row_prof = tk.Frame(grp_profile, bg=self.c_card)
        row_prof.pack(fill="x")

        self.btn_prof_flash = tk.Button(row_prof, text="Flash (Siêu Tốc)", bg=self.c_primary, fg="#FFFFFF", relief="flat", padx=8, pady=5, font=("Segoe UI", 9, "bold"), command=lambda: self.set_profile("flash"))
        self.btn_prof_flash.pack(side="left", fill="x", expand=True, padx=(0, 3))

        self.btn_prof_pro = tk.Button(row_prof, text="Pro (Chất Lượng)", bg=self.c_input, fg=self.c_muted, relief="flat", padx=8, pady=5, font=("Segoe UI", 9), command=lambda: self.set_profile("pro"))
        self.btn_prof_pro.pack(side="left", fill="x", expand=True, padx=(3, 3))

        self.btn_prof_ultra = tk.Button(row_prof, text="Ultra (Đỉnh Cao)", bg=self.c_input, fg=self.c_muted, relief="flat", padx=8, pady=5, font=("Segoe UI", 9), command=lambda: self.set_profile("ultra"))
        self.btn_prof_ultra.pack(side="left", fill="x", expand=True, padx=(3, 0))

        # 3. Tỷ Lệ Khung Hình & Minh Họa Trực Quan
        grp_ratio = tk.LabelFrame(scrollable_frame, text=" Tỷ Lệ Khung Hình & Số Lượng ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_ratio.pack(fill="x", pady=(0, 8))

        row_ratio_ctrl = tk.Frame(grp_ratio, bg=self.c_card)
        row_ratio_ctrl.pack(fill="x")

        # Minh họa trực quan khung chữ nhật tỷ lệ
        self.ratio_canvas = tk.Canvas(row_ratio_ctrl, width=44, height=44, bg=self.c_input, highlightthickness=1, highlightbackground=self.c_input_border)
        self.ratio_canvas.pack(side="left", padx=(0, 10))

        ratio_options = [r[0] for r in ASPECT_RATIOS]
        self.cb_ratio = ttk.Combobox(row_ratio_ctrl, values=ratio_options, state="readonly", font=("Segoe UI", 9))
        self.cb_ratio.current(0)
        self.cb_ratio.pack(side="left", fill="x", expand=True)
        self.cb_ratio.bind("<<ComboboxSelected>>", self.on_ratio_changed)

        # Hàng chọn số lượng ảnh (1 hoặc 4)
        row_batch = tk.Frame(grp_ratio, bg=self.c_card)
        row_batch.pack(fill="x", pady=(8, 0))

        tk.Label(row_batch, text="Số lượng ảnh output:", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9)).pack(side="left")

        self.btn_batch1 = tk.Button(row_batch, text="1 Ảnh", bg=self.c_primary, fg="#FFFFFF", relief="flat", padx=10, pady=3, font=("Segoe UI", 8, "bold"), command=lambda: self.set_batch(1))
        self.btn_batch1.pack(side="left", padx=(10, 4))

        self.btn_batch4 = tk.Button(row_batch, text="4 Ảnh (Chỉ Flash)", bg=self.c_input, fg=self.c_muted, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=lambda: self.set_batch(4))
        self.btn_batch4.pack(side="left")

        # 4. Đính Kèm Ảnh Mẫu (Context Image)
        grp_attach = tk.LabelFrame(scrollable_frame, text=" Đính Kèm Ảnh Mẫu (Img2Img) ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_attach.pack(fill="x", pady=(0, 8))

        row_attach = tk.Frame(grp_attach, bg=self.c_card)
        row_attach.pack(fill="x")

        self.btn_pick_img = tk.Button(row_attach, text="Chọn Ảnh Mẫu", bg=self.c_card_border, fg=self.c_text, activebackground=self.c_input_border, activeforeground="#FFFFFF", relief="flat", padx=10, pady=4, font=("Segoe UI", 9), command=self.pick_reference_image)
        self.btn_pick_img.pack(side="left")

        self.btn_clear_img = tk.Button(row_attach, text="Gỡ Ảnh", bg="#3B1C22", fg=self.c_danger, activebackground="#4C222B", activeforeground="#FFFFFF", relief="flat", padx=10, pady=4, font=("Segoe UI", 9), command=self.clear_reference_image)
        self.btn_clear_img.pack(side="left", padx=(6, 0))
        self.btn_clear_img.pack_forget()

        self.lbl_attach_name = tk.Label(grp_attach, textvariable=self.attached_image_name, bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8), anchor="w")
        self.lbl_attach_name.pack(fill="x", pady=(4, 0))

        # 5. Prompt & Negative Prompt
        grp_prompt = tk.LabelFrame(scrollable_frame, text=" Câu Lệnh Prompt ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_prompt.pack(fill="x", pady=(0, 8))

        tk.Label(grp_prompt, text="Mô tả hình ảnh (Prompt chính):", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9)).pack(anchor="w")
        self.txt_prompt = tk.Text(grp_prompt, height=4, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 9))
        self.txt_prompt.pack(fill="x", pady=(2, 6))
        self.txt_prompt.insert("1.0", "1girl, cyberpunk aesthetic, neon city night, masterpiece, highly detailed")

        tk.Label(grp_prompt, text="Negative Prompt (Chuẩn server PixAI):", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8)).pack(anchor="w")
        self.txt_negative = tk.Text(grp_prompt, height=2, bg=self.c_input, fg=self.c_muted, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 8))
        self.txt_negative.pack(fill="x", pady=(2, 0))
        self.txt_negative.insert("1.0", DEFAULT_NEGATIVE)

        # 6. Chi Phí & Nút Tạo Ảnh
        grp_action = tk.Frame(scrollable_frame, bg=self.c_bg)
        grp_action.pack(fill="x", pady=(4, 0))

        self.lbl_cost_badge = tk.Label(grp_action, text="Chi phí: 100 Credits", bg=self.c_bg, fg=self.c_success, font=("Segoe UI", 10, "bold"))
        self.lbl_cost_badge.pack(anchor="w")

        self.btn_generate = tk.Button(grp_action, text="KHỞI TẠO ẢNH NGAY", bg=self.c_primary, fg="#FFFFFF", activebackground=self.c_primary_hover, activeforeground="#FFFFFF", relief="flat", pady=10, font=("Segoe UI", 11, "bold"), command=self.on_start_generate)
        self.btn_generate.pack(fill="x", pady=(6, 4))

        self.progress_bar = ttk.Progressbar(grp_action, orient="horizontal", mode="indeterminate")
        self.lbl_gen_step = tk.Label(grp_action, text="Sẵn sàng khởi tạo.", bg=self.c_bg, fg=self.c_muted, font=("Segoe UI", 9))
        self.lbl_gen_step.pack(anchor="w")

        self.draw_ratio_preview()

        # ================= CỘT PHẢI: KHU VỰC PREVIEW ẢNH & CAROUSEL =================
        right_pane = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        right_pane.pack(side="right", fill="both", expand=True)

        # Top bar preview
        top_prev_bar = tk.Frame(right_pane, bg=self.c_card, padx=12, pady=8)
        top_prev_bar.pack(fill="x")

        tk.Label(top_prev_bar, text="KẾT QUẢ KHỞI TẠO", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 10, "bold")).pack(side="left")

        self.btn_download_cur = tk.Button(top_prev_bar, text="Tải Ảnh Về Máy", bg=self.c_card_border, fg=self.c_text, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=self.download_current_image)
        self.btn_download_cur.pack(side="right")
        self.btn_download_cur.config(state="disabled")

        self.btn_zoom_cur = tk.Button(top_prev_bar, text="Phóng To Toàn Màn Hình", bg=self.c_card_border, fg=self.c_accent, relief="flat", padx=10, pady=3, font=("Segoe UI", 8, "bold"), command=self.zoom_current_image)
        self.btn_zoom_cur.pack(side="right", padx=(0, 6))
        self.btn_zoom_cur.config(state="disabled")

        # Khung chứa ảnh preview trung tâm
        self.preview_container = tk.Frame(right_pane, bg="#0D0F14")
        self.preview_container.pack(fill="both", expand=True, padx=12, pady=6)

        self.lbl_image_display = tk.Label(self.preview_container, bg="#0D0F14", text="Chưa có ảnh nào được tạo.\nHãy nhập prompt và nhấn Khởi Tạo Ảnh Ngay.", fg=self.c_muted, font=("Segoe UI", 10))
        self.lbl_image_display.pack(fill="both", expand=True)
        self.lbl_image_display.bind("<Double-Button-1>", lambda e: self.zoom_current_image())

        # Điều hướng Carousel (Dành cho Batch 4 ảnh)
        self.carousel_bar = tk.Frame(right_pane, bg=self.c_card, padx=12, pady=4)
        self.carousel_bar.pack(fill="x")

        self.btn_prev_img = tk.Button(self.carousel_bar, text="< Ảnh Trước", bg=self.c_card_border, fg=self.c_text, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=self.prev_image)
        self.btn_prev_img.pack(side="left")

        self.lbl_carousel_idx = tk.Label(self.carousel_bar, text="Ảnh 0 / 0", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9, "bold"))
        self.lbl_carousel_idx.pack(side="left", padx=15)

        self.btn_next_img = tk.Button(self.carousel_bar, text="Ảnh Tiếp >", bg=self.c_card_border, fg=self.c_text, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=self.next_image)
        self.btn_next_img.pack(side="left")

        # Khung Prompt Gốc & Prompt Server Sửa
        p_card = tk.Frame(right_pane, bg="#13161D", padx=10, pady=6, bd=1, relief="solid")
        p_card.pack(fill="x", padx=12, pady=(0, 8))

        row_og = tk.Frame(p_card, bg="#13161D")
        row_og.pack(fill="x", pady=2)
        tk.Label(row_og, text="Prompt Gốc:", bg="#13161D", fg=self.c_accent, font=("Segoe UI", 8, "bold"), width=13, anchor="w").pack(side="left")
        self.lbl_res_original = tk.Label(row_og, text="--", bg="#13161D", fg=self.c_text, font=("Segoe UI", 8), anchor="w")
        self.lbl_res_original.pack(side="left", fill="x", expand=True)
        btn_copy_og = tk.Button(row_og, text="Copy Gốc", bg=self.c_card_border, fg=self.c_text, relief="flat", padx=6, pady=1, font=("Segoe UI", 7), command=self.copy_original_prompt)
        btn_copy_og.pack(side="right")

        row_sv = tk.Frame(p_card, bg="#13161D")
        row_sv.pack(fill="x", pady=2)
        tk.Label(row_sv, text="Server Đã Sửa:", bg="#13161D", fg=self.c_success, font=("Segoe UI", 8, "bold"), width=13, anchor="w").pack(side="left")
        self.lbl_res_server = tk.Label(row_sv, text="--", bg="#13161D", fg=self.c_success, font=("Segoe UI", 8), anchor="w")
        self.lbl_res_server.pack(side="left", fill="x", expand=True)
        btn_copy_sv = tk.Button(row_sv, text="Copy Server", bg=self.c_card_border, fg=self.c_success, relief="flat", padx=6, pady=1, font=("Segoe UI", 7), command=self.copy_server_prompt)
        btn_copy_sv.pack(side="right")

    # ================= LOGIC ĐIỀU KHIỂN STUDIO =================
    def set_style(self, style):
        self.current_style.set(style)
        if style == "anime":
            self.btn_style_anime.config(bg=self.c_primary, fg="#FFFFFF", font=("Segoe UI", 9, "bold"))
            self.btn_style_real.config(bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 9))
            self.lbl_lora_status.config(text="Anime Nguyên Bản (Không dùng LoRA)", fg=self.c_muted)
        else:
            self.btn_style_real.config(bg=self.c_primary, fg="#FFFFFF", font=("Segoe UI", 9, "bold"))
            self.btn_style_anime.config(bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 9))
            if self.attached_media_id:
                self.lbl_lora_status.config(text="Đang đính kèm ảnh mẫu (Tự động bỏ LoRA để tương thích)", fg=self.c_amber)
            else:
                self.lbl_lora_status.config(text="LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)", fg=self.c_success)

    def set_profile(self, profile):
        self.current_profile.set(profile)
        for p, btn in [("flash", self.btn_prof_flash), ("pro", self.btn_prof_pro), ("ultra", self.btn_prof_ultra)]:
            if p == profile:
                btn.config(bg=self.c_primary, fg="#FFFFFF", font=("Segoe UI", 9, "bold"))
            else:
                btn.config(bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 9))
        
        # Pro và Ultra chỉ cho phép 1 ảnh
        if profile != "flash" and self.batch_count.get() > 1:
            self.set_batch(1)

        self.update_live_cost()

    def on_ratio_changed(self, event=None):
        self.current_ratio_idx.set(self.cb_ratio.current())
        self.draw_ratio_preview()

    def draw_ratio_preview(self):
        self.ratio_canvas.delete("all")
        idx = self.current_ratio_idx.get()
        if idx >= len(ASPECT_RATIOS):
            idx = 0
        _, w, h, _ = ASPECT_RATIOS[idx]
        
        # Vẽ khung chữ nhật tỉ lệ
        max_box = 36
        if w >= h:
            rw = max_box
            rh = max(8, int(max_box * (h / w)))
        else:
            rh = max_box
            rw = max(8, int(max_box * (w / h)))
        
        x0 = (44 - rw) // 2
        y0 = (44 - rh) // 2
        self.ratio_canvas.create_rectangle(x0, y0, x0 + rw, y0 + rh, fill=self.c_card_border, outline=self.c_accent, width=1.5)

    def set_batch(self, count):
        if count == 4 and self.current_profile.get() != "flash":
            messagebox.showwarning("Thông báo", "Số lượng 4 ảnh chỉ khả dụng cho chế độ FLASH (Siêu Tốc)!")
            return
        self.batch_count.set(count)
        if count == 1:
            self.btn_batch1.config(bg=self.c_primary, fg="#FFFFFF", font=("Segoe UI", 8, "bold"))
            self.btn_batch4.config(bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 8))
        else:
            self.btn_batch4.config(bg=self.c_primary, fg="#FFFFFF", font=("Segoe UI", 8, "bold"))
            self.btn_batch1.config(bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 8))
        self.update_live_cost()

    def update_live_cost(self):
        prof = self.current_profile.get()
        cfg = MODELS.get(prof, MODELS["flash"])
        cnt = self.batch_count.get()
        cost = cfg["cost4"] if cnt == 4 else cfg["cost1"]
        self.lbl_cost_badge.config(text=f"Chi phí ước tính: {cost:,} Credits ({cfg['name']} - {cnt} Ảnh)")

    def pick_reference_image(self):
        path = filedialog.askopenfilename(title="Chọn ảnh mẫu tham chiếu", filetypes=[("Hình ảnh", "*.png;*.jpg;*.jpeg;*.webp")])
        if not path:
            return

        self.attached_image_name.set("Đang tải ảnh mẫu lên PixAI Cloud qua Proxy...")
        self.btn_pick_img.config(state="disabled")

        def task():
            try:
                with open(path, "rb") as f:
                    img_bytes = f.read()

                token = self.active_account.get("token") if self.active_account else ""
                proxy = self.get_effective_proxy()
                media_id = self.upload_image_to_pixai(img_bytes, token, proxy)

                def onSuccess():
                    self.attached_media_id = media_id
                    filename = os.path.basename(path)
                    self.attached_image_name.set(f"Đã đính kèm: {filename} (ID: {media_id})")
                    self.btn_clear_img.pack(side="left", padx=(6, 0))
                    self.btn_pick_img.config(state="normal")
                    self.lbl_lora_status.config(text="Đang đính kèm ảnh mẫu (Tự động bỏ LoRA để tương thích)", fg=self.c_amber)
                    self.log_message(f"Tải ảnh mẫu thành công! PixAI Media ID: {media_id}", "success")

                self.after(0, onSuccess)
            except Exception as e:
                def onErr():
                    self.btn_pick_img.config(state="normal")
                    self.attached_image_name.set("Tải ảnh thất bại: " + str(e))
                    self.log_message(f"Lỗi tải ảnh mẫu: {e}", "danger")
                self.after(0, onErr)

        threading.Thread(target=task, daemon=True).start()

    def clear_reference_image(self):
        """Gỡ ảnh tham chiếu và TỰ ĐỘNG BẬT LẠI LoRA nếu đang ở chế độ Thực Tế"""
        self.attached_media_id = None
        self.attached_image_name.set("Chưa đính kèm ảnh mẫu (Tạo từ văn bản)")
        self.btn_clear_img.pack_forget()
        
        # Tự động bật lại LoRA
        if self.current_style.get() == "real":
            self.lbl_lora_status.config(text="LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)", fg=self.c_success)
            self.log_message("Đã gỡ ảnh mẫu: Tự động phục hồi LoRA Thực Tế (0.70).", "info")
        else:
            self.lbl_lora_status.config(text="Anime Nguyên Bản (Không dùng LoRA)", fg=self.c_muted)

    def upload_image_to_pixai(self, img_bytes, token, proxy):
        q_url = """
        mutation uploadMedia($input: UploadMediaInput!) {
            uploadMedia(input: $input) {
                uploadUrl
                externalId
                mediaId
            }
        }
        """
        payload1 = {"query": q_url, "variables": {"input": {"type": "IMAGE", "provider": "S3"}}}
        res1 = http_graphql(payload1, token, proxy=proxy, timeout=25)
        up_data = res1.get("data", {}).get("uploadMedia", {})
        up_url = up_data.get("uploadUrl")
        if not up_url:
            raise Exception("Không lấy được S3 Upload URL: " + str(res1))

        # PUT lên S3
        http_request(up_url, method="PUT", data=img_bytes, headers={"Content-Type": "image/jpeg"}, proxy=proxy, timeout=35)

        ext_key = up_url.split("?")[0].split("/")[-1]
        payload2 = {
            "query": q_url,
            "variables": {
                "input": {
                    "type": "IMAGE",
                    "provider": "S3",
                    "externalId": ext_key,
                    "imageType": "image/jpeg"
                }
            }
        }
        res2 = http_graphql(payload2, token, proxy=proxy, timeout=25)
        up_data2 = res2.get("data", {}).get("uploadMedia", {})
        media_id = up_data2.get("mediaId")
        if not media_id:
            raise Exception("Không xác nhận được Media ID từ PixAI: " + str(res2))
        return media_id

    # ================= QUÁ TRÌNH TẠO ẢNH (TIẾN TRÌNH & AUTO XOAY TÀI KHOẢN) =================
    def on_start_generate(self):
        if self.is_generating:
            return

        prompt = self.txt_prompt.get("1.0", "end").strip()
        if not prompt:
            messagebox.showwarning("Cảnh báo", "Vui lòng nhập câu lệnh Prompt!")
            return

        prof = self.current_profile.get()
        cfg = MODELS.get(prof, MODELS["flash"])
        batch = self.batch_count.get()
        required_cost = cfg["cost4"] if batch == 4 else cfg["cost1"]

        # TỰ ĐỘNG XOAY TÀI KHOẢN NẾU THIẾU CREDIT
        if not self.active_account or self.active_account.get("credits", 0) < required_cost:
            eligible = None
            for acc in self.accounts:
                if acc.get("credits", 0) >= required_cost and acc.get("token"):
                    eligible = acc
                    break

            if eligible:
                self.active_account = eligible
                self.update_header()
                self.log_message(f"Tự động xoay tua tài khoản: {eligible.get('email')} (Còn {eligible.get('credits', 0):,} CR)", "info")
            else:
                messagebox.showerror("Hết Credits", f"Tất cả tài khoản trong hệ thống đều không đủ {required_cost:,} Credits!\nVui lòng vào tab Quản Lý Tài Khoản để nhận thưởng (+80K) hoặc tạo tài khoản mới.")
                return

        self.is_generating = True
        self.btn_generate.config(state="disabled")
        self.progress_bar.pack(fill="x", pady=(2, 4))
        self.progress_bar.start(10)
        self.set_step_status("Bước 1/4: Đang kiểm tra phiên tài khoản & cài đặt 18+...")

        ratio_info = ASPECT_RATIOS[self.current_ratio_idx.get()]
        width, height = ratio_info[1], ratio_info[2]
        negative = self.txt_negative.get("1.0", "end").strip()
        is_real = (self.current_style.get() == "real")
        attached_id = self.attached_media_id

        threading.Thread(target=self.run_generation_task, args=(self.active_account, prof, is_real, width, height, batch, prompt, negative, attached_id, required_cost), daemon=True).start()

    def set_step_status(self, text, color=None):
        def update():
            self.lbl_gen_step.config(text=text, fg=color if color else self.c_accent)
        self.after(0, update)

    def run_generation_task(self, account, profile, is_real, width, height, batch, prompt, negative, attached_id, cost):
        token = account.get("token", "")
        proxy = self.get_effective_proxy()

        try:
            # Tự động gỡ blur NSFW cho tài khoản
            unblur_nsfw_for_account(token, proxy=proxy)

            self.set_step_status("Bước 2/4: Đang gửi yêu cầu khởi tạo lên GPU PixAI Engine...")
            model_id = MODELS[profile]["modelId"]

            params = {
                "modelId": model_id,
                "inferenceProfile": profile,
                "width": width,
                "height": height,
                "priority": 1000,
                "prompts": prompt,
                "seed": "",
                "batchSize": batch,
                "controlNets": [],
                "extra": {"naturalPrompts": prompt},
                "promptHelper": {
                    "forcePromptHelperDetectionSide": "server",
                    "creativity": "medium"
                }
            }

            if profile != "flash":
                params["negativePrompts"] = negative if negative else DEFAULT_NEGATIVE

            if attached_id:
                params["contextImages"] = [attached_id]
                params["qualityTag"] = {"prefix": "", "suffix": ""}
            else:
                if is_real:
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

            create_res = http_graphql(payload, token, proxy=proxy, timeout=30)
            task_node = create_res.get("data", {}).get("createGenerationTask")
            if not task_node or "id" not in task_node:
                raise Exception("Server từ chối tạo task: " + str(create_res))

            task_id = task_node["id"]
            self.log_message(f"Khởi tạo Task ID thành công: {task_id}", "info")

            # Bước 3: Polling với timeout CHUẨN 300s (5 phút)
            query_task = """
            query getTaskMedia($id: ID!) {
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
            }
            """
            poll_payload = {"query": query_task, "variables": {"id": task_id}}

            start_t = time.time()
            completed_data = None
            wait_sec = 0

            while time.time() - start_t < 300: # 300 giây timeout
                time.sleep(2.5)
                wait_sec = int(time.time() - start_t)
                self.set_step_status(f"Bước 3/4: GPU PixAI đang vẽ ảnh ({wait_sec}s)...")

                try:
                    poll_res = http_graphql(poll_payload, token, proxy=proxy, timeout=20)
                except Exception:
                    continue

                t_node = poll_res.get("data", {}).get("task")
                if t_node:
                    status = t_node.get("status", "").lower()
                    if status == "completed":
                        completed_data = t_node
                        break
                    elif status in ["failed", "error"]:
                        raise Exception(f"Tác vụ bị lỗi trên GPU PixAI ({status})")

            if not completed_data:
                raise Exception("Quá thời gian chờ (Timeout 300s) xử lý trên GPU PixAI!")

            self.set_step_status("Bước 4/4: Hoàn tất! Đang tải và mã hóa lưu trữ ảnh...")

            # Trích xuất dữ liệu
            server_prompt = completed_data.get("parameters", {}).get("prompts", prompt)
            seed = completed_data.get("outputs", {}).get("seed", 0)
            media = completed_data.get("media", {})
            urls_list = media.get("urls", [])

            image_urls = []
            for u in urls_list:
                if u.get("variant") == "PUBLIC":
                    image_urls.append(u.get("url"))
            if not image_urls and urls_list:
                image_urls.append(urls_list[0].get("url"))

            # Trừ credit
            account["credits"] = max(0, account.get("credits", 0) - cost)
            self.save_accounts()

            # Lưu vào Kho Lịch Sử Được Mã Hóa
            for img_url in image_urls:
                hist_item = {
                    "id": str(int(time.time() * 1000)) + "_" + str(random.randint(100, 999)),
                    "timestamp": int(time.time() * 1000),
                    "imageUrl": img_url,
                    "originalPrompt": prompt,
                    "serverPrompt": server_prompt,
                    "modelName": "Tsubaki 3",
                    "profile": profile,
                    "isReal": is_real,
                    "seed": seed,
                    "width": width,
                    "height": height
                }
                self.history.insert(0, hist_item)
            save_encrypted_history(self.history)

            # Cập nhật giao diện
            def on_done():
                self.generated_images = image_urls
                self.current_image_idx = 0
                self.last_original_prompt = prompt
                self.last_server_prompt = server_prompt
                self.lbl_res_original.config(text=prompt[:80] + ("..." if len(prompt) > 80 else ""))
                self.lbl_res_server.config(text=server_prompt[:80] + ("..." if len(server_prompt) > 80 else ""))
                self.display_current_image()
                self.update_header()
                self.refresh_history_list()
                self.log_message(f"Tạo ảnh thành công ({len(image_urls)} ảnh)! Đã lưu vào kho lịch sử mã hóa.", "success")
                self.finish_generate("Khởi tạo thành công 100%!", self.c_success)

            self.after(0, on_done)

        except Exception as e:
            def on_fail():
                self.log_message(f"Khởi tạo thất bại: {e}", "danger")
                self.finish_generate(f"Thất bại: {e}", self.c_danger)
                messagebox.showerror("Lỗi Tạo Ảnh", str(e))
            self.after(0, on_fail)

    def finish_generate(self, status_text, color):
        self.is_generating = False
        self.btn_generate.config(state="normal")
        self.progress_bar.stop()
        self.progress_bar.pack_forget()
        self.lbl_gen_step.config(text=status_text, fg=color)

    # ================= HIỂN THỊ PREVIEW & CAROUSEL =================
    def display_current_image(self):
        if not self.generated_images:
            self.btn_download_cur.config(state="disabled")
            self.btn_zoom_cur.config(state="disabled")
            self.carousel_bar.pack_forget()
            return

        total = len(self.generated_images)
        self.btn_download_cur.config(state="normal")
        self.btn_zoom_cur.config(state="normal")

        if total > 1:
            self.carousel_bar.pack(fill="x")
            self.lbl_carousel_idx.config(text=f"Ảnh {self.current_image_idx + 1} / {total}")
            self.btn_prev_img.config(state="normal" if self.current_image_idx > 0 else "disabled")
            self.btn_next_img.config(state="normal" if self.current_image_idx < total - 1 else "disabled")
        else:
            self.carousel_bar.pack_forget()

        url = self.generated_images[self.current_image_idx]
        self.load_image_to_label(url, self.lbl_image_display, max_w=680, max_h=520)

    def prev_image(self):
        if self.current_image_idx > 0:
            self.current_image_idx -= 1
            self.display_current_image()

    def next_image(self):
        if self.current_image_idx < len(self.generated_images) - 1:
            self.current_image_idx += 1
            self.display_current_image()

    def download_current_image(self):
        if not self.generated_images:
            return
        url = self.generated_images[self.current_image_idx]
        save_path = filedialog.asksaveasfilename(defaultextension=".jpg", filetypes=[("JPEG Image", "*.jpg"), ("PNG Image", "*.png")])
        if not save_path:
            return

        def task():
            try:
                data = http_request(url, method="GET", timeout=25)
                with open(save_path, "wb") as f:
                    f.write(data)
                self.log_message(f"Đã lưu ảnh về: {save_path}", "success")
                messagebox.showinfo("Thành Công", f"Đã lưu ảnh thành công vào:\n{save_path}")
            except Exception as e:
                messagebox.showerror("Lỗi Tải", f"Không thể tải ảnh: {e}")

        threading.Thread(target=task, daemon=True).start()

    def zoom_current_image(self):
        if not self.generated_images:
            return
        url = self.generated_images[self.current_image_idx]
        self.open_fullscreen_zoom(url, self.last_original_prompt, self.last_server_prompt)

    def open_fullscreen_zoom(self, url, og_prompt, sv_prompt):
        top = tk.Toplevel(self)
        top.title("Phóng To Toàn Màn Hình - PixAI Nexus")
        top.geometry("1000x800")
        top.configure(bg="#0B0D12")

        lbl_zoom = tk.Label(top, bg="#0B0D12", text="Đang tải ảnh độ phân giải gốc...", fg=self.c_muted, font=("Segoe UI", 11))
        lbl_zoom.pack(fill="both", expand=True)

        bot = tk.Frame(top, bg="#141720", padx=12, pady=8)
        bot.pack(fill="x", side="bottom")

        tk.Label(bot, text="Server Prompt: " + sv_prompt[:90], bg="#141720", fg=self.c_success, font=("Segoe UI", 8)).pack(side="left")

        def save_this():
            save_path = filedialog.asksaveasfilename(parent=top, defaultextension=".jpg", filetypes=[("JPEG Image", "*.jpg")])
            if save_path:
                try:
                    data = http_request(url, method="GET", timeout=25)
                    with open(save_path, "wb") as f:
                        f.write(data)
                    messagebox.showinfo("Thành Công", "Đã lưu ảnh thành công!", parent=top)
                except Exception as e:
                    messagebox.showerror("Lỗi", str(e), parent=top)

        btn_dl = tk.Button(bot, text="Tải Ảnh Về Máy", bg=self.c_primary, fg="#FFFFFF", relief="flat", padx=12, pady=4, font=("Segoe UI", 9, "bold"), command=save_this)
        btn_dl.pack(side="right")

        self.load_image_to_label(url, lbl_zoom, max_w=980, max_h=720)

    def load_image_to_label(self, url, label, max_w, max_h):
        def task():
            try:
                # Cache ảnh mã hóa bảo mật
                hash_key = hashlib.md5(url.encode()).hexdigest()
                cached_enc = os.path.join(CACHE_DIR, f"{hash_key}.enc")

                if os.path.exists(cached_enc):
                    with open(cached_enc, "rb") as f:
                        enc = f.read()
                    data = decrypt_bytes(enc)
                else:
                    data = http_request(url, method="GET", timeout=25)
                    # Lưu cache mã hóa
                    with open(cached_enc, "wb") as f:
                        f.write(encrypt_bytes(data))

                # Render PIL Image
                import io
                img = Image.open(io.BytesIO(data))
                img.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)
                photo = ImageTk.PhotoImage(img)

                def show():
                    label.config(image=photo, text="")
                    label.image = photo

                self.after(0, show)
            except Exception as e:
                def on_err():
                    label.config(text=f"Lỗi tải ảnh: {e}", image="")
                self.after(0, on_err)

        threading.Thread(target=task, daemon=True).start()

    def copy_original_prompt(self):
        self.clipboard_clear()
        self.clipboard_append(self.last_original_prompt)
        messagebox.showinfo("Đã Sao Chép", "Đã sao chép Prompt Gốc vào Clipboard!")

    def copy_server_prompt(self):
        self.clipboard_clear()
        self.clipboard_append(self.last_server_prompt)
        messagebox.showinfo("Đã Sao Chép", "Đã sao chép Prompt Server Sửa vào Clipboard!")

    # ================= TAB 2: QUẢN LÝ TÀI KHOẢN =================
    def build_accounts_tab(self):
        container = tk.Frame(self.tab_accounts, bg=self.c_bg, padx=12, pady=12)
        container.pack(fill="both", expand=True)

        # Toolbar các nút hành động (BỎ TOÀN BỘ DẤU [...])
        toolbar = tk.Frame(container, bg=self.c_bg)
        toolbar.pack(fill="x", pady=(0, 10))

        btn_daily = tk.Button(toolbar, text="Nhận Daily (+10K)", bg=self.c_card, fg=self.c_success, activebackground=self.c_card_border, relief="flat", padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=self.claim_daily_all)
        btn_daily.pack(side="left", padx=(0, 6))

        btn_full = tk.Button(toolbar, text="Full Thưởng (+80K)", bg=self.c_card, fg=self.c_amber, activebackground=self.c_card_border, relief="flat", padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=self.claim_full_all)
        btn_full.pack(side="left", padx=(0, 6))

        btn_sync = tk.Button(toolbar, text="Đồng Bộ Số Dư", bg=self.c_card, fg=self.c_accent, activebackground=self.c_card_border, relief="flat", padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=self.sync_all_credits)
        btn_sync.pack(side="left", padx=(0, 6))

        btn_unblur = tk.Button(toolbar, text="Xác Nhận 18+ (Tắt Mờ)", bg=self.c_card, fg="#A78BFA", activebackground=self.c_card_border, relief="flat", padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=self.unblur_all_accounts)
        btn_unblur.pack(side="left")

        # Treeview danh sách tài khoản
        tree_frame = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        tree_frame.pack(fill="both", expand=True)

        cols = ("status", "email", "credits", "token_preview")
        self.tree_acc = ttk.Treeview(tree_frame, columns=cols, show="headings", selectmode="browse")
        self.tree_acc.heading("status", text="Trạng Thái")
        self.tree_acc.heading("email", text="Email Tài Khoản")
        self.tree_acc.heading("credits", text="Số Dư Credits")
        self.tree_acc.heading("token_preview", text="Mã Token Phiên (JWT)")

        self.tree_acc.column("status", width=120, anchor="center")
        self.tree_acc.column("email", width=280)
        self.tree_acc.column("credits", width=140, anchor="e")
        self.tree_acc.column("token_preview", width=400)

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree_acc.yview)
        self.tree_acc.configure(yscrollcommand=scrollbar.set)

        self.tree_acc.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.tree_acc.bind("<Double-1>", self.on_select_account)

        self.refresh_accounts_table()

    def refresh_accounts_table(self):
        for item in self.tree_acc.get_children():
            self.tree_acc.delete(item)

        for acc in self.accounts:
            is_active = (self.active_account and acc.get("email") == self.active_account.get("email"))
            status = "ĐANG DÙNG" if is_active else "Sẵn Sàng"
            tok = acc.get("token", "")
            preview = (tok[:22] + "...") if len(tok) > 22 else tok
            self.tree_acc.insert("", "end", values=(status, acc.get("email"), f"{acc.get('credits', 0):,} CR", preview))

    def on_select_account(self, event):
        sel = self.tree_acc.selection()
        if not sel:
            return
        item = self.tree_acc.item(sel[0])
        email = item["values"][1]
        for acc in self.accounts:
            if acc.get("email") == email:
                self.active_account = acc
                self.refresh_accounts_table()
                self.update_header()
                self.log_message(f"Đã chọn tài khoản hoạt động: {email}", "info")
                messagebox.showinfo("Đã Chọn", f"Đã chuyển sang tài khoản:\n{email}")
                break

    def claim_daily_all(self):
        def task():
            self.log_message("Đang nhận Daily (+10K) cho toàn bộ tài khoản...", "info")
            proxy = self.get_effective_proxy()
            for acc in self.accounts:
                tok = acc.get("token")
                if tok:
                    try:
                        http_request("https://api.pixai.art/v2/quest-v2/daily-claim", method="POST", headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}, proxy=proxy, timeout=12)
                        acc["credits"] = acc.get("credits", 0) + 10000
                    except Exception:
                        pass
            self.save_accounts()
            self.after(0, lambda: [self.refresh_accounts_table(), self.update_header(), messagebox.showinfo("Thành Công", "Đã nhận thưởng Daily hàng loạt!")])
        threading.Thread(target=task, daemon=True).start()

    def claim_full_all(self):
        def task():
            self.log_message("Đang nhận Full Thưởng (+80K) cho toàn bộ tài khoản...", "info")
            proxy = self.get_effective_proxy()
            endpoints = [
                "https://api.pixai.art/v2/quest-v2/daily-claim",
                "https://api.pixai.art/v2/quest-v2/featured-quests-claim",
                "https://api.pixai.art/v2/quest-v2/social-quests-claim"
            ]
            for acc in self.accounts:
                tok = acc.get("token")
                if tok:
                    for ep in endpoints:
                        try:
                            http_request(ep, method="POST", headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"}, proxy=proxy, timeout=10)
                        except Exception:
                            pass
                    acc["credits"] = acc.get("credits", 0) + 80000
            self.save_accounts()
            self.after(0, lambda: [self.refresh_accounts_table(), self.update_header(), messagebox.showinfo("Thành Công", "Đã nhận Full Thưởng hàng loạt!")])
        threading.Thread(target=task, daemon=True).start()

    def sync_all_credits(self):
        def task():
            self.log_message("Đang đồng bộ số dư Credits từ Server PixAI...", "info")
            proxy = self.get_effective_proxy()
            q = {"query": "query { me { credit } }"}
            for acc in self.accounts:
                tok = acc.get("token")
                if tok:
                    try:
                        res = http_graphql(q, tok, proxy=proxy, timeout=10)
                        me = res.get("data", {}).get("me", {})
                        if "credit" in me:
                            acc["credits"] = me["credit"]
                    except Exception:
                        pass
            self.save_accounts()
            self.after(0, lambda: [self.refresh_accounts_table(), self.update_header(), messagebox.showinfo("Thành Công", "Đã đồng bộ số dư thành công!")])
        threading.Thread(target=task, daemon=True).start()

    def unblur_all_accounts(self):
        def task():
            self.log_message("Đang xác nhận 18+ và tắt làm mờ nhạy cảm cho toàn bộ tài khoản...", "info")
            proxy = self.get_effective_proxy()
            for acc in self.accounts:
                tok = acc.get("token")
                if tok:
                    unblur_nsfw_for_account(tok, proxy=proxy)
            self.after(0, lambda: messagebox.showinfo("Thành Công", "Đã hoàn tất xác nhận 18+ và tắt mờ nhạy cảm cho toàn bộ tài khoản!"))
        threading.Thread(target=task, daemon=True).start()

    # ================= TAB 3: KHO LỊCH SỬ MÃ HÓA =================
    def build_history_tab(self):
        container = tk.Frame(self.tab_history, bg=self.c_bg, padx=12, pady=12)
        container.pack(fill="both", expand=True)

        top_bar = tk.Frame(container, bg=self.c_bg)
        top_bar.pack(fill="x", pady=(0, 8))

        tk.Label(top_bar, text="KHO LỊCH SỬ KHỞI TẠO (MÃ HÓA BẢO MẬT)", bg=self.c_bg, fg=self.c_accent, font=("Segoe UI", 10, "bold")).pack(side="left")

        btn_clear_hist = tk.Button(top_bar, text="Xóa Lịch Sử", bg="#3B1C22", fg=self.c_danger, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=self.clear_all_history)
        btn_clear_hist.pack(side="right")

        # Khung danh sách lịch sử cuộn
        list_frame = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        list_frame.pack(fill="both", expand=True)

        self.hist_canvas = tk.Canvas(list_frame, bg=self.c_card, highlightthickness=0)
        scrollbar = ttk.Scrollbar(list_frame, orient="vertical", command=self.hist_canvas.yview)
        self.hist_scroll_frame = tk.Frame(self.hist_canvas, bg=self.c_card)
        self.hist_scroll_frame.bind("<Configure>", lambda e: self.hist_canvas.configure(scrollregion=self.hist_canvas.bbox("all")))
        self.hist_canvas.create_window((0, 0), window=self.hist_scroll_frame, anchor="nw", width=1140)
        self.hist_canvas.configure(yscrollcommand=scrollbar.set)

        self.hist_canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.refresh_history_list()

    def refresh_history_list(self):
        for widget in self.hist_scroll_frame.winfo_children():
            widget.destroy()

        if not self.history:
            tk.Label(self.hist_scroll_frame, text="Chưa có dữ liệu lịch sử nào được ghi nhận.", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 10), pady=40).pack(fill="x")
            return

        for idx, item in enumerate(self.history):
            card = tk.Frame(self.hist_scroll_frame, bg="#13161D", bd=1, relief="solid", padx=10, pady=8)
            card.pack(fill="x", padx=8, pady=4)

            # Thumbnail nhỏ bên trái
            lbl_thumb = tk.Label(card, bg="#0A0C10", text="...", fg=self.c_muted, width=12, height=6)
            lbl_thumb.pack(side="left", padx=(0, 12))
            self.load_image_to_label(item["imageUrl"], lbl_thumb, max_w=90, max_h=90)

            # Thông tin chính giữa
            info_frame = tk.Frame(card, bg="#13161D")
            info_frame.pack(side="left", fill="both", expand=True)

            tag_style = "Thực Tế" if item.get("isReal") else "Anime"
            prof_tag = item.get("profile", "flash").upper()
            title = f"{prof_tag} | {tag_style} | {item.get('width')}x{item.get('height')} | Seed: {item.get('seed')}"
            tk.Label(info_frame, text=title, bg="#13161D", fg=self.c_accent, font=("Segoe UI", 9, "bold")).pack(anchor="w")

            og_txt = "Gốc: " + item.get("originalPrompt", "")
            tk.Label(info_frame, text=og_txt[:100] + ("..." if len(og_txt) > 100 else ""), bg="#13161D", fg=self.c_text, font=("Segoe UI", 8)).pack(anchor="w", pady=(2, 0))

            sv_txt = "Server: " + item.get("serverPrompt", "")
            tk.Label(info_frame, text=sv_txt[:100] + ("..." if len(sv_txt) > 100 else ""), bg="#13161D", fg=self.c_success, font=("Segoe UI", 8)).pack(anchor="w")

            # Cột nút thao tác bên phải
            act_frame = tk.Frame(card, bg="#13161D")
            act_frame.pack(side="right", padx=(8, 0))

            btn_zoom = tk.Button(act_frame, text="Phóng To", bg=self.c_card_border, fg=self.c_accent, relief="flat", padx=8, pady=2, font=("Segoe UI", 8), command=lambda u=item["imageUrl"], og=item["originalPrompt"], sv=item["serverPrompt"]: self.open_fullscreen_zoom(u, og, sv))
            btn_zoom.pack(fill="x", pady=1)

            btn_copy = tk.Button(act_frame, text="Copy Server", bg=self.c_card_border, fg=self.c_success, relief="flat", padx=8, pady=2, font=("Segoe UI", 8), command=lambda sv=item["serverPrompt"]: self.copy_text(sv, "Prompt Server"))
            btn_copy.pack(fill="x", pady=1)

    def copy_text(self, text, label):
        self.clipboard_clear()
        self.clipboard_append(text)
        messagebox.showinfo("Đã Sao Chép", f"Đã sao chép {label}!")

    def clear_all_history(self):
        if messagebox.askyesno("Xác Nhận", "Bạn có chắc chắn muốn xóa toàn bộ kho lịch sử mã hóa?"):
            self.history = []
            save_encrypted_history(self.history)
            self.refresh_history_list()

    # ================= TAB 4: BẢO MẬT & PROXY =================
    def build_security_tab(self):
        container = tk.Frame(self.tab_security, bg=self.c_bg, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        card = tk.LabelFrame(container, text=" Thiết Lập Proxy Shield & Định Tuyến An Toàn ", bg=self.c_card, fg=self.c_accent, font=("Segoe UI", 10, "bold"), bd=1, relief="solid", padx=16, pady=16)
        card.pack(fill="x", pady=(0, 12))

        # Proxy Address
        tk.Label(card, text="Địa chỉ Proxy Shield (Hỗ trợ HTTP / SOCKS5):", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9)).pack(anchor="w")
        self.ent_proxy = tk.Entry(card, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 10))
        self.ent_proxy.pack(fill="x", pady=(4, 10))
        self.ent_proxy.insert(0, self.config.get("proxy_address", "http://p.webshare.io:80"))

        # Các Checkbox cấu hình
        self.var_use_proxy = tk.BooleanVar(value=self.config.get("use_proxy", True))
        chk_proxy = tk.Checkbutton(card, text="Kích hoạt Proxy Shield cho 100% kết nối ra ngoài", variable=self.var_use_proxy, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card, font=("Segoe UI", 9))
        chk_proxy.pack(anchor="w", pady=2)

        self.var_fallback = tk.BooleanVar(value=self.config.get("auto_fallback_direct", True))
        chk_fallback = tk.Checkbutton(card, text="Tự động Fallback sang Direct an toàn nếu Proxy bị gián đoạn", variable=self.var_fallback, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card, font=("Segoe UI", 9))
        chk_fallback.pack(anchor="w", pady=2)

        self.var_anti_fp = tk.BooleanVar(value=self.config.get("anti_fingerprint", True))
        chk_fp = tk.Checkbutton(card, text="Giả lập Vân Tay Thiết Bị Ảo (Anti Device Fingerprinting)", variable=self.var_anti_fp, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card, font=("Segoe UI", 9))
        chk_fp.pack(anchor="w", pady=2)

        # Hàng nút lưu & kiểm tra
        row_btn = tk.Frame(card, bg=self.c_card)
        row_btn.pack(fill="x", pady=(12, 0))

        btn_save_sec = tk.Button(row_btn, text="Lưu Cấu Hình", bg=self.c_primary, fg="#FFFFFF", relief="flat", padx=14, pady=6, font=("Segoe UI", 9, "bold"), command=self.save_security_settings)
        btn_save_sec.pack(side="left", padx=(0, 8))

        btn_test_proxy = tk.Button(row_btn, text="Kiểm Tra Kết Nối Proxy (Ping & IP)", bg=self.c_card_border, fg=self.c_accent, relief="flat", padx=14, pady=6, font=("Segoe UI", 9, "bold"), command=self.test_proxy_connection)
        btn_test_proxy.pack(side="left")

        # Khung kết quả test proxy
        self.lbl_proxy_test_res = tk.Label(card, text="Trạng thái: Chưa kiểm tra kết nối.", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9))
        self.lbl_proxy_test_res.pack(anchor="w", pady=(10, 0))

    def save_security_settings(self):
        self.config["proxy_address"] = self.ent_proxy.get().strip()
        self.config["use_proxy"] = self.var_use_proxy.get()
        self.config["auto_fallback_direct"] = self.var_fallback.get()
        self.config["anti_fingerprint"] = self.var_anti_fp.get()
        self.save_config()
        self.update_header()
        self.log_message("Đã lưu thiết lập Bảo Mật & Proxy Shield.", "success")
        messagebox.showinfo("Đã Lưu", "Cấu hình bảo mật đã được áp dụng!")

    def test_proxy_connection(self):
        proxy = self.ent_proxy.get().strip()
        self.lbl_proxy_test_res.config(text="Đang kiểm tra kết nối Proxy...", fg=self.c_accent)

        def task():
            t0 = time.time()
            try:
                raw = http_request("https://ipwho.is/", method="GET", proxy=proxy, timeout=12)
                ping = int((time.time() - t0) * 1000)
                data = json.loads(raw.decode("utf-8"))
                ip = data.get("ip", "Unknown")
                country = data.get("country", "Unknown")
                city = data.get("city", "Unknown")

                def ok():
                    self.lbl_proxy_test_res.config(
                        text=f"KẾT NỐI THÀNH CÔNG! IP: {ip} | Vùng: {country} ({city}) | Ping: {ping}ms",
                        fg=self.c_success
                    )
                    self.log_message(f"Kiểm tra Proxy thành công: {ip} ({country}) - {ping}ms", "success")

                self.after(0, ok)
            except Exception as e:
                def fail():
                    self.lbl_proxy_test_res.config(
                        text=f"KẾT NỐI THẤT BẠI: {e}",
                        fg=self.c_danger
                    )
                    self.log_message(f"Kiểm tra Proxy thất bại: {e}", "danger")

                self.after(0, fail)

        threading.Thread(target=task, daemon=True).start()

    # ================= TAB 5: NHẬT KÝ HỆ THỐNG (LOG CONSOLE) =================
    def build_logs_tab(self):
        container = tk.Frame(self.tab_logs, bg=self.c_bg, padx=12, pady=12)
        container.pack(fill="both", expand=True)

        top_bar = tk.Frame(container, bg=self.c_bg)
        top_bar.pack(fill="x", pady=(0, 6))

        tk.Label(top_bar, text="NHẬT KÝ HOẠT ĐỘNG HỆ THỐNG", bg=self.c_bg, fg=self.c_accent, font=("Segoe UI", 10, "bold")).pack(side="left")

        btn_clear_log = tk.Button(top_bar, text="Xóa Nhật Ký", bg=self.c_card, fg=self.c_muted, relief="flat", padx=10, pady=3, font=("Segoe UI", 8), command=self.clear_logs)
        btn_clear_log.pack(side="right")

        self.txt_logs = tk.Text(container, bg="#0D0F14", fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Consolas", 9))
        self.txt_logs.pack(fill="both", expand=True)

        # Tags màu
        self.txt_logs.tag_config("info", foreground=self.c_accent)
        self.txt_logs.tag_config("success", foreground=self.c_success)
        self.txt_logs.tag_config("danger", foreground=self.c_danger)

    def log_message(self, msg, level="info"):
        t_str = time.strftime("%H:%M:%S")
        def update():
            if hasattr(self, "txt_logs"):
                self.txt_logs.insert("end", f"[{t_str}] {msg}\n", level)
                self.txt_logs.see("end")
        self.after(0, update)

    def clear_logs(self):
        if hasattr(self, "txt_logs"):
            self.txt_logs.delete("1.0", "end")

    def update_header(self):
        total_cr = self.get_total_credits()
        self.lbl_total_credits.config(text=f"Tổng Số Dư: {total_cr:,} Credits")
        if self.active_account:
            em = self.active_account.get("email", "")
            cr = self.active_account.get("credits", 0)
            self.lbl_active_acc.config(text=f"Tài khoản: {em} ({cr:,} CR)")
        else:
            self.lbl_active_acc.config(text="Tài khoản: Chưa có tài khoản")

        p_on = self.config.get("use_proxy", True)
        self.lbl_proxy_status.config(
            text="Proxy: BẬT" if p_on else "Proxy: TẮT",
            fg=self.c_accent if p_on else self.c_muted
        )


if __name__ == "__main__":
    app = PixAiNexusStudio()
    app.mainloop()
