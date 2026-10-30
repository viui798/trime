package com.osfans.trime.data.theme

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.serialization.SerializationException

class FontCompatibilityTest {
    private fun decode(font: String): Theme = ThemeYaml.parser.decodeFromString(
        Theme.serializer(),
        "name: test\nstyle:\n  key_font: $font\npreset_color_schemes:\n  default:\n    name: Test\n    key_text_color: 0xff123456\n    key_back_color: backgrounds/key.png\n    key_font: $font\n",
    )

    @Test fun scalarAndSingletonListHaveIdenticalMeaning() {
        val scalar = decode("hd.ttf")
        val list = decode("[hd.ttf]")
        assertEquals(scalar.style.keyFont.toList(), list.style.keyFont.toList())
        assertEquals(scalar.presetColorSchemes, list.presetColorSchemes)
        assertEquals(listOf("hd.ttf"), list.presetColorSchemes.getValue("default").fontNames("key_font"))
    }

    @Test fun fallbackOrderAndNonFontScalarsArePreserved() {
        val scheme = decode("[hd.ttf, fallback.ttf]").presetColorSchemes.getValue("default")
        assertEquals(listOf("hd.ttf", "fallback.ttf"), scheme.fontNames("key_font"))
        assertEquals("0xff123456", scheme["key_text_color"])
        assertEquals("backgrounds/key.png", scheme["key_back_color"])
        assertNotEquals(scheme, decode("[hd.ttf, other.ttf]").presetColorSchemes.getValue("default"))
    }

    @Test fun emptyFontsRemainValidAndMissingOverrideIsDistinct() {
        for (font in listOf("[]", "null", "''")) {
            val scheme = decode(font).presetColorSchemes.getValue("default")
            assertEquals(emptyList<String>(), scheme.fontNames("key_font"))
            assertNull(scheme.fontNames("candidate_font"))
        }
    }

    @Test fun malformedFontMapStillFailsClearly() {
        assertThrows(SerializationException::class.java) { decode("{file: hd.ttf}") }
    }

    @Test fun realMechanicalThemeLoadsWithoutRewritingItsFile() {
        val fixture = File("src/test/assets/coffee-mechanical-fonts.yaml")
        val theme = ThemeYaml.parser.decodeFromString(Theme.serializer(), fixture.readText())
        assertEquals(listOf("hd.ttf"), theme.presetColorSchemes.getValue("jixplv").fontNames("key_font"))
    }
}
