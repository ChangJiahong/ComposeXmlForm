package cn.changjiahong.composexmlform.engine.value

import cn.changjiahong.composexmlform.engine.diag.EvalError

/**
 * [Function] 的构造辅助。
 *
 * 手写 `Function { target, args -> … }` 每次都要 `args[0]` 解包并自己查个数，宿主会写得很痛。
 * 这一组把「个数校验」收进工厂：参数个数不对时抛 [EvalError]，消息说清期望几个、收到几个（F-6）。
 *
 * 个数校验放在**调用时**而不是注册时，是 `Func` 不携带签名（X-2）的代价补偿：XML 里不用写类型，
 * 代价是丢了编译期检查，那就把运行期检查做扎实。
 */
object Fn {

    /** 最原始的形式：拿得到 `target` 与完整参数表。 */
    fun of(block: (target: Value, args: List<Value>) -> Value): Function =
        Function { target, args -> block(target, args) }

    fun noArg(block: () -> Value): Function = Function { _, args ->
        args.requireArity(0)
        block()
    }

    fun of1(block: (Value) -> Value): Function = Function { _, args ->
        args.requireArity(1)
        block(args[0])
    }

    fun of2(block: (Value, Value) -> Value): Function = Function { _, args ->
        args.requireArity(2)
        block(args[0], args[1])
    }

    fun of3(block: (Value, Value, Value) -> Value): Function = Function { _, args ->
        args.requireArity(3)
        block(args[0], args[1], args[2])
    }

    /** 挂起函数：只能出现在 `await f(x)` 里，或由宿主的协程上下文直接调用。 */
    fun suspendOf(block: suspend (target: Value, args: List<Value>) -> Value): SuspendFunction =
        object : SuspendFunction {
            override suspend fun invokeSuspend(target: Value, args: List<Value>): Value =
                block(target, args)
        }

    fun suspendOf1(block: suspend (Value) -> Value): SuspendFunction = object : SuspendFunction {
        override suspend fun invokeSuspend(target: Value, args: List<Value>): Value {
            args.requireArity(1)
            return block(args[0])
        }
    }

    fun suspendOf2(block: suspend (Value, Value) -> Value): SuspendFunction =
        object : SuspendFunction {
            override suspend fun invokeSuspend(target: Value, args: List<Value>): Value {
                args.requireArity(2)
                return block(args[0], args[1])
            }
        }
}

/**
 * 参数个数校验。
 *
 * 消息写成「期望 N 个参数，收到 M 个」，是因为 XML 里调用不带类型信息，用户唯一能自查的就是个数。
 */
private fun List<Value>.requireArity(expected: Int) {
    if (size != expected) {
        throw EvalError("期望 $expected 个参数，收到 $size 个")
    }
}
