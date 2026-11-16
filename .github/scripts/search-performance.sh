#!/usr/bin/env bash
set -euo pipefail
timeout 7m adb shell am instrument -w -e phase search-benchmark \
  dev.huang.notify.test/dev.huang.notify.UpgradeHarness | tee search-performance.txt
grep -q 'INSTRUMENTATION_CODE: -1' search-performance.txt
adb exec-out run-as dev.huang.notify cat files/search-performance.json > search-performance.json
