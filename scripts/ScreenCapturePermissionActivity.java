package xyz.lovestyle.home;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/**
 * 透明活动，专门用来弹系统的“允许录屏/投屏”授权框。
 *
 * MediaProjection 的授权必须由前台 Activity 通过 startActivityForResult 触发，
 * 后台服务无法直接请求。拿到 token 后立刻转交给 {@link ScreenCaptureService} 常驻，
 * 之后截屏都走那个服务、不再需要重新授权。授权只需在 app 启动时点一次。
 */
public class ScreenCapturePermissionActivity extends Activity {

    private static final int REQ = 5001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 若已经授权就绪，直接结束，避免重复弹框
        if (ScreenCaptureService.isReady()) { finish(); return; }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { finish(); return; }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        }
        finish();
    }
}
