#!/usr/bin/env bash
set -euo pipefail
mkdir -p backup-results
fixture="$RUNNER_TEMP/backup-size-source.hnjson"
for sample in previous current; do
  app="$PWD/app"
  if [ "$sample" = previous ]; then app="$RUNNER_TEMP/benchmark-previous/app"; fi
  adb uninstall dev.huang.notify.test || true
  adb uninstall dev.huang.notify || true
  adb install "$app/build/outputs/apk/debug/app-debug.apk"
  adb install "$app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  if [ "$1" = images ] && [ "$sample" = current ]; then
    adb shell -T 'run-as dev.huang.notify sh -c "mkdir -p files && cat > files/backup-size-source.hnjson"' < "$fixture"
    expected=$(sha256sum "$fixture" | cut -d' ' -f1)
    actual=$(adb shell run-as dev.huang.notify sha256sum files/backup-size-source.hnjson | cut -d' ' -f1)
    test "$expected" = "$actual"
  fi
  timeout 16m adb shell am instrument -w -e phase benchmark -e benchmark_kind "$1" dev.huang.notify.test/dev.huang.notify.UpgradeHarness | tee "backup-results/$sample.txt"
  adb exec-out run-as dev.huang.notify cat files/backup-performance.json > "backup-results/$sample.json"
  grep -q 'INSTRUMENTATION_CODE: -1' "backup-results/$sample.txt"
  if [ "$1" = images ] && [ "$sample" = previous ]; then
    adb exec-out run-as dev.huang.notify cat files/backup-size-source.hnjson > "$fixture"
    test -s "$fixture"
  fi
done
if [ "$1" = images ]; then
  python3 - <<'PY'
import json
from pathlib import Path

previous = json.loads(Path("backup-results/previous.json").read_text())
current = json.loads(Path("backup-results/current.json").read_text())
same = current["same_data"]
assert same["records"] == previous["records"] and same["images"] == previous["images"]
assert same["previous_plain_bytes"] == previous["cases"][0]["runs"][0]["output_bytes"]
same["previous_encrypted_bytes"] = previous["cases"][1]["runs"][0]["output_bytes"]
assert same["current_encrypted_bytes"] < same["previous_encrypted_bytes"]
Path("backup-results/same-data.json").write_text(json.dumps(same, indent=2) + "\n")
PY
  rm -f "$fixture"
fi
