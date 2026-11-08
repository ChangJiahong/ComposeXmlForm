package cn.changjiahong.composexmlform.node

import cn.changjiahong.composexmlform.binding.ActionRef

/**
 * 一处动作引用：解析期从 `Attrs.actionOf(...)` 收集，随 `ParseResult` 带出。
 *
 * 为什么不在运行时遍历树收集：
 * - 树里只能看到「声明了 `ValAttr<ActionRef>` 字段的节点」，遍历时还得知道字段叫什么；
 * - `Attrs` 本来就在解析期经手每一处引用，顺手记下来零成本；
 * - 带得住 [site]，因此「引用了未注册的函数」能报到标签 / 属性名 / 行号。
 *
 * 放 `node` 包（而不是 `parser`）是因为 `Attrs` 要产出它：`node → parser` 会形成反向依赖。
 */
data class ActionUsage(val ref: ActionRef, val site: AttrSite)
