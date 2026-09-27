package xyz.chouxuewei.mobile_agent.device.root;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

// 调用只面向当前会话，不提供任意 shell 或任意显示操作接口。
interface IRootDevice {
    void enableNodeService();
    int createDisplay(in Surface surface);
    void releaseDisplay(int displayId);
    String launchApp(int displayId, String packageName);
    void gesture(int displayId, int x1, int y1, int x2, int y2, int durationMs);
    void pressKey(int displayId, int keyCode);
    ParcelFileDescriptor captureMain();
    // 仅清理指定应用的 cache/code_cache 目录内容；返回释放的字节数。
    long clearPackageCache(String packageName);
}
