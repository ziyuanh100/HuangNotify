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
TAG = "v1.10.0"
PREVIOUS = "v1.9.0"
APK = "HuangNotify-v1.10.0.apk"
CHECKSUM = "SHA256SUMS.txt"
API = f"repos/{REPO}"
ROOT = Path(__file__).resolve().parent


def gh_json(endpoint, method="GET", payload=None):
    command = ["gh", "api", "--method", method, f"{API}/{endpoint}"]
    data = None
    if payload is not None:
        command.extend(["--input", "-"])
        data = json.dumps(payload).encode("utf-8")
    return json.loads(subprocess.check_output(command, input=data))


def assets():
    return {asset["name"]: asset for asset in gh_json(f"releases/{release_id}/assets")}


def matches(asset, payload):
    return (asset is not None and asset["state"] == "uploaded" and asset["size"] == len(payload)
            and asset["digest"] == "sha256:" + hashlib.sha256(payload).hexdigest())


assert os.environ["GITHUB_REPOSITORY"] == REPO
assert os.environ["GITHUB_REF_NAME"] == "codex/publish-1.10.0"
manifest = json.loads((ROOT / "release-1.10.0.json").read_text())
commit = manifest["commit"]
assert gh_json("git/ref/heads/main")["object"]["sha"] == commit
assert gh_json(f"git/commits/{commit}")["tree"]["sha"] == manifest["tree"]
apk = base64.b64decode((ROOT / (APK + ".b64")).read_bytes(), validate=True)
digest = hashlib.sha256(apk).hexdigest()
assert len(apk) == manifest["bytes"] and digest == manifest["sha256"]
payloads = {APK: apk, CHECKSUM: f"{digest}  {APK}\n".encode("ascii")}

latest = gh_json("releases/latest")
assert latest["tag_name"] in (PREVIOUS, TAG)
refs = gh_json(f"git/matching-refs/tags/{TAG}")
tag_ref = next((ref for ref in refs if ref["ref"] == f"refs/tags/{TAG}"), None)
if tag_ref is not None:
    assert tag_ref["object"] == {"type": "commit", "sha": commit, "url": tag_ref["object"]["url"]}
matching = [release for release in gh_json("releases?per_page=100") if release["tag_name"] == TAG]
assert len(matching) <= 1
if matching:
    release = matching[0]
    assert release["target_commitish"] == commit and not release["prerelease"]
else:
    notes = (ROOT / "release-1.10.0.md").read_text(encoding="utf-8")
    release = gh_json("releases", "POST", {
        "tag_name": TAG, "target_commitish": commit, "name": f"HuangNotify {TAG}",
        "body": notes, "draft": True, "prerelease": False,
    })
    assert release["draft"]
release_id = release["id"]

if release["draft"]:
    with tempfile.TemporaryDirectory() as tmp:
        for name, payload in payloads.items():
            existing = assets().get(name)
            if matches(existing, payload):
                continue
            if existing is not None:
                assert existing["state"] == "starter" and existing["size"] == 0
                subprocess.run(["gh", "api", "--method", "DELETE",
                                f"{API}/releases/assets/{existing['id']}"], check=True)
            local_file = Path(tmp, name)
            local_file.write_bytes(payload)
            subprocess.run(["gh", "release", "upload", TAG, str(local_file), "--repo", REPO], check=True)
    uploaded = assets()
    for name, payload in payloads.items():
        assert matches(uploaded.get(name), payload), name
    release = gh_json(f"releases/{release_id}", "PATCH", {"draft": False, "make_latest": "true"})

assert not release["draft"] and not release["prerelease"]
for attempt in range(6):
    try:
        current = gh_json("releases/latest")
        assert current["id"] == release_id
        feed = gh_json("releases?per_page=100")
        assert any(item["id"] == release_id for item in feed if not item["draft"] and not item["prerelease"])
        ref = gh_json(f"git/ref/tags/{TAG}")["object"]
        assert ref["type"] == "commit" and ref["sha"] == commit
        by_name = {asset["name"]: asset for asset in current["assets"]}
        for name, payload in payloads.items():
            asset = by_name.get(name)
            assert matches(asset, payload), name
            url = f"https://github.com/{REPO}/releases/download/{TAG}/{name}"
            assert asset["browser_download_url"] == url
            with urlopen(Request(url, headers={"User-Agent": "HuangNotify-Updater"}), timeout=15) as download:
                assert download.read() == payload
        break
    except (AssertionError, OSError, subprocess.CalledProcessError):
        if attempt == 5:
            raise
        time.sleep(3)
print("Verified public downloads, source tag and app update feed", release["html_url"])
