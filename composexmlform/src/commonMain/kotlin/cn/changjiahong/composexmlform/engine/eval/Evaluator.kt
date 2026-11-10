package cn.changjiahong.composexmlform.engine.eval

import cn.changjiahong.composexmlform.engine.diag.EvalError
import cn.changjiahong.composexmlform.engine.diag.Span
import cn.changjiahong.composexmlform.engine.syntax.Expr
import cn.changjiahong.composexmlform.engine.syntax.Stmt
import cn.changjiahong.composexmlform.engine.syntax.TokenKind
import cn.changjiahong.composexmlform.engine.value.Absent
import cn.changjiahong.composexmlform.engine.value.DataArray
import cn.changjiahong.composexmlform.engine.value.DataObject
import cn.changjiahong.composexmlform.engine.value.Function
import cn.changjiahong.composexmlform.engine.value.HostObject
import cn.changjiahong.composexmlform.engine.value.MutableDataObject
import cn.changjiahong.composexmlform.engine.value.Path
import cn.changjiahong.composexmlform.engine.value.SuspendFunction
import cn.changjiahong.composexmlform.engine.value.Value
import cn.changjiahong.composexmlform.engine.value.WriteSource
import cn.changjiahong.composexmlform.engine.value.display
import cn.changjiahong.composexmlform.engine.value.kindOf
import cn.changjiahong.composexmlform.engine.value.normalize
import cn.changjiahong.composexmlform.engine.value.sameReference
import cn.changjiahong.composexmlform.engine.value.valuesEqual

/**
 * 同步求值器。**全系统只有这一份求值逻辑**（第 14 轮）。
 *
 * 挂起不是第二个求值器：`await` 只出现在语句层，`execute` 那个外壳把 `await` 拿出来单独处理，
 * 其余语句原样交给这里（见 [execute]）。若在表达式层支持 `await`，算术就得有 suspend 版本，
 * 两个版本迟早会分叉 —— 这类分叉查起来极贵。
 *
 * ### 名字解析顺序（[readName]）
 *
 * ```
 * env 链（帧 → 链 → 命名全局 → 兜底 data/action）  →  内置全局函数  →  Absent
 * ```
 *
 * 内置函数殿后而不是插在中间：`data.keys` 是用户数据，`keys(x)` 才是函数，
 * 两者靠「有没有括号」自然区分，不需要给函数留一个保留字。
 */
internal object Evaluator {

    // ── 入口 ──────────────────────────────────────────────────

    fun evaluate(expr: Expr, ctx: EvalContext): Value = eval(expr, ctx)

    /** 执行语句序列，返回最后一条语句的值。**不处理 `return`** —— 那要由 [runBody] 收尾。 */
    fun evalStatements(statements: List<Stmt>, ctx: EvalContext): Value {
        var last: Value = null
        for (stmt in statements) {
            if (ctx.frame?.returned == true) break
            last = evalStatement(stmt, ctx)
        }
        return last
    }

    /** 执行一个函数体 / 脚本体：把 `return` 的值取出来。 */
    fun runBody(statements: List<Stmt>, ctx: EvalContext): Value {
        val last = evalStatements(statements, ctx)
        val frame = ctx.frame
        return if (frame?.returned == true) frame.returnValue else last
    }

    fun evalStatement(stmt: Stmt, ctx: EvalContext): Value = when (stmt) {
        is Stmt.VarDecl -> {
            val value = eval(stmt.init, ctx)
            declare(stmt, value, ctx)
            value
        }

        is Stmt.Assign -> {
            val value = normalize(eval(stmt.value, ctx))
            assignValue(stmt, value, ctx)
            value
        }

        is Stmt.Return -> {
            val frame = ctx.frame ?: throw EvalError("return 只能出现在脚本里", stmt.span)
            val value = stmt.value?.let { eval(it, ctx) }
            frame.signalReturn(value)
            value
        }

        is Stmt.ExprStmt -> eval(stmt.expr, ctx)
    }

    // 声明与赋值拆成「求值」与「落位」两步：挂起外壳要把右侧的 await 自己求出来，
    // 再交给同一个落位逻辑，否则就得复制一份赋值规则。
    fun declare(stmt: Stmt.VarDecl, value: Value, ctx: EvalContext) {
        val frame = ctx.frame ?: throw EvalError("属性表达式里不能声明变量，脚本里才可以", stmt.span)
        frame.declare(stmt.name, value, stmt.mutable, stmt.span)
    }

    // ── 表达式 ────────────────────────────────────────────────

    private fun eval(expr: Expr, ctx: EvalContext): Value = when (expr) {
        is Expr.Lit -> expr.value
        is Expr.Name -> readName(expr.id, ctx)

        is Expr.Get -> member(eval(expr.target, ctx), expr.name, ctx)

        is Expr.Index -> index(eval(expr.target, ctx), eval(expr.index, ctx))

        is Expr.Call -> call(expr, ctx)

        is Expr.Unary -> unary(expr, ctx)

        is Expr.Binary -> binary(expr, ctx)

        is Expr.Obj -> {
            val obj = MutableDataObject()
            for ((key, item) in expr.entries) obj.setRaw(key, normalize(eval(item, ctx)))
            obj
        }

        is Expr.Arr -> {
            val array = DataArray()
            for (item in expr.items) array.append(eval(item, ctx))
            array
        }

        is Expr.If -> if (requireCondition(expr.cond, ctx)) {
            eval(expr.thenExpr, ctx)
        } else {
            expr.elseExpr?.let { eval(it, ctx) }
        }

        // 块只在语句位置出现（`if` 的分支 / `{ … }` 语句），走到这里说明是属性表达式里的块
        is Expr.Block -> evalStatements(expr.statements, ctx)

        is Expr.Lambda -> lambdaFunction(expr.lambda, ctx)

        is Expr.Await -> throw EvalError(
            "await 需要挂起上下文，属性表达式与 @{} 里都不能用；它只能出现在脚本语句中",
            expr.span,
        )
    }

    /**
     * 裸标识符。走 env 的那五个分支（帧 → 链 → 命名全局 → 兜底），都没命中再查内置函数。
     *
     * 兜底为什么排在「命名全局」之后：若先补 `data.` 前缀，`{config.x}` 会被读成 `data.config.x`。
     */
    private fun readName(name: String, ctx: EvalContext): Value {
        val fromEnv = ctx.env.read(Path.relative(name), ctx.hint, ctx.frame)
        if (fromEnv !== Absent) return fromEnv
        return BuiltinFunctions.lookup(name) ?: Absent
    }

    /**
     * 读成员。**数据优先，方法兜底**（F-2）。
     *
     * `Absent` / `null` 一律静默返回 `Absent`（P-4 / K-5）：`.` 本身是安全的，
     * 三级缺失的路径 `a.b.c` 应该是一串 Absent 而不是三个错误。
     */
    private fun member(target: Value, name: String, ctx: EvalContext): Value {
        when (target) {
            Absent, null -> return Absent
            is DataObject -> {
                val field = target.member(name)
                if (field !== Absent) return field
            }

            is Function -> return Absent
            is DataArray, is String, is Long, is Double, is Boolean, is HostObject -> Unit
            else -> return Absent
        }

        BuiltinMethods.lookup(kindOf(target), name)?.let { return it }
        return ctx.methods.find(target, name) ?: Absent
    }

    private fun index(target: Value, key: Value): Value = when (target) {
        is DataArray -> if (key is Long) target.get(key.toInt()) else Absent
        is DataObject -> when (key) {
            is String -> target.member(key)
            is Long -> target.member(key.toString())
            else -> Absent
        }

        is String -> if (key is Long) target.getOrNull(key.toInt())?.toString() ?: Absent else Absent
        else -> Absent
    }

    private fun unary(expr: Expr.Unary, ctx: EvalContext): Value {
        val operand = eval(expr.operand, ctx)
        return when (expr.op) {
            TokenKind.Not -> !requireBoolean(operand, expr.operand.span, "! 的操作数")

            TokenKind.Minus -> {
                requirePresent(operand, expr.operand.span)?.let { throw it }
                when (operand) {
                    is Long -> -operand
                    is Double -> -operand
                    else -> throw typeError("取负需要数字", operand)
                }
            }

            else -> throw EvalError("不支持的运算符『${expr.op}』", expr.span)
        }
    }

    private fun binary(expr: Expr.Binary, ctx: EvalContext): Value {
        val op = expr.op

        // 短路的三个先处理：右侧可能不该被求值
        if (op == TokenKind.Elvis) {
            val left = eval(expr.left, ctx)
            return if (left == null || left === Absent) eval(expr.right, ctx) else left
        }
        if (op == TokenKind.AndAnd || op == TokenKind.OrOr) {
            val left = requireBoolean(eval(expr.left, ctx), expr.left.span, "『$op』左侧")
            if (op == TokenKind.AndAnd && !left) return false
            if (op == TokenKind.OrOr && left) return true
            return requireBoolean(eval(expr.right, ctx), expr.right.span, "『$op』右侧")
        }

        val left = eval(expr.left, ctx)
        val right = eval(expr.right, ctx)
        requirePresent(left, expr.left.span)?.let { throw it }
        requirePresent(right, expr.right.span)?.let { throw it }

        return when (op) {
            TokenKind.EqEq -> valuesEqual(left, right)
            TokenKind.NotEq -> !valuesEqual(left, right)
            TokenKind.EqEqEq -> sameReference(left, right)
            TokenKind.NotEqEq -> !sameReference(left, right)
            TokenKind.Lt, TokenKind.LtEq, TokenKind.Gt, TokenKind.GtEq ->
                compare(op, left, right, expr.span)

            else -> arith(op, left, right, expr.span)
        }
    }

    // ── 运算 ──────────────────────────────────────────────────

    private fun arith(op: TokenKind, left: Value, right: Value, span: Span): Value {
        // 字符串拼接放在数字检查之前：显示场景里 `"共" + count` 是刚需，
        // 但只放宽左侧 —— 右侧是任意值都能转文本，左侧是数字时不让它悄悄变字符串（对齐 Kotlin）。
        if (op == TokenKind.Plus && left is String) return left + showValue(right)

        if (!isNumberValue(left) || !isNumberValue(right)) {
            throw EvalError(
                "『${symbol(op)}』的操作数必须是数字，收到${kindOf(left).display}与${kindOf(right).display}",
                span,
            )
        }

        // `/` 一律 Double（P-6）：表单里 7/2 想要 3.5，整数除法是本设计里最容易踩的坑
        if (op == TokenKind.Slash) {
            val divisor = (right as Number).toDouble()
            if (divisor == 0.0) throw EvalError("除数是 0", span)
            return (left as Number).toDouble() / divisor
        }

        if (left is Long && right is Long) {
            return when (op) {
                TokenKind.Plus -> left + right
                TokenKind.Minus -> left - right
                TokenKind.Star -> left * right
                TokenKind.Percent ->
                    if (right == 0L) throw EvalError("对 0 取余", span) else left % right

                else -> throw EvalError("不支持的运算符『${symbol(op)}』", span)
            }
        }

        val a = (left as Number).toDouble()
        val b = (right as Number).toDouble()
        return when (op) {
            TokenKind.Plus -> a + b
            TokenKind.Minus -> a - b
            TokenKind.Star -> a * b
            TokenKind.Percent -> a % b
            else -> throw EvalError("不支持的运算符『${symbol(op)}』", span)
        }
    }

    private fun compare(op: TokenKind, left: Value, right: Value, span: Span): Boolean {
        if (isNumberValue(left) && isNumberValue(right)) {
            // 两个 Long 用 Long 比：`9e15` 附近转 Double 会丢精度
            val order = if (left is Long && right is Long) {
                left.compareTo(right)
            } else {
                (left as Number).toDouble().compareTo((right as Number).toDouble())
            }
            return when (op) {
                TokenKind.Lt -> order < 0
                TokenKind.LtEq -> order <= 0
                TokenKind.Gt -> order > 0
                else -> order >= 0
            }
        }

        if (left is String && right is String) {
            val order = left.compareTo(right)
            return when (op) {
                TokenKind.Lt -> order < 0
                TokenKind.LtEq -> order <= 0
                TokenKind.Gt -> order > 0
                else -> order >= 0
            }
        }

        throw EvalError(
            "『${symbol(op)}』只能比较两个数字或两个字符串，收到${kindOf(left).display}与${kindOf(right).display}",
            span,
        )
    }

    private fun symbol(op: TokenKind): String = when (op) {
        TokenKind.Plus -> "+"
        TokenKind.Minus -> "-"
        TokenKind.Star -> "*"
        TokenKind.Slash -> "/"
        TokenKind.Percent -> "%"
        TokenKind.Lt -> "<"
        TokenKind.LtEq -> "<="
        TokenKind.Gt -> ">"
        TokenKind.GtEq -> ">="
        else -> op.toString()
    }

    // ── 调用 ──────────────────────────────────────────────────

    /**
     * 把一次调用拆成「函数 + 接收者 + 实参」，求值器与 `await` 外壳共用。
     *
     * `a.f(x)` 的接收者必须是 `a` 的**值**，而不是重新从 AST 求一遍 ——
     * 方法是一等值，`data.user.list.append` 里的 `append` 需要知道它挂在哪个数组上。
     */
    fun prepareCall(call: Expr.Call, ctx: EvalContext): PreparedCall {
        val callee = call.callee
        if (callee is Expr.Get) {
            val receiver = eval(callee.target, ctx)
            val fn = member(receiver, callee.name, ctx)
            return PreparedCall(fn, receiver, call.args.map { eval(it, ctx) }, callee.name, call.span)
        }
        val fn = eval(callee, ctx)
        return PreparedCall(fn, Absent, call.args.map { eval(it, ctx) }, describe(callee), call.span)
    }

    private fun call(call: Expr.Call, ctx: EvalContext): Value {
        val prepared = prepareCall(call, ctx)
        val fn = prepared.fn as? Function ?: throw callError(prepared)
        if (fn is SuspendFunction) {
            throw EvalError(
                "『${prepared.name}』需要挂起上下文，属性表达式里调用不了；写成 await ${prepared.name}(…) 放进脚本",
                prepared.span,
            )
        }
        return fn.invoke(prepared.target, prepared.args)
    }

    fun callError(prepared: PreparedCall): EvalError {
        val fn = prepared.fn
        return if (fn === Absent) {
            EvalError("『${prepared.name}』不存在（没注册？还是路径拼错了？）", prepared.span)
        } else {
            EvalError("『${prepared.name}』不是函数，是${kindOf(fn).display}", prepared.span)
        }
    }

    // ── 赋值 ──────────────────────────────────────────────────

    fun assignValue(stmt: Stmt.Assign, value: Value, ctx: EvalContext) {
        when (val target = stmt.target) {
            is Expr.Name -> assignName(target, value, ctx)
            is Expr.Get, is Expr.Index -> assignMember(target, value, ctx)
            else -> throw EvalError("赋值左侧只能是变量或成员访问", target.span)
        }
    }

    /**
     * `x = 2`。三步，顺序不能换：
     *
     * 1. 帧内局部 → 改它（不发事件，也不进 data）；
     * 2. 链上有绑定 → 有 `origin` 就按 origin 写回 data（`u.name = x` 能落回 `data.user.name`），
     *    没有 origin 就是纯局部量，改它；
     * 3. 都没有 → 兜底 `data.x`（K-7）。
     */
    private fun assignName(target: Expr.Name, value: Value, ctx: EvalContext) {
        val name = target.id
        val slot = ctx.frame?.lookup(name) ?: ctx.env.chainFind(name)

        if (slot != null) {
            if (slot.origin != null) {
                if (ctx.env.assign(Path.relative(name), value, WriteSource.Script)) return
                // 有 origin 说明它曾经是个数据位置；写不回去意味着位置没了（行被删），
                // 这时改槽位只会让数据与界面分叉，所以报错而不是静默
                throw EvalError("『$name』原来的位置已经不存在，无法写回", target.span)
            }
            if (!slot.mutable) throw EvalError("『$name』是 val，不能重新赋值", target.span)
            slot.setValue(value)
            return
        }

        if (!ctx.env.assign(Path.relative(name), value, WriteSource.Script)) {
            throw EvalError("『$name』没有可写目标", target.span)
        }
    }

    /**
     * `a.b = x` / `a[0] = x`。
     *
     * 先按**静态路径**写（这条路会发变更事件、也会把 `config` 的写入变成明确异常），
     * 路径不可达时再退到「直接改求值出来的容器」—— 后者对应 `val o = {a: 1}; o.a = 2`
     * 这种不落在 data 里的临时对象。
     */
    private fun assignMember(target: Expr, value: Value, ctx: EvalContext) {
        val path = staticPath(target, ctx)
        if (path != null && ctx.env.assign(path, value, WriteSource.Script)) return

        when (target) {
            is Expr.Get -> {
                val container = eval(target.target, ctx)
                if (container is DataObject) {
                    container.setMember(target.name, value)
                    return
                }
                throw EvalError("『${describe(target)}』不存在，无法赋值", target.span)
            }

            is Expr.Index -> {
                val container = eval(target.target, ctx)
                val key = eval(target.index, ctx)
                if (container is DataArray && key is Long) {
                    if (container.set(key.toInt(), value)) return
                    throw EvalError("下标 $key 越界", target.span)
                }
                if (container is DataObject) {
                    container.setMember(showValue(key), value)
                    return
                }
                throw EvalError("『${describe(target)}』不存在，无法赋值", target.span)
            }

            else -> throw EvalError("赋值目标不可写", target.span)
        }
    }

    /**
     * 把 AST 还原成一条**可写路径**。返回 `null` 表示「这个位置不是数据位置」
     * （帧内局部量、表达式结果、非字面量下标）。
     *
     * 与 `Env.read` 的读路径是镜像关系：读要沿链找值，写要沿链找**位置**。
     */
    private fun staticPath(expr: Expr, ctx: EvalContext): Path? = when (expr) {
        is Expr.Name -> when {
            ctx.env.schema.isReserved(expr.id) -> Path(expr.id, listOf(expr.id))
            ctx.frame?.lookup(expr.id) != null -> null
            else -> ctx.env.chainFind(expr.id)?.origin ?: Path(null, listOf(expr.id))
        }

        is Expr.Get -> staticPath(expr.target, ctx)?.child(expr.name)

        is Expr.Index -> {
            val key = (expr.index as? Expr.Lit)?.value
            if (key is Long) {
                staticPath(expr.target, ctx)?.withSegments(listOf(key.toString()))
            } else {
                null
            }
        }

        else -> null
    }

    // ── 校验辅助 ──────────────────────────────────────────────

    fun requireCondition(expr: Expr, ctx: EvalContext): Boolean =
        requireBoolean(eval(expr, ctx), expr.span, "if 的条件")

    /** 结构相等（`==`）与真值判断都靠它：**只接受 Boolean**，不做 truthy（P-3）。 */
    private fun requireBoolean(value: Value, span: Span, what: String): Boolean = when {
        value is Boolean -> value
        value === Absent -> throw EvalError("$what 不存在", span)
        value == null -> throw EvalError("$what 是 null，期望布尔值", span)
        else -> throw EvalError("$what 必须是布尔值，收到${kindOf(value).display}", span)
    }

    /** `Absent` / `null` 参与运算就是错误（P-4）。返回 `null` 表示没问题。 */
    private fun requirePresent(value: Value, span: Span): EvalError? = when {
        value === Absent -> EvalError("路径不存在，不能参与运算", span)
        value == null -> EvalError("null 不能参与运算", span)
        else -> null
    }

    /** 人读形式：`u.name` / `data.list[…]`。错误消息与赋值失败提示共用。 */
    fun describe(expr: Expr): String = when (expr) {
        is Expr.Name -> expr.id
        is Expr.Get -> describe(expr.target) + "." + expr.name
        is Expr.Index -> describe(expr.target) + "[…]"
        is Expr.Lit -> showValue(expr.value).ifEmpty { "字面量" }
        else -> "表达式"
    }
}

/** [Evaluator.prepareCall] 的结果：函数值、接收者、实参、用于报错的名字与位置。 */
internal class PreparedCall(
    val fn: Value,
    val target: Value,
    val args: List<Value>,
    val name: String,
    val span: Span,
)
