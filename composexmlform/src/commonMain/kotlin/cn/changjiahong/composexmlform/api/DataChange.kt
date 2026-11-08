package cn.changjiahong.composexmlform.api

/**
 * 一次数据变更。
 *
 * [BindableData.changes] 把它推给宿主，用于脏检查、离开拦截、增量提交。
 * 事件粒度是「**一次写入操作**一条」，不是「每个字段一条」——否则 `reset` 会喷出几十条。
 */
class DataChange(

    /** 点分路径（`user.name`、`contacts[0].phone`）；整体替换时为空串。 */
    val path: String,

    /** 写入前的值（普通结构，已 unwrap）。整体替换时是整份快照。 */
    val old: Any?,

    /** 写入后的值（普通结构，已 unwrap）。整体替换时是整份快照。 */
    val new: Any?,

    /** 谁改的。 */
    val source: Source,
) {

    /**
     * 变更来源。
     *
     * 数据层本身看不出「谁写的」，所以来源由调用点声明：
     * [Program] 是业务代码，[UserBinding] 是双向绑定写回。
     * 第三方直接操作引擎内部容器时没有来源，一律按 [Program] 处理。
     */
    enum class Source {
        Program,
        UserBinding,
    }

    /** 是否为用户输入触发（`TextField` / `Select` / 自定义组件的写回）。 */
    val isUserBinding: Boolean get() = source == Source.UserBinding

    override fun toString(): String =
        "DataChange(${path.ifEmpty { "<root>" }}, $old -> $new, $source)"
}
