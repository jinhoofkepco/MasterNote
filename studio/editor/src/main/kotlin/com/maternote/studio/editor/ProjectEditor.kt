package com.maternote.studio.editor

import com.maternote.studio.project.StudioProject

fun interface ProjectEditor { fun update(project: StudioProject): StudioProject }
