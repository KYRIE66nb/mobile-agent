package xyz.chouxuewei.mobile_agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageRenderRequestTest {
    @Test
    fun pngRequestNormalizesNameAndKeepsTransparentBackgroundByDefault() {
        val request = parseImageRenderRequest(buildJsonObject {
            put("name", "示意图.svg")
            put("format", "png")
            put("svg", SIMPLE_SVG)
            put("width", 640)
            put("height", 360)
        })

        assertEquals("示意图.png", request.name)
        assertEquals(ImageRenderFormat.PNG, request.format)
        assertEquals(null, request.backgroundColor)
        assertEquals(640, request.width)
        assertEquals(360, request.height)
    }

    @Test
    fun gifRequestAcceptsOrderedSvgFramesAndDurations() {
        val request = parseImageRenderRequest(buildJsonObject {
            put("name", "呼吸动画")
            put("format", "gif")
            putJsonArray("frames") {
                add(buildJsonObject {
                    put("svg", SIMPLE_SVG)
                    put("duration_ms", 120)
                })
                add(buildJsonObject {
                    put("svg", SIMPLE_SVG.replace("red", "blue"))
                    put("duration_ms", 180)
                })
            }
        })

        assertEquals("呼吸动画.gif", request.name)
        assertEquals(2, request.frames.size)
        assertEquals(300, request.frames.sumOf(SvgAnimationFrame::durationMs))
    }

    @Test
    fun gifRejectsSingleFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            parseImageRenderRequest(buildJsonObject {
                put("name", "single.gif")
                put("format", "gif")
                putJsonArray("frames") {
                    add(buildJsonObject {
                        put("svg", SIMPLE_SVG)
                        put("duration_ms", 100)
                    })
                }
            })
        }
    }

    @Test
    fun dimensionsMustBeProvidedTogether() {
        assertThrows(IllegalArgumentException::class.java) {
            parseImageRenderRequest(buildJsonObject {
                put("name", "demo.png")
                put("format", "png")
                put("svg", SIMPLE_SVG)
                put("width", 320)
            })
        }
    }

    @Test
    fun svgRejectsScriptAndExternalResources() {
        assertThrows(IllegalArgumentException::class.java) {
            validateSvgSafety("""<svg viewBox="0 0 10 10"><script>alert(1)</script></svg>""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateSvgSafety("""<svg viewBox="0 0 10 10"><use href="https://example.com/a.svg#x"/></svg>""")
        }
    }

    @Test
    fun localSvgReferencesRemainAvailableForGradientsAndSymbols() {
        validateSvgSafety(
            """<svg viewBox="0 0 10 10"><defs><linearGradient id="g"/></defs><rect width="10" height="10" fill="url(#g)"/></svg>""",
        )
    }

    private companion object {
        const val SIMPLE_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="30" fill="red"/></svg>"""
    }
}
