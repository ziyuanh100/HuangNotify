# 驗收與已知限制

## 自動檢查

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

JVM 測試：繁體中文加密往返、每次加密隨機性、錯誤密碼、內容／標頭竄改、截斷、惡意 KDF 參數、弱密碼輸入、內容指紋分隔、搜尋萬用字元。

Android 整合測試：重複更新不增筆數、版本保存、移除保留內容、key 重用、備份重複還原、收藏保留、匯入失敗不部分寫入、暫停與排除、SQLite 搜尋及清理、斷線狀態核對。

## 實機驗收（尤其 Samsung 背景行為）

1. 首次開啟：沒有捏造通知或統計數據；顯示權限引導。
2. 開啟通知存取權，回到 App，顯示真正服務連線狀態。
3. 使用其他 App 發送通知；核對來源、標題、內容、時間、今日與總數。
4. 更新同一則通知，總數不變，版本數增加；重複相同內容不增加版本。
5. 滑掉、全部清除、由來源取消通知後，仍能查看已存文字。
6. 搜尋 `%`、`_`、反斜線與繁體中文；App／日期／收藏篩選可組合。
7. 暫停、排除來源後確認不再保存；恢復後確認新通知可記錄。
8. 選擇 7 天期限前有確認；收藏不受定期清理影響。
9. 加密匯出再匯入、錯密碼、損壞檔、超大檔、取消選檔。失敗不能刪除既有資料。
10. 同一備份匯入兩次，第二次不增加通知總數；缺少版本可補入。
11. 淺／深色、200% 字型、TalkBack、橫向、小螢幕與鍵盤下的搜尋操作。
12. 重新啟動手機、移除最近使用 App、長時間鎖屏與省電模式，觀察是否漏記；不得聲稱保證不漏。
13. 解除通知權限，狀態應立即指出未授權；授權後可重新連線。
14. 用開發工具確認 manifest 無網路權限；備份密碼、通知正文不寫入 logcat。

## 第一版未提供

圖片／附件保存、自動備份、App 指紋鎖、資料庫額外加密、背景運作零漏記保證、正式穩定 release 簽章。測試 APK 與公開原始碼不代表完成所有品牌與版本相容性驗證。

## 通知端到端測試工具

CI 與上述指令只驗證 `app` 模組。`probe` 刻意以 API 28 為 target，用於舊版模擬器的手動通知測試，不適用上架 target SDK 檢查。

專案另含 `probe` 模組（獨立測試 APK，**不包含在 HuangNotify 安裝檔**）。可在模擬器使用：

```sh
./gradlew :probe:installDebug
adb shell cmd notification allow_listener dev.huang.notify/dev.huang.notify.CaptureService
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --es text first
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --es text updated
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --ez cancel true
```

確認同一通知只計一則、兩個文字版本均保留、移除後仍可查看。`UiTest` 另外檢查三個主要頁面的導覽。

## 本次驗證結果

- Debug APK 編譯成功。
- JVM 核心測試：7 項通過。
- Android 9 / API 28 軟體模擬器：6 項 SQLite 整合測試、1 項主要頁面導覽測試通過。
- Android Lint：0 errors；仍有 target SDK 更新提示及繁體中文單語介面的字串資源化等警告。
- 尚未在 Samsung Galaxy A55 或 Android 16 實機完成長時間背景驗證。
- 通知監聽端到端驗證通過：發布 → 更新 → 相同內容重送 → 移除，結果為 1 則紀錄、2 個版本，移除後文字仍保留。
