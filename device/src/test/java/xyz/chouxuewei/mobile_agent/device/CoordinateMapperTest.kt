package xyz.chouxuewei.mobile_agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.chouxuewei.mobile_agent.core.Viewport

class CoordinateMapperTest {
    @Test fun scalesObservationCoordinatesToTarget() {
        assertEquals(
            MappedPoint(719, 1279),
            CoordinateMapper.map(359, 639, Viewport(360, 640), Viewport(720, 1280), 0),
        )
    }

    @Test fun rotatesClockwiseInsideDeviceLayer() {
        assertEquals(
            MappedPoint(0, 1279),
            CoordinateMapper.map(359, 639, Viewport(360, 640), Viewport(720, 1280), 90),
        )
    }

    @Test fun rejectsCoordinatesOutsideObservation() {
        assertThrows(IllegalArgumentException::class.java) {
            CoordinateMapper.map(360, 10, Viewport(360, 640), Viewport(720, 1280), 0)
        }
    }
}
