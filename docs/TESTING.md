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

## 尚未提供

一般附件／影片保存、App 指紋鎖、資料庫額外加密、背景運作零漏記保證。測試 APK 與公開原始碼不代表完成所有品牌與版本相容性驗證。

## 通知端到端測試工具

CI 會建置 `app` 與獨立 `probe` APK，並安裝 probe 驗證真實通知。單獨執行 `:app:connectedDebugAndroidTest` 前也須先安裝 `probe`。`probe` 刻意以 API 28 為 target，用於舊版模擬器的手動通知測試，不適用上架 target SDK 檢查。

專案另含 `probe` 模組（獨立測試 APK，**不包含在 HuangNotify 安裝檔**）。可在模擬器使用：

```sh
./gradlew :probe:installDebug
adb shell cmd notification allow_listener dev.huang.notify/dev.huang.notify.CaptureService
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --es text first
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --es text updated
adb shell am start -n dev.huang.notify.probe/.ProbeActivity --ei id 42 --ez cancel true
```

確認同一通知只計一則、兩個文字版本均保留、移除後仍可查看。`UiTest` 另外檢查三個主要頁面的導覽。

## 早期版本驗證結果（歷史紀錄）

- Debug APK 編譯成功。
- JVM 核心測試：7 項通過。
- Android 9 / API 28 軟體模擬器：6 項 SQLite 整合測試、1 項主要頁面導覽測試通過。
- Android Lint：0 errors；仍有 target SDK 更新提示及繁體中文單語介面的字串資源化等警告。
- 尚未在 Samsung Galaxy A55 或 Android 16 實機完成長時間背景驗證。
- 通知監聽端到端驗證通過：發布 → 更新 → 相同內容重送 → 移除，結果為 1 則紀錄、2 個版本，移除後文字仍保留。

## 更新驗收

- SemVer 數字順序、Alpha / Beta / 正式版、相同版本、無效版本與拒絕降版。
- Release 選擇：略過草稿、無 APK、非官方網址，依版本號選取，不依 API 排序。
- 更新排程：alpha.2 起檢查預設開啟，明確關閉值保留；排程跨重開機、關閉後取消。自動下載與自動安裝另行選用。
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

alpha.2 的 CI 另外以獨立 instrumentation 將 v1.0.0-alpha.1 的真實 APK 升級至 alpha.2，驗證程序替換、通知文字、兩個內容版本、收藏、備註、暫停與保存期限，以及更新完成提示與重新開啟按鈕。測試採同一模擬器的 debug 簽章，正式 APK 仍使用獨立固定 release 簽章。


## 1.0.0-rc.1 驗證結果

程式來源：`469088db0ba98c5b858633b884fbbef45264f532`。CI：[41259906166](https://github.com/ziyuanh100/HuangNotify/actions/runs/41259906166)。

- JVM：10 項通過；Debug／Release 編譯與 Lint 通過。
- Android 8（API 26）、15（API 35）、16（API 36）模擬器：各 42 項裝置測試通過，共 126 項。
- 新增回歸涵蓋：取消備份不留失敗狀態、讀回成功但清理失敗保留備份、識別碼衝突完整回滾、拒絕字串及小數數值、更新程序中斷後重試、失敗下載按鈕可重新下載。
- 三個 API 均實際透過 Android 安裝確認將舊 APK 升級至 RC，並驗證通知、兩個內容版本、收藏、備註、暫停與保存期限保留，以及完成提示與重新開啟。
- 升級測試使用同一 runner 的 debug 簽章；公開 APK 另以固定 release 簽章簽署並驗證。這不代表完成 Samsung／Play Protect 的實機安裝驗收。
- 尚未完成 Samsung A55／Android 16 實機 48 小時與跨裝置加密還原。詳細清單見 [候選版驗收](RELEASE-1.0.0-rc.1.md)。


## 1.0.0-rc.2 來源清單驗證

程式建置來源：`3e2b2ba0e2c6cf2f1a1880d17037e46bf2ecf6d9`；CI：[41263179566](https://github.com/ziyuanh100/HuangNotify/actions/runs/41263179566)。

- JVM 10 項與 Android 8／15／16 各 52 項裝置測試通過，共 166 項；Debug／Release 與 Lint 通過。
- 實際安裝沒有 MAIN／LAUNCHER intent filter 的通知測試工具，驗證在沒有通知歷史時仍列入清單；該測試工具不包含在公開 APK。
- 驗證目前使用者已安裝 App、系統／停用狀態、歷史來源、同名不同套件、舊排除值與去重。
- 驗證第一則通知前預先排除、恢復允許、批次選擇的交易完整性；搜尋／分類後全選只影響目前結果。
- 驗證取消不儲存、回收列不串用勾選、1,000 個來源的列回收、實際設定入口、深色 180% 字型及縮短視窗仍可搜尋與儲存；保存介面截圖。
- 三個 API 的舊 APK 經系統確認升級、資料保留、完成提示與重新開啟均通過。CI 使用同 runner 的 debug 簽章；公開 APK 使用固定 release 簽章。
- QUERY_ALL_PACKAGES 的 Lint 例外僅限該權限宣告：完整來源清單是本次明確需求，scoped queries 無法涵蓋未知且沒有 intent filter 的已安裝 App；未全面關閉 Lint。若上架 Google Play，須另作該商店權限審核。
- 本次只修正來源選擇清單；不代表已驗證所有第三方 App 的通知擷取。Samsung A55 的長時間背景與跨裝置還原實機驗收仍待完成。


## 1.0.0 正式版驗證

程式來源：`b9b0b95440279b35f1fb2b6dca1d0b5ecd08abb9`；CI：[41268341966](https://github.com/ziyuanh100/HuangNotify/actions/runs/41268341966)。

- JVM 10 項，Android 8／15／16 各 53 項裝置測試通過，共 169 項；Debug／Release 建置與 Lint 通過。
- 新增驗證：預設正式版頻道、使用者明確選擇測試版的偏好保留、RC.2 可發現 1.0.0 正式更新、正式頻道略過較高版號的 RC。
- 升級 fixture 改為 RC.2，三個 API 均實際經過 Android 安裝確認升級至 1.0.0，驗證通知、歷程、收藏、備註、暫停與保存期限保留、完成提示與重新開啟。
- CI 升級使用同一 runner 的 debug 簽章；公開 APK 另用固定官方 release 簽章簽署並驗證 APK v2／v3 與 SHA-256。
- 正式版已公開已知限制，未將 Samsung A55 長時間背景觀察或跨手機加密還原標記為已通過。

Android 16 CI 曾遇到 Quickstep 桌面程式 ANR 視窗遮住安裝確認。已保存安裝畫面與 UI hierarchy，測試只對精確辨識的 Quickstep 系統 ANR 關閉一次桌面程式，保留診斷紀錄；不關閉或忽略 HuangNotify 本身的當機視窗，升級後資料與版本斷言仍照常執行。


## 1.0.1 截圖通知相容性修正

APK 來源：`64347225385d9ec674486b7601a9d93c32c03161`；[CI 41275259738](https://github.com/ziyuanh100/HuangNotify/actions/runs/41275259738)。

- JVM 10 項及 Android 8／15／16 各 60 項裝置測試通過，共 190 項；Debug／Release 編譯與 Lint 通過（0 errors、7 個既有文字資源化 warnings）。
- 六項解析測試涵蓋獨立摘要、同來源／使用者／群組去重、空白與錯誤欄位、ticker／展開標題、Person／圖片訊息及長文字。
- 獨立 probe APK 實際發布合成 BigPicture 群組摘要，驗證 listener → SQLite → 取消通知後保留 → 匯出備份有效；不含使用者截圖，probe 不包含在公開 APK。
- Android 8 測試工具移除 am -W 等待，並對 shell 加上逾時診斷；三個 API 均完成通知流程。
- 以 1.0.0 作為真實 APK 升級 fixture，驗證系統安裝確認、資料保留、完成提示與重新開啟；CI 使用 debug 簽章，公開 APK 另驗固定 release 簽章。
- Android 16 首次升級被 System UI ANR 遮擋；保存畫面及 hierarchy，使用新 runner 重跑後通過，未放寬 HuangNotify 的通過條件。
- 未完成 Samsung A55 實際截圖格式與長時間背景實機驗證；文字收錄通過不代表保存圖片。


## 1.1.0 通知圖片與備份 v2

建置來源：`b3094c147f97b1ef01fec09ad63a25cbda3a3f7f`；[CI 41278944755](https://github.com/ziyuanh100/HuangNotify/actions/runs/41278944755)。

- JVM 10 項，Android 8／15／16 各 76 項裝置測試通過，共 238 項；Debug／Release 建置與 Lint 通過（0 errors、8 warnings）。
- 真正從獨立 probe APK 發布 BigPicture、MessagingStyle content URI 圖片；驗證寫入、取消後圖片仍可讀、拒絕／未授權不丟文字、圖片開關停用。Android 8 沒有私有 URI 自動授權，分別驗證無授權的文字備援與來源明確授權後複製，沒有放寬正式 App 的讀取權限。
- PNG 大小／尺寸限制、圖片去重、換圖歷程、共享圖片刪除與收藏保護、暫停／排除來源不寫入圖片、加密 v2 完整還原與 v1 文字相容、圖片校驗／引用／狀態錯誤拒絕，以及通知與圖片衝突全交易回滾。
- 驗證詳情縮圖實際有 Bitmap、查看／放大／重設、透過 DocumentsProvider 另存後逐位元組比對、圖片開關不刪既有圖片。切換模擬器系統深色及 180% 字型後檢查真實配置與控制項可見性，保存截圖。
- 三個 API 均由 1.0.1 真實 APK 經系統確認升級，驗證 SQLite v1 → v2、既有文字／歷程／收藏／備註／暫停／保存期限保留、升級後新圖片寫入與 v2 備份有效、完成提示與重新開啟。CI 使用 debug 簽章；公開 APK 另驗固定 release 憑證與 APK v2／v3 簽章。
- APK SHA-256：`e825af2715a4059c9a7d085495f5621ea1af7a999a94c76556f7376a050d899d`；已核對 GitHub asset digest，公開下載檔另行驗證。
- Samsung A55 的先前通知漏記案例尚未取得實際格式，沒有將新增圖片功能宣稱為該案例的修復。
