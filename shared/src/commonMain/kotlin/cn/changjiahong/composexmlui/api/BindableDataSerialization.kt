package cn.changjiahong.composexmlui.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 对象 → [BindableData] 的转换扩展。
 *
 * 放在扩展而不是核心构造函数里，是为了让核心只依赖 `kotlinx-serialization-json` 的 runtime；
 * 使用方按需引入即可。**建议业务模块启用 `kotlin-serialization` 编译器插件并给模型加
 * `@Serializable`** —— 插件会把 `serializer<T>()` 内联成平台无关的查找，iOS / Native 也能正常
 * 生成序列化器；不启用插件时仅 JVM 反射路径可用。
 */
fun <T> BindableData.Companion.from(
    value: T,
    serializer: KSerializer<T>,
    format: Json = Json,
): BindableData = ofJson(format.encodeToJsonElement(serializer, value))

/**
 * 同 [from]，序列化器由 reified 类型自动推导（要求 `T` 可序列化）。
 */
inline fun <reified T> BindableData.Companion.from(
    value: T,
    format: Json = Json,
): BindableData = from(value, serializer<T>(), format)
