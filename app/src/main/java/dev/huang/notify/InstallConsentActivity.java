package dev.huang.notify;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
/** Explicit, non-exported trampoline launched only through our immutable notification intent. */
public final class InstallConsentActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Intent confirmation=getIntent().getParcelableExtra("confirmation");
    try { if (confirmation!=null) startActivity(confirmation); }
    catch (Exception e) { UpgradeManager.status(this,"安裝確認已失效，請重新點選安裝更新"); }
    finish();
  }
}
