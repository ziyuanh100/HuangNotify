package dev.huang.notify;
import android.content.*;
import android.content.pm.PackageInstaller;
public final class InstallStatusReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c,Intent intent) {
    if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
    int id=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);
    if (id != UpgradeManager.prefs(c).getInt("session",-2)) return;
    int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
    if (status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirmation==null) return;
      UpgradeManager.confirmation=confirmation;
      UpgradeManager.stage(c,"confirm","Android 需要你的確認，點選「安裝已下載更新」繼續");
      UpgradeManager.notice(c,"請確認安裝更新","點選繼續 Android 安裝程序",confirmation);
    } else if (status==PackageInstaller.STATUS_SUCCESS) {
      UpgradeManager.status(c,"更新已完成"); UpgradeManager.reconcile(c);
    } else {
      UpgradeManager.confirmation=null; UpgradeManager.prefs(c).edit().remove("session").apply();
      UpgradeManager.stage(c,"failed",status==PackageInstaller.STATUS_FAILURE_ABORTED?"已取消安裝，可稍後重試":"Android 未完成安裝，請確認權限與剩餘空間後重試");
    }
  }
}
