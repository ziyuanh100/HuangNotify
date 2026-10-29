# 發布可直接更新的版本

自 v0.2.0-alpha.1 起，官方 APK 使用固定私密簽章。私鑰與密碼只放在維護者的私人簽章備份中，**不可上傳 GitHub、Actions artifact 或公開 Release**。遺失金鑰會使已安裝版本無法繼續覆蓋更新。公開憑證指紋見 `signing-certificate.txt`。

1. 每次發布增加 `app/build.gradle` 的 versionCode，設定 SemVer versionName（包含 alpha / beta 後綴），tag 必須與 versionName 一致並加上 `v`。
2. 讓 GitHub Actions 的 build 與 device-tests 全部通過。
3. 下載同一提交的 `HuangNotify-unsigned-release-apk`，核對 Actions artifact SHA-256。
4. 使用 Android SDK build-tools 的 zipalign 及 apksigner，在受信任的本機以固定 keystore 簽署。用密碼檔（權限 0600）傳入，避免密碼出現在命令列。勿變更 package name。
5. 執行 `apksigner verify --verbose --print-certs`，核對公開憑證指紋，並產生 APK SHA-256。
6. 以對應 tag 發布 Release、上傳已簽署 APK 及 SHA256SUMS.txt。只有已公開且含 APK 的 Release 才會被更新檢查採用；測試版需勾選 Pre-release。
7. 在已安裝上一版且含測試資料的手機覆蓋安裝，驗證資料保留。不要將 debug APK 當作正式更新包。

檢查器讀取最近 100 個 GitHub Releases，使用 SemVer 選取比目前版本高的版本。維護者應維持最新可用版本在此範圍內，不應在新標籤重新發布較低 versionCode 的 APK。沒有付費推播服務；週期檢查會受 Android 排程、網路及 GitHub 配額影響。
