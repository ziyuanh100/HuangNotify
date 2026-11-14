#!/usr/bin/env bash
set -euo pipefail
mkdir -p backup-results
for sample in previous current; do
  app="$PWD/app"
  if [ "$sample" = previous ]; then app="$RUNNER_TEMP/benchmark-previous/app"; fi
  adb uninstall dev.huang.notify.test || true
  adb uninstall dev.huang.notify || true
  adb install "$app/build/outputs/apk/debug/app-debug.apk"
  adb install "$app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  timeout 16m adb shell am instrument -w -e phase benchmark -e benchmark_kind "$1" dev.huang.notify.test/dev.huang.notify.UpgradeHarness | tee "backup-results/$sample.txt"
  adb exec-out run-as dev.huang.notify cat files/backup-performance.json > "backup-results/$sample.json"
  grep -q 'INSTRUMENTATION_CODE: -1' "backup-results/$sample.txt"
done
