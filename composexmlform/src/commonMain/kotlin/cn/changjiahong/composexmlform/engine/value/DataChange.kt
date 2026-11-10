package cn.changjiahong.composexmlform.engine.value

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 谁写的。数据层自己看不出来，所以必须由写入口带进来。 */
enum class WriteSource {

    /** 程序性写入：宿主 `data.set(...)`、初始化、`Foreach` 之外的结构调整。 */
    Program,

    /** 用户输入回写：`{{path}}` 双向绑定。 */
    UserBinding,

    /** 脚本赋值：`{ data.x = 1 }` / action 脚本。 */
    Script,
}

/**
 * 一次数据变更。
 *
 * [path] 是**相对于 data 根**的路径（`"user.name"` / `"list[3].id"`），不带 `data.` 前缀。
 *
 * 为什么不像原计划那样用绝对路径：容器没有父指针（第 8 轮否掉了），写的时候只有「从根走下来的累积段」，
 * 而 `data.` 这个前缀对宿主没有任何信息量 —— 全系统只有 data 会发变更事件（`config` 只读、`local` 不发）。
 */
class DataChange(
    val path: String,
    val old: Value,
    val new: Value,
    val source: WriteSource,
) {
    override fun toString(): String = "$path: ${old ?: "null"} → ${new ?: "null"} ($source)"
}

/** 变更出口。容器只知道「往这儿吐」，不知道谁在听、怎么分发。 */
interface ChangeChannel {
    fun emit(change: DataChange)
}

/**
 * 默认实现：一条 `SharedFlow`。
 *
 * `replay = 0` 是有意的 —— 变更事件是「现在发生了什么」，不是状态。宿主晚订阅就收不到早先的事件，
 * 这与脏检查的语义一致；要拿当前值请读 `snapshot()`。
 */
class ChangeBus : ChangeChannel {

    private val _changes = MutableSharedFlow<DataChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: SharedFlow<DataChange> = _changes.asSharedFlow()

    override fun emit(change: DataChange) {
        _changes.tryEmit(change)
    }
}
