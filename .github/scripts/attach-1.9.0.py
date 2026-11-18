import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen

REPO = "ziyuanh100/HuangNotify"
TAG = "v1.9.0"
COMMIT = "013e5da90f9168aa51da95fafc25b331a121e159"
EXPECTED_APK = "77cad8dbb8544c3d8ed3c5c67ac94c0bd026f5d7ea4bc4699ecc55a2d7fccd77"
APK_NAME = "HuangNotify-v1.9.0.apk"
CHECKSUM_NAME = "SHA256SUMS.txt"
API = f"repos/{REPO}"

def gh_json(endpoint):
    return json.loads(subprocess.check_output(["gh", "api", f"{API}/{endpoint}"]))

assert os.environ["GITHUB_REPOSITORY"] == REPO
assert os.environ["GITHUB_REF_NAME"] == "codex/publish-1.9.0"
release = gh_json(f"releases/tags/{TAG}")
assert release["tag_name"] == TAG and not release["draft"] and not release["prerelease"]
assert gh_json("releases/latest")["id"] == release["id"]
tag = gh_json(f"git/ref/tags/{TAG}")["object"]
assert tag["type"] == "commit" and tag["sha"] == COMMIT

encoded = Path(__file__).with_name("HuangNotify-v1.9.0.apk.b64").read_text()
package = base64.b64decode(encoded, validate=True)
assert len(package) == 213695 and hashlib.sha256(package).hexdigest() == EXPECTED_APK
payloads = {
    APK_NAME: package,
    CHECKSUM_NAME: f"{EXPECTED_APK}  {APK_NAME}\n".encode("ascii"),
}

def matches(asset, payload):
    return (asset["state"] == "uploaded" and asset["size"] == len(payload)
            and asset["digest"] == "sha256:" + hashlib.sha256(payload).hexdigest())

def assets():
    return {a["name"]: a for a in gh_json(f"releases/{release['id']}/assets")}

with tempfile.TemporaryDirectory() as folder:
    for name, payload in payloads.items():
        existing = assets().get(name)
        if existing is not None:
            if matches(existing, payload):
                continue
            if existing["state"] != "starter" or existing["size"] != 0:
                raise SystemExit(f"Refusing to replace a nonempty release attachment: {name}")
            # GitHub creates empty starter entries when a transfer fails.
            subprocess.run(["gh", "api", "--method", "DELETE",
                            f"{API}/releases/assets/{existing['id']}"], check=True)
        source = Path(folder, name)
        source.write_bytes(payload)
        subprocess.run(["gh", "release", "upload", TAG, str(source), "--repo", REPO], check=True)

uploaded = assets()
for name, payload in payloads.items():
    asset = uploaded[name]
    assert matches(asset, payload)
    expected_url = f"https://github.com/{REPO}/releases/download/{TAG}/{name}"
    assert asset["browser_download_url"] == expected_url
    for attempt in range(5):
        try:
            with urlopen(Request(expected_url, headers={"User-Agent": "HuangNotify-Updater"}), timeout=15) as r:
                assert r.read() == payload
            break
        except (OSError, AssertionError):
            if attempt == 4:
                raise
            time.sleep(2)
    print(f"Public download verified: {name} ({len(payload)} bytes)")

feed = gh_json("releases?per_page=20")
stable = next(r for r in feed if not r["draft"] and not r["prerelease"])
assert stable["id"] == release["id"] and matches(
    next(a for a in stable["assets"] if a["name"] == APK_NAME), package)
print("Stable update feed verified for HuangNotify 1.9.0")
