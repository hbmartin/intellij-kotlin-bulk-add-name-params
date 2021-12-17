package com.github.hbmartin.intellijkotlinbulkaddnameparams.services

import com.intellij.openapi.project.Project
import com.github.hbmartin.intellijkotlinbulkaddnameparams.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
