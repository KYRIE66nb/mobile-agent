package xyz.chouxuewei.mobile_agent.device

import xyz.chouxuewei.mobile_agent.core.localizedText
import xyz.chouxuewei.mobile_agent.core.Viewport

internal data class MappedPoint(val x: Int, val y: Int)

/** 模型坐标只相对当前识别结果；真实显示尺寸和旋转统一在设备层转换。 */
internal object CoordinateMapper {
    fun map(x: Int, y: Int, source: Viewport, target: Viewport, rotationDegrees: Int): MappedPoint {
        require(x in 0 until source.width && y in 0 until source.height) { localizedText("坐标超出当前识别结果", "Coordinates are outside the current observation.") }
        val nx = if (source.width == 1) 0.0 else x.toDouble() / (source.width - 1)
        val ny = if (source.height == 1) 0.0 else y.toDouble() / (source.height - 1)
        val (rx, ry) = when (rotationDegrees) {
            0 -> nx to ny
            90 -> (1.0 - ny) to nx
            180 -> (1.0 - nx) to (1.0 - ny)
            270 -> ny to (1.0 - nx)
            else -> error(localizedText("不支持的旋转角度：$rotationDegrees", "Unsupported rotation: $rotationDegrees"))
        }
        return MappedPoint(
            (rx * (target.width - 1)).toInt().coerceIn(0, target.width - 1),
            (ry * (target.height - 1)).toInt().coerceIn(0, target.height - 1),
        )
    }
}
