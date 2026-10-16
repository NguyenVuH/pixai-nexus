import asyncio
import json
import os
import sys
from datetime import datetime
from playwright.async_api import async_playwright

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

CAPTURED_FILE = "captured_pixai_api.json"
captured_data = []

def save_entry(entry):
    captured_data.append(entry)
    try:
        with open(CAPTURED_FILE, "w", encoding="utf-8") as f:
            json.dump(captured_data, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[!] Lỗi ghi file {CAPTURED_FILE}: {e}")

async def main():
    print("=" * 70)
    print("   PIXAI API SNIFFER & NETWORK CAPTURE - CHUẨN XÁC 100%")
    print("=" * 70)
    print("[*] Công cụ này sẽ mở trình duyệt Chrome và bắt toàn bộ Request/Response")
    print("    gửi tới máy chủ PixAI (bao gồm 18+, NSFW preferences, Content-Safety,")
    print("    và GraphQL createGenerationTask không lọc từ khóa).")
    print("-" * 70)

    # Chọn tài khoản từ accounts.json nếu có
    token_to_inject = None
    account_email = None
    if os.path.exists("accounts.json"):
        try:
            with open("accounts.json", "r", encoding="utf-8") as f:
                accs = json.load(f)
            if accs:
                print(f"[+] Tìm thấy {len(accs)} tài khoản trong accounts.json.")
                print(f"    1. Tự động tiêm phiên tài khoản top: {accs[0].get('email')}")
                print(f"    2. Tự đăng nhập thủ công trên giao diện Web")
                choice = input("Chọn [1] hoặc [2] (mặc định 1): ").strip()
                if choice != "2":
                    token_to_inject = accs[0].get("token")
                    account_email = accs[0].get("email")
                    print(f"[✓] Đã chọn tài khoản: {account_email}")
        except Exception as e:
            print(f"[!] Không đọc được accounts.json: {e}")

    print("\n[*] Đang khởi động trình duyệt Chromium / Google Chrome...")
    async with async_playwright() as p:
        # Ưu tiên mở Google Chrome có sẵn trên Windows nếu tìm thấy
        chrome_exe = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
        launch_args = {
            "headless": False,
            "args": [
                "--start-maximized",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-blink-features=AutomationControlled"
            ]
        }
        if os.path.exists(chrome_exe):
            launch_args["executable_path"] = chrome_exe

        browser = await p.chromium.launch(**launch_args)
        context = await browser.new_context(no_viewport=True)
        page = await context.new_page()

        # Bắt Request
        async def on_request(request):
            url = request.url
            if "api.pixai.art" in url:
                now_str = datetime.now().strftime("%H:%M:%S")
                method = request.method
                post_data = request.post_data
                headers = request.headers
                auth = headers.get("authorization", "")

                print("\n" + "=" * 70)
                print(f"[{now_str}] >>> [REQUEST] {method} {url}")
                if auth:
                    print(f"[*] Authorization: {auth[:25]}... (Token hợp lệ)")

                entry = {
                    "timestamp": now_str,
                    "type": "REQUEST",
                    "method": method,
                    "url": url,
                    "headers": headers,
                    "post_data": None
                }

                if post_data:
                    try:
                        parsed_json = json.loads(post_data)
                        entry["post_data"] = parsed_json
                        op_name = parsed_json.get("operationName", "")
                        if op_name:
                            print(f"[⭐ GRAPHQL OPERATION]: {op_name}")
                        if "variables" in parsed_json:
                            print(f"[⭐ VARIABLES]:\n{json.dumps(parsed_json['variables'], ensure_ascii=False, indent=2)}")
                        if "query" in parsed_json:
                            print(f"[⭐ QUERY / MUTATION]:\n{parsed_json['query'].strip()[:200]}...")
                        else:
                            print(f"[⭐ PAYLOAD]:\n{json.dumps(parsed_json, ensure_ascii=False, indent=2)}")
                    except Exception:
                        entry["post_data"] = post_data
                        print(f"[⭐ RAW BODY]: {post_data}")

                save_entry(entry)
                print("=" * 70)

        # Bắt Response
        async def on_response(response):
            url = response.url
            if "api.pixai.art" in url:
                now_str = datetime.now().strftime("%H:%M:%S")
                status = response.status
                print(f"[{now_str}] <<< [RESPONSE] Status: {status} | URL: {url}")
                try:
                    res_body = await response.text()
                    try:
                        res_json = json.loads(res_body)
                        print(f"[⭐ RESPONSE BODY]:\n{json.dumps(res_json, ensure_ascii=False, indent=2)}")
                        save_entry({
                            "timestamp": now_str,
                            "type": "RESPONSE",
                            "status": status,
                            "url": url,
                            "data": res_json
                        })
                    except Exception:
                        print(f"[⭐ RESPONSE TEXT]: {res_body[:300]}")
                        save_entry({
                            "timestamp": now_str,
                            "type": "RESPONSE",
                            "status": status,
                            "url": url,
                            "text": res_body
                        })
                except Exception as e:
                    pass

        page.on("request", on_request)
        page.on("response", on_response)

        print("[*] Đang mở trang PixAI Settings...")
        await page.goto("https://pixai.art/en")

        # Tiêm Token nếu có
        if token_to_inject:
            print(f"[*] Đang tiêm token phiên của {account_email} vào trình duyệt...")
            await page.evaluate(f"""() => {{
                localStorage.setItem('https://api.pixai.art:token', '{token_to_inject}');
                localStorage.setItem('user_token', '{token_to_inject}');
                document.cookie = 'user_token={token_to_inject}; path=/; domain=.pixai.art';
            }}""")
            await page.reload()
            print("[✓] Đã tiêm token thành công! Trình duyệt đã ở trạng thái ĐĂNG NHẬP.")

        print("\n" + "#" * 70)
        print(">>> TRÌNH DUYỆT ĐÃ SẴN SÀNG ĐỂ BẠN THAO TÁC! <<<")
        print("1. Hãy truy cập vào phần Cài Đặt (Profile -> Settings) để bật 18+ / NSFW.")
        print("2. Hoặc vào Trình tạo ảnh (Generator) để nhập bất kỳ prompt nào bạn muốn.")
        print("3. TẤT CẢ API (URL, Headers, GraphQL, JSON Variables) sẽ hiển thị ngay")
        print(f"   tại terminal này và tự động lưu vào file: {CAPTURED_FILE}")
        print("#" * 70 + "\n")

        # Giữ phiên chạy cho đến khi người dùng đóng trình duyệt
        try:
            while not page.is_closed():
                await asyncio.sleep(1)
        except Exception:
            pass
        print("\n[*] Đã kết thúc phiên bắt API. Toàn bộ dữ liệu được lưu tại: " + CAPTURED_FILE)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[*] Người dùng đã dừng chương trình.")
