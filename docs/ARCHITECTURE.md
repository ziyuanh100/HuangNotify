# 架構與資料格式

## 組件

- `CaptureService`：Android NotificationListenerService；在回呼時擷取文字，交由單一 IO executor 寫入；連線時核對目前通知。
- `Store`：SQLiteOpenHelper，WAL、外鍵、索引。通知與版本寫入為單一交易；備份還原完整驗證後在一個交易內完成。
- `MainActivity` / `Ui`：Android 原生 Views；可回收 ListView 列、分頁、搜尋節流、無資料畫面、權限引導、系統深色模式。
- `EventPolicy`：內容指紋、搜尋跳脫、系統移除原因的呈現。
- `BackupCrypto`：與 Android UI 無關的密碼加密容器。
- `BackupFormat`：完整備份結構檢查與資源上限。
- `NotificationImages`：只處理通知附帶的大圖與已授權本機圖片訊息；有界解碼、縮圖、雜湊去重、URI 大小／逾時限制，不掃描相簿或直接開啟網路圖片。
- `ImageGallery` / `ImageViewerActivity`：詳情縮圖、雙指縮放與另存 PNG；獨立 viewer 為未匯出的 Activity。
- SQLite v3：新增有界 `diagnostics` 表，保留 v2 圖片及原有資料。
- SQLite v2：保留 v1 表與資料後新增圖片狀態、`images` blob、`record_images` 與 `version_images` 引用。通知、版本、圖片與引用在單一交易內寫入；刪除後移除孤立 blob，不使用額外圖片檔案。

## 計數與生命週期

每次新的有效通知生命週期產生一個 UUID。`nkey` 相同且 `active=1` 時，文字、圖片內容或圖片狀態的指紋不同就新增版本，指紋相同則略過。收到移除回呼後將 `active=0`，保留紀錄。相同 key 在移除後重現會成為新紀錄。

斷線重連時，如果通知已不在系統目前清單，僅標示非作用中及原因未知，不捏造移除時間。無法判定中斷期間同一 key 是否被移除又重新發布，因此不能保證該情況的生命週期分類完全精確。

匯入紀錄使用原 UUID 去重，不僅比較文字。同一筆紀錄缺少的版本可以補入，但不覆蓋本機收藏、備註或最新內容。匯入的新紀錄一律為歷史紀錄，不連結到當前系統通知。

## JSON 備份 v1（`.hnjson`）

頂層：`format="HuangNotify"`、`version=1`、`created`（Unix 毫秒）、`records`、`settings`。

每筆：`id,nkey,pkg,app,title,body,note,posted,first_seen,updated,removed,reason,favorite,versions`。

每個版本：`id,time,title,body`。

設定只包含保存期限與排除 App 清單；不還原暫停狀態、系統授權或上次保存時間。預設不覆蓋本機設定，需在預覽時勾選。

## JSON 備份 v2（1.1.0 起）

保留 v1 所有欄位，`version=2`，頂層增加 `images` 陣列；每筆圖片為 `sha256,mime="image/png",width,height,data`（標準 Base64）。每筆通知與每個歷程增加 `images`（有序 SHA-256 引用）及 `image_status`（none/saved/partial/unavailable/disabled）。共享圖片只存一次。

`save_images` 設定可選擇還原。新版可讀 v1，舊版不讀 v2。驗證圖片大小、數量、尺寸、PNG 可解碼性、SHA-256、引用完整性與狀態；未知／重複／遺失的圖片引用及未引用 blob 均拒絕。完整驗證後同一交易匯入；識別碼與歷程圖片衝突全部回滾，保留本機既有紀錄與圖片。

每張上限 256 KiB、最長邊 1440 px；每則／每版本最多 4 張。總備份上限仍為 20 MiB、100,000 筆與 200,000 個版本，超限不產生部分備份、不清理既有自動備份。JSON v2 是資料格式版本，與下列加密容器版本無關。

## 加密備份 v1（`.hnbk`）

| 位元組 | 內容 |
|---|---|
| 0–4 | `HNBK` + `0x01` |
| 5–8 | Big-endian PBKDF2 次數：210000 |
| 9–24 | 16-byte 隨機 salt |
| 25–36 | 12-byte 隨機 GCM nonce |
| 37 起 | AES-256-GCM 加密 JSON，末尾含 16-byte authentication tag |

37-byte header 亦作為 AAD 驗證。KDF 為 PBKDF2-HMAC-SHA256。解密僅接受固定參數，避免惡意檔案要求無限次運算。GCM tag 同時驗證密碼與資料完整性。JSON 文字備份沒有密碼驗證能力，僅做結構檢查。

此為舊 HNBK1 的相容讀取規格，保留 20 MiB 限制；1.2.0 新匯出一律使用下列 v3／HNBK2。


## JSON v3 與大型備份（1.2.0）

v3 沿用 v2 欄位，增加 `cleared` 圖片狀態表示使用者清除了圖片；此狀態不帶圖片引用。所有新匯出使用 v3，舊 App 不支援。單份未加密 UTF-8 JSON 上限 **100 MiB = 104,857,600 bytes**，圖片 Base64、歷程及設定全部計入；不是資料庫總量上限。最多 100,000 筆通知、200,000 個歷程及 100,000 張唯一圖片，圖片個別限制不變。

`BackupArchive` 使用 JsonReader／JsonWriter 逐筆處理，最多一張 256 KiB 圖片及單列文字在記憶體。嚴格驗證 UTF-8、整數型別、重複欄位／UUID、引用、PNG 及雜湊；在解析器前限制單個引用字串 token 2,200,000 字元、數值 token 128 字元、巢狀 32 層。每個通知文字欄位最多 100,000 字元。

匯入先寫私有 SQLite 暫存資料庫，全部驗證成功才顯示預覽；確認後在單一 live DB 交易合併。取消、衝突或錯誤會回滾整個交易。既有收藏、備註及最新內容不覆寫；本機已清除的圖片，只有內容／時間相符或同一歷程識別碼時才可由備份補回。相同時間的歷程保留原有順序。

匯出在 `Store.IO` checkpoint 後持 writer transaction 複製 DB 快照，釋放後在獨立 backup executor 逐筆讀寫與加密；快照複製、還原合併仍會暫時排隊通知寫入，JSON／加密不占用通知 executor。磁碟須容納快照、JSON、加密檔與匯入 staging（可能數倍於備份大小），會預查剩餘空間並處理 ENOSPC。作業完成／取消刪除自己的快取目錄，下次啟動清理超過 24 小時的遺留作業。快取可能受系統清理影響；畫面重建或程序重啟後需重新操作，不儲存手動密碼。

手動與自動備份皆在輸出串流關閉後讀回比對長度與 SHA-256。自動備份只在完整驗證成功後清理本次安裝產生的舊檔；未完成只嘗試刪除新建立的檔案。手動目標可能是使用者覆寫的檔案，失敗只提示不完整，不主動刪除該目標。取消於資料區塊及各階段檢查，外部檔案提供者卡住時可能要等待讀寫返回。

## HNBK2 分段加密容器

| 位元組 | 內容 |
|---|---|
| 0–4 | `HNBK` + `0x02` |
| 5–8 | Big-endian PBKDF2-HMAC-SHA256 次數 210000 |
| 9–24 | 每檔 16-byte 隨機 salt，導出 256-bit AES key |
| 25–32 | 每檔 8-byte 隨機 nonce prefix |
| 33 起 | 逐個 frame：4-byte big-endian 明文長度（1–65536）+ ciphertext + 16-byte GCM tag |
| 結尾 | 長度 0 + 空明文的 16-byte GCM tag，後方必須 EOF |

每 frame 的 nonce 為 prefix8 + big-endian frame index4，從 0 遞增；AAD 為完整 header33 + index4 + 明文長度4 + 前面累計明文長度8，共 49 bytes。終止 frame 也有驗證，防止刪除尾段；交換、篡改、截斷或附加資料皆拒絕。只接受固定 KDF 參數，避免惡意運算要求。解密結果只寫入私有暫存檔，終止驗證與完整 JSON 驗證後才能匯入。

writer 每段 64 KiB（最後一段可較短），100 MiB 檔案最多額外 32,053 bytes，最大容器 104,889,653 bytes。nonce 在同一 salt 導出的 key 下不重複。讀取另外限制區塊數，避免小區塊濫用。HNBK2 是容器版本，JSON v3 是資料版本。

## 本機診斷與圖片管理

`Diagnostics` 只接受處理狀態、時間與來源套件名稱，最多保留 200 筆，不寫入正文或通知 key。收到、重連補讀、暫停、排除、重複、群組摘要、成功與失敗分開呈現；無法觀察未送達 NotificationListenerService 的事件。停用會清除，匯出須使用者主動確認，不包含在通知備份。

清除圖片在同一交易更新 current／history 的 `cleared` 狀態及指紋、移除引用、回收孤立 blob。預設略過收藏；共用圖片仍被收藏引用時會保留。沒有自動 VACUUM，實體檔案可能保留可重用空間。
