import base64
import os
from pathlib import Path
import httpx
from dotenv import load_dotenv

# Resolve path to backend/.env regardless of current working directory
env_path = Path(__file__).resolve().parent.parent / ".env"
if not env_path.exists():
    # Fallback to local directory if run from root
    env_path = Path(".env").resolve()

load_dotenv(dotenv_path=env_path, override=True)

app_id = os.getenv("AGORA_APP_ID", "").strip().strip('"').strip("'")
key = (
    os.getenv("AGORA_CUSTOMER_KEY") or os.getenv("AGORA_CUSTOMER_ID", "")
).strip().strip('"').strip("'")
secret = os.getenv("AGORA_CUSTOMER_SECRET", "").strip().strip('"').strip("'")

print(f"Loaded .env from    : {env_path}")
print(f"Loaded App ID       : {app_id}")
print(f"Customer Key length : {len(key)} ({key[:4]}...{key[-4:] if len(key) >= 4 else ''})")
print(f"Secret length       : {len(secret)} ({secret[:4]}...{secret[-4:] if len(secret) >= 4 else ''})")

if not key or not secret or not app_id:
    print("\n[!] Error: Missing one or more credentials in .env")
    exit(1)

raw_credentials = f"{key}:{secret}"
auth = base64.b64encode(raw_credentials.encode("utf-8")).decode("utf-8")
headers = {
    "Authorization": f"Basic {auth}",
    "Content-Type": "application/json",
}

# 1. Test Global Endpoint
url_global = f"https://api.agora.io/api/conversational-ai-agent/v2/projects/{app_id}/join"
print(f"\n1. Testing Global endpoint:\n   {url_global}")
try:
    r1 = httpx.post(url_global, headers=headers, json={}, timeout=10.0)
    print(f"   Status  : {r1.status_code}")
    print(f"   Response: {r1.text}")
except Exception as e:
    print(f"   Request failed: {e}")

# 2. Test Regional / Asia-Pacific Endpoint
url_regional = f"https://api.sd-rtn.com/api/conversational-ai-agent/v2/projects/{app_id}/join"
print(f"\n2. Testing Regional endpoint:\n   {url_regional}")
try:
    r2 = httpx.post(url_regional, headers=headers, json={}, timeout=10.0)
    print(f"   Status  : {r2.status_code}")
    print(f"   Response: {r2.text}")
except Exception as e:
    print(f"   Request failed: {e}")