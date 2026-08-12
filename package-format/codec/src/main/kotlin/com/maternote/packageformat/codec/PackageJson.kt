package com.maternote.packageformat.codec

import com.maternote.packageformat.model.PackageManifest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PackageJson {
    val reader = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
        allowSpecialFloatingPointValues = false
        classDiscriminator = "type"
    }
    val strict = Json(reader) { ignoreUnknownKeys = false }
    fun encode(manifest: PackageManifest): String = reader.encodeToString(manifest)
    fun decode(value: String, strictMode: Boolean = false): PackageManifest =
        (if (strictMode) strict else reader).decodeFromString(value)
}
