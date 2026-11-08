package cn.changjiahong.composexmlform.renderer

import androidx.compose.runtime.staticCompositionLocalOf
import cn.changjiahong.composexmlform.validation.FormState

/**
 * 当前表单状态（R11）。
 *
 * 默认 `null`：没用校验的界面完全不受影响，渲染器读到 null 就当「没有错误」。
 * 由 `DynamicLayout(form = ...)` 提供 —— 这是**唯一**需要新参数的地方，
 * 因为「谁来持有 errors」没有 CompositionLocal 能替代（宿主必须自己创建它，
 * 才能在提交按钮里 `form.validate()`）。
 */
val LocalFormState = staticCompositionLocalOf<FormState?> { null }
