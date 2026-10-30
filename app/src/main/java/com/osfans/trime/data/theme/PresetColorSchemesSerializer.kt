/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.untagged
import com.osfans.trime.util.yamlNode
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** String-valued colors stay unchanged; fonts retain their ordered fallback lists. */
internal class FontColorScheme(
    values: Map<String, String>,
    val fonts: Map<String, List<String>>,
) : AbstractMap<String, String>() {
    override val entries = values.entries
}

internal fun ColorScheme.fontNames(key: String): List<String>? =
    (this as? FontColorScheme)?.fonts?.get(key)
        ?: get(key)?.let { if (it.isBlank()) emptyList() else listOf(it) }

@OptIn(InternalSerializationApi::class)
object PresetColorSchemesSerializer : KSerializer<PresetColorSchemes> {
    override val descriptor = buildSerialDescriptor("PresetColorSchemes", SerialKind.CONTEXTUAL)

    override fun serialize(encoder: Encoder, value: PresetColorSchemes): Unit =
        throw UnsupportedOperationException("Color schemes are only read from theme files")

    override fun deserialize(decoder: Decoder): PresetColorSchemes {
        val schemes = decoder.yamlNode().mapping
            ?: throw SerializationException("Expected a color scheme mapping")
        return schemes.pairs.mapValues { (id, node) ->
            val mapping = node.mapping ?: throw SerializationException("Expected a mapping for scheme $id")
            val fonts = linkedMapOf<String, List<String>>()
            val values = mapping.pairs.mapValues { (key, raw) ->
                val value = raw.untagged
                if (key.endsWith("_font")) {
                    val names = when (value) {
                        is YamlNull -> emptyList()
                        is YamlScalar -> listOf(value.content).filter { it.isNotBlank() }
                        is YamlList -> value.items.map { item ->
                            (item.untagged as? YamlScalar)?.content
                                ?: throw SerializationException("Font list $id.$key must contain strings")
                        }.filter { it.isNotBlank() }
                        else -> throw SerializationException("Expected a string or font list for $id.$key")
                    }
                    fonts[key] = names
                    // Canonical list text keeps map/theme equality sensitive to every fallback.
                    // Rendering uses the typed list above, never this display representation.
                    Json.encodeToString(names)
                } else {
                    (value as? YamlScalar)?.content
                        ?: throw SerializationException("Expected a scalar for $id.$key")
                }
            }
            FontColorScheme(values, fonts)
        }
    }
}
