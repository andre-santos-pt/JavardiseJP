package pt.iscte.javardise.widgets.members

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Modifier.Keyword.*
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.SimpleName
import com.github.javaparser.ast.observer.AstObserver
import com.github.javaparser.ast.observer.AstObserverAdapter
import com.github.javaparser.ast.observer.ObservableProperty
import com.github.javaparser.ast.stmt.BlockStmt
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.ScrolledComposite
import org.eclipse.swt.layout.RowLayout
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Event
import pt.iscte.javardise.*
import pt.iscte.javardise.basewidgets.*
import pt.iscte.javardise.external.*
import pt.iscte.javardise.widgets.statements.*
import pt.iscte.javardise.widgets.statements.addInsert


val MODIFIERS = "(${
    Modifier.Keyword.values().joinToString(separator = "|") { it.asString() }
})"
val TYPE = Regex("$ID(<$ID(,$ID)*>)?(\\[\\])*")

val MEMBER_REGEX = Regex(
    "($MODIFIERS\\s+)*$TYPE\\s+$ID"
)


fun matchModifier(keyword: String) =
    Modifier(Modifier.Keyword.valueOf(keyword.uppercase()))


// TODO require compliant model
open class ClassWidget(
    parent: Composite,
    type: ClassOrInterfaceDeclaration,
    configuration: Configuration = DefaultConfigurationSingleton,
    override val commandStack: CommandStack = CommandStack.create(),
    val staticClass: Boolean = false
) :
    MemberWidget<ClassOrInterfaceDeclaration>(
        parent,
        type,
        listOf(PUBLIC, FINAL, ABSTRACT),
        configuration = configuration
    ), SequenceContainer<ClassOrInterfaceDeclaration>,
    ConfigurationRoot {

    private val keyword: TokenWidget
    override val name: Id
    override lateinit var bodyWidget: SequenceWidget
    override val closingBracket: TokenWidget

    override val body: BlockStmt? = null

    private val modelFocusObservers =
        mutableListOf<(BodyDeclaration<*>?, Node?) -> Unit>()

    private val widgetFocusObservers = mutableListOf<(Control) -> Unit>()

    private val focusListenerGlobal = { event: Event ->
        val control = event.widget as Control
        if (control.isChildOf(this@ClassWidget)) {
            widgetFocusObservers.forEach {
                it(control)
            }
            val memberWidget = control.findNode<BodyDeclaration<*>>()
            val nodeWidget = control.findNode<Node>()
            modelFocusObservers.forEach {
                it(memberWidget, nodeWidget)
            }
        }
    }

    fun setAutoScroll() {
        require(parent is ScrolledComposite)
        val scroll = parent as ScrolledComposite
        addFocusObserver { control ->
            val p = Display.getDefault().map(control, scroll, control.location)
            p.x = 0
            p.y += 10
            if (p.y < scroll.origin.y) {
                scroll.origin = p
            } else if (p.y > scroll.origin.y + scroll.bounds.height) {
                scroll.origin = p
            }
        }
    }

    enum class TypeTypes {
        CLASS, INTERFACE;
        //, ENUM;

        fun element(type: ClassOrInterfaceDeclaration) =
            when (this) {
                CLASS -> !type.isInterface
                INTERFACE -> type.isInterface
                //ENUM -> type.isEnumDeclaration
            }

        fun apply(type: ClassOrInterfaceDeclaration) =
            when (this) {
                CLASS -> type.isInterface = false
                INTERFACE -> type.isInterface = true
                //ENUM -> type.setE
            }

        fun applyReverse(type: ClassOrInterfaceDeclaration) =
            when (this) {
                CLASS -> type.isInterface = true
                INTERFACE -> type.isInterface = false
                //ENUM -> type.setE
            }
    }

    init {
        layout = ROW_LAYOUT_H_SHRINK
        val layout = RowLayout()
        layout.marginTop = 10
        layout.marginLeft = 10
        this.layout = layout
        keyword = newKeywordWidget(firstRow, "class",
            alternatives = { TypeTypes.values().map { it.name.lowercase() } }) {
            commandStack.execute(object : Command {
                override val target = node
                override val kind = CommandKind.MODIFY
                override val element =
                    TypeTypes.valueOf(it.uppercase()).element(node)

                override fun run() {
                    TypeTypes.valueOf(it.uppercase()).apply(node)
                }

                override fun undo() {
                    TypeTypes.valueOf(it.uppercase()).applyReverse(node)
                }
            })
        }
        keyword.addKeyEvent(SWT.SPACE) {
            commandStack.execute(object : Command {
                override val target = node
                override val kind = CommandKind.ADD
                override val element = Modifier(PUBLIC)

                val index = node.modifiers.size

                override fun run() {
                    node.modifiers.add(index, element)
                }

                override fun undo() {
                    node.modifiers.remove(element)
                }
            })
        }

        name = SimpleNameWidget(firstRow, type)
        name.addFocusLostAction(::isValidClassType) {
            commandStack.execute(object: Command {
                override val target: Node
                    get() = node
                override val kind: CommandKind = CommandKind.MODIFY
                override val element = node.name

                override fun run() {
                    node.setName(it)
                    node.constructors.forEach { c->
                        c.name = SimpleName(it)
                    }
                }

                override fun undo() {
                    node.setName(element)
                    node.constructors.forEach { c->
                        c.name = element
                    }
                }

            })
//            node.modifyCommand(node.nameAsString, it, node::setName)
//            node.constructors.forEach { c->
//                c.name = SimpleName(it)
//            }
        }
        bodyWidget = SequenceWidget(
            column,
            if (staticClass) 0 else configuration.tabLength,
            10
        ) { seq, _ ->
            createInsert(seq)
        }
        TokenWidget(firstRow, "{").addInsert(null, bodyWidget, false)

        node.members.forEach {
            createMember(it)
        }

        closingBracket = TokenWidget(column, "}")

        if (staticClass) {
            firstRow.dispose()
            closingBracket.dispose()
        }

        registerObservers()
        Display.getDefault().addFilter(SWT.FocusIn, focusListenerGlobal)
    }

    /**
     * Adds an observer whenever a class member (field, constructor, method) gains focus.
     * Changes of focus within a member do not trigger an event.
     */
    fun addFocusObserver(action: (BodyDeclaration<*>?, Node?) -> Unit) {
        modelFocusObservers.add(action)
    }

    fun addFocusObserver(action: (Control) -> Unit) {
        widgetFocusObservers.add(action)
    }

    /**
     * Removes a previously registered an observer.
     */
    fun removeFocusObserver(action: (BodyDeclaration<*>?, Node?) -> Unit) {
        modelFocusObservers.remove(action)
    }

    private fun registerObservers() {
        node.observeProperty<Boolean>(ObservableProperty.INTERFACE) {
            keyword.set(if (it!!) "interface" else "class")
            name.setFocus()
        }
        node.observeProperty<SimpleName>(ObservableProperty.NAME) {
            name.set(it?.id ?: "")
            name.textWidget.data = it
        }

        node.members.register(
            object : AstObserverAdapter() {
                override fun listChange(
                    observedNode: NodeList<*>,
                    change: AstObserver.ListChangeType,
                    index: Int,
                    nodeAddedOrRemoved: Node
                ) {
                    if (change == AstObserver.ListChangeType.ADDITION) {
                        val tail = index == node.members.size
                        val w =
                            createMember(nodeAddedOrRemoved as BodyDeclaration<*>)
                        if (!tail)
                            w.moveAbove(bodyWidget.findByModelIndex(index) as Control)

                        if (w is MethodWidget)
                            w.focusParameters()
                        else
                            (w as FieldWidget).focusExpressionOrSemiColon()

                    } else {
                        (bodyWidget.find(nodeAddedOrRemoved) as? Control)?.dispose()
                        bodyWidget.focusAt(index)
                    }
                    bodyWidget.requestLayout()
                }
            })
    }

    fun createMember(dec: BodyDeclaration<*>): Composite =
        when (dec) {
            is FieldDeclaration -> {
                val w = FieldWidget(bodyWidget, dec, configuration = configuration)
                w.semiColon.addInsert(w, bodyWidget, true)
                w
            }
            is MethodDeclaration, is ConstructorDeclaration -> {
                val w = MethodWidget(
                    bodyWidget,
                    dec as CallableDeclaration<*>,
                    configuration = configuration,
                    commandStack = commandStack
                )
                w.closingBracket.addInsert(w, bodyWidget, true)
                w
            }
            is ClassOrInterfaceDeclaration -> {
                val w = ClassWidget(
                    bodyWidget,
                    dec,
                    configuration = configuration,
                    commandStack = commandStack)
                w.closingBracket.addInsert(w, bodyWidget, true)
                w
            }
            else -> {
                val w = UnsupportedWidget(bodyWidget, dec)
                w.widget.addDeleteListener {
                    this@ClassWidget.node.members.removeCommand(
                        node as Node,
                        dec
                    )
                }
                w.widget.addInsert(w, bodyWidget, true)
                w
            }
        }.apply {
            if (this is MemberWidget<*>) {
                name.addKeyEvent(SWT.BS, precondition = { it.isEmpty() }) {
                    this@ClassWidget.node.members.removeCommand(
                        node as Node,
                        dec
                    )
                }
            }
        }



    override fun dispose() {
        Display.getDefault().removeFilter(SWT.FocusIn, focusListenerGlobal)
        super.dispose()
    }

    private fun createInsert(seq: SequenceWidget): TextWidget {
        val CONSTRUCTOR_REGEX =
            { Regex("($MODIFIERS\\s+)*${node.nameAsString}") }

        val insert = TextWidget.create(seq) { c, s ->
            c.toString()
                .matches(Regex("[\\w\\d\\[\\]<>]")) || c == SWT.SPACE && s.isNotEmpty() || c == SWT.BS
        }

        fun modifiers(tail: Int): NodeList<Modifier> {
            val split = insert.text.split(Regex("\\s+"))
            val modifiers = NodeList<Modifier>()
            split.subList(0, split.size - tail).forEach {
                val m = matchModifier(it)
                modifiers.add(m)
            }
            return modifiers
        }

        if (!staticClass)
            insert.addKeyEvent('(',
                precondition = { it.matches(CONSTRUCTOR_REGEX()) }) {
                val newConst =
                    ConstructorDeclaration(modifiers(1), node.nameAsString)

                val insertIndex = seq.findIndexByModel(insert.widget)
                node.members.addCommand(node, newConst, insertIndex)
                insert.delete()
            }

        val memberChars = if(staticClass) arrayOf('(') else arrayOf(';','=','(')

        insert.addKeyEvent(*memberChars.toCharArray(), precondition = {
            if(it.matches(MEMBER_REGEX)) {
                val split = it.split(Regex("\\s+"))
                isValidType(split[split.lastIndex-1]) && isValidSimpleName(split[split.lastIndex])
            }
            else
                false
        }) {
            val split = insert.text.split(Regex("\\s+"))
            val modifiers = NodeList(split.dropLast(2).map { matchModifier(it) })
            val dec = if(it.character == ';' || it.character == '=') {
                val newField = FieldDeclaration(
                    modifiers,
                    StaticJavaParser.parseType(split[split.lastIndex - 1]),
                    split.last()
                )
                if (it.character == '=')
                    newField.variables[0].setInitializer(NameExpr(Configuration.fillInToken))
                newField
            }
            else {
                val newMethod = MethodDeclaration(
                    modifiers,
                    split.last(),
                    StaticJavaParser.parseType(split[split.lastIndex - 1]),
                    NodeList()
                )
                if (node.isInterface)
                    newMethod.setBody(null)

                customizeNewMethodDeclaration(newMethod)
                newMethod
            }
            val insertIndex = seq.findIndexByModel(insert.widget)
            node.members.addCommand(node, dec, insertIndex)
            insert.delete()
        }
        insert.addFocusLostAction {
            insert.clear()
        }
        return insert
    }

    open fun customizeNewMethodDeclaration(dec: MethodDeclaration) {

    }

    override fun setFocusOnCreation(firstFlag: Boolean) {
        name.setFocus()
    }
}

