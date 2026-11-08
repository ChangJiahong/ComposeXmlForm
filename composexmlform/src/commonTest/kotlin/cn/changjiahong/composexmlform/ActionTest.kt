package cn.changjiahong.composexmlform

import cn.changjiahong.composexmlform.api.ActionHandler
import cn.changjiahong.composexmlform.api.ActionScope
import cn.changjiahong.composexmlform.api.Actions
import cn.changjiahong.composexmlform.api.BindableData
import cn.changjiahong.composexmlform.api.actions
import cn.changjiahong.composexmlform.binding.ActionRef
import cn.changjiahong.composexmlform.core.Code
import cn.changjiahong.composexmlform.core.Diagnostic
import cn.changjiahong.composexmlform.core.XmlUiException
import cn.changjiahong.composexmlform.node.AttrSite
import cn.changjiahong.composexmlform.node.Attrs
import cn.changjiahong.composexmlform.node.ColumnNode
import cn.changjiahong.composexmlform.node.Node
import cn.changjiahong.composexmlform.node.ValAttr
import cn.changjiahong.composexmlform.node.valAttr
import cn.changjiahong.composexmlform.parser.EngineRegistry
import cn.changjiahong.composexmlform.renderer.readIn
import cn.changjiahong.composexmlform.runtime.ActionRegistry
import cn.changjiahong.composexmlform.runtime.DynamicLayoutRuntime
import cn.changjiahong.composexmlform.runtime.runAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 动作链路：属性化（R3）+ 执行上下文（R9）。
 *
 * 断言的落点是「错误发生在哪一刻」，这是这套设计唯一容易被改坏的地方：
 * 未注册的动作在**运行期构造时**就抛（不是点击时），绑定写法的动作在**解析期**抛，
 * 而 handler 里的异常**永不外抛**、只进上报通道。
 */
class ActionTest {

    private fun registryWithChip(): EngineRegistry {
        val registry = ComposeXmlForm.createRegistry()
        registry.nodes.register("Chip") { ChipNode(it) }
        return registry
    }

    private fun runtime(
        xml: String,
        actions: Actions = Actions.Empty,
        registry: EngineRegistry = registryWithChip(),
        data: Map<String, Any?> = emptyMap(),
    ) = DynamicLayoutRuntime(xml, BindableData.of(data), emptyMap(), actions, registry)

    // ---------------- R3：动作就是属性 ----------------

    /**
     * 自定义节点的动作属性名由节点自己定（`onTap` 而不是 `action`），
     * 报错必须指向真实属性名和真实标签 —— 这是 R3 要解决的第一个问题。
     */
    @Test
    fun unregisteredActionOnCustomNodeIsRejectedAtConstruction() {
        val error = assertFailsWith<XmlUiException> {
            runtime(
                """<Column>
                    <Chip label="a" onTap="pick()"/>
                  </Column>""",
            )
        }

        assertEquals("onTap", error.attribute, "报错属性名不能是 action")
        assertEquals("Chip", error.tag)
        assertEquals(Code.UnregisteredAction, error.code)
        assertTrue(error.line >= 2, "应带上 Chip 所在行号，实际 ${error.line}")
        assertTrue(error.message!!.contains("pick()"), error.message)
    }

    @Test
    fun registeredActionExposesRef() {
        val rt = runtime(
            """<Column><Chip label="a" onTap="pick()"/></Column>""",
            actions = Actions.of(mapOf("pick" to {})),
        )

        val chip = (rt.layout.root as ColumnNode).children.single() as ChipNode
        val ref = chip.onTap!!.readIn(rt.rootOwner)

        assertNotNull(ref)
        assertEquals("pick", ref.name)
        assertEquals("pick()", ref.raw)
    }

    /** `onTap="{{fn}}"`：动作不能被数据驱动，必须在解析期就拒绝。 */
    @Test
    fun boundActionIsRejectedAtParse() {
        val registry = registryWithChip()

        val error = assertFailsWith<XmlUiException> {
            registry.parse("""<Column><Chip onTap="{{fn}}"/></Column>""")
        }

        assertEquals("onTap", error.attribute)
        assertTrue(error.message!!.contains("不能绑定"), error.message)
    }

    /**
     * 校验数据来自解析期的收集清单，而不是遍历节点树 ——
     * 所以分支里的引用照样被查到（老实现只看 `ButtonNode`，这里 `If` 包一层就漏）。
     */
    @Test
    fun actionInsideIfBranchIsValidatedToo() {
        val error = assertFailsWith<XmlUiException> {
            runtime("""<If test="true"><Then><Button action="nope()"/></Then></If>""")
        }

        assertEquals("Button", error.tag)
        assertEquals("action", error.attribute)
        assertTrue(error.message!!.contains("nope()"), error.message)
    }

    @Test
    fun actionsDslRejectsDuplicateAndIllegalNames() {
        val duplicate = assertFailsWith<XmlUiException> {
            actions {
                on("submit") {}
                on("submit") {}
            }
        }
        assertTrue(duplicate.message!!.contains("重复注册"), duplicate.message)

        val illegal = assertFailsWith<XmlUiException> {
            actions { on("submit-form") {} }
        }
        assertTrue(illegal.message!!.contains("非法"), illegal.message)
    }

    @Test
    fun actionsComposeWithPlus() {
        val common = Actions.of(mapOf("save" to {}))
        val merged = common + actions { on("only") {} }

        assertEquals(setOf("save", "only"), merged.names)
    }

    // ---------------- R9：动作可以挂起，异常不外抛 ----------------

    @Test
    fun handlerSuspendsThenWritesBack() = runBlocking {
        val data = BindableData.of(mapOf("status" to "idle"))
        val registry = ActionRegistry()
        registry.registerAll(
            actions {
                on("submit") {
                    this.data.set("status", "running")
                    delay(10)
                    this.data.set("status", "done")
                }
            },
        )

        runAction(
            registry = registry,
            ref = ActionRef("submit", "submit()"),
            site = AttrSite("Button", "action", 7),
            scope = ActionScope(data, "submit"),
            report = {},
        )

        assertEquals("done", data.get("status"))
    }

    @Test
    fun handlerFailureIsReportedNotThrown() = runBlocking {
        val data = BindableData.of(emptyMap())
        val registry = ActionRegistry()
        registry.registerAll(
            Actions.ofHandlers(
                mapOf("boom" to ActionHandler { throw IllegalStateException("服务端 500") }),
            ),
        )
        val reported = mutableListOf<Diagnostic>()

        runAction(
            registry = registry,
            ref = ActionRef("boom", "boom()"),
            site = AttrSite("Chip", "onTap", 12),
            scope = ActionScope(data, "boom"),
            report = { reported += it },
        )

        val diagnostic = reported.single()
        assertEquals(Code.ActionFailed, diagnostic.code)
        assertEquals("Chip", diagnostic.tag)
        assertEquals("onTap", diagnostic.attribute)
        assertEquals(12, diagnostic.line)
        assertTrue(diagnostic.message.contains("500"), diagnostic.message)
    }

    /** 构造期注册过、运行期被移除：同样只上报，不给 `onClick` 抛。 */
    @Test
    fun missingHandlerAtRuntimeIsReported() = runBlocking {
        val reported = mutableListOf<Diagnostic>()

        runAction(
            registry = ActionRegistry(),
            ref = ActionRef("gone", "gone()"),
            site = AttrSite("Button", "action", 3),
            scope = ActionScope(BindableData.of(emptyMap()), "gone"),
            report = { reported += it },
        )

        assertEquals(Code.UnregisteredAction, reported.single().code)
    }
}

/**
 * 用户侧自定义节点的样子：一行 `actionOf` 换来「白名单 / 坐标 / 校验 / 报错」全套。
 * 属性名 `onTap` 完全是这个节点自己的约定，引擎不认识它。
 */
private class ChipNode(
    val label: ValAttr<String> = valAttr(""),
    val onTap: ValAttr<ActionRef>? = null,
    id: String? = null,
    line: Int = 0,
) : Node(id, line) {

    constructor(attrs: Attrs) : this(
        label = attrs.valOf("label") ?: valAttr(""),
        onTap = attrs.actionOf("onTap"),
        id = attrs.id,
        line = attrs.line,
    )
}
