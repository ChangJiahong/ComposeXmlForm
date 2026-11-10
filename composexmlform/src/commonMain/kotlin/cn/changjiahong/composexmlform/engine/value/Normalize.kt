package cn.changjiahong.composexmlform.engine.value

/**
 * **写入边界的唯一一次规范化。** 之后引擎内部不再判断值的形态 —— 任何一个 `is Map` 出现在
 * 这个文件之外，都算设计违规（DECISIONS §11.6）。
 *
 * | 输入 | 存为 |
 * | --- | --- |
 * | `Int` / `Short` / `Byte` | `Long` |
 * | `Float` | `Double` |
 * | `Char` | `String` |
 * | `Map` | [DataObject] |
 * | `List` / `Array` / `Set` | [DataArray] |
 * | 其他非白名单对象 | [HostObject] |
 *
 * [frozen] 会传给所有后代容器 —— 这样 `config` 下的嵌套对象也是冻结的，
 * 无法通过「把只读对象塞进可写位置」绕过（V-12 的落地方式）。
 */
fun normalize(value: Value, frozen: Boolean = false): Value = when (value) {
    null, Absent -> value
    is Boolean, is Long, is Double, is String -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Float -> value.toDouble()
    is Char -> value.toString()
    is Map<*, *> -> mapToObject(value, frozen)
    is List<*> -> DataArray.ofValues(value, frozen)
    is Array<*> -> DataArray.ofValues(value.asIterable(), frozen)
    is Set<*> -> DataArray.ofValues(value, frozen)
    is DataObject -> if (value.frozen && !frozen) copyContainer(value, frozen) else value
    is DataArray -> if (value.frozen && !frozen) copyContainer(value, frozen) else value
    is HostObject, is Function -> value
    else -> HostObject(value)
}

private fun mapToObject(map: Map<*, *>, frozen: Boolean): MutableDataObject {
    val obj = MutableDataObject(frozen)
    for ((key, raw) in map) {
        // key 只允许 String（V-4）：放开 Any 会造出两套等价写法（"1" 与 1）
        val name = key as? String ?: key.toString()
        obj.setRaw(name, normalize(raw, frozen))
    }
    return obj
}

/** 只读 → 可写：深拷贝成独立副本（S-2）。目标是只读时保持引用，不必复制。 */
private fun copyContainer(value: Any, frozen: Boolean): Value = when (value) {
    is DataObject -> {
        val target = MutableDataObject(frozen)
        for (name in value.keys()) {
            val member = value.member(name)
            if (member is Function) continue
            target.setRaw(name, renormalize(member, frozen))
        }
        target
    }

    is DataArray -> {
        val target = DataArray(frozen)
        for (element in value.elements) {
            target.appendRaw(renormalize(element, frozen))
        }
        target
    }

    else -> value
}

private fun renormalize(value: Value, frozen: Boolean): Value = when (value) {
    is DataObject -> copyContainer(value, frozen)
    is DataArray -> copyContainer(value, frozen)
    else -> value
}

// ── 深拷贝 ────────────────────────────────────────────────────

/**
 * 独立副本。`copy(x)` / `new(obj)` 用它，也是「改草稿不动原数据」的实现方式。
 *
 * 与 [normalize] 的只读→可写拷贝有两处**有意不同**：
 * 1. 这里会保留 `Function` 成员（对象带方法时，副本也该带）；
 * 2. 这里不因 `frozen` 而跳过 —— 冻结对象是为了保护原数据，不是为了不让复制。
 *
 * 标量与引用语义对象（[HostObject]）原样返回：前者不可变，后者的身份本身有意义。
 */
fun deepCopy(value: Value, frozen: Boolean = false): Value = when (value) {
    is DataObject -> {
        val target = MutableDataObject(frozen)
        for (name in value.keys()) target.setRaw(name, deepCopy(value.member(name), frozen))
        target
    }

    is DataArray -> {
        val target = DataArray(frozen)
        for (element in value.elements) target.appendRaw(deepCopy(element, frozen))
        target
    }

    else -> value
}

// ── 相等 ──────────────────────────────────────────────────────

/**
 * 结构相等。`==` 在脚本里的语义就是它（K-2，推翻 V-8 的引用相等）。
 *
 * 数字跨类型按数值比较：`2 == 2.0` 为真 —— 表单里 `Long` 与 `Double` 混用是常态，
 * 按 `equals` 比会让「看起来相等的两个数不相等」，这比精度问题更难查。
 */
fun valuesEqual(a: Value, b: Value): Boolean = valuesEqual(a, b, HashSet())

/** 引用相等。脚本里的 `===`。 */
fun sameReference(a: Value, b: Value): Boolean = a === b

private fun valuesEqual(a: Value, b: Value, seen: MutableSet<Pair<Any, Any>>): Boolean {
    if (a === b) return true
    if (a === Absent || b === Absent) return false
    if (a == null || b == null) return false

    if (a.isNumber() && b.isNumber()) return numbersEqual(a, b)
    if (a is Function || b is Function) return false // 函数只按引用相等，上面已判

    if (a is DataObject && b is DataObject) {
        if (!seen.add(a to b)) return true // 环：已经比过这一对，视为相等
        val keysA = a.keys()
        if (keysA.size != b.keys().size) return false
        for (key in keysA) {
            if (!valuesEqual(a.member(key), b.member(key), seen)) return false
        }
        return true
    }

    if (a is DataArray && b is DataArray) {
        if (!seen.add(a to b)) return true
        val itemsA = a.elements
        val itemsB = b.elements
        if (itemsA.size != itemsB.size) return false
        return itemsA.indices.all { valuesEqual(itemsA[it], itemsB[it], seen) }
    }

    if (a is HostObject && b is HostObject) return a.raw == b.raw
    return a == b
}

private fun Any?.isNumber(): Boolean = this is Long || this is Int || this is Double || this is Float ||
    this is Short || this is Byte

private fun numbersEqual(a: Any, b: Any): Boolean {
    val left = (a as Number).toDouble()
    val right = (b as Number).toDouble()
    return left == right
}

// ── 工厂 ──────────────────────────────────────────────────────

fun dataObjectOf(vararg pairs: Pair<String, Any?>): MutableDataObject {
    val obj = MutableDataObject()
    for ((key, value) in pairs) obj.setRaw(key, normalize(value))
    return obj
}

fun dataObjectOf(map: Map<String, Any?>): MutableDataObject {
    val obj = MutableDataObject()
    for ((key, value) in map) obj.setRaw(key, normalize(value))
    return obj
}

fun dataArrayOf(vararg items: Any?): DataArray = DataArray.ofValues(items.asList())

fun Map<String, Any?>.toDataObject(): MutableDataObject = dataObjectOf(this)
