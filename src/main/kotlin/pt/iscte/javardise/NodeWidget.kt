package pt.iscte.javardise

import com.github.javaparser.ParseProblemException
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName
import com.github.javaparser.ast.observer.AstObserver
import com.github.javaparser.ast.observer.ObservableProperty
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.Type
import org.eclipse.swt.SWT
import org.eclipse.swt.events.*
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Text
import pt.iscte.javardise.basewidgets.TextWidget
import pt.iscte.javardise.basewidgets.TokenWidget
import pt.iscte.javardise.external.*
import javax.lang.model.SourceVersion
import kotlin.reflect.KFunction1

val DefaultConfigurationSingleton = DefaultConfiguration()
interface NodeWidget<T> {
    val configuration: Configuration get() {
        val conf by lazy { findConf(control) }
        return conf
    }
    val commands: Commands get() {
        val comm by lazy { findComm(control) }
        return comm
    }

    val node: T
    val control: Control
    fun setFocusOnCreation(firstFlag: Boolean = false)

    fun findConf(n: Control): Configuration =
        if(n is ConfigurationRoot)
            n.configuration
        else if(n.parent != null)
            findConf(n.parent)
        else
            DefaultConfigurationSingleton

    fun findComm(n: Control): Commands =
        if(n is ConfigurationRoot)
            n.commands
        else if(n.parent != null)
            findComm(n.parent)
        else
            TODO("null object commands")

    fun <T : Node> observeProperty(prop: ObservableProperty, event: (T?) -> Unit): AstObserver {
        val obs = object : PropertyObserver<T>(prop) {
            override fun modified(oldValue: T?, newValue: T?) {
                event(newValue)
            }
        }
        (node as Node).register(obs)
        return obs
    }

    fun newKeywordWidget(
        parent: Composite, keyword: String,
        alternatives: () -> List<String> = { emptyList() },
        editAtion: (String) -> Unit = {}
    ): TokenWidget {
        val w = TokenWidget(parent, keyword, alternatives, editAtion)
        w.widget.foreground = configuration.keywordColor
        return w
    }

    fun TextWidget.addFocusLostAction(
        isValid: (String) -> Boolean,
        action: (String) -> Unit
    ): FocusListener {
        val listener = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                if (isValid(widget.text)) {
                    action(widget.text)
                    if(!widget.isDisposed)
                        widget.background = configuration.backgroundColor
                } else
                    widget.background = configuration.errorColor
            }
        }
        widget.addFocusListener(listener)
        return listener
    }

    fun updateColor(text: Text) {
        if (SourceVersion.isKeyword(text.text))
            text.foreground = configuration.keywordColor
        else if (text.isNumeric)
            text.foreground = configuration.numberColor
        else
            text.foreground = configuration.foregroundColor
    }

    fun addUpdateColor(text: Text) {
        updateColor(text)
        text.addModifyListener {
            updateColor(text)
        }
    }

    fun <E: Any> Node.modifyCommand(old: E?, new: E?, setOperation: KFunction1<E?, Node>) {
        if (old != new)
            commands.execute(object : Command {
                override val target = this@modifyCommand
                override val kind: CommandKind = CommandKind.MODIFY
                override val element: E? = old

                override fun run() {
                    setOperation(new)
                }

                override fun undo() {
                    if(old is Node)
                        setOperation(old.clone() as E)
                    else
                        setOperation(old)
                }
            })
    }

    fun <N: Node> NodeList<in N>.addCommand(owner: Node, e: N, index: Int = size) {
        commands.execute(object : Command {
            override val target = owner
            override val kind: CommandKind = CommandKind.ADD
            override val element = e

            override fun run() {
                add(index, e)
            }

            override fun undo() {
                removeAt(index)
            }
        })
    }

    fun <N: Node> NodeList<in N>.changeCommand(owner: Node, e: N, index: Int = size) {
        commands.execute(object : Command {
            override val target = owner
            override val kind: CommandKind = CommandKind.MODIFY
            override val element: Node = e

            override fun run() {
                set(index, e)
            }

            override fun undo() {
                set(index, element.clone() as N)
            }
        })
    }

    fun <N: Node> NodeList<in N>.removeCommand(owner: Node, e: N) {
        commands.execute(object : Command {
            override val target = owner
            override val kind: CommandKind = CommandKind.REMOVE
            override val element = e

            var i: Int = -1
            override fun run() {
                i = indexOfIdentity(element)
                removeAt(i)
            }

            override fun undo() {
                add(i, element.clone() as N)
            }
        })
    }


}

inline fun <reified T : NodeWidget<*>> Control.findAncestor(): T? {
    var w : Control? = this
    while(w !is T && w != null && w.data !is T)
        w = w.parent

    return w as? T
}

inline fun <reified T : Node> Control.findNode(): T? {
    var w : Control? = this
    while(!(w is NodeWidget<*> && w.node is T) && w != null && w.data !is T)
        w = w.parent

    return if(w is NodeWidget<*>) w.node as T else w?.data as? T
}

fun Composite.findChild(model: Node): Control? {
    var n: Control? = null
    traverse {
        if (it is NodeWidget<*> && it.node === model) {
            n = it
            return@traverse false
        }

        if (it is Text && it.data === model) {
            n = it
            return@traverse false
        }
        return@traverse true
    }
    return n
}



val ID = Regex("[a-zA-Z][a-zA-Z0-9_]*")
val ID_CHARS = Regex("[a-zA-Z0-9_]")

val TYPE_CHARS = Regex("[a-zA-Z0-9_\\[\\]<>]")

data class Validation(val ok: Boolean, val msg: String) {
    val fail get() = !ok
}

open class Id(parent: Composite, id: NodeWithSimpleName<*>, allowedChars: Regex,
              validate: (String) -> Validation
) :
    TextWidget {
    private var readOnly: Boolean
    internal val textWidget: Text
    private var skip = false

    init {
        readOnly = false
        textWidget = TextWidget.createText(parent, id.nameAsString) { c, s ->
            skip ||
                    !readOnly && (
                    c.toString().matches(allowedChars)
                            || c == SWT.BS
                            || c == SWT.CR)
        }
       // textWidget.menu = Menu(textWidget) // prevent system menu



//        updateColor(textWidget)
//
//        textWidget.addModifyListener {
//            updateColor(textWidget)
//        }
//        textWidget.addModifyListener {
//            val validate = validate(textWidget.text)
//            if (validate.fail) {
//                textWidget.background = ERROR_COLOR()
//                textWidget.toolTipText = validate.msg
//                //textWidget.toolTipText = "Valid identifiers cannot start with a number."
//            } else if (SourceVersion.isKeyword(textWidget.text)) {
//                textWidget.background = ERROR_COLOR()
//                textWidget.toolTipText = "'${textWidget.text}' is a reserved keyword in Java." // BUG shown in types
//            } else {
//                textWidget.background = BACKGROUND_COLOR()
//                textWidget.toolTipText = ""
//            }
//        }
    }


    open fun isValid() = true

    override val widget: Text get() = textWidget

    override fun setFocus(): Boolean {
        textWidget.setFocus()
        textWidget.requestLayout()
        return true
    }


    override fun addKeyListenerInternal(listener: KeyListener) {
        textWidget.addKeyListener(listener)
    }

    fun setReadOnly() {
        readOnly = true
    }

    fun set(text: String?) {
        skip = true
        textWidget.text = text ?: ""
        skip = false
    }
}

class SimpleNameWidget<N : NodeWithSimpleName<*>>(
    parent: Composite,
    override val node: N
)
    : NodeWidget<N>, Id(parent, node, ID_CHARS, {
        s ->
    try {
        StaticJavaParser.parseSimpleName(s)
        Validation(true, "")
    } catch (e: ParseProblemException) {
        Validation(false, e.message.toString())
    }
}) {
    init {
        textWidget.data = node
        addUpdateColor(textWidget)
    }

    override val control: Control
        get() = textWidget

    override fun setFocusOnCreation(firstFlag: Boolean) {
        textWidget.setFocus()
    }

    override fun isValid(): Boolean = SourceVersion.isIdentifier(textWidget.text) && !SourceVersion.isKeyword(textWidget.text)

}

open class TypeId(parent: Composite, id: NodeWithSimpleName<*>) : Id(parent, id, TYPE_CHARS, {
        s ->
    try {
        StaticJavaParser.parseType(s)
        Validation(true, "")
    } catch (e: ParseProblemException) {
        Validation(false, e.message.toString())
    }
})

class SimpleTypeWidget<N : Type>(parent: Composite, override val node: N)
    : TypeId(parent, object : NodeWithSimpleName<N> {
    override fun getName() = SimpleName(node.asString())

    override fun setName(name: SimpleName?): N {
        // do nothing, not applicable
        return node
    }

}), NodeWidget<N> {
    init {
        textWidget.data = node
        addUpdateColor(textWidget)
    }

    override fun isValid(): Boolean = isValidType(textWidget.text)
    override fun setFocusOnCreation(firstFlag: Boolean) {
        setFocus()
    }

    override val control: Control
        get() = textWidget
}




