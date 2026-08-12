package com.maternote.studio.preview

import com.maternote.studio.project.StudioProject

data class PreviewSummary(val title: String, val pageCount: Int, val activityCount: Int)
fun StudioProject.previewSummary() = PreviewSummary(title, pages.size, activities.size)
