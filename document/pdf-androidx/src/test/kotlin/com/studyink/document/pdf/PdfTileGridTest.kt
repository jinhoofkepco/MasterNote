package com.studyink.document.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTileGridTest {
    @Test
    fun visibleTiles_requestsOnlyTilesIntersectingViewport() {
        val tiles = PdfTileGrid.visibleTiles(
            pageWidth = 3_200,
            pageHeight = 4_000,
            visibleArea = PixelRect(850, 900, 1_550, 1_650),
        )

        assertEquals(
            setOf(TileKey(1, 1), TileKey(2, 1)),
            tiles.mapTo(mutableSetOf()) { it.key },
        )
    }

    @Test
    fun visibleTiles_clipsEdgeTileToPageBounds() {
        val tiles = PdfTileGrid.visibleTiles(
            pageWidth = 1_050,
            pageHeight = 1_150,
            visibleArea = PixelRect(800, 800, 1_050, 1_150),
        )

        assertEquals(1, tiles.size)
        assertEquals(TileKey(1, 1), tiles.single().key)
        assertEquals(PixelRect(800, 800, 1_050, 1_150), tiles.single().rect)
    }

    @Test
    fun visibleTiles_returnsEmptyForAreaOutsidePage() {
        val tiles = PdfTileGrid.visibleTiles(
            pageWidth = 1_600,
            pageHeight = 2_400,
            visibleArea = PixelRect(1_700, 100, 2_000, 700),
        )

        assertTrue(tiles.isEmpty())
    }

    @Test
    fun visibleTiles_usesAndroidxEightHundredPixelTileSize() {
        val tiles = PdfTileGrid.visibleTiles(
            pageWidth = 2_000,
            pageHeight = 2_000,
            visibleArea = PixelRect(0, 0, 2_000, 2_000),
        )

        assertEquals(9, tiles.size)
        assertEquals(PixelRect(1_600, 1_600, 2_000, 2_000), tiles.last().rect)
    }
}
