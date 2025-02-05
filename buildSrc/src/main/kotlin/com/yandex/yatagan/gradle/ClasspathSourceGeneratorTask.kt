/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/**
 * Generates Kotlin source, that contains declarations of a top-level const property with the given name and
 * given classpath value.
 *
 * Classpath is a list of files joined with ':' into a single string.
 */
abstract class ClasspathSourceGeneratorTask @Inject constructor(
    objects: ObjectFactory,
) : DefaultTask() {
    @get:Input
    val packageName: Property<String> = objects.property()

    @get:Input
    val propertyName: Property<String> = objects.property()

    @get:InputFiles
    val classpath: Property<FileCollection> = objects.property()

    @get:OutputDirectory
    val generatedSourceDir: DirectoryProperty = objects.directoryProperty().apply {
        convention(project.layout.buildDirectory.dir("generated-sources"))
    }

    @get:Input
    val generatedSourceName: Property<String> = objects.property<String>().apply {
        convention("$name.kt")
    }

    val generatedSource: Provider<RegularFile>
        @OutputFile get() = generatedSourceDir.file(generatedSourceName)

    @TaskAction
    fun action() {
        val text = buildString {
            append("// This source is GENERATED by gradle task `").append(name).appendLine("`. Do not edit!")
            append("package ").appendLine(packageName.get())
            append("const val ").append(propertyName.get()).appendLine(" =")
            classpath.get().joinTo(this, separator = ":\" + \n    \"", prefix = "    \"", postfix = "\"")
        }
        generatedSource.get().asFile.writeText(text)
    }
}