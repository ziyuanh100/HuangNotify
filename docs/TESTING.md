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
14. 用開發工具確認更新檢查不會傳送通知資料；備份密碼、通知正文不寫入 logcat。

## 第一版未提供

圖片／附件保存、App 指紋鎖、資料庫額外加密、背景運作零漏記保證。測試 APK 與公開原始碼不代表完成所有品牌與版本相容性驗證。

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

## 更新驗收

- SemVer 數字順序、Alpha / Beta / 正式版、相同版本、無效版本與拒絕降版。
- Release 選擇：略過草稿、無 APK、非官方網址，依版本號選取，不依 API 排序。
- 更新排程：預設關閉、開啟後持續跨重開機、關閉後取消。
- 手動實機：允許／拒絕通知權限、關閉通知頻道、離線、GitHub 限流、重開機、省電、點通知到官方 Release。
- 安裝相同簽章、更高 versionCode 的 APK 後，確認原有資料仍在。

## 0.3.0 權限與視窗驗收

新增裝置測試：單選回傳、多選與確認、輸入驗證保留視窗、權限精靈略過後不再彈出、深色與大字型視窗高度。CI 會保存淺色選單、深色大字型選單與權限引導截圖。廠牌系統授權及 Samsung 背景設定仍需實機驗收。

## 1.0.0 開發測試

- 記錄開關：切換寫入資料庫後才顯示成功，跨頁面保留；不會把啟用偏好等同於服務已連線。
- 自動備份：Keystore 密碼往返、可攜式解密、實際 DocumentsProvider 寫入與讀回、損壞／取消不清理舊備份、只清理本次安裝的自動檔案、持久排程與停用。
- 更新：拒絕簽章／套件／宣告版本不符、降版、SHA 損壞、非官方／非 HTTPS 網址；缺少 digest 不進行 App 內下載。
- Debug-only 私有 DocumentsProvider 僅作測試，release APK 不包含。
- 正式版前仍須：簽章相同的真實 APK 覆蓋安裝、Android 12／16 系統確認與免確認分支、安裝取消／失敗、程序重啟、撤銷資料夾權限、磁碟滿、Samsung A55 長時間鎖屏與省電驗證。

## 1.0.0-alpha.2 更新流程

- 預設開啟檢查；舊版明確關閉值保留；每次前景進入略過 24 小時快取。
- 檢查中關閉設定，舊回應不再提示；同一前景時段不因對話框關閉重複檢查。
- 自訂「現在更新／稍後再說」、App 內下載進度、權限入口與 Android 安裝確認銜接。
- 更新完成標記跨重啟保留，通知提供重新開啟入口；完成提示可確認並避免重複顯示。
- 仍需實機驗證：Play Protect／安裝來源限制、真實 APK 自我覆蓋後重新開啟、各廠牌安裝確認與背景排程。

CI 另外以獨立 instrumentation 將 v1.0.0-alpha.1 的真實 APK 升級至 alpha.2，驗證程序替換、通知文字、兩個內容版本、收藏、備註、暫停與保存期限，以及更新完成提示與重新開啟按鈕。測試採同一模擬器的 debug 簽章，正式 APK 仍使用獨立固定 release 簽章。
