package cn.changjiahong.composexmlui.api

import cn.changjiahong.composexmlui.binding.ValuePath
import cn.changjiahong.composexmlui.core.JsonDebug
import cn.changjiahong.composexmlui.core.XmlUiException
import cn.changjiahong.composexmlui.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlui.store.ObservableList

/**
 * 运行时句柄：让调用方在 Composable 之外读取 / 更新数据。
 *
 * 线程约定：请在 Compose 主线程（UI 线程）调用。跨线程更新需要业务侧自行切回主线程，
 * 例如 withContext(Dispatchers.Main)。
 */
class DynamicLayoutController internal constructor(
    private val runtime: DynamicLayoutRuntime,
) {

    /** 当前业务数据（普通 Map 副本，不含 config 与局部变量）。 */
    fun getData(): Map<String, Any?> = runtime.rootOwner.store.getData()

    /** 按路径读取，例如 `get("user.name")`。 */
    fun get(path: String): Any? = runtime.rootOwner.read(ValuePath.parse(path))

    /** 按路径写入，例如 `update("username", "李四")`。 */
    fun update(path: String, value: Any?) {
        ensureAlive()
        runtime.rootOwner.write(ValuePath.parse(path), value)
    }

    fun invoke(actionName: String) {
        ensureAlive()
        runtime.actionRegistry.invoke(actionName)
    }

    fun getJson(): String = JsonDebug.encode(getData())

    fun isDisposed(): Boolean = runtime.isDisposed

    /** 向列表追加元素（业务上等价于 update 整个列表，但保留了元素身份）。 */
    fun appendToList(path: String, value: Any?) {
        ensureAlive()
        listAt(path).add(value)
    }

    fun removeListAt(path: String, index: Int) {
        ensureAlive()
        val list = listAt(path)
        if (index !in 0 until list.size) {
            throw XmlUiException("removeListAt: 下标 $index 越界（列表长度 ${list.size}）")
        }
        list.removeAt(index)
    }

    fun replaceList(path: String, values: List<Any?>) {
        ensureAlive()
        val list = listAt(path)
        list.clear()
        list.addAll(values)
    }

    private fun listAt(path: String): ObservableList =
        get(path) as? ObservableList
            ?: throw XmlUiException("路径 '$path' 不是列表，无法执行列表操作")

    private fun ensureAlive() {
        if (runtime.isDisposed) {
            throw XmlUiException("DynamicLayoutController 已随界面销毁，不能再访问")
        }
    }
}
