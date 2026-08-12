package com.studyink.annotation.storage

import com.maternote.packageformat.model.PackageManifest
import com.maternote.packageformat.validator.PackageValidationReport
import com.maternote.packageformat.validator.PackageValidator
import java.io.File
import java.util.zip.ZipFile

internal data class PreparedMaternotePackage(val manifest: PackageManifest, val report: PackageValidationReport)

internal object MaternotePackageImporter {
    fun validate(file: File): PreparedMaternotePackage {
        val (manifest, report) = PackageValidator().validatePackage(file)
        require(report.isValid) { report.errors.joinToString { "${it.code}:${it.path}" } }
        return PreparedMaternotePackage(requireNotNull(manifest), report)
    }

    suspend fun importAssets(file: File, assets: ManagedAssetRepository, manifest: PackageManifest): Map<String, ManagedAsset> =
        ZipFile(file).use { zip ->
            buildMap {
                manifest.assets.forEach { definition ->
                    val entry = requireNotNull(zip.getEntry(definition.path)) { "PKG_MISSING_ASSET:${definition.path}" }
                    val imported = zip.getInputStream(entry).use { assets.importStream(it, definition.path.substringAfterLast('/'), definition.mimeType) }
                    require(imported.sha256 == definition.sha256 && imported.byteSize == definition.byteSize) { "PKG_ASSET_HASH_MISMATCH:${definition.path}" }
                    put(definition.assetId, imported)
                }
            }
        }
}
