package cn.changjiahong.composexmlform.engine.node

/**
 * 一种标签的完整声明：名字、属性表、子节点规则、构造函数。
 *
 * 构造契约是 `(id, AttrSet, children)`（N-1），**不引入 `NodeSpec`**：
 * 属性按需从 [AttrSet] 里取，缺失 / 默认 / 必填在取的时候处理，
 * 不需要先攒一个「属性大对象」再传给节点 —— 那层中间对象只是把参数搬了一遍。
 *
 * [create] 的第一个参数是**结构化 id**（N-5）。id 由解析器按树位置生成
 * （`0` / `0.1` / `0.1.2`），节点自己无从知道它在树里的位置，所以必须由外部传入。
 *
 * [create] 拿到的 `children` 已经过 [childRule] 校验，实现里可以直接按位置假设子节点存在。
 */
class NodeType(
    val tag: String,
    val attributes: List<AttrDef<*>> = emptyList(),
    val childRule: ChildRule = ChildRule.None,
    val create: (id: String, attrs: AttrSet, children: List<Node>) -> Node,
) {

    fun defOf(name: String): AttrDef<*>? = attributes.firstOrNull { it.name == name }

    /** 声明了函数槽位吗 —— 渲染层据此决定要不要包 `EventHandler`。 */
    fun hasFunctionSlot(): Boolean = attributes.any { it.isFunction }

    override fun toString(): String = "NodeType($tag, ${attributes.size} attrs, $childRule)"
}

/**
 * 标签 → [NodeType] 的**不可变注册表**。
 *
 * 不可变是刻意的：解析一次拿到的树，其节点类型在整棵树的寿命内不能变 ——
 * 否则「解析时是 Text、渲染时是别的」这种漂移只会在特定时序下复现。
 * 要加类型就 `plus()` 出一份新的，然后重新解析（M-4）。
 */
class NodeTypes private constructor(private val byTag: Map<String, NodeType>) {

    val tags: Set<String> get() = byTag.keys

    val size: Int get() = byTag.size

    operator fun get(tag: String): NodeType? = byTag[tag]

    fun contains(tag: String): Boolean = byTag.containsKey(tag)

    /**
     * 加一个类型。**重名直接失败** —— 两个 `Text` 声明同时存在时，
     * 「谁生效」取决于注册顺序，那是最难查的一类 bug。
     */
    fun plus(type: NodeType): NodeTypes {
        require(type.tag !in byTag) {
            "标签『${type.tag}』已注册；要替换请用 replacing()"
        }
        return NodeTypes(byTag + (type.tag to type))
    }

    fun plus(types: Iterable<NodeType>): NodeTypes = types.fold(this) { acc, type -> acc.plus(type) }

    /**
     * 合并另一张表（宿主把自己的表与 `StructuralTypes.All` 拼起来时用）。
     *
     * 与 [plus] 同一条规则：**重名直接失败**，要覆盖请先 [replacing]。
     */
    fun plus(other: NodeTypes): NodeTypes {
        var merged = this
        for (tag in other.byTag.keys) {
            merged = merged.plus(other.byTag.getValue(tag))
        }
        return merged
    }

    /** 有意替换一个已有类型（宿主覆盖内置节点）。 */
    fun replacing(type: NodeType): NodeTypes = NodeTypes(byTag + (type.tag to type))

    fun minus(tag: String): NodeTypes = NodeTypes(byTag - tag)

    override fun toString(): String = "NodeTypes(${tags.joinToString()})"

    companion object {

        val Empty: NodeTypes = NodeTypes(emptyMap())

        fun of(vararg types: NodeType): NodeTypes = Empty.plus(types.asIterable())
    }
}
