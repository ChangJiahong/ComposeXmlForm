package cn.changjiahong.composexmlui.api

import androidx.compose.runtime.State
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.runtime.DynamicLayoutRuntime

/**
 * 运行时句柄：生命周期 + 动作调用的入口。
 *
 * 数据读写统一收敛到 [data]（[BindableData]）：它是调用方真正持有的对象，
 * 控制器只额外提供「界面已销毁就报错」这层保护。新代码建议直接用 `controller.data`。
 *
 * 线程约定：请在 Compose 主线程（UI 线程）调用。跨线程更新需要业务侧自行切回主线程，
 * 例如 withContext(Dispatchers.Main)。
 */
class DynamicLayoutController internal constructor(
    private val runtime: DynamicLayoutRuntime,
) {

    /** 本次界面实例使用的业务数据门面（与 `DynamicLayout(data = ...)` 传入的是同一个实例）。 */
    val data: BindableData get() = runtime.data

    /** 整表快照的响应式状态，等价于 `controller.data.asState()`。 */
    fun asState(): State<Map<String, Any?>> = data.asState()

    /** 当前业务数据（普通 Map 副本，不含 config 与局部变量）。 */
    fun getData(): Map<String, Any?> = data.snapshot()

    /** 按路径读取，例如 `get("user.name")`。 */
    fun get(path: String): Any? = data.get(path)

    /** 按路径写入，例如 `update("username", "李四")`；返回数据是否真的变化。 */
    fun update(path: String, value: Any?): Boolean {
        ensureAlive()
        return data.set(path, value)
    }

    fun invoke(actionName: String) {
        ensureAlive()
        runtime.actionRegistry.invoke(actionName)
    }

    fun getJson(pretty: Boolean = true): String = data.getJson(pretty)

    fun isDisposed(): Boolean = runtime.isDisposed

    /** 向列表追加元素（原地操作，已有元素身份不变）。 */
    fun appendToList(path: String, value: Any?) {
        ensureAlive()
        data.appendToList(path, value)
    }

    fun removeListAt(path: String, index: Int) {
        ensureAlive()
        data.removeListAt(path, index)
    }

    fun replaceList(path: String, values: List<Any?>) {
        ensureAlive()
        data.replaceList(path, values)
    }

    private fun ensureAlive() {
        if (runtime.isDisposed) {
            throw XmlUiException("DynamicLayoutController 已随界面销毁，不能再访问")
        }
    }
}
