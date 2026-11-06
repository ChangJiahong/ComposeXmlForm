package cn.changjiahong.composexmlui.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlui.node.AttrValue
import cn.changjiahong.composexmlui.node.VarAttr
import cn.changjiahong.composexmlui.store.StoreOwner
import kotlin.reflect.KProperty

/**
 * 在 Composable 里取属性值：常量直接返回定型结果，变量读当前作用域。
 *
 * 读 state 即订阅，因此 `fontSize="{{size}}"` 变化时只会重组用到它的那个组件。
 * 常量分支**不会**读 CompositionLocal，也没有任何转换开销（已在构造期定型）。
 */
@Composable
fun <T> AttrValue<T>.current(): T? = read(LocalStoreOwner.current)

/**
 * 在非 Composable 环境（单元测试、工具函数）取属性值。
 *
 * 注意：这里只是「取值」，不会建立订阅 —— 监听只能在 composition 里通过 [current] 获得。
 */
fun <T> AttrValue<T>.get(env: StoreOwner): T? = read(env)

/**
 * 双向绑定句柄：把当前作用域捕获进来，读写都不再需要显式传 owner。
 *
 * 只有 [VarAttr] 提供这个方法 —— 只读槽位（[cn.changjiahong.composexmlui.node.ValAttr]）
 * 在类型上就没有写入口，因此「字面量写回」这类错误不需要在渲染器里兜底。
 *
 * ```
 * val text = node.value.asState()
 * OutlinedTextField(value = text.value ?: "", onValueChange = { text.value = it })
 * ```
 *
 * 或直接委托：
 * ```
 * var text by node.value.asState()
 * ```
 *
 * 作用域是在 composition 里读到的，所以 `Foreach` 每一行拿到的句柄各自指向本行的元素；
 * 节点与属性值本身仍然无状态、可跨界面共享。
 */
@Composable
fun <T> VarAttr<T>.asState(): AttrState<T> {
    val env = LocalStoreOwner.current
    return remember(this, env) { AttrState(this, env) }
}

class AttrState<T> internal constructor(
    private val attr: VarAttr<T>,
    private val env: StoreOwner,
) {

    /** 读：订阅当前绑定的字段；写：直接写回数据源。 */
    var value: T?
        get() = attr.read(env)
        set(newValue) = attr.write(env, newValue)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T?) {
        value = newValue
    }
}

/**
 * 枚举型属性（对齐 / 排列等）的标准读法：节点声明成 `AttrValue<String>`，
 * 渲染器提供「字符串 → Compose 枚举」的映射。
 *
 * 映射留在渲染器，是因为 `Alignment` / `Arrangement` 属于 Compose 排版体系，
 * 不应该出现在节点定义的类型里。
 */
@Composable
fun <T> AttrValue<String>?.choose(mapping: Map<String, T>, default: T): T {
    val attr = this ?: return default
    val raw = attr.current()?.trim() ?: return default
    return mapping[raw.lowercase()]
        ?: attr.site.fail("属性值 '$raw' 无效，期望 ${mapping.keys.sorted().joinToString(" / ")}")
}
