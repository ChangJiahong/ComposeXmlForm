package cn.changjiahong.composexmlform.api

import cn.changjiahong.composexmlform.parser.EngineRegistry

/**
 * 一个组件包：把「注册几样东西」装成一个可复用的值。
 *
 * 取代原来的 `object DemoComponents { fun register(registry: EngineRegistry) }` ——
 * 那套写法在调用侧必须写成 `createRegistry().also { DemoComponents.register(it) }`，
 * 而 `install` 能直接进 DSL，也能 vararg 组合：
 * ```
 * val DemoComponents = ComponentModule {
 *     leaf("RatingBar", ::RatingBarNode, RatingBarRenderer())
 * }
 *
 * val registry = ComposeXmlForm.createRegistry {
 *     install(DemoComponents)
 *     ...
 * }
 * ```
 *
 * `fun interface` 而不是 `interface`：单个 lambda 就能定义一个小模块，不必先起名字。
 */
fun interface ComponentModule {

    fun install(registry: EngineRegistry)
}

/**
 * 安装一个或多个组件包。
 *
 * 同一个包装两次会因为标签撞名报错（`leaf` / `container` 要求标签未被占用）——
 * 这是**故意的**：重复安装几乎总是「依赖被引入两遍」的信号，静默去重会把这个信号吞掉。
 */
fun EngineRegistry.install(vararg modules: ComponentModule) {
    modules.forEach { it.install(this) }
}
