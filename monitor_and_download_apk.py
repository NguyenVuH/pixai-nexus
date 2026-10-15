import ctypes
import ctypes.wintypes
import json
import os
import subprocess
import sys
import time
import zipfile

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

class CREDENTIAL(ctypes.Structure):
    _fields_ = [
        ('Flags', ctypes.wintypes.DWORD),
        ('Type', ctypes.wintypes.DWORD),
        ('TargetName', ctypes.c_wchar_p),
        ('Comment', ctypes.c_wchar_p),
        ('LastWritten', ctypes.wintypes.FILETIME),
        ('CredentialBlobSize', ctypes.wintypes.DWORD),
        ('CredentialBlob', ctypes.POINTER(ctypes.c_char)),
        ('Persist', ctypes.wintypes.DWORD),
        ('AttributeCount', ctypes.wintypes.DWORD),
        ('Attributes', ctypes.c_void_p),
        ('TargetAlias', ctypes.c_wchar_p),
        ('UserName', ctypes.c_wchar_p),
    ]

def get_token():
    advapi32 = ctypes.windll.advapi32
    pCred = ctypes.POINTER(CREDENTIAL)()
    if advapi32.CredReadW('git:https://github.com', 1, 0, ctypes.byref(pCred)):
        cred = pCred.contents
        raw = ctypes.string_at(cred.CredentialBlob, cred.CredentialBlobSize)
        token = raw.decode('utf-16-le').strip('\x00\r\n\t ')
        advapi32.CredFree(pCred)
        return token
    return None

def curl_json(url, token):
    cmd = [
        'curl.exe', '-s',
        '-H', f'Authorization: token {token}',
        '-H', 'Accept: application/vnd.github.v3+json',
        '-H', 'User-Agent: PixAI-Nexus-Monitor',
        url
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    try:
        return json.loads(res.stdout)
    except Exception:
        return None

def main():
    token = get_token()
    if not token:
        print("[!] Không tìm thấy GitHub token!")
        return

    repo = "NguyenVuH/pixai-nexus"
    print(f"[*] Theo dõi tiến trình GitHub Actions cho repository: {repo}")

    start_time = time.time()
    while time.time() - start_time < 900:
        data = curl_json(f'https://api.github.com/repos/{repo}/actions/runs', token)
        if data and 'workflow_runs' in data and data['workflow_runs']:
            latest = data['workflow_runs'][0]
            target_run_id = latest['id']
            status = latest.get('status')
            conclusion = latest.get('conclusion')
            event = latest.get('event')
            print(f"[+] Run #{target_run_id} | Sự kiện: {event} | Trạng thái: {status} | Kết quả: {conclusion}")

            if status == 'completed':
                if conclusion == 'success':
                    print("[✓] Build Release APK trên GitHub Actions đã thành công 100%!")
                    download_apk(repo, target_run_id, token)
                    return
                else:
                    print(f"[!] Build kết thúc với lỗi (conclusion: {conclusion})!")
                    fetch_failure_logs(repo, target_run_id, token)
                    return
        else:
            print("[-] Đang kết nối GitHub Actions...")

        time.sleep(12)

    print("[!] Hết thời gian chờ (15 phút)!")

def download_apk(repo, run_id, token):
    out_dir = os.path.join(os.path.dirname(__file__), "release_apk")
    os.makedirs(out_dir, exist_ok=True)

    art_data = curl_json(f'https://api.github.com/repos/{repo}/actions/runs/{run_id}/artifacts', token)
    if art_data and 'artifacts' in art_data:
        for art in art_data['artifacts']:
            name = art.get('name')
            dl_url = art.get('archive_download_url')
            print(f"[*] Tìm thấy Artifact: {name} ({art.get('size_in_bytes')} bytes)")
            zip_path = os.path.join(out_dir, f"{name}.zip")

            cmd = [
                'curl.exe', '-s', '-L',
                '-H', f'Authorization: token {token}',
                '-H', 'User-Agent: PixAI-Nexus-Downloader',
                '-o', zip_path,
                dl_url
            ]
            subprocess.run(cmd)

            if os.path.exists(zip_path) and os.path.getsize(zip_path) > 1000:
                print(f"[✓] Đã tải Artifact zip về: {zip_path} ({os.path.getsize(zip_path):,} bytes)")
                try:
                    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                        zip_ref.extractall(out_dir)
                    print(f"[✓] Đã giải nén APK thành công vào thư mục: {out_dir}")
                    for fname in os.listdir(out_dir):
                        if fname.endswith('.apk'):
                            apk_full = os.path.join(out_dir, fname)
                            print(f"\n=======================================================")
                            print(f"==> FILE APK CHÍNH THỨC: {apk_full}")
                            print(f"==> KÍCH THƯỚC: {os.path.getsize(apk_full):,} bytes")
                            print(f"=======================================================\n")
                except Exception as e:
                    print(f"[!] Lỗi giải nén: {e}")
                return

    # Thử tải từ GitHub Releases
    rel_data = curl_json(f'https://api.github.com/repos/{repo}/releases/tags/v1.0.0', token)
    if rel_data and 'assets' in rel_data:
        for asset in rel_data['assets']:
            if asset.get('name', '').endswith('.apk'):
                dest_apk = os.path.join(out_dir, asset.get('name'))
                cmd = [
                    'curl.exe', '-s', '-L',
                    '-H', f'Authorization: token {token}',
                    '-o', dest_apk,
                    asset.get('browser_download_url')
                ]
                subprocess.run(cmd)
                print(f"[✓] Đã tải APK từ GitHub Release: {dest_apk}")

def fetch_failure_logs(repo, run_id, token):
    job_data = curl_json(f'https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs', token)
    if job_data and 'jobs' in job_data:
        for j in job_data['jobs']:
            print(f"Job: {j['name']} | Conclusion: {j.get('conclusion')}")
            job_id = j['id']
            cmd = [
                'curl.exe', '-s', '-L',
                '-H', f'Authorization: token {token}',
                f'https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs'
            ]
            res = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
            for line in res.stdout.splitlines():
                if 'error:' in line.lower() or 'failure:' in line.lower():
                    print("  -->", line)

if __name__ == "__main__":
    main()
