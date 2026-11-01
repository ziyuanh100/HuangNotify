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

目前以有界記憶體處理備份；限制 20 MiB、100,000 則紀錄與 200,000 個版本。自動備份沿用相同格式與大小上限；未實作串流大型檔案。
