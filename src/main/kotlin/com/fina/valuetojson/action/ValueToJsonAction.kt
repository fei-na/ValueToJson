package com.fina.valuetojson.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.google.gson.GsonBuilder
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import javax.swing.JTextArea
import com.intellij.xdebugger.frame.XFullValueEvaluator
import javax.swing.Icon
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilderImpl
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.impl.DebuggerContextUtil
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.google.gson.JsonElement
import com.intellij.codeInsight.codeFragment.CodeFragment
import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.*
import javax.swing.JButton
import java.awt.Color
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.text.DefaultHighlighter
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.find.FindResult
import com.intellij.openapi.editor.FoldingModel
import com.intellij.openapi.editor.FoldRegion
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Point
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilder
import com.intellij.icons.AllIcons.Nodes.Project
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.BuildNumber
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiCodeFragment
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluatorImpl
import com.intellij.psi.JavaCodeFragmentFactory
import com.intellij.psi.PsiExpressionCodeFragment
import com.intellij.openapi.fileTypes.PlainTextFileType
import org.jetbrains.concurrency.Promise
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.engine.SuspendContextImpl


class ValueToJsonAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val debugSession = XDebuggerManager.getInstance(project).currentSession ?: return
        
        // 获取选中的文本并去除末尾的分号
        val selectedText = editor.selectionModel.selectedText?.trimEnd(';')
        if (selectedText.isNullOrEmpty()) {
            return
        }

        // 获取Java调试进程
        val javaProcess = debugSession.debugProcess as? JavaDebugProcess ?: return
        
        // 获取必要的上下文
        val context = javaProcess.debuggerSession.contextManager.context
        val suspendContext = context.suspendContext as? SuspendContextImpl ?: return
        val debugProcess = javaProcess.debuggerSession.process as DebugProcessImpl

        // 在调试器上下文中评估表达式
        debugProcess.managerThread.invoke(object : SuspendContextCommandImpl(suspendContext) {
            override fun contextAction(suspendContext: SuspendContextImpl) {
                try {
                    val frameProxy = context.frameProxy ?: return
                    
                    // 创建评估上下文
                    val evaluationContext = EvaluationContextImpl(
                        suspendContext,
                        frameProxy,
                        null as com.sun.jdi.Value?
                    )
                    
                    // 创建表达式 - 使用 fastjson 转换为 JSON
                    val jsonExpression = "com.alibaba.fastjson.JSON.toJSONString(${selectedText})"
                    val textWithImports = TextWithImportsImpl(
                        CodeFragmentKind.EXPRESSION,
                        jsonExpression
                    )
                    
                    // 在 ReadAction 中执行代码评估
                    val evaluator = ApplicationManager.getApplication().runReadAction<ExpressionEvaluator> {
                        // 获取当前 IDEA 版本
                        val currentBuild = ApplicationInfo.getInstance().build
                        
                        if (currentBuild.baselineVersion >= 241) {  // 2024.1 及以上版本
                            // 使用 JavaCodeFragmentFactory
                            val fragmentFactory = JavaCodeFragmentFactory.getInstance(project)
                            val codeFragment = fragmentFactory.createExpressionCodeFragment(
                                textWithImports.text,
                                context.sourcePosition.elementAt,
                                null,
                                true
                            ) as PsiExpressionCodeFragment
                            
                            EvaluatorBuilderImpl.getInstance()!!.build(codeFragment, context.sourcePosition)
                        } else {
                            // 使用旧版本的 API
                            val codeFragment = DefaultCodeFragmentFactory.getInstance()
                                .createCodeFragment(textWithImports, context.sourcePosition.elementAt, project)
                            EvaluatorBuilderImpl.getInstance()!!.build(codeFragment, context.sourcePosition)
                        }
                    }
                    
                    // 获取值
                    val value = evaluator.evaluate(evaluationContext)
                    
                    // 显示结果
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            // 移除首尾的引号并格式化 JSON
                            var jsonStr = value?.toString() ?: "null"
                            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"")) {
                                jsonStr = jsonStr.substring(1, jsonStr.length - 1)
                            }

                            // 使用 Gson 格式化 JSON
        val gson = GsonBuilder().setPrettyPrinting().create()
                            try {
                                val jsonElement = gson.fromJson(jsonStr, JsonElement::class.java)
                                jsonStr = gson.toJson(jsonElement)
                            } catch (e: Exception) {
                                // 如果解析失败，使用原始字符串
                            }

                            // 创建搜索相关的按钮
                            val prevButton = JButton("上一个").apply { isEnabled = false }
                            val nextButton = JButton("下一个").apply { isEnabled = false }
                            
                            // 创建折叠按钮
                            val foldAllButton = JButton("全部折叠")
                            val unfoldAllButton = JButton("全部展开")
                            
                            // 创建搜索面板
                            val searchField = JTextField(20)
                            val searchPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                                add(JLabel("搜索:"))
                                add(searchField)
                                add(prevButton)
                                add(nextButton)
                                add(foldAllButton)
                                add(unfoldAllButton)
                            }

                            // 创建编辑器
                            val editor = EditorFactory.getInstance().createEditor(
                                EditorFactory.getInstance().createDocument(jsonStr),
                                project,
                                PlainTextFileType.INSTANCE,
                                true
                            ) as EditorEx

                            // 配置编辑器
                            editor.settings.apply {
                                isLineNumbersShown = true
                                isUseSoftWraps = true
                                isFoldingOutlineShown = true
                                isAutoCodeFoldingEnabled = true
                            }

                            // 设置编辑器颜色方案
                            editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
                            
                            // 获取折叠模型
                            val foldingModel = editor.foldingModel

                            // 完全重写折叠区域的查找逻辑
                            fun findFoldRegions(): List<Pair<Int, Int>> {
                                val regions = mutableListOf<Pair<Int, Int>>()
                                val stack = mutableListOf<Int>()  // 存储开始括号的位置
                                
                                var i = 0
                                while (i < jsonStr.length) {
                                    when (val char = jsonStr[i]) {
                                        '{', '[' -> {
                                            stack.add(i)
                                        }
                                        '}', ']' -> {
                                            if (stack.isNotEmpty()) {
                                                val startPos = stack.removeAt(stack.size - 1)
                                                // 检查括号匹配
                                                if ((jsonStr[startPos] == '{' && char == '}') || 
                                                    (jsonStr[startPos] == '[' && char == ']')) {
                                                    // 添加所有层级的折叠区域
                                                    regions.add(Pair(startPos, i + 1))
                                                }
                                            }
                                        }
                                        '"' -> {
                                            // 跳过字符串内容，避免字符串中的括号干扰
                                            i++
                                            while (i < jsonStr.length && jsonStr[i] != '"') {
                                                if (jsonStr[i] == '\\') i++  // 跳过转义字符
                                                i++
                                            }
                                        }
                                    }
                                    i++
                                }
                                return regions
                            }

                            // 创建折叠区域
                            val foldRegions = findFoldRegions()
                            foldingModel.runBatchFoldingOperation {
                                // 先移除所有现有的折叠区域
                                editor.foldingModel.allFoldRegions.forEach { region ->
                                    editor.foldingModel.removeFoldRegion(region)
                                }
                                
                                // 添加新的折叠区域，按长度排序确保嵌套正确
                                foldRegions.sortedByDescending { it.second - it.first }.forEach { (start, end) ->
                                    val placeholder = when (jsonStr[start]) {
                                        '{' -> "{...}"
                                        '[' -> "[...]"
                                        else -> "..."
                                    }
                                    editor.foldingModel.addFoldRegion(start, end, placeholder)?.let { region ->
                                        region.isExpanded = true
                                    }
                                }
                            }

                            // 修改键值对的高亮逻辑，只高亮特殊类型的键名
                            val keyValuePattern = """"([^"]+)"\s*:\s*([^,}\]]+)""".toRegex()
                            keyValuePattern.findAll(jsonStr).forEach { matchResult ->
                                val (key, value) = matchResult.destructured
                                val start = matchResult.range.first
                                val colonPos = key.length + 2  // +2 是因为有引号和冒号
                                val keyStart = start + 1  // +1 跳过开始的引号
                                val keyEnd = keyStart + key.length  // key的结束位置
                                val valueStart = start + colonPos + 1  // 值的开始位置
                                val valueEnd = start + colonPos + value.length + 1  // 值的结束位置（包括逗号）
                                
                                highlightKey(editor.markupModel, keyStart, keyEnd, valueStart, valueEnd, value)
                            }

                            // 高亮空字符串和 null 值
                            val emptyPattern = """(?:""|null)""".toRegex()
                            emptyPattern.findAll(jsonStr).forEach { matchResult ->
                                val start = matchResult.range.first
                                val end = matchResult.range.last + 1
                                val markupModel = editor.markupModel
                                markupModel.addRangeHighlighter(
                                    start,
                                    end,
                                    HighlighterLayer.ADDITIONAL_SYNTAX,
                                    TextAttributes(Color(255, 68, 68), null, null, null, Font.BOLD),
                                    com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
                                )
                            }

                            // 存储搜索结果
                            var currentSearchResults = mutableListOf<FindResult>()
                            var currentResultIndex = -1

                            // 搜索函数
                            fun search(forward: Boolean = true) {
                                val searchText = searchField.text
                                if (searchText.isEmpty()) {
                                    prevButton.isEnabled = false
                                    nextButton.isEnabled = false
                                    return
                                }

                                val findModel = FindModel().apply {
                                    stringToFind = searchText
                                    isCaseSensitive = false
                                    isWholeWordsOnly = false
                                    isRegularExpressions = false
                                }

                                ApplicationManager.getApplication().runReadAction {
                                    // 获取所有匹配项
                                    currentSearchResults.clear()
                                    var offset = 0
                                    val findManager = FindManager.getInstance(project)
                                    while (true) {
                                        val result = findManager.findString(
                                            editor.document.charsSequence,
                                            offset,
                                            findModel
                                        )
                                        if (!result.isStringFound) break
                                        currentSearchResults.add(result)
                                        offset = result.endOffset
                                    }

                                    if (currentSearchResults.isNotEmpty()) {
                                        // 更新当前索引
                                        if (forward) {
                                            currentResultIndex = (currentResultIndex + 1) % currentSearchResults.size
                                        } else {
                                            currentResultIndex = if (currentResultIndex <= 0) 
                                                currentSearchResults.size - 1 
                                            else 
                                                currentResultIndex - 1
                                        }

                                        // 高亮当前结果
                                        val result = currentSearchResults[currentResultIndex]
                                        editor.caretModel.moveToOffset(result.endOffset)
                                        editor.selectionModel.setSelection(result.startOffset, result.endOffset)
                                        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
                                    }

                                    // 更新按钮状态
                                    prevButton.isEnabled = currentSearchResults.isNotEmpty()
                                    nextButton.isEnabled = currentSearchResults.isNotEmpty()
                                }
                            }

                            // 添加搜索功能
                            searchField.document.addDocumentListener(object : DocumentListener {
                                override fun insertUpdate(e: DocumentEvent) = search()
                                override fun removeUpdate(e: DocumentEvent) = search()
                                override fun changedUpdate(e: DocumentEvent) = search()
                            })

                            // 添加按钮事件
                            nextButton.addActionListener { search(true) }
                            prevButton.addActionListener { search(false) }

                            // 添加搜索快捷键
                            searchField.addKeyListener(object : KeyAdapter() {
                                override fun keyPressed(e: KeyEvent) {
                                    when (e.keyCode) {
                                        KeyEvent.VK_ENTER -> search(true)
                                        KeyEvent.VK_F3 -> {
                                            if (e.isShiftDown) search(false) else search(true)
                                        }
                                    }
                                }
                            })

                            // 添加折叠/展开按钮事件
                            foldAllButton.addActionListener {
                                foldingModel.runBatchFoldingOperation {
                                    foldingModel.allFoldRegions.forEach { region ->
                                        region.isExpanded = false
                                    }
                                }
                            }

                            unfoldAllButton.addActionListener {
                                foldingModel.runBatchFoldingOperation {
                                    foldingModel.allFoldRegions.forEach { region ->
                                        region.isExpanded = true
                                    }
                                }
                            }

                            // 添加鼠标点击折叠/展开功能
                            editor.component.addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent) {
                                    if (e.clickCount == 1) {
                                        val offset = editor.logicalPositionToOffset(
                                            editor.xyToLogicalPosition(Point(e.x, e.y))
                                        )
                                        
                                        // 检查是否点击了 { 或 [ 字符
                                        val clickedChar = if (offset < jsonStr.length) jsonStr[offset] else null
                                        if (clickedChar == '{' || clickedChar == '[') {
                                            foldingModel.runBatchFoldingOperation {
                                                foldingModel.allFoldRegions.find { region ->
                                                    region.startOffset == offset
                                                }?.let { region ->
                                                    region.isExpanded = !region.isExpanded
                                                }
                                            }
                                        }
                                    }
                                }
                            })

                            // 创建主面板
                            val mainPanel = JPanel(BorderLayout()).apply {
                                add(searchPanel, BorderLayout.NORTH)
                                add(editor.component, BorderLayout.CENTER)
                            }

                            // 显示弹出窗口
                            JBPopupFactory.getInstance()
                                .createComponentPopupBuilder(mainPanel, searchField)
                                .setTitle("JSON Value")
                                .setResizable(true)
                                .setMovable(true)
                                .setRequestFocus(true)
                                .setFocusable(true)
                                .setMinSize(Dimension(600, 400))
                                .createPopup()
                                .showInBestPositionFor(e.getData(CommonDataKeys.EDITOR)!!)

                        } catch (ex: Exception) {
                            Messages.showErrorDialog(
                                project,
                                "得不到变量的值喵...变不了了喵...\n: ${ex.message}",
                                "错误"
                            )
                        }
                    }
                } catch (ex: EvaluateException) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "这...这是什么喵！你确定这是可以变成json的变量喵？！\n: ${ex.message}",
                            "错误"
                        )
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val debugSession = project?.let { XDebuggerManager.getInstance(it).currentSession }
        
        // 只在调试模式下且有选中文本时显示
        e.presentation.isVisible = debugSession != null && editor?.selectionModel?.hasSelection() == true
    }

    // 在类中添加 JSON 格式化和高亮的函数
    private fun formatJson(json: String): String {
        val indent = "    "
        val result = StringBuilder()
        var indentLevel = 0
        var inQuotes = false
        
        for (char in json) {
            when (char) {
                '"' -> {
                    result.append(char)
                    if (json[json.indexOf(char) - 1] != '\\') {
                        inQuotes = !inQuotes
                    }
                }
                '{', '[' -> {
                    result.append(char)
                    if (!inQuotes) {
                        indentLevel++
                        result.append("\n").append(indent.repeat(indentLevel))
                    }
                }
                '}', ']' -> {
                    if (!inQuotes) {
                        indentLevel--
                        result.append("\n").append(indent.repeat(indentLevel))
                    }
                    result.append(char)
                }
                ',' -> {
                    result.append(char)
                    if (!inQuotes) {
                        result.append("\n").append(indent.repeat(indentLevel))
                    }
                }
                else -> result.append(char)
            }
        }
        
        return result.toString()
    }

    // 修改颜色常量
    private val BOOL_KEY_COLOR = Color(0, 120, 255)    // 蓝色
    private val STRING_KEY_COLOR = Color(0, 180, 0)        // 绿色
    private val FLOAT_KEY_COLOR = Color(255, 140, 0)     // 橙色
    private val INT_KEY_COLOR = Color(200, 180, 0)       // 黄色
    private val DATE_KEY_COLOR = Color(139, 69, 19)      // 棕色

    // 修改高亮逻辑
    private fun highlightKey(markupModel: MarkupModel, keyStart: Int, keyEnd: Int, valueStart: Int, valueEnd: Int, value: String) {
        // 判断值的类型并设置相应的颜色
        val color = when {
            // 布尔值
            value.trim().matches("""true|false""".toRegex()) -> BOOL_KEY_COLOR
            
            // 日期（时间戳，13位数字）
            value.trim().matches("""\d{13}""".toRegex()) -> DATE_KEY_COLOR
            
            // 浮点数
            value.trim().matches("""-?\d*\.\d+""".toRegex()) -> FLOAT_KEY_COLOR
            
            // 整数（非日期的数字）
            value.trim().matches("""-?\d+""".toRegex()) && !value.trim().matches("""\d{13}""".toRegex()) -> 
                INT_KEY_COLOR
            
            else -> null
        }
        
        // 高亮整个字段（包括键和值）
        if (color != null) {
            markupModel.addRangeHighlighter(
                keyStart - 1,  // 包括开始的引号
                valueEnd + 1,  // 包括结束的逗号
                HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes(color, null, null, null, Font.BOLD),
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
            )
        }
    }
} 