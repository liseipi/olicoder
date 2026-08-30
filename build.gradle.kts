plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.aicoder"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // IntelliJ 平台本身已内置 Gson，这里显式声明只是为了让编译期可以正常解析类型
    compileOnly("com.google.code.gson:gson:2.10.1")
}

// 依赖的 IDE 版本和插件，可按需修改成你要开发的具体 IDE
// 例如 "IU-2024.1" (IntelliJ Ultimate), "PY-2024.1" (PyCharm) 等
// 使用 "IC" (IntelliJ Community) 作为通用基座，兼容所有基于同内核的 IDE
intellij {
    version.set("2024.1")
    type.set("IC") // IntelliJ Community，插件可运行在所有 JetBrains IDE 上
    plugins.set(listOf("com.intellij.java")) // 需要用到 PSI/Java 支持时才需要，可去掉换成语言无关实现
}

kotlin {
    jvmToolchain(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }

    // 禁止使用 buildSearchableOptions（首次构建更快）
    buildSearchableOptions {
        enabled = false
    }
}
