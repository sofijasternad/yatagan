package com.yandex.yatagan.testing.tests

import com.tschuchort.compiletesting.KotlinCompilation
import com.yandex.yatagan.processor.common.Options
import com.yandex.yatagan.processor.jap.JapYataganProcessor
import java.io.File

class JapCompileTestDriver : CompileTestDriverBase() {
    override fun createKotlinCompilation() = super.createKotlinCompilation().apply {
        sources = sourceFiles
        annotationProcessors = listOf(JapYataganProcessor())
        kaptArgs[Options.MaxIssueEncounterPaths.key] = "100"
    }

    override fun getGeneratedFiles(from: KotlinCompilation): Collection<File> {
        return from.kaptSourceDir.walk()
            .filter { it.isFile && it.extension == "java" }
            .toList()
    }

    override val backendUnderTest: Backend
        get() = Backend.Kapt

}
