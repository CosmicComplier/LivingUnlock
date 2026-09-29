plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}

tasks.register<JavaExec>("generateEgg") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.windowslockpin.companion.core.egg.EggQrTool")
    args(providers.gradleProperty("eggMode").getOrElse("A7"),
        providers.gradleProperty("eggType").getOrElse("TEXT"),
        providers.gradleProperty("eggInput").orElse(providers.gradleProperty("eggText")).getOrElse(""),
        providers.gradleProperty("eggOutput").getOrElse(""))
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
