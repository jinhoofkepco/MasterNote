/*
 * Tile selection follows the AndroidX PDF BitmapFetcher/TileBoard design.
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.studyink.document.pdf

internal data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom
}

internal data class TileKey(val row: Int, val column: Int)

internal data class TileSpec(val key: TileKey, val rect: PixelRect)

internal object PdfTileGrid {
    const val TILE_SIZE_PX = 800

    fun visibleTiles(
        pageWidth: Int,
        pageHeight: Int,
        visibleArea: PixelRect,
        tileSize: Int = TILE_SIZE_PX,
    ): List<TileSpec> {
        require(pageWidth > 0 && pageHeight > 0)
        require(tileSize > 0)

        val clipped = PixelRect(
            left = visibleArea.left.coerceIn(0, pageWidth),
            top = visibleArea.top.coerceIn(0, pageHeight),
            right = visibleArea.right.coerceIn(0, pageWidth),
            bottom = visibleArea.bottom.coerceIn(0, pageHeight),
        )
        if (clipped.isEmpty) return emptyList()

        val firstColumn = clipped.left / tileSize
        val lastColumn = (clipped.right - 1) / tileSize
        val firstRow = clipped.top / tileSize
        val lastRow = (clipped.bottom - 1) / tileSize

        return buildList {
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) {
                    val left = column * tileSize
                    val top = row * tileSize
                    add(
                        TileSpec(
                            key = TileKey(row, column),
                            rect = PixelRect(
                                left = left,
                                top = top,
                                right = minOf(left + tileSize, pageWidth),
                                bottom = minOf(top + tileSize, pageHeight),
                            ),
                        )
                    )
                }
            }
        }
    }
}
