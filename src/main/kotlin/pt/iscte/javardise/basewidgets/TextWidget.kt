package pt.iscte.javardise.basewidgets

import org.eclipse.swt.SWT
import org.eclipse.swt.events.*
import org.eclipse.swt.widgets.*
import pt.iscte.javardise.*
import pt.iscte.javardise.widgets.members.MemberWidget
import pt.iscte.javardise.widgets.statements.StatementWidget

interface TextWidget {
    val widget: Text

    var text: String
        get() = widget.text
        set(value) {
            widget.text = value
        }

    val isEmpty: Boolean
        get() = widget.text.isBlank()

    val caretPosition: Int
        get() = widget.caretPosition

    val selectionCount: Int
        get() = widget.selectionCount

    val isAtBeginning: Boolean
        get() = widget.caretPosition == 0 && widget.selectionCount == 0

    val isAtEnd: Boolean
        get() = widget.caretPosition == widget.text.length

    val isSelected: Boolean
        get() = widget.selectionCount == widget.text.length

    val isModifiable: Boolean
        get() = widget.editable


    fun delete() {
        //removeKeyListeners()
        //removeFocusOutListeners()
        widget.dispose()
    }
    fun setAtLeft() = widget.setSelection(0, 0)
    fun setAtRight() = widget.setSelection(widget.text.length)
    fun setFocus() = widget.setFocus()

    fun moveBelowInternal(control: Control) = widget.moveBelow(control)
    fun moveAboveInternal(control: Control) = widget.moveAbove(control)
    fun layoutInternal() = widget.requestLayout()

    fun setToolTip(text: String) {
        widget.toolTipText = text
    }

    fun clear(text: String = "") {
        widget.text = text
    }

    fun addKeyEvent(
        vararg chars: Char,
        precondition: (String) -> Boolean = { true },
        action: (KeyEvent) -> Unit
    ): KeyListener {
        val l = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!widget.isDisposed && precondition(widget.text) && chars.contains(e.character)) {
                    action(e)
                    e.doit = false
                }
            }
        }
        widget.addKeyListener(l)
        widget.toolTipText += chars.joinToString() + " do something\n"
        return l
    }

    fun addKeyListenerInternal(listener: KeyListener)

    fun addFocusLostAction(action: () -> Unit): FocusListener {
        val listener = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                action()
            }
        }
        widget.addFocusListener(listener)
        return listener
    }

    fun addFocusLostAction(isValid: (String) -> Boolean, action: () -> Unit): FocusListener {
        val listener = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                if(isValid(widget.text)) {
                    action()
                    widget.background = BACKGROUND_COLOR()
                }
                else
                    widget.background = ERROR_COLOR()
            }
        }
        widget.addFocusListener(listener)
        return listener
    }

    fun addDeleteListener(action: () -> Unit) =
        addKeyEvent(SWT.BS, precondition = { widget.text.isEmpty() && widget.caretPosition == 0 }) {
            action()
        }

    fun removeFocusOutListeners() {
        widget.getListeners(SWT.FocusOut).forEach {
            widget.removeListener(SWT.FocusOut, it)
        }
    }

    fun removeKeyListeners() {
        widget.getListeners(SWT.KeyDown).forEach {
            widget.removeListener(SWT.KeyDown, it)
        }
    }

    companion object {
        @JvmStatic
        fun createText(
            parent: Composite,
            text: String,
            accept: ((Char, String) -> Boolean)? = null
        ): Text {
            val t = Text(parent, SWT.NONE)
            t.background = parent.background
            t.foreground = parent.foreground
            t.text = text
            t.font = CODE_FONT()
            t.cursor = Display.getCurrent().getSystemCursor(SWT.CURSOR_HAND)

            t.menu = Menu(t)

            accept?.let {
                t.addVerifyListener {
                    it.doit = accept(it.character, t.text)
                }
            }

            t.addTraverseListener { e ->
                if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)
                    e.doit = true
            }
            t.addFocusListener(FOCUS_SELECTALL)
            //t.addMouseTrackListener(MOUSE_FOCUS)
            t.addModifyListener(MODIFY_PACK)
            t.addKeyListener(LISTENER_ARROW_KEYS)  // TODO BUGS

            return t
        }


        @JvmStatic
        fun create(
            parent: Composite,
            text: String = "",
            accept: ((Char, String) -> Boolean) = { _: Char, _: String -> false }
        ): TextWidget {
            return object : TextWidget {

                var acceptFlag = false

                val w: Text = createText(parent, text) { c, s ->
                    acceptFlag || accept(c, s)
                }.apply {
                    background = parent.background
                    foreground = parent.foreground
                }

                override val widget: Text
                    get() = w

                override fun clear(text: String) {
                    acceptFlag = true
                    w.text = text
                    acceptFlag = false
                }

                override var text: String
                    get() = super.text
                    set(value) {
                        acceptFlag = true
                        w.text = value
                        acceptFlag = false
                    }

                override fun addKeyListenerInternal(listener: KeyListener) {
                    widget.addKeyListener(listener)
                }

                override fun addFocusLostAction(action: () -> Unit): FocusListener {
                    val listener = object : FocusAdapter() {
                        override fun focusLost(e: FocusEvent?) {
                            action()
                        }
                    }
                    widget.addFocusListener(listener)
                    return listener
                }
            }
        }

        private val MODIFY_PACK =
            ModifyListener { e -> //			((Control) e.widget).setLayoutData(new RowData(SWT.DEFAULT, SWT.DEFAULT));
                (e.widget as Control).pack()
                (e.widget as Control).requestLayout()
            }

        inline fun <reified T> Control.findAncestorOfType(): T? {
            var w: Control? = this.parent
            while (w !is T && w != null && w.data !is T)
                w = w.parent

            return w as? T
        }

        val Text.isAtBeginning: Boolean
            get() = caretPosition == 0 && selectionCount == 0

        val Text.isAtEnd: Boolean
            get() = caretPosition == text.length

        // TODO handle insert cursor
        // TODO handle else special case
        private val LISTENER_ARROW_KEYS: KeyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val text = Display.getDefault().focusControl
                if (text is Text) {
                    if (e.keyCode == SWT.ARROW_RIGHT && (!text.editable || text.isAtEnd)) {
                        text.traverse(SWT.TRAVERSE_TAB_NEXT)
                        e.doit = false
                    } else if (e.keyCode == SWT.ARROW_LEFT && (!text.editable || text.isAtBeginning)) {
                        text.traverse(SWT.TRAVERSE_TAB_PREVIOUS)
                        e.doit = false
                    } else if (e.keyCode == SWT.ARROW_UP || e.keyCode == SWT.ARROW_DOWN) {
                        val sw = text.findAncestorOfType<StatementWidget<*>>() ?: text.findAncestorOfType<MemberWidget<*>>()

                        if (sw != null) {
                            val index = sw.parent.children.indexOf(sw)
                            if (e.keyCode == SWT.ARROW_UP) {
                                if (sw is SequenceContainer && text == sw.closingBracket.widget)
                                    sw.focusLast()
                                else if (index > 0) {
                                    val prev = sw.parent.children[index - 1]
                                    if(prev is SequenceContainer)
                                        prev.focusLast()
                                    else
                                        prev.setFocus()
                                }
                                else {
                                    val levelUp = sw.findAncestorOfType<SequenceContainer>()
                                    levelUp?.setFocus()
                                }

                            } else {
                                if (sw is SequenceContainer && text != sw.closingBracket.widget) {
                                    if(sw.body?.isEmpty() == true)
                                        sw.closingBracket.setFocus()
                                    else
                                        sw.body?.setFocus()
                                }
                                else if (index + 1 < sw.parent.children.size)
                                    sw.parent.children[index + 1].setFocus()
                                else {
                                    val levelUp = sw.findAncestorOfType<SequenceContainer>()
                                    levelUp?.closingBracket?.setFocus()
                                }
                            }
                        }

                    }
                }
            }
        }

        private val FOCUS_SELECTALL: FocusListener = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                (e.widget as Text).selectAll()
            }
        }

        private val MOUSE_FOCUS: MouseTrackListener = object : MouseTrackAdapter() {
            override fun mouseEnter(e: MouseEvent) {
                if (Configuration.focusFollowsMouse) {
                    val c = e.widget as Control
                    c.foreground = Display.getDefault().getSystemColor(SWT.COLOR_YELLOW)
                    c.requestLayout()
                }
            }

            override fun mouseExit(e: MouseEvent) {
                val c = e.widget as Control
                if (Configuration.focusFollowsMouse) {
                    c.foreground = Display.getDefault().getSystemColor(SWT.COLOR_WHITE)
                    c.requestLayout()
                }
            }
        }
    }
}