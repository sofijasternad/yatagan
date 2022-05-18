import com.yandex.daggerlite.gradle.ClasspathSourceGeneratorTask

plugins {
    id("daggerlite.base-module")
}

val kotlinCompileTestingVersion: String by extra
val kspVersion: String by extra
val junitVersion: String by extra
val mockitoKotlinVersion: String by extra
val kotlinxCliVersion: String by extra

val baseTestRuntime: Configuration by configurations.creating
val dynamicTestRuntime: Configuration by configurations.creating {
    extendsFrom(baseTestRuntime)
}
val compiledTestRuntime: Configuration by configurations.creating {
    extendsFrom(baseTestRuntime)
}

configurations.all {
    resolutionStrategy {
        // Force KSP version as testing framework may depend on an older version.
        force("com.google.devtools.ksp:symbol-processing:$kspVersion")
        force("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
    }
}

dependencies {
    // Third-party test dependencies
    implementation("com.github.tschuchortdev:kotlin-compile-testing-ksp:$kotlinCompileTestingVersion")
    implementation("junit:junit:$junitVersion")

    // Base test dependencies
    implementation(project(":processor"))
    implementation(project(":validation-impl"))
    implementation(project(":core-impl"))
    implementation(project(":graph-impl"))
    implementation(project(":api"))
    implementation(project(":base"))

    // KSP dependencies
    implementation(project(":lang-ksp"))
    implementation(project(":processor-ksp"))

    // JAP dependencies
    implementation(project(":lang-jap"))
    implementation(project(":processor-jap"))

    // RT dependencies
    implementation(project(":lang-rt"))

    // Standalone launcher dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-cli:$kotlinxCliVersion")

    // Heavy test dependencies
    testImplementation(project(":testing-generator"))

    baseTestRuntime("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")  // required for heavy tests
    dynamicTestRuntime(project(":api-dynamic", configuration = "runtimeElements"))
    compiledTestRuntime(project(":api-compiled", configuration = "runtimeElements"))
}

val dynamicApiClasspathTask = tasks.register<ClasspathSourceGeneratorTask>("generateDynamicApiClasspath") {
    packageName.set("com.yandex.daggerlite.generated")
    propertyName.set("DynamicApiClasspath")
    classpath.set(dynamicTestRuntime)
}

val compiledApiClasspathTask = tasks.register<ClasspathSourceGeneratorTask>("generateCompiledApiClasspath") {
    packageName.set("com.yandex.daggerlite.generated")
    propertyName.set("CompiledApiClasspath")
    classpath.set(compiledTestRuntime)
}

tasks.named("compileKotlin") {
    dependsOn(dynamicApiClasspathTask, compiledApiClasspathTask)
}

tasks.test {
    // Needed for "heavy" tests, as they compile a very large Kotlin project in-process.
    jvmArgs = listOf("-Xmx4G", "-Xms256m")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(dynamicApiClasspathTask.map { it.generatedSourceDir })
            kotlin.srcDir(compiledApiClasspathTask.map { it.generatedSourceDir })
        }
    }
}
