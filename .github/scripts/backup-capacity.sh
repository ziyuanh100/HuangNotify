#!/usr/bin/env bash
set -euo pipefail
mkdir -p backup-results
fixture_name=backup-benchmark-source.hnbackup
fixture="$RUNNER_TEMP/$fixture_name"
for sample in previous current; do
  app="$PWD/app"
  if [ "$sample" = previous ]; then
    app="$RUNNER_TEMP/benchmark-previous/app"
    capacity=250
  else
    capacity=500
  fi
  adb uninstall dev.huang.notify.test || true
  adb uninstall dev.huang.notify || true
  adb install "$app/build/outputs/apk/debug/app-debug.apk"
  adb install "$app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  if [ "$sample" = current ]; then
    adb shell -T "run-as dev.huang.notify sh -c 'mkdir -p files && cat > files/$fixture_name'" < "$fixture"
    expected=$(sha256sum "$fixture" | cut -d' ' -f1)
    actual=$(adb shell run-as dev.huang.notify sha256sum "files/$fixture_name" | cut -d' ' -f1)
    test "$expected" = "$actual"
    rm -f "$fixture"
  fi
  timeout 24m adb shell am instrument -w -e phase benchmark -e benchmark_kind "$1" -e benchmark_size "$capacity" \
    dev.huang.notify.test/dev.huang.notify.UpgradeHarness | tee "backup-results/$sample.txt"
  adb exec-out run-as dev.huang.notify cat files/backup-performance.json > "backup-results/$sample.json"
  grep -q 'INSTRUMENTATION_CODE: -1' "backup-results/$sample.txt"
  if [ "$sample" = previous ]; then
    adb exec-out run-as dev.huang.notify cat "files/$fixture_name" > "$fixture"
    test "$(wc -c < "$fixture")" -eq "$((capacity * 1024 * 1024))"
  fi
done
python3 - <<'PY'
import json
from pathlib import Path

previous = json.loads(Path("backup-results/previous.json").read_text())
current = json.loads(Path("backup-results/current.json").read_text())
assert previous["completed"] and current["completed"]
baseline = previous["at_limit"]
same = current["same_data"]
for field in ("plain_bytes", "records", "images", "versions"):
    assert same[field] == baseline[field], field
for old, new in zip(baseline["cases"], same["cases"], strict=True):
    assert old["encrypted"] == new["encrypted"]
    for old_trial, new_trial in zip(old["runs"], new["runs"], strict=True):
        assert old_trial["output_bytes"] == new_trial["output_bytes"]
assert current["at_limit"]["plain_bytes"] == current["limit_bytes"]
PY
