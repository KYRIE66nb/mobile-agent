package xyz.chouxuewei.mobile_agent.device.capture

/**
 * 单帧像素访问口：MediaProjection 采集与无障碍截屏共用同一契约，
 * 坐标系为真实屏幕像素（无障碍窗口帧内部做窗口→屏幕坐标偏移）。
 * 帧所有权不跨线程——回调返回后底层资源立即释放。
 */
interface FrameAccess {
    val width: Int
    val height: Int

    /** 在 [l,t,r,b] 像素矩形内按 grid×grid 网格取样，返回 ARGB 数组。 */
    fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray

    /** 整帧缩略图（仅校准预览用，内存对象不落盘）。 */
    fun snapshot(targetWidth: Int): android.graphics.Bitmap
}
