package cn.changjiahong.composexmlform.renderer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.changjiahong.composexmlform.node.AttrValue
import cn.changjiahong.composexmlform.node.VarAttr
import cn.changjiahong.composexmlform.store.StoreOwner
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
 * 名字带 `In` 是为了和读取糖 `get(default)` 区分：一个要显式给作用域，一个在 composition 里读。
 */
fun <T> AttrValue<T>.readIn(env: StoreOwner): T? = read(env)

/**
 * 带缺省值的读法：`node.enabled.get(true)`、`node.fontSize.get(14f)`。
 *
 * 定义在**可空接收者**上，缺省属性因此自然退化 —— 节点没写这个属性时就是「用默认值」，
 * 渲染器不必写 `node.x?.current() ?: default`（这个模式原来在代码里出现了约 25 次）。
 */
@Composable
fun <T> AttrValue<T>?.get(default: T): T = this?.current() ?: default

/**
 * `val x by node.x`：一次读、多处用，或需要把若干属性集中声明在函数开头。
 *
 * 只读槽位只给 `getValue`，**不给 `provideDelegate`** —— 所以 `var x by node.text` 编译不过。
 * 这是有意的：`ValAttr` 在类型上就没有写入口，写回不该在渲染器里被「碰巧支持」。
 */
@Composable
operator fun <T> AttrValue<T>?.getValue(thisObj: Any?, property: KProperty<*>): T? =
    this?.current()

/**
 * `var x by node.value`：双向绑定，声明处就把作用域捕获进 [AttrState]。
 *
 * 为什么必须走 `provideDelegate` 而不是 `setValue`：`setValue` 执行在
 * `onValueChange = { value = it }` 这种**普通 lambda** 里，那里拿不到 `LocalStoreOwner`；
 * 而属性值是跨作用域共享的不可变对象（`Foreach` 里同一节点渲染 N 行），不能持有 env。
 * 只有「声明处」这个时机能同时看到属性与作用域 —— `provideDelegate` 正好在声明处执行。
 * 附带的好处是 `getValue` 能是 `@Composable`（能订阅），`setValue` 不能。
 */
@Composable
operator fun <T> VarAttr<T>?.provideDelegate(thisObj: Any?, property: KProperty<*>): AttrState<T> =
    asStateOr()

/**
 * 可空 [VarAttr] 的读法：**没有绑定就退化成局部 state**。
 *
 * 这是「属性都可以不写」在双向绑定上的落点：`<TextField/>` 不写 `value`，
 * 输入框照常可以打字、清空、选中，只是内容不回写任何数据 ——
 * 用户看到的差别仅仅是「data 没跟着变」。
 *
 * 局部 state 活在 composition 里（`remember`），不放进节点：节点树跨重组复用，
 * 而且同一个节点在 `Foreach` 里会被渲染成 N 行 —— 把 `MutableState` 放进节点，
 * 等于让所有行共享同一个输入框内容。
 */
@Composable
fun <T> VarAttr<T>?.asStateOr(default: T? = null): AttrState<T> {
    val env = LocalStoreOwner.current
    val local = remember { mutableStateOf(default) }
    val bound = this
    return remember(bound, env, local) {
        if (bound != null) {
            AttrState({ bound.read(env) }, { bound.write(env, it) })
        } else {
            AttrState({ local.value }, { local.value = it })
        }
    }
}

class AttrState<T> internal constructor(
    private val reader: () -> T?,
    private val writer: (T?) -> Unit,
) {

    /** 读：订阅当前绑定的字段；写：写回数据源（或局部 state）。 */
    var value: T?
        get() = reader()
        set(newValue) = writer(newValue)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T? = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T?) {
        value = newValue
    }
}

/**
 * 枚举型属性（对齐 / 排列等）的标准读法：节点声明成 `ValAttr<String>`，
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
