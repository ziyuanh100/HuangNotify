import os, json, hashlib, io, zipfile, pathlib, urllib.request, urllib.parse, urllib.error, time
REPO="ziyuanh100/HuangNotify"; BASE="https://api.github.com/repos/"+REPO
TOKEN=os.environ["GH_TOKEN"]; RELEASE=431622320
APK_SHA="ea50641f09d6ffa2142300c279b633f245882c6ab520204df5efa73b10fe1cd4"
SOURCE="0c65e5d3124bc4cef3b37833bf553e2ff04a1989"
FUNCTIONAL_SOURCE="ad4cf5983af12cd540cbbcfc87a9ecd5c0256007"
TAG_SOURCE=os.environ["GITHUB_SHA"]
ARTIFACT_ID=13156469791
ARTIFACT_DIGEST="sha256:48b3dae64104f8717923350fccb1024520dad88482faa8a625b79c92fc18e624"
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        if urllib.parse.urlparse(newurl).scheme!="https":raise RuntimeError("Non-HTTPS redirect")
        new=super().redirect_request(req,fp,code,msg,headers,newurl)
        if new is not None:new.remove_header("Authorization")
        return new
opener=urllib.request.build_opener(Redirect())
def api(path,method="GET",payload=None):
    url=BASE+path
    data=None if payload is None else json.dumps(payload).encode()
    req=urllib.request.Request(url,data=data,method=method,headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","Content-Type":"application/json","X-GitHub-Api-Version":"2022-11-28"})
    try:
        with opener.open(req,timeout=90) as r:body=r.read()
    except urllib.error.HTTPError as error:
        print("GitHub API error:",method,path,error.code,error.read().decode()[:2000],flush=True)
        raise
    return json.loads(body) if body else None
def binary(url):
    assert url.startswith(BASE+"/actions/artifacts/")
    req=urllib.request.Request(url,headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"})
    with opener.open(req,timeout=90) as r:return r.read()
meta=api("/actions/artifacts/"+str(ARTIFACT_ID))
assert meta["name"]=="HuangNotify-1.3.0-verified-assets" and meta["digest"]==ARTIFACT_DIGEST
data=binary(BASE+"/actions/artifacts/"+str(ARTIFACT_ID)+"/zip")
assert "sha256:"+hashlib.sha256(data).hexdigest()==ARTIFACT_DIGEST
z=zipfile.ZipFile(io.BytesIO(data))
def member(name):
    names=[n for n in z.namelist() if n.endswith(name)];assert len(names)==1,names
    return z.read(names[0])
apk=member("HuangNotify-v1.3.0.apk");assert hashlib.sha256(apk).hexdigest()==APK_SHA
validation=json.loads(member("validation-1.3.0.json"))
assert validation["apk_payload_matches_final_test_build"] is True
assert validation["apk_sha256"]==APK_SHA and validation["apk_source"]==SOURCE and validation["functional_source"]==FUNCTIONAL_SOURCE
assert validation["performance_cases_completed"]==16 and validation["performance_all_targets_met"] is True
body=pathlib.Path("docs/RELEASE-1.3.0.md").read_text()
assert "__" not in body and "尚待發布" not in body and "313" in body
release=api("/releases/"+str(RELEASE))
assert release["tag_name"] in ("unpublished-1.3.0-0c65","v1.3.0")
if not release["draft"]:TAG_SOURCE=api("/git/ref/tags/v1.3.0")["object"]["sha"]
comparison=api("/compare/"+FUNCTIONAL_SOURCE+"..."+TAG_SOURCE)
assert comparison["status"] in ("ahead","identical")
assert all(f["filename"].startswith((".github/","docs/","app/src/androidTest/","app/src/test/")) or f["filename"]=="README.md" for f in comparison.get("files",[])),"Production source changed after functional verification"
if release["draft"]:
    try:
        tag=api("/git/ref/tags/v1.3.0");assert tag["object"]["sha"]==TAG_SOURCE
    except urllib.error.HTTPError as e:
        if e.code!=404:raise
        api("/git/refs","POST",{"ref":"refs/tags/v1.3.0","sha":TAG_SOURCE})
    release=api("/releases/"+str(RELEASE),"PATCH",{"tag_name":"v1.3.0","target_commitish":TAG_SOURCE,"name":"HuangNotify 1.3.0｜備份加速、操作延續與進階篩選","body":body,"draft":True,"prerelease":False})
    existing={a["name"]:a for a in release["assets"]}
    assert existing["HuangNotify-v1.3.0.apk"]["digest"]=="sha256:"+APK_SHA
    for name in ("SHA256SUMS.txt","backup-performance-1.3.0-api35.json","validation-1.3.0.json"):
        content=member(name);digest="sha256:"+hashlib.sha256(content).hexdigest()
        if name in existing:
            assert existing[name]["digest"]==digest and existing[name]["size"]==len(content)
            continue
        upload="https://uploads.github.com/repos/"+REPO+"/releases/"+str(RELEASE)+"/assets?name="+urllib.parse.quote(name)
        req=urllib.request.Request(upload,data=content,method="POST",headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","Content-Type":"text/plain" if name.endswith(".txt") else "application/json","X-GitHub-Api-Version":"2022-11-28"})
        with opener.open(req,timeout=90) as r:created=json.loads(r.read())
        assert created["digest"]==digest and created["state"]=="uploaded"
    release=api("/releases/"+str(RELEASE),"PATCH",{"draft":False,"prerelease":False,"make_latest":"true"})
assert release["tag_name"]=="v1.3.0" and release["draft"] is False and release["prerelease"] is False
for attempt in range(20):
    latest=api("/releases/latest")
    if latest["id"]==RELEASE:break
    time.sleep(2)
assert latest["id"]==RELEASE
asset=next(a for a in latest["assets"] if a["name"]=="HuangNotify-v1.3.0.apk")
assert asset["state"]=="uploaded" and asset["digest"]=="sha256:"+APK_SHA
url=asset["browser_download_url"]; parsed=urllib.parse.urlparse(url)
assert parsed.scheme=="https" and parsed.netloc=="github.com" and parsed.path.startswith("/"+REPO+"/releases/download/v1.3.0/") and not parsed.query and not parsed.fragment
# Intentionally omit authentication to prove the APK is publicly downloadable.
with opener.open(urllib.request.Request(url),timeout=90) as r:download=r.read()
assert len(download)==asset["size"] and hashlib.sha256(download).hexdigest()==APK_SHA
tag=api("/git/ref/tags/v1.3.0");assert tag["object"]["sha"]==TAG_SOURCE
print("RELEASE_PUBLICATION_JSON="+json.dumps({"release_url":latest["html_url"],"apk_url":url,"apk_sha256":APK_SHA,"public_download_verified":True,"latest":True,"draft":False,"prerelease":False,"source":SOURCE,"tag_source":TAG_SOURCE,"version_name":"1.3.0","version_code":12}))
