package xyz.chouxuewei.mobile_agent.device.capture

/**
 * 单帧像素访问口：MediaProjection 采集与无障碍截屏共用同一契约，
 * 坐标系为真实屏幕像素（无障碍窗口帧内部做窗口→屏幕坐标偏移）。
 * 帧所有权不跨线程——回调返回后底层资源立即释放。
 */
interface FrameAccess {
    val width: Int
    val height: Int

    /**
     * [l,t,r,b] 采样矩形是否完整落在有效画面内：
     * 投影=整帧像素范围；无障碍=游戏窗口 bounds（屏幕坐标）。
     * 越界 ROI 不得夹到边缘像素后当有效豆槽——调用方须先检查再采样。
     */
    fun contains(l: Int, t: Int, r: Int, b: Int): Boolean

    /**
     * 在 [l,t,r,b] 像素矩形内按 grid×grid 网格取样，返回 ARGB 数组。
     * 矩形不完整落在有效画面内时返回空数组——宁可丢帧也不读边缘像素。
     */
    fun sample(l: Int, t: Int, r: Int, b: Int, grid: Int): IntArray

    /**
     * [l,t,r,b] 像素矩形的 1:1 裁剪副本（诊断取证用）。
     * 必须在回调线程内同步完成拷贝；越界返回 null。
     */
    fun crop(l: Int, t: Int, r: Int, b: Int): android.graphics.Bitmap?

    /** 整帧缩略图（仅校准预览用，内存对象不落盘）。 */
    fun snapshot(targetWidth: Int): android.graphics.Bitmap
}
