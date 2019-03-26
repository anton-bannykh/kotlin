plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:ir.psi2ir"))
    compile(project(":compiler:ir.serialization.common"))
    compile(project(":js:js.frontend"))

    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testCompile(projectTests(":compiler:tests-common"))
    testCompileOnly(project(":compiler:frontend"))
    testCompileOnly(project(":compiler:cli"))
    testCompileOnly(project(":compiler:util"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

val generateIrRuntimeKlib by task<NoDebugJavaExec> { 
    val inDirs = arrayOf("core/builtins/src",
                         "core/builtins/native",
                         "libraries/stdlib/common/src",
                         "libraries/stdlib/src/kotlin",
                         "libraries/stdlib/js/src/generated",
                         "libraries/stdlib/js/irRuntime",
                         "libraries/stdlib/unsigned",
                         "js/js.translator/testData/_commonFiles",
                         "libraries/kotlin.test/annotations-common/src/main",
                         "libraries/kotlin.test/common/src/main",
                         "libraries/kotlin.test/js/src/main")
    inDirs.forEach { inputs.dir("$rootDir/$it") }

    val outDir = "$rootDir/js/js.translator/testData/out/klibs/"
    outputs.dir(outDir)

    classpath = sourceSets.test.get().runtimeClasspath
    main = "org.jetbrains.kotlin.ir.backend.js.GenerateIrRuntimeKt"
    workingDir = rootDir

    passClasspathInJar()
}

testsJar {}