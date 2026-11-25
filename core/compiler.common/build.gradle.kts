plugins {
    id("common-configuration")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("gradle-plugin-compiler-dependency-configuration")
}

// `CompilationPhaseEvent.kt` measures the memory with the Java Flight Recorder, whose streaming API
// (`RecordingStream`) and the current-thread allocation counter appeared in JDK 14. The compiler itself requires
// a JDK 17 runtime, so that API is available wherever the compiler runs, while the bytecode stays runnable on
// older JDKs.
jvmToolchains {
    jdkVersion = JdkMajorVersion.JDK_17_0
    jdkApiVersion = jdkVersion
}

dependencies {
    api(project(":core:util.runtime"))
    api(project(":core:names"))
    api(project(":core:language.model"))
    api(project(":core:language.targets"))
    api(kotlinStdlib())
    api(project(":kotlin-annotations-jvm"))
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
