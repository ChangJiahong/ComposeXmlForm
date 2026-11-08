import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// 示例工程：只放「怎么用这个库」，自身不含任何引擎逻辑。
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            // 保持 "Shared"：iosApp 的 Swift 代码 import Shared，拆分后无需改动 iOS 工程
            baseName = "Shared"
            isStatic = true
            // 静态框架会把依赖模块的代码一起链进来（Swift 侧只要能调 MainViewController 就够）。
            // 若以后要在 Swift 里直接用引擎 API，再打开下面这行：
            // export(project(":composexmlform"))
        }
    }
    jvm()
    android {
        // 必须与 androidApp 的 namespace 区分开，否则 manifest 合并会冲突
        namespace = "cn.changjiahong.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    sourceSets {
        commonMain.dependencies {
            // api：desktopApp / androidApp 直接消费库里的类型，需要传递可见
            api(project(":composexmlform"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
        }
    }
}
