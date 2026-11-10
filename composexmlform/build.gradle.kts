import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    android {
        namespace = "cn.changjiahong.composexmlform"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeXmlForm"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // 库作为 UI 库对外暴露 Compose 类型，必须用 api：
            // 宿主（shared / androidApp / desktopApp）直接使用这些类型，不应再重复声明版本
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            // JsonElement 是 BindableData 的公开输入格式
            api(libs.kotlinx.serializationJson)
            // Flow 出现在 BindableData.observe 的签名里
            api(libs.kotlinx.coroutinesCore)
            implementation(libs.compose.uiToolingPreview)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // runComposeUiTest：渲染层测试（重组计数、缺失渲染器降级）
            implementation(libs.compose.uiTest)
            // runTest：脚本执行外壳（await）的测试
            implementation(libs.kotlinx.coroutinesTest)
        }
        getByName("jvmTest").dependencies {
            // 桌面端 UI 测试需要一个具体的 skiko 运行时（headless 也能跑）
            implementation(compose.desktop.currentOs)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testRunner)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
