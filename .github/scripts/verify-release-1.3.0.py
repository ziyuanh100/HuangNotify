import os, json, time, hashlib, io, zipfile, re, pathlib, urllib.request, urllib.parse, subprocess
REPO="ziyuanh100/HuangNotify"
BASE="https://api.github.com/repos/"+REPO
TOKEN=os.environ["GH_TOKEN"]
ROOT=pathlib.Path("release-assets"); ROOT.mkdir(exist_ok=True)
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme!="https": raise RuntimeError("Non-HTTPS redirect")
        new=super().redirect_request(req,fp,code,msg,headers,newurl)
        if new is not None: new.remove_header("Authorization")
        return new
opener=urllib.request.build_opener(Redirect())
def request(url,binary=False):
    assert url.startswith(BASE+"/")
    req=urllib.request.Request(url,headers={"Authorization":"Bearer "+TOKEN,"Accept":"application/octet-stream" if "/releases/assets/" in url and binary else "application/vnd.github+json","X-GitHub-Api-Version":"2022-11-28"})
    with opener.open(req,timeout=90) as r: data=r.read()
    return data if binary else json.loads(data)
def artifacts(run):
    return {x["name"]:x for x in request(BASE+"/actions/runs/"+str(run)+"/artifacts?per_page=100")["artifacts"]}
def archive(artifact):
    data=request(BASE+"/actions/artifacts/"+str(artifact["id"])+"/zip",True)
    assert "sha256:"+hashlib.sha256(data).hexdigest()==artifact["digest"],"Artifact digest mismatch"
    return zipfile.ZipFile(io.BytesIO(data))
def member(z,suffix):
    names=[n for n in z.namelist() if n.endswith(suffix)]; assert len(names)==1,(suffix,names)
    return z.read(names[0])
def counters(z):
    candidates=[n for n in z.namelist() if n.endswith("index.html") and ("connected/debug/" in n or "testDebugUnitTest/" in n)]
    assert len(candidates)==1,candidates
    return re.findall(r'<div class="counter">(.*?)</div>',z.read(candidates[0]).decode())[:3]
deadline=time.monotonic()+1200
while True:
    state=request(BASE+"/actions/runs/41475726031")
    if state["status"]=="completed":
        assert state["conclusion"]=="success","Supplementary benchmark failed"
        break
    assert time.monotonic()<deadline,"Timed out waiting for supplementary benchmark"
    print("Waiting for supplementary benchmark completion",flush=True); time.sleep(15)
functional=request(BASE+"/actions/runs/41475172663")
assert functional["status"]=="completed" and functional["conclusion"]=="success"
assert functional["head_sha"]=="ad4cf5983af12cd540cbbcfc87a9ecd5c0256007"
fa=artifacts(41475172663)
assert counters(archive(fa["test-and-lint-reports"]))==["16","0","0"]
for api in (26,35,36):
    assert counters(archive(fa["device-test-reports-api-"+str(api)]))==["99","0","0"],api
    cycle=archive(fa["self-update-cycle-api-"+str(api)])
    for name in ("backup-interrupt-after.txt","upgrade-after.txt"):
        text=member(cycle,name).decode()
        assert "PASS:" in text and "INSTRUMENTATION_CODE: -1" in text,(api,name)
a=artifacts(41471929501); b=artifacts(41475726031)
selected=[(a,"text-10"),(a,"text-50"),(a,"text-99"),(a,"images-50"),(a,"images-99"),(a,"many-short-10"),(b,"images-10"),(b,"fragmented-10")]
cases=[]; evidence=[]
for source,name in selected:
    item=source["backup-performance-"+name+"-api-35"]
    report=json.loads(member(archive(item),"backup-performance.json"))
    assert report["completed"] is True and report["measured_runs_per_mode"]==5 and report["warmups_per_mode"]==1
    assert report["app_version"]=="1.3.0" and report["baseline_commit"]=="527a0f18091d209fce11debee02d8d38be73ce18"
    assert len(report["cases"])==2
    for c in report["cases"]:
        assert len(c["old"])==5 and len(c["new"])==5 and c["target_met"] is True
        assert len({x["output_bytes"] for mode in ("old","new") for x in c[mode]})==1
        for mode in ("old","new"):
            values=sorted(x["total_ms"] for x in c[mode])
            assert abs(values[2]-c[mode+"_median_ms"])<0.001
        if c["kind"]=="fragmented": assert c["database_bytes"]>=80*1024*1024
        c["evidence_artifact_id"]=item["id"]
        c["benchmark_source_commit"]=item["workflow_run"]["head_sha"]
        cases.append(c)
    evidence.append({"name":item["name"],"id":item["id"],"digest":item["digest"],"run":item["workflow_run"]["id"]})
expected={(k,s,e) for k in ("text","images","many-short","fragmented") for s in ((10,50,99) if k in ("text","images") else (10,)) for e in (False,True)}
assert {(x["kind"],x["nominal_mib"],x["encrypted"]) for x in cases}==expected and len(cases)==16
old=request(BASE+"/releases/assets/702187699",True)
apk_sha=hashlib.sha256(old).hexdigest()
assert apk_sha=="ea50641f09d6ffa2142300c279b633f245882c6ab520204df5efa73b10fe1cd4"
apk=ROOT/"HuangNotify-v1.3.0.apk"; apk.write_bytes(old)
unsigned=member(archive(fa["HuangNotify-unsigned-release-apk"]),"app-release-unsigned.apk")
za=zipfile.ZipFile(io.BytesIO(old)); zb=zipfile.ZipFile(io.BytesIO(unsigned))
signing_entries={"META-INF/HUANGNOT.RSA","META-INF/HUANGNOT.SF","META-INF/MANIFEST.MF"}
assert set(za.namelist())-signing_entries==set(zb.namelist()),"Non-signature APK entry set changed"
provenance="META-INF/version-control-info.textproto"
assert b"0c65e5d3124bc4cef3b37833bf553e2ff04a1989" in za.read(provenance)
assert b"ad4cf5983af12cd540cbbcfc87a9ecd5c0256007" in zb.read(provenance)
different=[n for n in zb.namelist() if za.read(n)!=zb.read(n)]
assert different==[provenance],different
signers=sorted(pathlib.Path(os.environ["ANDROID_HOME"]).glob("build-tools/*/apksigner"))
assert signers,"Official Android SDK apksigner unavailable"
verified=subprocess.check_output([str(signers[-1]),"verify","--verbose","--print-certs",str(apk)],text=True)
cert="da461b9da7d07aaf0305ad9e4f57fbd4f0d1f3e6b03be7f9d4c7f3eb6467c5e5"
assert cert in verified and "Verified using v2 scheme (APK Signature Scheme v2): true" in verified and "Verified using v3 scheme (APK Signature Scheme v3): true" in verified
(ROOT/"SHA256SUMS.txt").write_text(apk_sha+"  HuangNotify-v1.3.0.apk\n")
full={"app_version":"1.3.0","api":35,"baseline_commit":"527a0f18091d209fce11debee02d8d38be73ce18","warmups_per_mode":1,"measured_runs_per_mode":5,"destination":"private debug DocumentsProvider; local disk; no picker time","memory":"Java used heap, process PSS and private temporary bytes sampled every 100 ms; sampled maxima are not guaranteed peaks","cases":cases,"artifacts":evidence,"completed":True}
(ROOT/"backup-performance-1.3.0-api35.json").write_text(json.dumps(full,ensure_ascii=False,indent=2)+"\n")
validation={"jvm":{"tests":16,"failures":0},"android":{"26":{"tests":99,"failures":0},"35":{"tests":99,"failures":0},"36":{"tests":99,"failures":0}},"upgrade_cycles_passed":3,"process_interruption_cycles_passed":3,"functional_run":41475172663,"functional_source":"ad4cf5983af12cd540cbbcfc87a9ecd5c0256007","apk_source":"0c65e5d3124bc4cef3b37833bf553e2ff04a1989","apk_sha256":apk_sha,"certificate_sha256":cert,"apk_payload_matches_final_test_build":True,"only_payload_difference":provenance,"excluded_jar_signing_entries":sorted(signing_entries),"performance_cases_completed":16,"performance_all_targets_met":True,"performance_artifacts":evidence}
(ROOT/"validation-1.3.0.json").write_text(json.dumps(validation,indent=2)+"\n")
summary={"validation":validation,"cases":[{k:c[k] for k in ("kind","nominal_mib","encrypted","records","database_bytes","old_median_ms","new_median_ms","improvement_percent")} | {"output_bytes":c["new"][0]["output_bytes"],"old_range_ms":[min(x["total_ms"] for x in c["old"]),max(x["total_ms"] for x in c["old"])],"new_range_ms":[min(x["total_ms"] for x in c["new"]),max(x["total_ms"] for x in c["new"])]} for c in cases]}
print("RELEASE_EVIDENCE_JSON="+json.dumps(summary,ensure_ascii=False),flush=True)
