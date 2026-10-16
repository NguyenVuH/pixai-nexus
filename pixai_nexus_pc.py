# -*- coding: utf-8 -*-
"""
PIXAI NEXUS PC STUDIO - PRO EDITION v3.0 (MONOCHROME DARK OCD)
==============================================================
Giao diện đơn sắc tối OLED Minimalist chuẩn mực (#0A0A0A / #141414 / #222222).
Gỡ bỏ hoàn toàn mọi ký tự ngoặc vuông [...], bố cục căn chỉnh cân đối, xuống dòng chuẩn.
Tùy chọn kích thước ảnh: 3 chuẩn XL 1104x1824, L 944x1584, M 768x1280, Tùy chỉnh (W x H), Auto theo ảnh mẫu.
Tỷ lệ khung hình: 3:5, 5:3, 1:1, 9:16, 16:9, 3:4, 4:3, 2:3, 3:2, 7:9, 9:7, 1:3, 3:1 (Toàn bộ là nút bấm chip, không dùng combobox).
Minh họa trực quan tỷ lệ hình học chuẩn xác theo chiều rộng x chiều cao thực tế.
Đính kèm đa ảnh tham chiếu (Img2Img), cuộn ngang xem thumbnail có nút gỡ riêng từng ảnh; tự động phục hồi LoRA Thực Tế khi gỡ hết.
Tự động tính chi phí Credit thời gian thực và tự động xoay tua tài khoản có đủ Credit.
Tự động đăng ký tài khoản mới ngầm và tự động nhận toàn bộ phần thưởng Daily & Quests.
Mở khóa 18+ và bỏ làm mờ nhạy cảm chuẩn xác với mutation setPreferences và lưu bằng chứng phản hồi.
Nhập hàng loạt tài khoản từ danh sách text hoặc JSON; sao lưu xuất dữ liệu JSON ra file & clipboard.
Kho Lịch Sử mã hóa bảo mật toàn diện trên ổ đĩa kèm cơ chế khóa mật khẩu / PIN.
"""

import os
import sys
import json
import time
import random
import threading
import hashlib
import base64
import re
import urllib.request
import urllib.error
import requests
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
        "cost1": 1900,
        "cost4": 4400
    },
    "pro": {
        "name": "Tsubaki 3 PRO (Chất Lượng)",
        "modelId": "2024383379556065549",
        "profile": "pro",
        "hasNegative": True,
        "cost1": 4000,
        "cost4": 9500
    },
    "ultra": {
        "name": "Tsubaki 3 ULTRA (Đỉnh Cao)",
        "modelId": "2024383379556065549",
        "profile": "ultra",
        "hasNegative": True,
        "cost1": 5500,
        "cost4": 13000
    }
}

def calculate_task_cost(profile: str, width: int, height: int, batch: int, has_reference_images: bool = False) -> int:
    pixels = width * height
    if pixels <= 768 * 1280:
        tier = "m"
    elif pixels <= 944 * 1584:
        tier = "l"
    else:
        tier = "xl"

    pricing = {
        "flash": {
            "m":  {1: 1900, 4: 4400},
            "l":  {1: 2400, 4: 5500},
            "xl": {1: 3000, 4: 7000}
        },
        "pro": {
            "m":  {1: 4000, 4: 9500},
            "l":  {1: 5000, 4: 12000},
            "xl": {1: 6500, 4: 15500}
        },
        "ultra": {
            "m":  {1: 5500, 4: 13000},
            "l":  {1: 7000, 4: 16500},
            "xl": {1: 9000, 4: 21500}
        }
    }
    prof = (profile or "flash").lower()
    if prof not in pricing:
        prof = "flash"
    b = 4 if batch == 4 else 1
    base = pricing[prof][tier][b]
    if not has_reference_images:
        base += 500
    return base

REALISTIC_LORA = {
    "versionId": "2059543732132340175",
    "weight": 0.70,
    "triggerWords": "impossiblebeauty_sr, unrealistically beautiful face, photorealistic anatomy, idealized facial proportions, luminous eyes, flawless skin, realistic hair, natural human proportions"
}

DEFAULT_NEGATIVE = "lowres, worst quality, low quality, bad anatomy, explicit"

def normalize_proxy_url(proxy_str):
    if not proxy_str or not str(proxy_str).strip():
        return None
    s = str(proxy_str).strip().strip("'\"`")
    if s.lower().startswith("socks5h://"):
        return s
    if s.lower().startswith("socks5://"):
        return "socks5h://" + s[9:]
    if s.lower().startswith("socks://"):
        return "socks5h://" + s[8:]
    if s.lower().startswith("http://") or s.lower().startswith("https://"):
        return s

    if "@" in s:
        return "http://" + s

    parts = s.split(":")
    if len(parts) == 4:
        if "." in parts[0] and parts[1].isdigit():
            # host:port:user:pass -> http://user:pass@host:port
            return f"http://{parts[2]}:{parts[3]}@{parts[0]}:{parts[1]}"
        elif "." in parts[2] and parts[3].isdigit():
            # user:pass:host:port -> http://user:pass@host:port
            return f"http://{parts[0]}:{parts[1]}@{parts[2]}:{parts[3]}"
    elif len(parts) == 2 and parts[1].isdigit():
        return f"http://{parts[0]}:{parts[1]}"

    return "http://" + s

def extract_proxies_from_command_or_text(raw_input):
    if not raw_input or not raw_input.strip():
        return []
    text = raw_input.strip()
    extracted = []

    m_user = re.search(r'(?:-U|--proxy-user)\s+[\'"]?([^\'"\s]+:[^\'"\s]+)[\'"]?', text)
    creds = m_user.group(1) if m_user else None

    hosts = re.findall(r'(?:-x|--proxy|--socks5|--socks5-hostname)\s+[\'"]?([^\'"\s]+)[\'"]?', text)
    is_socks = "--socks5" in text
    for h in hosts:
        if creds and "@" not in h:
            proto = "socks5://" if is_socks else "http://"
            clean_h = re.sub(r'^(?i)(https?|socks5h?|socks)://', '', h)
            norm = normalize_proxy_url(f"{proto}{creds}@{clean_h}")
        else:
            norm = normalize_proxy_url(h)
        if norm and norm not in extracted:
            extracted.append(norm)

    urls = re.findall(r'(?i)\b((?:https?|socks5h?|socks)://[^\s\'"`<>]+)', text)
    for u in urls:
        norm = normalize_proxy_url(u)
        if norm and norm not in extracted:
            extracted.append(norm)

    tokens = re.split(r'[\r\n\s,;]+', text)
    for tok in tokens:
        clean = tok.strip().strip("'\"`()[]")
        if not clean or clean.lower().startswith("https://dashboard.webshare.io") or clean.lower().startswith("https://api.pixai.art") or clean.lower().startswith("https://pixai.art"):
            continue
        norm = normalize_proxy_url(clean)
        if norm and norm not in extracted:
            extracted.append(norm)

    return extracted

def fetch_webshare_proxies(token_or_input):
    if not token_or_input or not token_or_input.strip():
        return []
    trimmed = token_or_input.strip()

    # Case 1: JSON payload
    if trimmed.startswith("{") and '"results"' in trimmed:
        try:
            data = json.loads(trimmed)
            res = []
            for item in data.get("results", []):
                h = item.get("proxy_address")
                p = item.get("port")
                if not p and isinstance(item.get("ports"), dict):
                    p = item["ports"].get("http", item["ports"].get("socks5"))
                u = item.get("username")
                pw = item.get("password")
                if h and p:
                    if u and pw:
                        res.append(f"http://{u}:{pw}@{h}:{p}")
                    else:
                        res.append(f"http://{h}:{p}")
            if res:
                return res
        except Exception:
            pass

    clean_token = re.sub(r'(?i)^(Token|Bearer)\s+', '', trimmed)
    m_auth = re.search(r'Authorization:\s*(?:Token|Bearer)\s+([a-zA-Z0-9_\-]+)', trimmed, re.I)
    if m_auth:
        clean_token = m_auth.group(1).strip()

    # Case 2: Webshare Download URL
    if "proxy.webshare.io/api/v2/proxy/list/download/" in trimmed.lower():
        m_dl = re.search(r'(https://proxy\.webshare\.io/api/v2/proxy/list/download/[^\s\'"`<>]+)', trimmed, re.I)
        if m_dl:
            dl_url = m_dl.group(1)
            headers = {}
            if len(clean_token) >= 20:
                headers["Authorization"] = f"Token {clean_token}"
            try:
                r = requests.get(dl_url, headers=headers, timeout=15)
                if r.status_code == 200:
                    proxies = extract_proxies_from_command_or_text(r.text)
                    if proxies:
                        return proxies
            except Exception:
                pass

    # Case 3: Webshare API Token
    if re.match(r'^[a-zA-Z0-9_\-]{20,64}$', clean_token):
        headers = {
            "Authorization": f"Token {clean_token}",
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PixAI-Nexus"
        }
        url = "https://proxy.webshare.io/api/v2/proxy/list/?mode=direct&page=1&page_size=100"
        for _ in range(5):
            try:
                r = requests.get(url, headers=headers, timeout=15)
                if r.status_code == 200:
                    data = r.json()
                    res = []
                    for item in data.get("results", []):
                        h = item.get("proxy_address")
                        p = item.get("port")
                        if not p and isinstance(item.get("ports"), dict):
                            p = item["ports"].get("http", item["ports"].get("socks5"))
                        u = item.get("username")
                        pw = item.get("password")
                        if h and p:
                            if u and pw:
                                res.append(f"http://{u}:{pw}@{h}:{p}")
                            else:
                                res.append(f"http://{h}:{p}")
                    if res:
                        return res
                time.sleep(2)
            except Exception:
                break

    # Case 4: Text or curl lines
    return extract_proxies_from_command_or_text(trimmed)

def http_request(url, method="POST", data=None, headers=None, proxy=None, timeout=30):
    if headers is None:
        headers = {}
    if "User-Agent" not in headers:
        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    clean_proxy = normalize_proxy_url(proxy)
    proxies = None
    if clean_proxy:
        proxies = {
            "http": clean_proxy,
            "https": clean_proxy
        }

    try:
        if isinstance(data, (dict, list)):
            resp = requests.request(method, url, json=data, headers=headers, proxies=proxies, timeout=timeout)
        elif isinstance(data, (bytes, str)):
            resp = requests.request(method, url, data=data, headers=headers, proxies=proxies, timeout=timeout)
        else:
            resp = requests.request(method, url, headers=headers, proxies=proxies, timeout=timeout)
        resp.raise_for_status()
        return resp.content
    except Exception as req_err:
        # Fallback to urllib only if requests had an internal issue and no proxy was requested
        if not proxies:
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
            opener = urllib.request.build_opener()
            with opener.open(req, timeout=timeout) as resp:
                return resp.read()
        # If proxy was set and failed, strictly re-raise to prevent IP leak
        raise req_err

def http_graphql(payload, token, proxy=None, timeout=30):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/graphql-response+json,application/json;q=0.9",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    raw = http_request(GRAPHQL_URL, method="POST", data=payload, headers=headers, proxy=proxy, timeout=timeout)
    return json.loads(raw.decode("utf-8"))

def unblur_nsfw_for_account(token, proxy=None, proof_collector=None):
    """Xác nhận người lớn (Adult) và mở khóa 18+ toàn diện qua REST Content-Safety API & GraphQL setPreferences"""
    clean_token = token.replace("Bearer ", "").strip()
    headers = {
        "Authorization": f"Bearer {clean_token}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    # 1. Gọi trực tiếp REST API Content Safety Preferences (giải quyết triệt để lỗi 'nsfw prompts detected adult not set')
    safety_endpoints = [
        ("https://api.pixai.art/v2/me/content-safety/preferences/age-verified", {"value": "adult"}),
        ("https://api.pixai.art/v2/me/content-safety/preferences/show-nsfw", {"value": "show"}),
        ("https://api.pixai.art/v2/me/content-safety/preferences/blur-nsfw", {"value": "show"})
    ]
    for url, body_data in safety_endpoints:
        try:
            http_request(url, method="PUT", data=body_data, headers=headers, proxy=proxy, timeout=8)
        except Exception:
            pass

    # 2. Đồng bộ qua GraphQL mutation setPreferences
    try:
        mutation = {
            "query": """mutation setPreferences($value: JSONObject!) {
  setPreferences(value: $value)
}""",
            "variables": {
                "value": {
                    "ageVerified": "adult",
                    "isAdult": True,
                    "adult": True,
                    "ageConfirmed": True,
                    "ageVerificationStatus": "VERIFIED",
                    "isNsfw": True,
                    "showNsfw": True,
                    "blurNsfw": False,
                    "nsfwFilter": False
                }
            }
        }
        res = http_graphql(mutation, clean_token, proxy=proxy, timeout=12)

        # 3. Lấy bằng chứng trực tiếp từ máy chủ PixAI
        try:
            raw_verify = http_request("https://api.pixai.art/v2/me/content-safety/preferences", method="GET", headers=headers, proxy=proxy, timeout=8)
            verify_data = json.loads(raw_verify.decode("utf-8"))
            if proof_collector is not None:
                proof_collector.append(f"Content-Safety: {json.dumps(verify_data, ensure_ascii=False)}")
        except Exception:
            if proof_collector is not None:
                proof_collector.append(json.dumps(res, ensure_ascii=False))

        return "errors" not in res
    except Exception as e:
        if proof_collector is not None:
            proof_collector.append(f"Lỗi: {e}")
        return False

def get_account_quota(token, proxy=None):
    try:
        headers = {"Authorization": f"Bearer {token}", "User-Agent": "Mozilla/5.0"}
        raw = http_request("https://api.pixai.art/v2/user/me/quota", method="GET", headers=headers, proxy=proxy, timeout=10)
        data = json.loads(raw.decode("utf-8"))
        return data.get("amount", 0)
    except Exception:
        return 0

def claim_daily(token, proxy=None):
    try:
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
        raw = http_request("https://api.pixai.art/v2/claim/pixai-daily-credits", method="POST", data={"id": "pixai-daily-credits"}, headers=headers, proxy=proxy, timeout=10)
        return True
    except Exception:
        return False

def claim_all_quests(token, proxy=None):
    success_count = 0
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
    for p in ["discord", "twitter", "instagram", "youtube", "tiktok"]:
        try:
            http_request("https://api.pixai.art/v2/quest-v2/report-social-follow", method="POST", data={"platform": p}, headers=headers, proxy=proxy, timeout=6)
            success_count += 1
        except Exception:
            pass
    for v in ["https://youtu.be/xGBoyWR4MBQ", "https://agent.pixai.art/"]:
        try:
            http_request("https://api.pixai.art/v2/quest-v2/report-visit", method="POST", data={"url": v}, headers=headers, proxy=proxy, timeout=6)
            success_count += 1
        except Exception:
            pass
    return success_count


class PixAiNexusStudio(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("PixAI Nexus Studio - AI Generation & Account Manager (Monochrome Dark)")
        self.geometry("1280x880")
        self.minsize(1080, 750)
        
        icon_path = os.path.join(BASE_DIR, "nexus_icon.ico")
        if os.path.exists(icon_path):
            try:
                self.iconbitmap(icon_path)
            except Exception:
                pass

        self.load_config()
        self.load_accounts()
        self.history = load_encrypted_history()

        # State Variables
        self.current_style = tk.StringVar(value="real")  # anime | real
        self.current_profile = tk.StringVar(value="flash") # flash | pro | ultra
        self.current_size_tier = tk.StringVar(value=self.config.get("size_tier", "xl")) # xl | l | m | custom | auto
        self.current_ratio = tk.StringVar(value=self.config.get("ratio", "9:16"))
        self.custom_w = tk.StringVar(value=str(self.config.get("custom_w", 1024)))
        self.custom_h = tk.StringVar(value=str(self.config.get("custom_h", 1024)))
        self.batch_count = tk.IntVar(value=1)

        # Multi-image state
        self.attached_media_list = [] # List of {"path": str, "mediaId": str, "thumb": PhotoImage}
        self.first_attached_dims = None

        self.generated_images = []
        self.current_image_idx = 0
        self.last_original_prompt = ""
        self.last_server_prompt = ""
        self.is_generating = False
        self.is_history_unlocked = not bool(self.config.get("history_pin_hash"))

        self.setup_theme()
        self.build_ui()
        self.refresh_accounts_table()
        self.refresh_history_table()
        self.set_ratio(self.current_ratio.get())
        self.update_dimensions_and_preview()
        self.update_live_cost()
        self.update_header()
        self.log_message("Hệ thống PixAI Nexus Studio v3.0 Monochrome Dark đã sẵn sàng.", "info")

    def load_config(self):
        default_config = {
            "use_proxy": True,
            "proxy_address": "",
            "proxy_pool": [],
            "auto_rotate_proxies": True,
            "strict_kill_switch": True,
            "auto_fallback_direct": False,
            "auto_rotate_accounts": True,
            "size_tier": "xl",
            "ratio": "9:16",
            "custom_w": 1024,
            "custom_h": 1024,
            "history_pin_hash": ""
        }
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    self.config = json.load(f)
                for k, v in default_config.items():
                    if k not in self.config:
                        self.config[k] = v
            except Exception:
                self.config = default_config
        else:
            self.config = default_config
            self.save_config()

    def save_config(self):
        try:
            if hasattr(self, "current_size_tier"):
                self.config["size_tier"] = self.current_size_tier.get()
            if hasattr(self, "current_ratio"):
                self.config["ratio"] = self.current_ratio.get()
            if hasattr(self, "custom_w"):
                try:
                    self.config["custom_w"] = int(self.custom_w.get())
                except Exception:
                    pass
            if hasattr(self, "custom_h"):
                try:
                    self.config["custom_h"] = int(self.custom_h.get())
                except Exception:
                    pass
            if hasattr(self, "txt_proxy_pool"):
                raw_txt = self.txt_proxy_pool.get("1.0", "end").strip()
                extracted = extract_proxies_from_command_or_text(raw_txt)
                self.config["proxy_pool"] = extracted
                if extracted:
                    self.config["proxy_address"] = extracted[0]
            elif hasattr(self, "ent_proxy_addr"):
                self.config["proxy_address"] = self.ent_proxy_addr.get().strip()
            if hasattr(self, "var_use_proxy"):
                self.config["use_proxy"] = self.var_use_proxy.get()
            if hasattr(self, "var_auto_rotate_proxies"):
                self.config["auto_rotate_proxies"] = self.var_auto_rotate_proxies.get()
            if hasattr(self, "var_strict_kill_switch"):
                self.config["strict_kill_switch"] = self.var_strict_kill_switch.get()
            if hasattr(self, "var_auto_fallback"):
                self.config["auto_fallback_direct"] = self.var_auto_fallback.get()
            if hasattr(self, "var_auto_rotate"):
                self.config["auto_rotate_accounts"] = self.var_auto_rotate.get()
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2, ensure_ascii=False)
        except Exception:
            pass

    def load_accounts(self):
        self.accounts = []
        self.active_account = None
        if os.path.exists(ACCOUNTS_FILE):
            try:
                with open(ACCOUNTS_FILE, "r", encoding="utf-8-sig") as f:
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
        if not self.config.get("use_proxy", True):
            return None
        pool = self.config.get("proxy_pool", [])
        if not pool:
            single = self.config.get("proxy_address", "").strip()
            return normalize_proxy_url(single) if single else None

        if not self.config.get("auto_rotate_proxies", True) or len(pool) == 1:
            return pool[0]

        cursor = getattr(self, "_proxy_cursor", 0)
        idx = cursor % len(pool)
        self._proxy_cursor = idx + 1
        return pool[idx]

    def setup_theme(self):
        # OLED True Dark Palette (#0A0A0A / #141414 / #222222)
        self.c_bg = "#0A0A0A"          # True black background
        self.c_card = "#141414"        # Surface
        self.c_card_border = "#222222" # Crisp subtle border
        self.c_input = "#181818"       # Dark input
        self.c_input_border = "#2A2A2A"
        self.c_text = "#FFFFFF"        # Pure white text
        self.c_muted = "#9E9E9E"       # Crisp secondary text
        self.c_active = "#2A2A2A"      # Active chip background
        self.c_active_stroke = "#E0E0E0"
        self.c_success = "#81C784"     # Soft green
        self.c_danger = "#E57373"      # Soft red

        self.configure(bg=self.c_bg)

        self.style = ttk.Style(self)
        self.style.theme_use("clam")
        self.style.configure(".", background=self.c_bg, foreground=self.c_text, font=("Segoe UI", 9))
        self.style.configure("TFrame", background=self.c_bg)
        self.style.configure("Card.TFrame", background=self.c_card, relief="solid", borderwidth=1)
        self.style.configure("TNotebook", background=self.c_bg, borderwidth=0)
        self.style.configure("TNotebook.Tab", background=self.c_card, foreground=self.c_muted, padding=[16, 8], font=("Segoe UI", 9, "bold"))
        self.style.map("TNotebook.Tab",
            background=[("selected", self.c_active)],
            foreground=[("selected", self.c_text)]
        )
        self.style.configure("Horizontal.TProgressbar", troughcolor=self.c_input, background="#E0E0E0", bordercolor=self.c_input_border)

    def build_ui(self):
        # 1. HEADER BAR
        self.header_frame = tk.Frame(self, bg=self.c_card, height=48, padx=16, pady=8)
        self.header_frame.pack(side="top", fill="x")

        self.lbl_brand = tk.Label(self.header_frame, text="PIXAI NEXUS STUDIO", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 12, "bold"))
        self.lbl_brand.pack(side="left")

        self.lbl_total_credits = tk.Label(self.header_frame, text="Tổng: 0 CR", bg=self.c_card, fg=self.c_success, font=("Segoe UI", 10, "bold"))
        self.lbl_total_credits.pack(side="left", padx=(20, 0))

        self.lbl_active_acc = tk.Label(self.header_frame, text="Tài khoản: Chưa kích hoạt | Số dư: 0 CR", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9))
        self.lbl_active_acc.pack(side="left", padx=(15, 0))

        self.lbl_proxy_status = tk.Label(self.header_frame, text="Proxy Shield: BẬT", bg="#1E1E1E", fg=self.c_text, font=("Segoe UI", 9, "bold"), padx=10, pady=2)
        self.lbl_proxy_status.pack(side="right")

        tk.Frame(self, bg=self.c_card_border, height=1).pack(fill="x")

        # 2. TAB NOTEBOOK
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=12, pady=8)

        self.tab_studio = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_studio, text=" STUDIO ")

        self.tab_accounts = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_accounts, text=" TÀI KHOẢN ")

        self.tab_history = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_history, text=" LỊCH SỬ ")

        self.tab_security = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_security, text=" BẢO MẬT & PROXY ")

        self.tab_logs = tk.Frame(self.notebook, bg=self.c_bg)
        self.notebook.add(self.tab_logs, text=" NHẬT KÝ ")

        self.build_studio_tab()
        self.build_accounts_tab()
        self.build_history_tab()
        self.build_security_tab()
        self.build_logs_tab()

    # ================= TAB 1: STUDIO =================
    def build_studio_tab(self):
        container = tk.Frame(self.tab_studio, bg=self.c_bg)
        container.pack(fill="both", expand=True, padx=8, pady=8)

        left_pane = tk.Frame(container, bg=self.c_bg, width=500)
        left_pane.pack(side="left", fill="both", padx=(0, 10))
        left_pane.pack_propagate(False)

        canvas = tk.Canvas(left_pane, bg=self.c_bg, highlightthickness=0)
        scrollbar = ttk.Scrollbar(left_pane, orient="vertical", command=canvas.yview)
        scrollable_frame = tk.Frame(canvas, bg=self.c_bg)
        scrollable_frame.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=scrollable_frame, anchor="nw", width=480)
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # 1. Phong Cách (Anime vs Thực Tế)
        grp_style = tk.LabelFrame(scrollable_frame, text=" Phong Cách Nghệ Thuật ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_style.pack(fill="x", pady=(0, 8))

        row_style = tk.Frame(grp_style, bg=self.c_card)
        row_style.pack(fill="x")

        self.btn_style_anime = tk.Button(row_style, text="Anime Nguyên Bản", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=12, pady=6, font=("Segoe UI", 9), command=lambda: self.set_style("anime"))
        self.btn_style_anime.pack(side="left", fill="x", expand=True, padx=(0, 4))

        self.btn_style_real = tk.Button(row_style, text="Thực Tế (LoRA Chuẩn)", bg=self.c_active, fg=self.c_text, relief="solid", bd=1, padx=12, pady=6, font=("Segoe UI", 9, "bold"), command=lambda: self.set_style("real"))
        self.btn_style_real.pack(side="left", fill="x", expand=True, padx=(4, 0))

        self.lbl_lora_status = tk.Label(grp_style, text="LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)", bg=self.c_card, fg=self.c_success, font=("Segoe UI", 8))
        self.lbl_lora_status.pack(anchor="w", pady=(6, 0))

        # 2. Engine Profile
        grp_profile = tk.LabelFrame(scrollable_frame, text=" Chế Độ Engine (GPU Profile) ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_profile.pack(fill="x", pady=(0, 8))

        row_prof = tk.Frame(grp_profile, bg=self.c_card)
        row_prof.pack(fill="x")

        self.btn_prof_flash = tk.Button(row_prof, text="Flash", bg=self.c_active, fg=self.c_text, relief="solid", bd=1, padx=8, pady=5, font=("Segoe UI", 9, "bold"), command=lambda: self.set_profile("flash"))
        self.btn_prof_flash.pack(side="left", fill="x", expand=True, padx=(0, 3))

        self.btn_prof_pro = tk.Button(row_prof, text="Pro", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=8, pady=5, font=("Segoe UI", 9), command=lambda: self.set_profile("pro"))
        self.btn_prof_pro.pack(side="left", fill="x", expand=True, padx=(3, 3))

        self.btn_prof_ultra = tk.Button(row_prof, text="Ultra", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=8, pady=5, font=("Segoe UI", 9), command=lambda: self.set_profile("ultra"))
        self.btn_prof_ultra.pack(side="left", fill="x", expand=True, padx=(3, 0))

        # 3. Kích Thước Ảnh (Tùy Chọn Size: XL, L, M, Tùy Chỉnh, Auto)
        grp_size = tk.LabelFrame(scrollable_frame, text=" Kích Thước Ảnh (Tùy Chọn Size) ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_size.pack(fill="x", pady=(0, 8))

        row_sizes1 = tk.Frame(grp_size, bg=self.c_card)
        row_sizes1.pack(fill="x")

        self.btn_size_xl = tk.Button(row_sizes1, text="XL 1104x1824", bg=self.c_active, fg=self.c_text, relief="solid", bd=1, padx=4, pady=4, font=("Segoe UI", 8, "bold"), command=lambda: self.set_size_tier("xl"))
        self.btn_size_xl.pack(side="left", fill="x", expand=True, padx=(0, 2))

        self.btn_size_l = tk.Button(row_sizes1, text="L 944x1584", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=4, pady=4, font=("Segoe UI", 8), command=lambda: self.set_size_tier("l"))
        self.btn_size_l.pack(side="left", fill="x", expand=True, padx=(2, 2))

        self.btn_size_m = tk.Button(row_sizes1, text="M 768x1280", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=4, pady=4, font=("Segoe UI", 8), command=lambda: self.set_size_tier("m"))
        self.btn_size_m.pack(side="left", fill="x", expand=True, padx=(2, 0))

        row_sizes2 = tk.Frame(grp_size, bg=self.c_card)
        row_sizes2.pack(fill="x", pady=(4, 0))

        self.btn_size_custom = tk.Button(row_sizes2, text="Tùy Chỉnh Size", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=4, pady=4, font=("Segoe UI", 8), command=lambda: self.set_size_tier("custom"))
        self.btn_size_custom.pack(side="left", fill="x", expand=True, padx=(0, 2))

        self.btn_size_auto = tk.Button(row_sizes2, text="Auto Theo Ảnh Mẫu", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=4, pady=4, font=("Segoe UI", 8), command=lambda: self.set_size_tier("auto"))
        self.btn_size_auto.pack(side="left", fill="x", expand=True, padx=(2, 0))

        # Khung nhập kích thước tùy chỉnh
        self.frame_custom_dims = tk.Frame(grp_size, bg=self.c_card)
        self.frame_custom_dims.pack(fill="x", pady=(6, 0))
        tk.Label(self.frame_custom_dims, text="W:", bg=self.c_card, fg=self.c_muted).pack(side="left")
        self.ent_custom_w = tk.Entry(self.frame_custom_dims, textvariable=self.custom_w, width=6, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid")
        self.ent_custom_w.pack(side="left", padx=(2, 8))
        tk.Label(self.frame_custom_dims, text="H:", bg=self.c_card, fg=self.c_muted).pack(side="left")
        self.ent_custom_h = tk.Entry(self.frame_custom_dims, textvariable=self.custom_h, width=6, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid")
        self.ent_custom_h.pack(side="left", padx=(2, 8))
        self.custom_w.trace_add("write", lambda *args: self.on_custom_dim_changed())
        self.custom_h.trace_add("write", lambda *args: self.on_custom_dim_changed())
        self.frame_custom_dims.pack_forget()

        # 4. Tỷ Lệ Khung Hình & Minh Họa Trực Quan (Từng tỷ lệ có minh họa hình học riêng)
        grp_ratio = tk.LabelFrame(scrollable_frame, text=" Tỷ Lệ Khung Hình & Minh Họa Trực Quan ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_ratio.pack(fill="x", pady=(0, 8))

        self.lbl_ratio_dims = tk.Label(grp_ratio, text="Tỷ lệ: 9:16 • Kích thước: 1104 x 1824 px (XL)", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9), anchor="w")
        self.lbl_ratio_dims.pack(fill="x", pady=(0, 6))

        frame_ratio_cards = tk.Frame(grp_ratio, bg=self.c_card)
        frame_ratio_cards.pack(fill="x", pady=(2, 6))

        self.ratio_cards = {}
        ratio_list = [
            ("9:16", 10, 20),
            ("16:9", 20, 10),
            ("1:1", 16, 16),
            ("3:4", 12, 17),
            ("4:3", 17, 12),
            ("2:3", 12, 18),
            ("3:2", 18, 12),
            ("3:5", 11, 19),
            ("5:3", 19, 11),
            ("7:9", 13, 17),
            ("9:7", 17, 13),
            ("1:3", 7, 21),
            ("3:1", 21, 7),
        ]

        row1 = tk.Frame(frame_ratio_cards, bg=self.c_card)
        row1.pack(fill="x", pady=(0, 4))
        row2 = tk.Frame(frame_ratio_cards, bg=self.c_card)
        row2.pack(fill="x")

        for idx, (r_name, sw, sh) in enumerate(ratio_list):
            parent_row = row1 if idx < 7 else row2
            card = tk.Frame(parent_row, bg=self.c_input, bd=1, relief="solid", padx=2, pady=3, cursor="hand2")
            card.pack(side="left", fill="x", expand=True, padx=2)

            cv = tk.Canvas(card, width=24, height=24, bg=self.c_input, highlightthickness=0)
            cv.pack()
            x0 = (24 - sw) / 2
            y0 = (24 - sh) / 2
            shape_id = cv.create_rectangle(x0, y0, x0 + sw, y0 + sh, fill="#1A1A1A", outline="#555555", width=1)

            lbl = tk.Label(card, text=r_name, bg=self.c_input, fg=self.c_muted, font=("Segoe UI", 7, "bold"))
            lbl.pack(pady=(2, 0))

            for widget in (card, cv, lbl):
                widget.bind("<Button-1>", lambda e, rn=r_name: self.set_ratio(rn))

            self.ratio_cards[r_name] = {
                "frame": card,
                "canvas": cv,
                "shape": shape_id,
                "label": lbl,
                "sw": sw,
                "sh": sh
            }

        # Hàng chọn số lượng ảnh (1 hoặc 4)
        row_batch = tk.Frame(grp_ratio, bg=self.c_card)
        row_batch.pack(fill="x", pady=(8, 0))

        tk.Label(row_batch, text="Số lượng output:", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9)).pack(side="left")

        self.btn_batch1 = tk.Button(row_batch, text="1 Ảnh", bg=self.c_active, fg=self.c_text, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 8, "bold"), command=lambda: self.set_batch(1))
        self.btn_batch1.pack(side="left", padx=(10, 4))

        self.btn_batch4 = tk.Button(row_batch, text="4 Ảnh", bg=self.c_input, fg=self.c_muted, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 8), command=lambda: self.set_batch(4))
        self.btn_batch4.pack(side="left")

        # 5. Đính Kèm Đa Ảnh Mẫu (Img2Img)
        grp_attach = tk.LabelFrame(scrollable_frame, text=" Đính Kèm Ảnh Mẫu (Img2Img - Đa Ảnh) ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_attach.pack(fill="x", pady=(0, 8))

        row_attach = tk.Frame(grp_attach, bg=self.c_card)
        row_attach.pack(fill="x")

        self.btn_pick_img = tk.Button(row_attach, text="Thêm Ảnh Tham Chiếu", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9), command=self.pick_reference_image)
        self.btn_pick_img.pack(side="left")

        self.btn_clear_all_img = tk.Button(row_attach, text="Gỡ Tất Cả", bg="#2A1616", fg=self.c_danger, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9), command=self.clear_all_reference_images)
        self.btn_clear_all_img.pack(side="left", padx=(6, 0))
        self.btn_clear_all_img.pack_forget()

        # Dải hiển thị thumbnails ảnh đính kèm
        self.frame_thumbs_container = tk.Frame(grp_attach, bg=self.c_card)
        self.frame_thumbs_container.pack(fill="x", pady=(6, 0))
        self.frame_thumbs_container.pack_forget()

        self.lbl_attach_name = tk.Label(grp_attach, text="Chưa đính kèm ảnh (Tạo từ văn bản, bật LoRA Thực Tế)", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8), anchor="w")
        self.lbl_attach_name.pack(fill="x", pady=(4, 0))

        # 6. Prompt & Negative Prompt
        grp_prompt = tk.LabelFrame(scrollable_frame, text=" Câu Lệnh Prompt Chính ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=10, pady=8)
        grp_prompt.pack(fill="x", pady=(0, 8))

        tk.Label(grp_prompt, text="Mô tả hình ảnh:", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9)).pack(anchor="w")
        self.txt_prompt = tk.Text(grp_prompt, height=4, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 9))
        self.txt_prompt.pack(fill="x", pady=(2, 6))
        self.txt_prompt.insert("1.0", "1girl, realistic portrait, highly detailed, masterpieces")

        tk.Label(grp_prompt, text="Negative Prompt (Loại trừ):", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8)).pack(anchor="w")
        self.txt_negative = tk.Text(grp_prompt, height=2, bg=self.c_input, fg=self.c_muted, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 8))
        self.txt_negative.pack(fill="x", pady=(2, 0))
        self.txt_negative.insert("1.0", DEFAULT_NEGATIVE)

        # 7. Chi Phí & Nút Tạo Ảnh
        grp_action = tk.Frame(scrollable_frame, bg=self.c_bg)
        grp_action.pack(fill="x", pady=(4, 0))

        self.lbl_cost_badge = tk.Label(grp_action, text="Chi phí: 1,900 CR | Đang kiểm tra tài khoản khả dụng...", bg=self.c_bg, fg=self.c_success, font=("Segoe UI", 10, "bold"))
        self.lbl_cost_badge.pack(anchor="w")

        self.btn_generate = tk.Button(grp_action, text="KHỞI TẠO ẢNH NGAY", bg="#242424", fg="#FFFFFF", activebackground="#333333", activeforeground="#FFFFFF", relief="solid", bd=1, pady=10, font=("Segoe UI", 11, "bold"), command=self.on_start_generate)
        self.btn_generate.pack(fill="x", pady=(6, 4))

        self.progress_bar = ttk.Progressbar(grp_action, orient="horizontal", mode="indeterminate")
        self.lbl_gen_step = tk.Label(grp_action, text="Sẵn sàng khởi tạo.", bg=self.c_bg, fg=self.c_muted, font=("Segoe UI", 9))
        self.lbl_gen_step.pack(anchor="w")

        # Active Tasks Queue (Newest tasks on top)
        self.grp_queue = tk.LabelFrame(scrollable_frame, text=" Tiến Trình Tạo Ảnh Đang Chạy ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=8, pady=6)
        self.grp_queue.pack(fill="x", pady=(8, 4))
        self.queue_container = tk.Frame(self.grp_queue, bg=self.c_card)
        self.queue_container.pack(fill="x")
        self.grp_queue.pack_forget()

        # ================= CỘT PHẢI: KHU VỰC PREVIEW ẢNH (DANH SÁCH TỪNG ẢNH ĐỘC LẬP) =================
        right_pane = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        right_pane.pack(side="right", fill="both", expand=True)

        top_prev_bar = tk.Frame(right_pane, bg=self.c_card, padx=12, pady=8)
        top_prev_bar.pack(fill="x")

        tk.Label(top_prev_bar, text="KẾT QUẢ KHỞI TẠO ẢNH", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 10, "bold")).pack(side="left")
        self.lbl_result_status = tk.Label(top_prev_bar, text="Chưa có ảnh được tạo", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8))
        self.lbl_result_status.pack(side="right")

        # Scrollable Feed Container for Images
        feed_container = tk.Frame(right_pane, bg=self.c_card)
        feed_container.pack(fill="both", expand=True, padx=8, pady=(0, 6))

        self.feed_canvas = tk.Canvas(feed_container, bg=self.c_bg, highlightthickness=0)
        self.feed_scrollbar = ttk.Scrollbar(feed_container, orient="vertical", command=self.feed_canvas.yview)
        self.feed_inner_frame = tk.Frame(self.feed_canvas, bg=self.c_bg)

        self.feed_inner_frame.bind("<Configure>", lambda e: self.feed_canvas.configure(scrollregion=self.feed_canvas.bbox("all")))
        self.feed_window = self.feed_canvas.create_window((0, 0), window=self.feed_inner_frame, anchor="nw")
        self.feed_canvas.bind("<Configure>", lambda e: self.feed_canvas.itemconfig(self.feed_window, width=e.width))

        self.feed_canvas.configure(yscrollcommand=self.feed_scrollbar.set)
        self.feed_scrollbar.pack(side="right", fill="y")
        self.feed_canvas.pack(side="left", fill="both", expand=True)

        # Bottom info: Prompts
        bot_info = tk.Frame(right_pane, bg=self.c_card, padx=12, pady=6)
        bot_info.pack(fill="x", side="bottom")

        row_sp = tk.Frame(bot_info, bg=self.c_card)
        row_sp.pack(fill="x")
        tk.Label(row_sp, text="Prompt Server Tối Ưu:", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8)).pack(side="left")
        btn_copy_sp = tk.Button(row_sp, text="Sao Chép", bg=self.c_input, fg=self.c_text, relief="solid", bd=1, padx=6, pady=1, font=("Segoe UI", 7), command=self.copy_server_prompt)
        btn_copy_sp.pack(side="right")

        self.lbl_server_prompt = tk.Label(bot_info, text="Chưa có", bg=self.c_card, fg=self.c_success, font=("Segoe UI", 8), wraplength=550, justify="left", anchor="w")
        self.lbl_server_prompt.pack(fill="x", pady=(2, 4))

        row_op = tk.Frame(bot_info, bg=self.c_card)
        row_op.pack(fill="x")
        tk.Label(row_op, text="Prompt Gốc:", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8)).pack(side="left")
        btn_copy_op = tk.Button(row_op, text="Sao Chép", bg=self.c_input, fg=self.c_text, relief="solid", bd=1, padx=6, pady=1, font=("Segoe UI", 7), command=self.copy_orig_prompt)
        btn_copy_op.pack(side="right")

        self.lbl_orig_prompt = tk.Label(bot_info, text="Chưa có", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 8), wraplength=550, justify="left", anchor="w")
        self.lbl_orig_prompt.pack(fill="x", pady=(2, 0))

    # ================= TAB 2: QUẢN LÝ TÀI KHOẢN =================
    def build_accounts_tab(self):
        container = tk.Frame(self.tab_accounts, bg=self.c_bg, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        tk.Label(container, text="QUẢN LÝ TÀI KHOẢN", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(0, 10))

        # Khung thêm token và tự đăng ký
        card_add = tk.LabelFrame(container, text=" Thêm Token Hoặc Tự Đăng Ký Acc Mới ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=12, pady=10)
        card_add.pack(fill="x", pady=(0, 10))

        row_add = tk.Frame(card_add, bg=self.c_card)
        row_add.pack(fill="x")

        self.ent_new_token = tk.Entry(row_add, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Segoe UI", 9))
        self.ent_new_token.pack(side="left", fill="x", expand=True, padx=(0, 8))

        btn_add = tk.Button(row_add, text="Thêm Token Này", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=12, pady=4, font=("Segoe UI", 9), command=self.on_add_account_token)
        btn_add.pack(side="left", padx=(0, 6))

        self.btn_auto_reg = tk.Button(row_add, text="Tự Đăng Ký Acc Mới", bg="#242424", fg="#FFFFFF", activebackground="#303030", activeforeground="#FFFFFF", relief="solid", bd=1, padx=12, pady=4, font=("Segoe UI", 9, "bold"), command=self.on_auto_register_account)
        self.btn_auto_reg.pack(side="left")

        # Hàng Import / Export hàng loạt
        row_io = tk.Frame(card_add, bg=self.c_card)
        row_io.pack(fill="x", pady=(8, 0))

        btn_import_txt = tk.Button(row_io, text="Nhập List Text", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 8), command=self.on_import_text_dialog)
        btn_import_txt.pack(side="left", padx=(0, 6))

        btn_import_json = tk.Button(row_io, text="Nhập File JSON", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 8), command=self.on_import_json_dialog)
        btn_import_json.pack(side="left", padx=(0, 6))

        btn_export_json = tk.Button(row_io, text="Xuất File JSON", bg=self.c_card_border, fg=self.c_success, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 8, "bold"), command=self.on_export_json)
        btn_export_json.pack(side="left")

        # Hàng nút tác vụ đồng bộ & claim
        row_ops = tk.Frame(container, bg=self.c_bg)
        row_ops.pack(fill="x", pady=(0, 10))

        btn_sync = tk.Button(row_ops, text="Đồng Bộ Số Dư", bg=self.c_card, fg=self.c_text, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_sync_credits)
        btn_sync.pack(side="left", padx=(0, 6))

        btn_claim_daily = tk.Button(row_ops, text="Nhận Daily All", bg=self.c_card, fg=self.c_success, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_claim_daily)
        btn_claim_daily.pack(side="left", padx=(0, 6))

        btn_claim_all = tk.Button(row_ops, text="Nhận All Thưởng Quests", bg=self.c_card, fg=self.c_success, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_claim_quests)
        btn_claim_all.pack(side="left", padx=(0, 6))

        btn_unblur_all = tk.Button(row_ops, text="Mở Khóa 18+ Toàn Bộ", bg=self.c_card, fg=self.c_text, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_unblur_all)
        btn_unblur_all.pack(side="left", padx=(0, 6))

        btn_clear_accs = tk.Button(row_ops, text="Xóa Hết Tài Khoản", bg="#2A1616", fg=self.c_danger, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_clear_accounts)
        btn_clear_accs.pack(side="right")

        # Bảng danh sách tài khoản
        tree_frame = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        tree_frame.pack(fill="both", expand=True)

        self.acc_tree = ttk.Treeview(tree_frame, columns=("email", "credits", "status"), show="headings", selectmode="browse")
        self.acc_tree.heading("email", text="Email / Danh Tính")
        self.acc_tree.heading("credits", text="Số Dư (Credits)")
        self.acc_tree.heading("status", text="Trạng Thái Hoạt Động")
        self.acc_tree.column("email", width=420)
        self.acc_tree.column("credits", width=150, anchor="center")
        self.acc_tree.column("status", width=200, anchor="center")

        acc_scroll = ttk.Scrollbar(tree_frame, orient="vertical", command=self.acc_tree.yview)
        self.acc_tree.configure(yscrollcommand=acc_scroll.set)
        self.acc_tree.pack(side="left", fill="both", expand=True)
        acc_scroll.pack(side="right", fill="y")

        self.acc_tree.bind("<Double-1>", self.on_select_active_account)

    # ================= TAB 3: KHO LỊCH SỬ MÃ HÓA =================
    def build_history_tab(self):
        self.container_hist = tk.Frame(self.tab_history, bg=self.c_bg, padx=16, pady=16)
        self.container_hist.pack(fill="both", expand=True)

        top_bar = tk.Frame(self.container_hist, bg=self.c_bg)
        top_bar.pack(fill="x", pady=(0, 10))

        tk.Label(top_bar, text="KHO LỊCH SỬ MÃ HÓA", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 13, "bold")).pack(side="left")

        self.btn_hist_security = tk.Button(top_bar, text="Thiết Lập Mật Khẩu PIN", bg=self.c_card, fg=self.c_text, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9), command=self.on_history_security_dialog)
        self.btn_hist_security.pack(side="right")

        btn_clear_hist = tk.Button(top_bar, text="Xóa Toàn Bộ Lịch Sử", bg="#2A1616", fg=self.c_danger, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9), command=self.on_clear_history)
        btn_clear_hist.pack(side="right", padx=(0, 8))

        btn_delete_selected_hist = tk.Button(top_bar, text="Xóa Phiên Đã Chọn", bg="#2A1616", fg=self.c_danger, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9), command=self.on_delete_selected_history)
        btn_delete_selected_hist.pack(side="right", padx=(0, 8))

        # View khóa PIN
        self.frame_hist_locked = tk.Frame(self.container_hist, bg=self.c_card, bd=1, relief="solid", padx=24, pady=32)
        tk.Label(self.frame_hist_locked, text="Kho Lịch Sử Đã Được Khóa Bảo Mật", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 12, "bold")).pack(pady=(0, 8))
        tk.Label(self.frame_hist_locked, text="Nhập mã PIN hoặc mật khẩu bảo vệ để giải mã và xem lịch sử:", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9)).pack(pady=(0, 12))

        self.ent_hist_pin = tk.Entry(self.frame_hist_locked, show="*", bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", width=18, font=("Segoe UI", 12), justify="center")
        self.ent_hist_pin.pack(pady=(0, 12))
        self.ent_hist_pin.bind("<Return>", lambda e: self.on_unlock_history())

        btn_unlock = tk.Button(self.frame_hist_locked, text="MỞ KHÓA LỊCH SỬ", bg="#242424", fg="#FFFFFF", activebackground="#303030", activeforeground="#FFFFFF", relief="solid", bd=1, padx=16, pady=6, font=("Segoe UI", 9, "bold"), command=self.on_unlock_history)
        btn_unlock.pack()

        # View nội dung lịch sử
        self.frame_hist_content = tk.Frame(self.container_hist, bg=self.c_bg)
        
        hist_tree_frame = tk.Frame(self.frame_hist_content, bg=self.c_card, bd=1, relief="solid")
        hist_tree_frame.pack(fill="both", expand=True)

        self.hist_tree = ttk.Treeview(hist_tree_frame, columns=("date", "model", "size", "prompt"), show="headings", selectmode="browse")
        self.hist_tree.heading("date", text="Thời Gian")
        self.hist_tree.heading("model", text="Engine Profile")
        self.hist_tree.heading("size", text="Kích Thước")
        self.hist_tree.heading("prompt", text="Prompt")
        self.hist_tree.column("date", width=150)
        self.hist_tree.column("model", width=120, anchor="center")
        self.hist_tree.column("size", width=100, anchor="center")
        self.hist_tree.column("prompt", width=500)

        hist_scroll = ttk.Scrollbar(hist_tree_frame, orient="vertical", command=self.hist_tree.yview)
        self.hist_tree.configure(yscrollcommand=hist_scroll.set)
        self.hist_tree.pack(side="left", fill="both", expand=True)
        hist_scroll.pack(side="right", fill="y")

        self.hist_tree.bind("<Double-1>", self.on_history_double_click)
        self.hist_tree.bind("<Delete>", lambda e: self.on_delete_selected_history())

        self.check_history_lock_view()

    def check_history_lock_view(self):
        has_pin = bool(self.config.get("history_pin_hash"))
        if has_pin and not self.is_history_unlocked:
            self.frame_hist_content.pack_forget()
            self.frame_hist_locked.pack(fill="both", expand=True)
        else:
            self.frame_hist_locked.pack_forget()
            self.frame_hist_content.pack(fill="both", expand=True)
            self.refresh_history_table()

    # ================= TAB 4: BẢO MẬT & PROXY =================
    def update_proxy_pool_status_label(self):
        pool = self.config.get("proxy_pool", [])
        cnt = len(pool)
        rot = "BẬT" if self.config.get("auto_rotate_proxies", True) else "TẮT"
        ks = "BẬT" if self.config.get("strict_kill_switch", True) else "TẮT"
        txt = f"Proxy Pool: {cnt} proxy khả dụng | Auto-Rotate: {rot} | Kill-Switch: {ks}"
        if hasattr(self, "lbl_pool_status"):
            self.lbl_pool_status.config(text=txt, fg=self.c_success if cnt > 0 else self.c_muted)

    def build_security_tab(self):
        container = tk.Frame(self.tab_security, bg=self.c_bg, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        tk.Label(container, text="BẢO MẬT & PROXY SHIELD (TỐI ĐA HÓA QUYỀN RIÊNG TƯ)", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 13, "bold")).pack(anchor="w", pady=(0, 10))

        card_sec = tk.LabelFrame(container, text=" Cấu Hình Proxy Pool & Anti-Leak Shield ", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"), bd=1, relief="solid", padx=14, pady=12)
        card_sec.pack(fill="x", pady=(0, 10))

        tk.Label(card_sec, text="Danh Sách Proxy (HTTP/SOCKS5 - mỗi dòng 1 proxy, hoặc lệnh curl/token/download URL Webshare):", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9)).pack(anchor="w")

        self.txt_proxy_pool = tk.Text(card_sec, height=5, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", font=("Consolas", 9))
        self.txt_proxy_pool.pack(fill="x", pady=(4, 4))

        pool = self.config.get("proxy_pool", [])
        if pool:
            self.txt_proxy_pool.insert("1.0", "\n".join(pool))
        elif self.config.get("proxy_address"):
            self.txt_proxy_pool.insert("1.0", self.config.get("proxy_address"))

        self.lbl_pool_status = tk.Label(card_sec, text="Khởi tạo pool...", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8))
        self.lbl_pool_status.pack(anchor="w", pady=(0, 6))
        self.update_proxy_pool_status_label()

        row_import = tk.Frame(card_sec, bg=self.c_card)
        row_import.pack(fill="x", pady=(0, 8))

        btn_webshare = tk.Button(row_import, text="⚡ Nạp Proxy / Tự Lấy Từ Lệnh Webshare", bg="#1E3A8A", fg="#93C5FD", activebackground="#2563EB", activeforeground="#FFFFFF", relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9, "bold"), command=self.on_import_webshare)
        btn_webshare.pack(side="left")

        self.var_use_proxy = tk.BooleanVar(value=self.config.get("use_proxy", True))
        self.cb_use_proxy = tk.Checkbutton(card_sec, text="Kích hoạt định tuyến qua Proxy Shield (Bảo vệ IP & che giấu ISP)", variable=self.var_use_proxy, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card)
        self.cb_use_proxy.pack(anchor="w", pady=2)

        self.var_auto_rotate_proxies = tk.BooleanVar(value=self.config.get("auto_rotate_proxies", True))
        self.cb_auto_rotate_proxy = tk.Checkbutton(card_sec, text="Tự động xoay Proxy liên tục qua từng tác vụ (Proxy Rotation)", variable=self.var_auto_rotate_proxies, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card)
        self.cb_auto_rotate_proxy.pack(anchor="w", pady=2)

        self.var_strict_kill_switch = tk.BooleanVar(value=self.config.get("strict_kill_switch", True))
        self.cb_strict_kill_switch = tk.Checkbutton(card_sec, text="Strict Anti-Leak Kill-Switch (Chặn hoàn toàn rò rỉ IP thật nếu proxy đứt)", variable=self.var_strict_kill_switch, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card)
        self.cb_strict_kill_switch.pack(anchor="w", pady=2)

        self.var_auto_fallback = tk.BooleanVar(value=self.config.get("auto_fallback_direct", False))
        self.cb_fallback = tk.Checkbutton(card_sec, text="Tự động Fallback sang Direct an toàn nếu Proxy lỗi (Cảnh báo: Có thể lộ IP thật)", variable=self.var_auto_fallback, bg=self.c_card, fg=self.c_muted, selectcolor=self.c_input, activebackground=self.c_card)
        self.cb_fallback.pack(anchor="w", pady=2)

        self.var_auto_rotate = tk.BooleanVar(value=self.config.get("auto_rotate_accounts", True))
        self.cb_rotate = tk.Checkbutton(card_sec, text="Tự động đổi tài khoản khi hết credit hoặc không đủ tạo ảnh", variable=self.var_auto_rotate, bg=self.c_card, fg=self.c_text, selectcolor=self.c_input, activebackground=self.c_card)
        self.cb_rotate.pack(anchor="w", pady=2)

        row_sec_btns = tk.Frame(card_sec, bg=self.c_card)
        row_sec_btns.pack(fill="x", pady=(10, 0))

        btn_save_sec = tk.Button(row_sec_btns, text="LƯU CẤU HÌNH", bg="#242424", fg="#FFFFFF", activebackground="#303030", activeforeground="#FFFFFF", relief="solid", bd=1, padx=14, pady=5, font=("Segoe UI", 9, "bold"), command=self.on_save_security_config)
        btn_save_sec.pack(side="left", padx=(0, 8))

        btn_test_proxy = tk.Button(row_sec_btns, text="Kiểm Tra Proxy", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=12, pady=5, font=("Segoe UI", 9), command=self.on_test_proxy)
        btn_test_proxy.pack(side="left")

        self.lbl_proxy_test_res = tk.Label(card_sec, text="Chưa kiểm tra kết nối.", bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 9))
        self.lbl_proxy_test_res.pack(anchor="w", pady=(8, 0))

    # ================= TAB 5: NHẬT KÝ =================
    def build_logs_tab(self):
        container = tk.Frame(self.tab_logs, bg=self.c_bg, padx=16, pady=16)
        container.pack(fill="both", expand=True)

        top_log = tk.Frame(container, bg=self.c_bg)
        top_log.pack(fill="x", pady=(0, 10))

        tk.Label(top_log, text="NHẬT KÝ HOẠT ĐỘNG", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 13, "bold")).pack(side="left")

        btn_clear_log = tk.Button(top_log, text="Xóa Nhật Ký", bg=self.c_card, fg=self.c_danger, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 8), command=lambda: self.txt_logs.delete("1.0", "end"))
        btn_clear_log.pack(side="right")

        log_frame = tk.Frame(container, bg=self.c_card, bd=1, relief="solid")
        log_frame.pack(fill="both", expand=True)

        self.txt_logs = tk.Text(log_frame, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=0, font=("Consolas", 9))
        log_scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.txt_logs.yview)
        self.txt_logs.configure(yscrollcommand=log_scroll.set)
        self.txt_logs.pack(side="left", fill="both", expand=True, padx=4, pady=4)
        log_scroll.pack(side="right", fill="y")

    # ================= LOGIC & SỰ KIỆN STUDIO =================
    def set_style(self, style):
        self.current_style.set(style)
        is_real = (style == "real")
        self.btn_style_anime.config(bg=self.c_input if is_real else self.c_active, fg=self.c_muted if is_real else self.c_text)
        self.btn_style_real.config(bg=self.c_active if is_real else self.c_input, fg=self.c_text if is_real else self.c_muted)

        if self.attached_media_list:
            self.lbl_lora_status.config(text=f"Đang đính kèm {len(self.attached_media_list)} ảnh mẫu: Tự động bỏ LoRA để tương thích Img2Img", fg=self.c_muted)
        elif is_real:
            self.lbl_lora_status.config(text="LoRA Tsubaki 3 Realism v1 đã kích hoạt (Trọng số 0.70)", fg=self.c_success)
        else:
            self.lbl_lora_status.config(text="Chế độ Anime nguyên bản, không dùng LoRA", fg=self.c_muted)

    def set_profile(self, profile):
        self.current_profile.set(profile)
        for p, btn in [("flash", self.btn_prof_flash), ("pro", self.btn_prof_pro), ("ultra", self.btn_prof_ultra)]:
            active = (p == profile)
            btn.config(bg=self.c_active if active else self.c_input, fg=self.c_text if active else self.c_muted, font=("Segoe UI", 9, "bold" if active else "normal"))

        self.update_live_cost()

    def set_size_tier(self, tier):
        self.current_size_tier.set(tier)
        for t, btn in [("xl", self.btn_size_xl), ("l", self.btn_size_l), ("m", self.btn_size_m), ("custom", self.btn_size_custom), ("auto", self.btn_size_auto)]:
            active = (t == tier)
            btn.config(bg=self.c_active if active else self.c_input, fg=self.c_text if active else self.c_muted, font=("Segoe UI", 8, "bold" if active else "normal"))

        if tier == "custom":
            self.frame_custom_dims.pack(fill="x", pady=(6, 0))
        else:
            self.frame_custom_dims.pack_forget()

        self.save_config()
        self.update_dimensions_and_preview()

    def set_ratio(self, ratio_str):
        self.current_ratio.set(ratio_str)
        if hasattr(self, "ratio_cards"):
            for r_name, item in self.ratio_cards.items():
                is_active = (r_name == ratio_str)
                bg_col = self.c_active if is_active else self.c_input
                item["frame"].config(bg=bg_col)
                item["canvas"].config(bg=bg_col)
                fill_col = "#FFFFFF" if is_active else "#222222"
                outline_col = "#FFFFFF" if is_active else "#444444"
                item["canvas"].itemconfig(item["shape"], fill=fill_col, outline=outline_col)
                item["label"].config(bg=bg_col, fg=(self.c_text if is_active else self.c_muted), font=("Segoe UI", 7, "bold" if is_active else "normal"))

        self.save_config()
        self.update_dimensions_and_preview()

    def on_custom_dim_changed(self):
        if self.current_size_tier.get() == "custom":
            self.update_dimensions_and_preview()

    def get_selected_dimensions(self):
        tier = self.current_size_tier.get()
        ratio = self.current_ratio.get()

        if tier == "custom":
            try:
                w = int(self.custom_w.get())
                h = int(self.custom_h.get())
            except Exception:
                w, h = 1024, 1024
            w = max(256, (w // 16) * 16)
            h = max(256, (h // 16) * 16)
            return w, h, f"Tùy Chỉnh ({w}x{h})"

        if tier == "auto" and self.first_attached_dims:
            bw, bh = self.first_attached_dims
            scale = (1024.0 * 1024.0 / (bw * bh)) ** 0.5
            aw = max(384, (int(bw * scale) // 16) * 16)
            ah = max(384, (int(bh * scale) // 16) * 16)
            return aw, ah, f"Auto Theo Ảnh Mẫu ({aw}x{ah})"

        # Standard Presets
        is_xl = (tier == "xl")
        is_l = (tier == "l")
        presets = {
            "9:16": (1104 if is_xl else (944 if is_l else 768), 1824 if is_xl else (1584 if is_l else 1280)),
            "16:9": (1824 if is_xl else (1584 if is_l else 1280), 1104 if is_xl else (944 if is_l else 768)),
            "1:1":  (1440 if is_xl else (1216 if is_l else 1024), 1440 if is_xl else (1216 if is_l else 1024)),
            "3:4":  (1248 if is_xl else (1056 if is_l else 896),  1664 if is_xl else (1408 if is_l else 1152)),
            "4:3":  (1664 if is_xl else (1408 if is_l else 1152), 1248 if is_xl else (1056 if is_l else 896)),
            "2:3":  (1184 if is_xl else (1008 if is_l else 832),  1776 if is_xl else (1520 if is_l else 1248)),
            "3:2":  (1776 if is_xl else (1520 if is_l else 1248), 1184 if is_xl else (1008 if is_l else 832)),
            "3:5":  (1104 if is_xl else (936 if is_l else 768),   1840 if is_xl else (1568 if is_l else 1280)),
            "5:3":  (1840 if is_xl else (1568 if is_l else 1280), 1104 if is_xl else (936 if is_l else 768)),
            "7:9":  (1280 if is_xl else (1088 if is_l else 928),  1664 if is_xl else (1408 if is_l else 1184)),
            "9:7":  (1664 if is_xl else (1408 if is_l else 1184), 1280 if is_xl else (1088 if is_l else 928)),
            "1:3":  (720 if is_xl else (608 if is_l else 512),    2160 if is_xl else (1824 if is_l else 1536)),
            "3:1":  (2160 if is_xl else (1824 if is_l else 1536), 720 if is_xl else (608 if is_l else 512)),
        }
        w, h = presets.get(ratio, (1104, 1824))
        return w, h, f"{ratio} ({tier.upper()})"

    def update_dimensions_and_preview(self):
        w, h, desc = self.get_selected_dimensions()
        cur_r = self.current_ratio.get()
        if hasattr(self, "lbl_ratio_dims"):
            self.lbl_ratio_dims.config(text=f"Tỷ lệ: {cur_r} • Kích thước: {w} x {h} px ({desc})")
        self.update_live_cost()

    def set_batch(self, count):
        self.batch_count.set(count)
        self.btn_batch1.config(bg=self.c_active if count == 1 else self.c_input, fg=self.c_text if count == 1 else self.c_muted, font=("Segoe UI", 8, "bold" if count == 1 else "normal"))
        self.btn_batch4.config(bg=self.c_active if count == 4 else self.c_input, fg=self.c_text if count == 4 else self.c_muted, font=("Segoe UI", 8, "bold" if count == 4 else "normal"))
        self.update_live_cost()

    def update_live_cost(self):
        prof = self.current_profile.get()
        b = self.batch_count.get()
        w, h, _ = self.get_selected_dimensions()
        has_ref = bool(self.attached_media_list)
        cost = calculate_task_cost(prof, w, h, b, has_ref)

        # Kiểm tra tài khoản có đủ credit
        eligible_acc = None
        for a in self.accounts:
            if a.get("credits", 0) >= cost and a.get("token"):
                eligible_acc = a
                break

        if eligible_acc:
            self.lbl_cost_badge.config(text=f"Chi phí: {cost:,} CR | Khả dụng: {eligible_acc.get('email', 'N/A')} ({eligible_acc.get('credits', 0):,} CR) | Đủ điều kiện", fg=self.c_success)
            self.btn_generate.config(state="normal")
        else:
            self.lbl_cost_badge.config(text=f"Chi phí: {cost:,} CR | Cảnh báo: Không có tài khoản nào đủ {cost:,} CR!", fg=self.c_danger)

    def update_header(self):
        tot = self.get_total_credits()
        self.lbl_total_credits.config(text=f"Tổng: {tot:,} CR")
        if self.active_account:
            self.lbl_active_acc.config(text=f"Tài khoản: {self.active_account.get('email', 'N/A')} | Số dư: {self.active_account.get('credits', 0):,} CR")
        else:
            self.lbl_active_acc.config(text="Tài khoản: Chưa kích hoạt | Số dư: 0 CR")

    # ================= MULTI-IMAGE CONTEXT (IMG2IMG) =================
    def pick_reference_image(self):
        paths = filedialog.askopenfilenames(title="Chọn ảnh tham chiếu", filetypes=[("Hình ảnh", "*.png *.jpg *.jpeg *.webp")])
        if not paths:
            return

        token = self.active_account.get("token") if self.active_account else ""
        proxy = self.get_effective_proxy()

        threading.Thread(target=self._upload_reference_images_worker, args=(paths, token, proxy), daemon=True).start()

    def _upload_reference_images_worker(self, paths, token, proxy):
        for path in paths:
            try:
                with open(path, "rb") as f:
                    img_bytes = f.read()

                pil_img = Image.open(path)
                if not self.first_attached_dims:
                    self.first_attached_dims = pil_img.size

                # Tải ảnh lên PixAI 3 bước chuẩn xác
                # Bước 1: Yêu cầu S3 uploadUrl và externalId
                res1 = http_graphql({
                    "query": """mutation uploadMedia($input: UploadMediaInput!) {
                        uploadMedia(input: $input) {
                            uploadUrl
                            externalId
                            mediaId
                        }
                    }""",
                    "variables": {"input": {"type": "IMAGE", "provider": "S3"}}
                }, token, proxy=proxy, timeout=20)
                u_info = res1.get("data", {}).get("uploadMedia", {})
                upload_url = u_info.get("uploadUrl")
                ext_id = u_info.get("externalId")

                if upload_url and ext_id:
                    # Bước 2: Đẩy dữ liệu ảnh nhị phân lên AWS S3
                    http_request(upload_url, method="PUT", data=img_bytes, headers={"Content-Type": "image/jpeg"}, proxy=proxy, timeout=30)

                    # Bước 3: Đăng ký xác nhận mediaId hoàn chỉnh với PixAI qua externalId
                    res2 = http_graphql({
                        "query": """mutation uploadMedia($input: UploadMediaInput!) {
                            uploadMedia(input: $input) {
                                uploadUrl
                                externalId
                                mediaId
                            }
                        }""",
                        "variables": {"input": {"type": "IMAGE", "provider": "S3", "externalId": ext_id, "imageType": "image/jpeg"}}
                    }, token, proxy=proxy, timeout=20)
                    media_id = res2.get("data", {}).get("uploadMedia", {}).get("mediaId")

                    if media_id and media_id != "null":
                        self.after(0, lambda p=path, m=media_id, im=pil_img: self.add_attached_thumbnail(p, m, im))
                    else:
                        raise Exception("Máy chủ PixAI không cấp mediaId hợp lệ")
            except Exception as e:
                self.after(0, lambda err=str(e): self.log_message(f"Lỗi tải ảnh mẫu lên: {err}", "error"))

    def add_attached_thumbnail(self, path, media_id, pil_img):
        thumb_im = pil_img.copy()
        thumb_im.thumbnail((50, 50))
        tk_thumb = ImageTk.PhotoImage(thumb_im)

        item = {"path": path, "mediaId": media_id, "thumb": tk_thumb}
        self.attached_media_list.append(item)

        # Build thumbnail card with delete button
        card = tk.Frame(self.frame_thumbs_container, bg=self.c_card_border, padx=2, pady=2)
        card.pack(side="left", padx=4)

        lbl_img = tk.Label(card, image=tk_thumb, bg=self.c_card)
        lbl_img.image = tk_thumb
        lbl_img.pack()

        btn_del = tk.Button(card, text="X", bg="#3B1C22", fg=self.c_danger, relief="flat", font=("Segoe UI", 7, "bold"), command=lambda c=card, it=item: self.remove_single_reference_image(c, it))
        btn_del.pack(fill="x")

        self.frame_thumbs_container.pack(fill="x", pady=(6, 0))
        self.btn_clear_all_img.pack(side="left", padx=(6, 0))
        self.lbl_attach_name.config(text=f"Đã đính kèm {len(self.attached_media_list)} ảnh mẫu", fg=self.c_success)
        self.btn_size_auto.pack(side="left", fill="x", expand=True, padx=(2, 0))

        self.set_style(self.current_style.get())
        self.update_live_cost()
        self.log_message(f"Đã thêm ảnh tham chiếu: Media ID {media_id}", "info")

    def remove_single_reference_image(self, card_widget, item):
        card_widget.destroy()
        if item in self.attached_media_list:
            self.attached_media_list.remove(item)

        if not self.attached_media_list:
            self.clear_all_reference_images()
        else:
            self.lbl_attach_name.config(text=f"Đã đính kèm {len(self.attached_media_list)} ảnh mẫu")
            self.set_style(self.current_style.get())
            self.update_live_cost()

    def clear_all_reference_images(self):
        for widget in self.frame_thumbs_container.winfo_children():
            widget.destroy()
        self.frame_thumbs_container.pack_forget()
        self.btn_clear_all_img.pack_forget()
        self.attached_media_list.clear()
        self.first_attached_dims = None
        self.lbl_attach_name.config(text="Chưa đính kèm ảnh (Tạo từ văn bản, bật LoRA Thực Tế)", fg=self.c_muted)
        self.btn_size_auto.pack_forget()

        if self.current_size_tier.get() == "auto":
            self.set_size_tier("xl")

        self.set_style(self.current_style.get())
        self.update_live_cost()
        self.log_message("Đã gỡ toàn bộ ảnh mẫu: Tự động phục hồi LoRA Thực Tế.", "info")

    # ================= TẠO ẢNH & POLLING KẾT QUẢ =================
    def on_start_generate(self):
        if self.is_generating:
            return

        prompt = self.txt_prompt.get("1.0", "end").strip()
        if not prompt:
            messagebox.showwarning("Cảnh báo", "Vui lòng nhập câu lệnh Prompt!")
            return

        prof = self.current_profile.get()
        b = self.batch_count.get()
        w, h, _ = self.get_selected_dimensions()
        has_ref = bool(self.attached_media_list)
        cost = calculate_task_cost(prof, w, h, b, has_ref)

        # Tự động xoay tua tài khoản có đủ credit
        eligible_acc = None
        for a in self.accounts:
            if a.get("credits", 0) >= cost and a.get("token"):
                eligible_acc = a
                break

        if not eligible_acc:
            messagebox.showerror("Hết Credit", f"Toàn bộ tài khoản không đủ {cost:,} Credit để tạo ảnh! Hãy nhận thưởng hoặc đăng ký tài khoản mới.")
            return

        if not self.active_account or self.active_account.get("email") != eligible_acc.get("email"):
            self.active_account = eligible_acc
            self.update_header()
            self.log_message(f"Tự động đổi sang tài khoản đủ số dư: {eligible_acc.get('email')} ({eligible_acc.get('credits', 0):,} CR)", "info")

        # Nút tạo ảnh không bị khóa, cho phép bấm tạo tiếp các tiến trình song song!
        self.lbl_gen_step.config(text=f"Đang gửi lệnh tạo tác vụ mới tới GPU PixAI ({prof.upper()})...", fg=self.c_text)

        w, h, _ = self.get_selected_dimensions()
        neg = self.txt_negative.get("1.0", "end").strip()
        context_ids = [it["mediaId"] for it in self.attached_media_list]

        threading.Thread(target=self._generate_worker, args=(eligible_acc, prompt, neg, prof, b, w, h, context_ids, cost), daemon=True).start()

    def _generate_worker(self, account, prompt, neg, profile, batch_size, width, height, context_ids, cost):
        proxy = self.get_effective_proxy()
        token = account.get("token")

        try:
            # Xác nhận 18+ với bằng chứng log
            proof = []
            unblur_nsfw_for_account(token, proxy=proxy, proof_collector=proof)
            self.after(0, lambda: self.log_message(f"Xác nhận 18+ cho {account.get('email')}: {''.join(proof)}", "info"))

            model_id = MODELS[profile]["modelId"]
            params = {
                "modelId": model_id,
                "inferenceProfile": profile,
                "width": width,
                "height": height,
                "priority": 1000, # Ưu tiên chuẩn web PixAI kết xuất siêu tốc (~24s)
                "prompts": prompt,
                "seed": "",
                "batchSize": batch_size,
                "controlNets": [],
                "extra": {"naturalPrompts": prompt},
                "promptHelper": {"forcePromptHelperDetectionSide": "server", "creativity": "medium"},
                "qualityTag": {"prefix": "", "suffix": ""}
            }

            if profile != "flash" or neg != DEFAULT_NEGATIVE:
                params["negativePrompts"] = neg

            # Context Images hoặc LoRA Realism
            if context_ids:
                params["contextImages"] = context_ids
            elif self.current_style.get() == "real":
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

            errors = create_res.get("errors")
            if errors:
                err_msg = errors[0].get("message", "Lỗi server")
                if "insufficient balance" in err_msg.lower() or "balance" in err_msg.lower():
                    account["credits"] = 0
                    self.save_accounts()
                    # Tự động xoay sang tài khoản khác có đủ credit
                    next_acc = None
                    for a in self.accounts:
                        if a.get("credits", 0) >= cost and a.get("token") and a.get("email") != account.get("email"):
                            next_acc = a
                            break
                    if next_acc:
                        self.after(0, lambda: self.log_message(f"Tài khoản hết credit, tự xoay sang {next_acc.get('email')}", "warning"))
                        self._generate_worker(next_acc, prompt, neg, profile, batch_size, width, height, context_ids, cost)
                        return
                    self.after(0, lambda: self.on_generation_failed("Toàn bộ tài khoản đã hết Credit trên PixAI!"))
                    return
                self.after(0, lambda msg=err_msg: self.on_generation_failed(f"Máy chủ từ chối: {msg}"))
                return

            task_obj = create_res.get("data", {}).get("createGenerationTask")
            if not task_obj or not task_obj.get("id"):
                self.after(0, lambda: self.on_generation_failed(f"Không nhận được Task ID từ máy chủ: {create_res}"))
                return

            task_id = task_obj["id"]
            paid_credit = task_obj.get("paidCredit", cost)

            # Trừ credit tài khoản
            account["credits"] = max(0, account.get("credits", 0) - paid_credit)
            self.save_accounts()
            self.after(0, self.update_header)
            self.after(0, self.update_live_cost)

            # LƯU NGAY VÀO LỊCH SỬ MÃ HÓA VỚI STATUS PENDING (ĐẢM BẢO KHÔNG BỊ MẤT)
            hist_item = {
                "id": task_id,
                "taskId": task_id,
                "status": "PENDING",
                "timestamp": int(time.time() * 1000),
                "imageUrl": "",
                "imageUrls": [],
                "originalPrompt": prompt,
                "serverPrompt": "Đang kết xuất trên GPU PixAI...",
                "modelName": "Tsubaki 3",
                "profile": profile,
                "isReal": self.current_style.get() == "real",
                "seed": 0,
                "width": width,
                "height": height
            }
            self.history.insert(0, hist_item)
            save_encrypted_history(self.history)

            self.after(0, lambda: self.log_message(f"Đã tạo Task #{task_id} ({profile.upper()}). Đã lưu vào lịch sử.", "info"))
            self.after(0, lambda: self.lbl_gen_step.config(text=f"Task #{task_id[-6:]} đã vào hàng đợi GPU PixAI.", fg=self.c_success))

            # Thêm card vào hàng đợi UI (Cũ ở trên, Mới ở dưới)
            self.after(0, lambda: self.attach_task_to_queue_ui(hist_item, token, proxy, cost))

        except Exception as e:
            self.after(0, lambda err=str(e): self.on_generation_failed(f"Lỗi ngoại lệ: {err}"))

    def attach_task_to_queue_ui(self, hist_item, token, proxy, cost):
        self.grp_queue.pack(fill="x", pady=(8, 4))

        task_id = hist_item["id"]
        card = tk.Frame(self.queue_container, bg=self.c_card, bd=1, relief="solid", padx=8, pady=6)
        existing_tasks = self.queue_container.winfo_children()
        if existing_tasks:
            card.pack(fill="x", pady=2, before=existing_tasks[0])
        else:
            card.pack(fill="x", pady=2)

        top_f = tk.Frame(card, bg=self.c_card)
        top_f.pack(fill="x")

        lbl_id = tk.Label(top_f, text=f"Task #{task_id[-8:]} • {hist_item['profile'].upper()} • {hist_item['width']}x{hist_item['height']}", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold"))
        lbl_id.pack(side="left")

        lbl_badge = tk.Label(top_f, text="Đang chờ GPU...", bg=self.c_card, fg=self.c_warning, font=("Segoe UI", 8, "bold"))
        lbl_badge.pack(side="right")

        lbl_p = tk.Label(card, text=hist_item["originalPrompt"], bg=self.c_card, fg=self.c_muted, font=("Segoe UI", 8), anchor="w")
        lbl_p.pack(fill="x", pady=(2, 4))

        pb = ttk.Progressbar(card, orient="horizontal", mode="indeterminate")
        pb.pack(fill="x", pady=(2, 4))
        pb.start(10)

        # Polling ngầm riêng biệt không chặn UI
        threading.Thread(target=self._poll_task_worker, args=(card, pb, lbl_badge, hist_item, token, proxy, cost), daemon=True).start()

    def _poll_task_worker(self, card, pb, lbl_badge, hist_item, token, proxy, cost):
        task_id = hist_item["id"]
        poll_query = """query getTaskMedia($id: ID!) {
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
        poll_payload = {"query": poll_query, "variables": {"id": task_id}}

        start_t = time.time()
        while True:
            time.sleep(2.5)
            elapsed = int(time.time() - start_t)

            try:
                poll_res = http_graphql(poll_payload, token, proxy=proxy, timeout=15)
                t_node = poll_res.get("data", {}).get("task")
                if t_node:
                    status = t_node.get("status", "").lower()
                    if status == "running":
                        self.after(0, lambda s=elapsed: lbl_badge.config(text=f"Đang render ({s}s)...", fg=self.c_info))
                    elif status == "waiting":
                        self.after(0, lambda s=elapsed: lbl_badge.config(text=f"Đang chờ GPU ({s}s)...", fg=self.c_warning))
                    elif status == "completed":
                        server_prompt = t_node.get("parameters", {}).get("prompts", hist_item["originalPrompt"])
                        outputs = t_node.get("outputs", {})
                        seed = outputs.get("seed", 0)
                        urls = []

                        # Trích xuất danh sách các ảnh độc lập từ outputs.batch khi tạo 4 ảnh
                        batch = outputs.get("batch")
                        if batch and isinstance(batch, list) and len(batch) > 0:
                            m_ids = [b.get("mediaId") for b in batch if b.get("mediaId")]
                            if m_ids:
                                query_parts = [f"  m{i}: media(id: $id{i}) {{ urls {{ variant url }} }}" for i in range(len(m_ids))]
                                vars_def = [f"$id{i}: String!" for i in range(len(m_ids))]
                                vars_dict = {f"id{i}": m_ids[i] for i in range(len(m_ids))}
                                batch_query = f"query getBatchMedias({', '.join(vars_def)}) {{\n" + "\n".join(query_parts) + "\n}"
                                try:
                                    b_res = http_graphql({"query": batch_query, "variables": vars_dict}, token, proxy=proxy, timeout=15)
                                    b_data = b_res.get("data", {})
                                    for i in range(len(m_ids)):
                                        m_info = b_data.get(f"m{i}", {})
                                        m_urls = m_info.get("urls", [])
                                        pub_url = next((u["url"] for u in m_urls if u.get("variant", "").upper() == "PUBLIC"), None)
                                        if not pub_url and m_urls:
                                            pub_url = m_urls[0].get("url")
                                        if pub_url:
                                            urls.append(pub_url)
                                except Exception:
                                    pass

                        if not urls:
                            media = t_node.get("media", {})
                            for u in media.get("urls", []):
                                if u.get("variant", "").upper() == "PUBLIC":
                                    urls.append(u.get("url"))
                            if not urls and media.get("urls"):
                                urls.append(media["urls"][0].get("url"))

                        # Cập nhật lịch sử mã hóa
                        hist_item["status"] = "COMPLETED"
                        hist_item["imageUrls"] = urls
                        hist_item["imageUrl"] = urls[0] if urls else ""
                        hist_item["serverPrompt"] = server_prompt
                        hist_item["seed"] = seed
                        save_encrypted_history(self.history)

                        self.after(0, lambda u=urls, sp=server_prompt, sd=seed, el=elapsed: self.on_task_finished_ui(card, pb, lbl_badge, hist_item, u, sp, sd, el))
                        return
                    elif status in ["failed", "error"]:
                        hist_item["status"] = "FAILED"
                        save_encrypted_history(self.history)
                        self.after(0, lambda: pb.stop())
                        self.after(0, lambda: pb.pack_forget())
                        self.after(0, lambda: lbl_badge.config(text="Thất bại", fg=self.c_danger))
                        self.after(0, lambda: self.log_message(f"Task #{task_id} thất bại trên GPU PixAI ({status})", "error"))
                        return
            except Exception:
                pass

            if elapsed > 1800: # 30 phút tối đa
                break

    def on_task_finished_ui(self, card, pb, lbl_badge, hist_item, urls, server_prompt, seed, elapsed):
        pb.stop()
        pb.pack_forget()
        lbl_badge.config(text=f"Hoàn tất ({elapsed}s)", fg=self.c_success)

        # Nút hành động nhanh trên card
        act_f = tk.Frame(card, bg=self.c_card)
        act_f.pack(fill="x", pady=(2, 0))

        if urls:
            first_url = urls[0]
            btn_view = tk.Button(act_f, text="Phóng To", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=6, pady=2, font=("Segoe UI", 8), command=lambda u=first_url: self.open_zoom_modal(u))
            btn_view.pack(side="left")

            btn_apply = tk.Button(act_f, text="Đưa Lên Màn Chính", bg=self.c_input, fg=self.c_success, relief="solid", bd=1, padx=6, pady=2, font=("Segoe UI", 8, "bold"), command=lambda: self.apply_task_to_preview(hist_item))
            btn_apply.pack(side="left", padx=(4, 0))

            # Tự động hiển thị danh sách ảnh mới nhất lên đầu feed chính
            self.display_batch_images_in_feed(urls, hist_item.get("originalPrompt", ""), server_prompt)

        self.log_message(f"Task #{hist_item['id']} hoàn thành 100%! Đã nhận {len(urls)} ảnh.", "info")

    def apply_task_to_preview(self, hist_item):
        self.last_original_prompt = hist_item.get("originalPrompt", "")
        self.last_server_prompt = hist_item.get("serverPrompt", "")
        self.lbl_orig_prompt.config(text=self.last_original_prompt)
        self.lbl_server_prompt.config(text=self.last_server_prompt)
        urls = hist_item.get("imageUrls", [])
        if not urls and hist_item.get("imageUrl"):
            urls = [hist_item["imageUrl"]]
        if urls:
            self.display_batch_images_in_feed(urls, self.last_original_prompt, self.last_server_prompt)
        self.is_generating = False
        self.progress_bar.stop()
        self.progress_bar.pack_forget()
        self.btn_generate.config(state="normal")
        self.lbl_gen_step.config(text="Khởi tạo hoàn tất 100%! Tác vụ thành công.", fg=self.c_success)

    def copy_server_prompt(self):
        text = getattr(self, "last_server_prompt", "") or ""
        if not text and hasattr(self, "lbl_server_prompt"):
            lbl_txt = self.lbl_server_prompt.cget("text")
            if lbl_txt and lbl_txt != "Chưa có":
                text = lbl_txt
        if text:
            self.clipboard_clear()
            self.clipboard_append(text)
            self.log_message("Đã sao chép Prompt Server Tối Ưu vào clipboard.", "success")
            messagebox.showinfo("Sao Chép", "Đã sao chép Prompt Server Tối Ưu vào bộ nhớ tạm!")
        else:
            messagebox.showinfo("Thông báo", "Chưa có Prompt Server nào để sao chép.")

    def copy_orig_prompt(self):
        text = getattr(self, "last_original_prompt", "") or ""
        if not text and hasattr(self, "lbl_orig_prompt"):
            lbl_txt = self.lbl_orig_prompt.cget("text")
            if lbl_txt and lbl_txt != "Chưa có":
                text = lbl_txt
        if not text and hasattr(self, "txt_prompt"):
            text = self.txt_prompt.get("1.0", "end").strip()
        if text:
            self.clipboard_clear()
            self.clipboard_append(text)
            self.log_message("Đã sao chép Prompt Gốc vào clipboard.", "success")
            messagebox.showinfo("Sao Chép", "Đã sao chép Prompt Gốc vào bộ nhớ tạm!")
        else:
            messagebox.showinfo("Thông báo", "Chưa có Prompt nào để sao chép.")

    def display_batch_images_in_feed(self, urls, orig_prompt="", server_prompt=""):
        if not urls:
            return
        if orig_prompt:
            self.last_original_prompt = orig_prompt
            self.lbl_orig_prompt.config(text=orig_prompt)
        if server_prompt:
            self.last_server_prompt = server_prompt
            self.lbl_server_prompt.config(text=server_prompt)

        self.lbl_result_status.config(text=f"Đã nhận {len(urls)} ảnh")

        # Container cho batch này (batch mới sẽ pack trước existing children để nằm trên cùng)
        batch_frame = tk.Frame(self.feed_inner_frame, bg=self.c_bg)
        existing = self.feed_inner_frame.winfo_children()
        if existing:
            batch_frame.pack(fill="x", padx=4, pady=6, before=existing[0])
        else:
            batch_frame.pack(fill="x", padx=4, pady=6)

        for i, url in enumerate(urls):
            card = tk.Frame(batch_frame, bg=self.c_card, bd=1, relief="solid", padx=10, pady=10)
            card.pack(fill="x", pady=4)

            # Header card
            hdr = tk.Frame(card, bg=self.c_card)
            hdr.pack(fill="x", pady=(0, 6))
            tk.Label(hdr, text=f"Ảnh {i + 1} / {len(urls)}", bg=self.c_card, fg=self.c_text, font=("Segoe UI", 9, "bold")).pack(side="left")

            # Image Label
            lbl_img = tk.Label(card, text="Đang tải ảnh xem trước...", bg="#101010", fg=self.c_muted, height=12)
            lbl_img.pack(fill="x", pady=(0, 8))

            # Action buttons
            btn_row = tk.Frame(card, bg=self.c_card)
            btn_row.pack(fill="x")

            btn_zoom = tk.Button(btn_row, text="Phóng To Chi Tiết", bg=self.c_card_border, fg=self.c_text, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9, "bold"), command=lambda u=url: self.open_zoom_modal(u))
            btn_zoom.pack(side="left", padx=(0, 6))

            btn_dl = tk.Button(btn_row, text="Lưu Vào Máy", bg=self.c_card_border, fg=self.c_success, relief="solid", bd=1, padx=10, pady=4, font=("Segoe UI", 9, "bold"), command=lambda u=url, idx=i+1: self.download_single_image(u, idx))
            btn_dl.pack(side="left")

            threading.Thread(target=self._load_feed_image_worker, args=(url, lbl_img), daemon=True).start()

    def _load_feed_image_worker(self, url, lbl_widget):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=25) as resp:
                data = resp.read()
            pil_img = Image.open(__import__("io").BytesIO(data))
            pil_img.thumbnail((520, 650), Image.Resampling.LANCZOS)
            tk_photo = ImageTk.PhotoImage(pil_img)
            self.after(0, lambda: self._apply_feed_image(lbl_widget, tk_photo))
        except Exception:
            self.after(0, lambda: lbl_widget.config(text="Không thể tải ảnh xem trước", fg=self.c_danger))

    def _apply_feed_image(self, lbl_widget, tk_photo):
        lbl_widget.config(image=tk_photo, text="", height=0)
        lbl_widget.image = tk_photo

    def on_generation_failed(self, error_message):
        self.is_generating = False
        self.progress_bar.stop()
        self.progress_bar.pack_forget()
        self.btn_generate.config(state="normal")
        self.lbl_gen_step.config(text=f"Thất bại: {error_message}", fg=self.c_danger)
        self.log_message(f"Tạo ảnh thất bại: {error_message}", "error")
        messagebox.showerror("Lỗi Khởi Tạo", error_message)

    def open_zoom_modal(self, url):
        top = tk.Toplevel(self)
        top.title("Phóng To Chi Tiết - PixAI Nexus")
        top.geometry("960x920")
        top.configure(bg="#0A0A0A")

        # Top Bar
        top_bar = tk.Frame(top, bg="#141414", bd=1, relief="solid", padx=14, pady=10)
        top_bar.pack(fill="x")

        btn_close = tk.Button(top_bar, text="✕ Đóng", bg="#242424", fg="#FFFFFF", activebackground="#333333", activeforeground="#FFFFFF", relief="solid", bd=1, padx=12, pady=4, font=("Segoe UI", 9, "bold"), command=top.destroy)
        btn_close.pack(side="left")

        tk.Label(top_bar, text="PHÓNG TO CHI TIẾT", bg="#141414", fg="#FFFFFF", font=("Segoe UI", 10, "bold")).pack(side="left", padx=20)

        btn_dl = tk.Button(top_bar, text="⬇ Lưu Máy", bg="#242424", fg="#FFFFFF", activebackground="#333333", activeforeground="#FFFFFF", relief="solid", bd=1, padx=12, pady=4, font=("Segoe UI", 9, "bold"), command=lambda: self.download_single_image(url))
        btn_dl.pack(side="right")

        # Center area
        center_f = tk.Frame(top, bg="#0A0A0A")
        center_f.pack(fill="both", expand=True, padx=10, pady=10)

        lbl_status = tk.Label(center_f, text="Đang tải ảnh độ phân giải cao...", bg="#0A0A0A", fg="#A0A0A0", font=("Segoe UI", 11))
        lbl_status.pack(expand=True)

        lbl_img = tk.Label(center_f, bg="#0A0A0A")

        def _fetch_zoom():
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    data = resp.read()
                pil_im = Image.open(__import__("io").BytesIO(data))
                pil_im.thumbnail((920, 820), Image.Resampling.LANCZOS)
                tk_photo = ImageTk.PhotoImage(pil_im)
                
                def _show():
                    lbl_status.pack_forget()
                    lbl_img.config(image=tk_photo)
                    lbl_img.image = tk_photo
                    lbl_img.pack(fill="both", expand=True)

                self.after(0, _show)
            except Exception as e:
                self.after(0, lambda: lbl_status.config(text=f"Lỗi tải ảnh: {e}", fg="#FF5555"))

        threading.Thread(target=_fetch_zoom, daemon=True).start()

    def open_zoom_window(self, url):
        self.open_zoom_modal(url)

    def zoom_current_image(self):
        if self.generated_images:
            self.open_zoom_modal(self.generated_images[self.current_image_idx])

    def download_single_image(self, url, idx=1):
        fpath = filedialog.asksaveasfilename(defaultextension=".png", filetypes=[("PNG Image", "*.png")], initialfile=f"PixAI_Nexus_{int(time.time())}_{idx}.png")
        if not fpath:
            return

        def _dl():
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=35) as resp:
                    with open(fpath, "wb") as f:
                        f.write(resp.read())
                self.after(0, lambda: messagebox.showinfo("Thành công", f"Đã lưu ảnh thành công vào:\n{fpath}"))
            except Exception as e:
                self.after(0, lambda err=str(e): messagebox.showerror("Lỗi", f"Không thể lưu ảnh: {err}"))
        threading.Thread(target=_dl, daemon=True).start()

    def download_current_image(self):
        if self.generated_images:
            self.download_single_image(self.generated_images[self.current_image_idx])

    def open_history_detail_window(self, hist_item, urls):
        top = tk.Toplevel(self)
        top.title(f"Phiên Lịch Sử #{hist_item.get('id', '')[-8:]} - PixAI Nexus")
        top.geometry("800x850")
        top.configure(bg="#0A0A0A")

        top_bar = tk.Frame(top, bg="#141414", bd=1, relief="solid", padx=12, pady=10)
        top_bar.pack(fill="x")

        tk.Label(top_bar, text=f"LỊCH SỬ • {hist_item.get('profile', '').upper()} • {hist_item.get('width', '')}x{hist_item.get('height', '')}", bg="#141414", fg="#FFFFFF", font=("Segoe UI", 10, "bold")).pack(side="left")

        btn_close = tk.Button(top_bar, text="✕ Đóng", bg="#242424", fg="#FFFFFF", relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 9), command=top.destroy)
        btn_close.pack(side="right")

        def _delete_this_history():
            if messagebox.askyesno("Xác nhận xóa phiên", "Bạn có chắc muốn xóa phiên này khỏi lịch sử?", parent=top):
                t_id = hist_item.get("taskId") or hist_item.get("id")
                self.history = [x for x in self.history if (x.get("taskId") != t_id and x.get("id") != t_id)]
                save_encrypted_history(self.history)
                self.refresh_history_table()
                self.log_message(f"Đã xóa phiên #{t_id} khỏi lịch sử.", "info")
                top.destroy()

        btn_del_this = tk.Button(top_bar, text="Xóa Phiên Này", bg="#2A1616", fg=self.c_danger, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 9), command=_delete_this_history)
        btn_del_this.pack(side="right", padx=(0, 8))

        btn_load_main = tk.Button(top_bar, text="Nạp Vào Studio", bg="#242424", fg=self.c_success, relief="solid", bd=1, padx=10, pady=3, font=("Segoe UI", 9, "bold"), command=lambda: [self.apply_task_to_preview(hist_item), top.destroy()])
        btn_load_main.pack(side="right", padx=(0, 8))

        # Prompts info
        p_frame = tk.Frame(top, bg="#141414", padx=12, pady=8)
        p_frame.pack(fill="x", padx=10, pady=(8, 0))

        tk.Label(p_frame, text="Prompt:", bg="#141414", fg=self.c_muted, font=("Segoe UI", 8)).pack(anchor="w")
        tk.Label(p_frame, text=hist_item.get("originalPrompt", ""), bg="#141414", fg="#FFFFFF", font=("Segoe UI", 8), wraplength=750, justify="left").pack(anchor="w", pady=(0, 4))

        if hist_item.get("serverPrompt"):
            tk.Label(p_frame, text="Server Prompt:", bg="#141414", fg=self.c_muted, font=("Segoe UI", 8)).pack(anchor="w")
            tk.Label(p_frame, text=hist_item.get("serverPrompt", ""), bg="#141414", fg=self.c_success, font=("Segoe UI", 8), wraplength=750, justify="left").pack(anchor="w")

        # Scrollable image feed
        feed_box = tk.Frame(top, bg="#0A0A0A")
        feed_box.pack(fill="both", expand=True, padx=10, pady=8)

        cv = tk.Canvas(feed_box, bg="#0A0A0A", highlightthickness=0)
        sb = ttk.Scrollbar(feed_box, orient="vertical", command=cv.yview)
        inner = tk.Frame(cv, bg="#0A0A0A")
        inner.bind("<Configure>", lambda e: cv.configure(scrollregion=cv.bbox("all")))
        cv_win = cv.create_window((0, 0), window=inner, anchor="nw")
        cv.bind("<Configure>", lambda e: cv.itemconfig(cv_win, width=e.width))
        cv.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        cv.pack(side="left", fill="both", expand=True)

        for i, u in enumerate(urls):
            c = tk.Frame(inner, bg="#141414", bd=1, relief="solid", padx=10, pady=10)
            c.pack(fill="x", pady=6)

            h_row = tk.Frame(c, bg="#141414")
            h_row.pack(fill="x", pady=(0, 4))
            tk.Label(h_row, text=f"Ảnh {i + 1} / {len(urls)}", bg="#141414", fg="#FFFFFF", font=("Segoe UI", 9, "bold")).pack(side="left")

            l_img = tk.Label(c, text="Đang tải ảnh...", bg="#101010", fg=self.c_muted, height=10)
            l_img.pack(fill="x", pady=(0, 6))

            b_row = tk.Frame(c, bg="#141414")
            b_row.pack(fill="x")
            tk.Button(b_row, text="Phóng To", bg="#242424", fg="#FFFFFF", relief="solid", bd=1, padx=8, pady=3, font=("Segoe UI", 8, "bold"), command=lambda x=u: self.open_zoom_modal(x)).pack(side="left", padx=(0, 6))
            tk.Button(b_row, text="Lưu Vào Máy", bg="#242424", fg=self.c_success, relief="solid", bd=1, padx=8, pady=3, font=("Segoe UI", 8, "bold"), command=lambda x=u, j=i+1: self.download_single_image(x, j)).pack(side="left")

            threading.Thread(target=self._load_feed_image_worker, args=(u, l_img), daemon=True).start()

    # ================= QUẢN LÝ TÀI KHOẢN & AUTO-REG =================
    def on_add_account_token(self):
        token = self.ent_new_token.get().strip()
        if not token:
            messagebox.showwarning("Cảnh báo", "Vui lòng nhập Bearer Token!")
            return
        if token.startswith("Bearer "):
            token = token[7:].strip()

        threading.Thread(target=self._add_single_token_worker, args=(token,), daemon=True).start()

    def _add_single_token_worker(self, token):
        proxy = self.get_effective_proxy()
        quota = get_account_quota(token, proxy=proxy)
        email = f"acc_{int(time.time() * 1000) % 100000}@nexus.com"

        for a in self.accounts:
            if a.get("token") == token:
                a["credits"] = quota
                self.save_accounts()
                self.after(0, lambda: self._on_accounts_updated("Đã cập nhật số dư cho token hiện có."))
                return

        new_acc = {"email": email, "token": token, "credits": quota}
        self.accounts.insert(0, new_acc)
        if not self.active_account:
            self.active_account = new_acc
        self.save_accounts()
        self.after(0, lambda: self._on_accounts_updated(f"Đã thêm tài khoản {email} ({quota:,} CR)"))

    def on_auto_register_account(self):
        self.btn_auto_reg.config(state="disabled")
        self.log_message("Bắt đầu tự động đăng ký tài khoản PixAI mới ngầm...", "info")

        proxy = self.get_effective_proxy()
        threading.Thread(target=self._auto_register_worker, args=(proxy,), daemon=True).start()

    def _auto_register_worker(self, proxy):
        try:
            # Sinh email ngẫu nhiên & mật khẩu
            rand_id = random.randint(100000, 999999)
            email = f"nexus_user_{int(time.time())}_{rand_id}@pixai-nexus.internal"
            password = f"NexusPass@{rand_id}X!"

            # Gửi đăng ký trực tiếp API PixAI
            reg_payload = {
                "operationName": "signup",
                "variables": {
                    "email": email,
                    "password": password
                },
                "query": """mutation signup($email: String!, $password: String!) {
  signup(email: $email, password: $password) {
    token
    user {
      id
      email
    }
  }
}"""
            }
            res = http_graphql(reg_payload, token="", proxy=proxy, timeout=25)
            token = res.get("data", {}).get("signup", {}).get("token")

            if not token:
                # Nếu API signup yêu cầu captcha, fallback tạo guest token
                guest_payload = {
                    "operationName": "createGuestSession",
                    "variables": {},
                    "query": """mutation createGuestSession {
  createGuestSession {
    token
  }
}"""
                }
                res_g = http_graphql(guest_payload, token="", proxy=proxy, timeout=20)
                token = res_g.get("data", {}).get("createGuestSession", {}).get("token")

            if token:
                # Tự động nhận thưởng và mở khóa 18+
                claim_daily(token, proxy=proxy)
                claim_all_quests(token, proxy=proxy)
                unblur_nsfw_for_account(token, proxy=proxy)
                quota = get_account_quota(token, proxy=proxy)

                new_acc = {"email": email, "token": token, "credits": quota}
                self.accounts.insert(0, new_acc)
                if not self.active_account:
                    self.active_account = new_acc
                self.save_accounts()

                self.after(0, lambda: self._on_accounts_updated(f"Tự đăng ký thành công: {email} với {quota:,} CR!"))
            else:
                self.after(0, lambda: self.log_message(f"Đăng ký thất bại: Máy chủ yêu cầu captcha hoặc từ chối ({res})", "error"))

        except Exception as e:
            self.after(0, lambda err=str(e): self.log_message(f"Lỗi tự đăng ký: {err}", "error"))
        finally:
            self.after(0, lambda: self.btn_auto_reg.config(state="normal"))

    def _on_accounts_updated(self, msg):
        self.ent_new_token.delete(0, "end")
        self.refresh_accounts_table()
        self.update_header()
        self.update_live_cost()
        self.log_message(msg, "success")
        messagebox.showinfo("Thành công", msg)

    def refresh_accounts_table(self):
        for item in self.acc_tree.get_children():
            self.acc_tree.delete(item)
        for acc in self.accounts:
            is_active = (self.active_account and self.active_account.get("email") == acc.get("email"))
            status = "Đang Hoạt Động" if is_active else "Sẵn Sàng"
            email_display = ("* " if is_active else "") + acc.get("email", "Unknown")
            self.acc_tree.insert("", "end", values=(email_display, f"{acc.get('credits', 0):,} CR", status))

    def on_select_active_account(self, event):
        sel = self.acc_tree.selection()
        if not sel:
            return
        idx = self.acc_tree.index(sel[0])
        if 0 <= idx < len(self.accounts):
            self.active_account = self.accounts[idx]
            self.refresh_accounts_table()
            self.update_header()
            self.update_live_cost()
            self.log_message(f"Đã chọn làm việc với tài khoản: {self.active_account.get('email')}", "info")

    def on_sync_credits(self):
        proxy = self.get_effective_proxy()
        def _sync():
            for acc in self.accounts:
                t = acc.get("token")
                if t:
                    acc["credits"] = get_account_quota(t, proxy=proxy)
            self.save_accounts()
            self.after(0, lambda: self._on_accounts_updated("Đồng bộ số dư toàn bộ tài khoản hoàn tất."))
        threading.Thread(target=_sync, daemon=True).start()

    def on_claim_daily(self):
        proxy = self.get_effective_proxy()
        def _claim():
            succ = 0
            for acc in self.accounts:
                t = acc.get("token")
                if t and claim_daily(t, proxy=proxy):
                    succ += 1
                    acc["credits"] = get_account_quota(t, proxy=proxy)
            self.save_accounts()
            self.after(0, lambda: self._on_accounts_updated(f"Nhận Daily thành công cho {succ} tài khoản!"))
        threading.Thread(target=_claim, daemon=True).start()

    def on_claim_quests(self):
        proxy = self.get_effective_proxy()
        def _quests():
            total_q = 0
            for acc in self.accounts:
                t = acc.get("token")
                if t:
                    total_q += claim_all_quests(t, proxy=proxy)
                    acc["credits"] = get_account_quota(t, proxy=proxy)
            self.save_accounts()
            self.after(0, lambda: self._on_accounts_updated(f"Đã hoàn thành {total_q} lượt nhận thưởng nhiệm vụ!"))
        threading.Thread(target=_quests, daemon=True).start()

    def on_unblur_all(self):
        proxy = self.get_effective_proxy()
        def _unblur():
            for acc in self.accounts:
                t = acc.get("token")
                if t:
                    unblur_nsfw_for_account(t, proxy=proxy)
            self.after(0, lambda: self.log_message("Đã gửi yêu cầu mở khóa 18+ và bỏ làm mờ nhạy cảm cho toàn bộ tài khoản.", "success"))
            self.after(0, lambda: messagebox.showinfo("Thành công", "Đã mở khóa 18+ toàn bộ tài khoản!"))
        threading.Thread(target=_unblur, daemon=True).start()

    def on_clear_accounts(self):
        if messagebox.askyesno("Xác nhận", "Bạn có chắc chắn muốn xóa toàn bộ danh sách tài khoản?"):
            self.accounts.clear()
            self.active_account = None
            self.save_accounts()
            self.refresh_accounts_table()
            self.update_header()
            self.update_live_cost()
            self.log_message("Đã xóa toàn bộ tài khoản.", "info")

    def on_import_text_dialog(self):
        top = tk.Toplevel(self)
        top.title("Nhập Danh Sách Token Hàng Loạt")
        top.geometry("600x400")
        top.configure(bg=self.c_bg)

        tk.Label(top, text="Dán danh sách token (mỗi dòng 1 token hoặc email:token):", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 9)).pack(anchor="w", padx=12, pady=(10, 4))
        txt = tk.Text(top, bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid")
        txt.pack(fill="both", expand=True, padx=12, pady=(0, 10))

        def _do_import():
            raw_text = txt.get("1.0", "end").strip()
            if not raw_text:
                return
            count = 0
            for line in raw_text.splitlines():
                l = line.strip()
                if not l:
                    continue
                parts = l.split(":")
                token = parts[-1].strip()
                if token.startswith("Bearer "):
                    token = token[7:].strip()
                if len(token) >= 20:
                    email = parts[0].strip() if len(parts) > 1 else f"acc_{int(time.time()*1000)%100000}_{random.randint(10,99)}@nexus.com"
                    self.accounts.append({"email": email, "token": token, "credits": 0})
                    count += 1
            self.save_accounts()
            top.destroy()
            self.on_sync_credits()
            messagebox.showinfo("Thành công", f"Đã nhập {count} tài khoản! Đang đồng bộ số dư...")

        btn = tk.Button(top, text="Nhập Vào Danh Sách", bg="#242424", fg="#FFFFFF", activebackground="#303030", activeforeground="#FFFFFF", relief="solid", bd=1, padx=16, pady=6, font=("Segoe UI", 9, "bold"), command=_do_import)
        btn.pack(pady=(0, 12))

    def on_import_json_dialog(self):
        fpath = filedialog.askopenfilename(title="Chọn file backup JSON", filetypes=[("JSON Files", "*.json")])
        if not fpath:
            return
        try:
            with open(fpath, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                self.accounts.extend(data)
                self.save_accounts()
                self.refresh_accounts_table()
                self.update_header()
                self.update_live_cost()
                messagebox.showinfo("Thành công", f"Đã nhập thành công {len(data)} tài khoản từ JSON!")
        except Exception as e:
            messagebox.showerror("Lỗi", f"Không thể đọc file JSON: {e}")

    def on_export_json(self):
        fpath = filedialog.asksaveasfilename(defaultextension=".json", filetypes=[("JSON Files", "*.json")], initialfile="nexus_accounts_backup.json")
        if not fpath:
            return
        try:
            with open(fpath, "w", encoding="utf-8") as f:
                json.dump(self.accounts, f, indent=2, ensure_ascii=False)
            # Copy to clipboard
            self.clipboard_clear()
            self.clipboard_append(json.dumps(self.accounts, indent=2, ensure_ascii=False))
            messagebox.showinfo("Thành công", f"Đã lưu ra {fpath} và sao chép vào Clipboard!")
        except Exception as e:
            messagebox.showerror("Lỗi", f"Không thể xuất file JSON: {e}")

    # ================= KHO LỊCH SỬ MÃ HÓA & MẬT KHẨU PIN =================
    def on_unlock_history(self):
        entered = self.ent_hist_pin.get().strip()
        expected = self.config.get("history_pin_hash", "")
        if hashlib.sha256(entered.encode("utf-8")).hexdigest() == expected:
            self.is_history_unlocked = True
            self.ent_hist_pin.delete(0, "end")
            self.check_history_lock_view()
            messagebox.showinfo("Mở Khóa", "Mở khóa kho lịch sử thành công!")
        else:
            messagebox.showerror("Lỗi", "Mã PIN hoặc mật khẩu không chính xác!")

    def on_history_security_dialog(self):
        has_pin = bool(self.config.get("history_pin_hash"))
        if has_pin:
            if messagebox.askyesno("Bảo Mật", "Kho lịch sử đang được bảo vệ bằng mật khẩu.\nBạn có muốn gỡ bỏ mật khẩu?"):
                self.config["history_pin_hash"] = ""
                self.save_config()
                self.is_history_unlocked = True
                self.check_history_lock_view()
                messagebox.showinfo("Thành công", "Đã gỡ mật khẩu bảo vệ lịch sử!")
        else:
            top = tk.Toplevel(self)
            top.title("Thiết Lập Mật Khẩu PIN Kho Lịch Sử")
            top.geometry("380x180")
            top.configure(bg=self.c_bg)

            tk.Label(top, text="Nhập mã PIN hoặc mật khẩu mới:", bg=self.c_bg, fg=self.c_text, font=("Segoe UI", 9)).pack(pady=(16, 6))
            ent = tk.Entry(top, show="*", bg=self.c_input, fg=self.c_text, insertbackground=self.c_text, bd=1, relief="solid", justify="center", font=("Segoe UI", 11), width=18)
            ent.pack(pady=(0, 14))

            def _save_pin():
                p = ent.get().strip()
                if p:
                    self.config["history_pin_hash"] = hashlib.sha256(p.encode("utf-8")).hexdigest()
                    self.save_config()
                    self.is_history_unlocked = False
                    self.check_history_lock_view()
                    top.destroy()
                    messagebox.showinfo("Thành công", "Đã kích hoạt mật khẩu bảo vệ kho lịch sử!")

            tk.Button(top, text="Lưu Mật Khẩu", bg="#242424", fg="#FFFFFF", activebackground="#303030", activeforeground="#FFFFFF", relief="solid", bd=1, padx=12, pady=4, font=("Segoe UI", 9, "bold"), command=_save_pin).pack()

    def refresh_history_table(self):
        if not hasattr(self, "hist_tree"):
            return
        for item in self.hist_tree.get_children():
            self.hist_tree.delete(item)
        for h in self.history[:100]:
            t_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(h.get("timestamp", 0) / 1000))
            self.hist_tree.insert("", "end", values=(t_str, h.get("profile", "").upper(), f"{h.get('width')}x{h.get('height')}", h.get("originalPrompt", "")))

    def on_history_double_click(self, event):
        sel = self.hist_tree.selection()
        if not sel:
            return
        idx = self.hist_tree.index(sel[0])
        if 0 <= idx < len(self.history):
            h = self.history[idx]
            urls = h.get("imageUrls", [])
            if not urls and h.get("imageUrl"):
                urls = [h["imageUrl"]]
            if urls:
                self.open_history_detail_window(h, urls)

    def on_delete_selected_history(self):
        sel = self.hist_tree.selection()
        if not sel:
            messagebox.showinfo("Thông báo", "Vui lòng chọn một phiên trong danh sách để xóa.")
            return
        idx = self.hist_tree.index(sel[0])
        if 0 <= idx < len(self.history):
            h = self.history[idx]
            task_id = h.get("taskId") or h.get("id", "N/A")
            short_id = task_id[-8:] if len(task_id) > 8 else task_id
            if messagebox.askyesno("Xác nhận xóa phiên", f"Bạn có chắc muốn xóa phiên #{short_id} khỏi lịch sử?"):
                self.history.pop(idx)
                save_encrypted_history(self.history)
                self.refresh_history_table()
                self.log_message(f"Đã xóa phiên #{task_id} khỏi lịch sử.", "info")

    def on_clear_history(self):
        if messagebox.askyesno("Xác nhận", "Bạn có chắc chắn muốn xóa toàn bộ kho lịch sử mã hóa?"):
            self.history.clear()
            save_encrypted_history(self.history)
            self.refresh_history_table()
            self.log_message("Đã xóa sạch kho lịch sử.", "info")

    # ================= BẢO MẬT & PROXY =================
    def on_import_webshare(self):
        raw = self.txt_proxy_pool.get("1.0", "end").strip()
        if not raw:
            from tkinter.simpledialog import askstring
            ans = askstring("Nạp Proxy Webshare", "Dán lệnh cURL, Token, Link Download hoặc danh sách Proxy Webshare:")
            if not ans:
                return
            raw = ans.strip()

        self.log_message("Đang nạp và phân tích danh sách Proxy Webshare...", "info")

        def _fetch():
            proxies = fetch_webshare_proxies(raw)
            if not proxies:
                self.after(0, lambda: messagebox.showwarning("Cảnh báo", "Không tìm thấy proxy hợp lệ từ nội dung nhập. Vui lòng kiểm tra lại."))
                return

            def _apply():
                self.txt_proxy_pool.delete("1.0", "end")
                self.txt_proxy_pool.insert("1.0", "\n".join(proxies))
                self.config["proxy_pool"] = proxies
                self.config["proxy_address"] = proxies[0]
                self.config["use_proxy"] = True
                self.var_use_proxy.set(True)
                self.save_config()
                self.update_proxy_pool_status_label()
                self.lbl_proxy_status.config(text="Proxy Shield: BẬT")
                self.log_message(f"Đã nạp thành công {len(proxies)} proxy vào Pool và kích hoạt Shield!", "success")
                messagebox.showinfo("Thành công", f"Đã nạp thành công {len(proxies)} Proxy vào danh sách xoay tua!")
            self.after(0, _apply)

        threading.Thread(target=_fetch, daemon=True).start()

    def on_save_security_config(self):
        raw_txt = self.txt_proxy_pool.get("1.0", "end").strip()
        extracted = extract_proxies_from_command_or_text(raw_txt)
        self.config["proxy_pool"] = extracted
        self.config["proxy_address"] = extracted[0] if extracted else ""
        self.config["use_proxy"] = self.var_use_proxy.get()
        self.config["auto_rotate_proxies"] = self.var_auto_rotate_proxies.get()
        self.config["strict_kill_switch"] = self.var_strict_kill_switch.get()
        self.config["auto_fallback_direct"] = self.var_auto_fallback.get()
        self.config["auto_rotate_accounts"] = self.var_auto_rotate.get()
        self.save_config()
        self.update_proxy_pool_status_label()
        self.lbl_proxy_status.config(text="Proxy Shield: BẬT" if self.config["use_proxy"] else "Proxy Shield: TẮT")
        self.log_message(f"Đã lưu cấu hình: Pool={len(extracted)} proxy (Shield={'BẬT' if self.config['use_proxy'] else 'TẮT'})", "info")
        messagebox.showinfo("Thành công", f"Đã lưu cấu hình Bảo Mật & Proxy Shield!\n- Pool: {len(extracted)} proxy\n- Xoay tua: {'BẬT' if self.config['auto_rotate_proxies'] else 'TẮT'}\n- Strict Kill-Switch: {'BẬT' if self.config['strict_kill_switch'] else 'TẮT'}")

    def on_test_proxy(self):
        proxy = self.get_effective_proxy()
        if not proxy:
            raw_txt = self.txt_proxy_pool.get("1.0", "end").strip()
            ext = extract_proxies_from_command_or_text(raw_txt)
            if ext:
                proxy = ext[0]

        self.lbl_proxy_test_res.config(text="Đang kiểm tra kết nối qua Proxy Shield...", fg=self.c_text)

        def _test():
            t0 = time.time()
            try:
                raw = http_request("https://ipwho.is/", method="GET", proxy=proxy, timeout=12)
                ping = int((time.time() - t0) * 1000)
                data = json.loads(raw.decode("utf-8"))
                ip = data.get("ip", "Unknown")
                country = data.get("country", "Unknown")
                res_txt = f"BẢO VỆ THÀNH CÔNG: IP {ip} | Quốc gia: {country} | Ping: {ping}ms"
                self.after(0, lambda: self.lbl_proxy_test_res.config(text=res_txt, fg=self.c_success))
                self.after(0, lambda: self.log_message(f"Test Proxy thành công: IP {ip} ({country}) - Ping {ping}ms", "success"))
            except Exception as e:
                err_txt = f"KẾT NỐI THẤT BẠI: {e}"
                self.after(0, lambda: self.lbl_proxy_test_res.config(text=err_txt, fg=self.c_danger))
                self.after(0, lambda: self.log_message(f"Test Proxy thất bại: {e}", "error"))

        threading.Thread(target=_test, daemon=True).start()

    # ================= LOGGING =================
    def log_message(self, message, level="info"):
        t_str = time.strftime("%H:%M:%S")
        line = f"{t_str} {message}\n"
        self.txt_logs.insert("end", line)
        self.txt_logs.see("end")


if __name__ == "__main__":
    app = PixAiNexusStudio()
    app.mainloop()
