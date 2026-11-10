package cn.changjiahong.composexmlform.engine.value

import cn.changjiahong.composexmlform.engine.diag.EvalError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 函数工厂。参数个数校验是 `Func` 不携带签名（X-2）的代价补偿，所以消息必须能自查。
 */
class FnTest {

    @Test
    fun of1PassesSingleArgument() {
        val fn = Fn.of1 { it }
        assertEquals("x", fn.invoke(null, listOf("x")))
    }

    @Test
    fun arityMismatchReportsExpectedAndActual() {
        val fn = Fn.of1 { it }
        val error = assertFailsWith<EvalError> { fn.invoke(null, emptyList()) }
        assertTrue(error.message!!.contains("期望 1 个参数"), error.message!!)
        assertTrue(error.message!!.contains("收到 0 个"), error.message!!)
    }

    @Test
    fun of2PassesBothArguments() {
        val fn = Fn.of2 { a, b -> "${a}${b}" }
        assertEquals("ab", fn.invoke(null, listOf("a", "b")))
    }

    @Test
    fun noArgIgnoresTarget() {
        val fn = Fn.noArg { "done" }
        assertEquals("done", fn.invoke(dataObjectOf(), emptyList()))
    }

    @Test
    fun ofExposesTarget() {
        val fn = Fn.of { target, _ -> target }
        val target = dataObjectOf()
        assertEquals(target, fn.invoke(target, emptyList()))
    }

    @Test
    fun suspendFunctionRefusesSyncInvocation() {
        val fn = Fn.suspendOf1 { it }
        assertFailsWith<IllegalStateException> { fn.invoke(null, listOf("x")) }
    }
}
