package cn.com.csrcb.aiassist;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class AICompletionHandler extends AbstractHandler {

    /** 正在进行的补全请求，用于去重 */
    private static volatile String currentCompletionKey = null;

    /** AST 上下文信息 */
    private ASTAnalyzer.EnhancedContext astContext;

    /** Import 管理器 */
    private ImportManager importManager = new ImportManager();

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {

        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        if (!(editorPart instanceof ITextEditor))
            return null;
        ITextEditor editor = (ITextEditor) editorPart;

        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        ITextSelection sel = (ITextSelection) editor.getSelectionProvider().getSelection();
        int offset = sel.getOffset();

        // 1) 有选中：进入"提问/解释/生成"模式
        if (sel.getLength() > 0) {
            String selected = sel.getText();

            AIAskDialog.open(HandlerUtil.getActiveShell(event), selected, (ask, dialog) -> {

                String prompt = PromptTemplates.buildSelectionTaskPrompt(selected, ask.userQuestion, ask.mode);

                Job job = new Job("AI Assist") {
                    @Override
                    protected IStatus run(IProgressMonitor monitor) {

                        String answer = AIHttpClient.callAICachedWithTimeout(prompt, "ask_" + ask.mode.name(), 60_000, monitor);

                        Display.getDefault().asyncExec(() -> {
                            dialog.setSendEnabled(true);

                            if (answer == null || answer.trim().isEmpty()) {
                                dialog.setStatus("超过60秒未返回，请稍后重试。");
                                return;
                            }

                            dialog.setStatus("完成。");
                            AIResultDialog.openForSelection(editor, sel.getOffset(), sel.getLength(), answer, ask.mode);
                            dialog.close();
                        });

                        return Status.OK_STATUS;
                    }
                };
                job.setUser(true);
                job.schedule();
            });

            return null;
        }

        // 2) 没选中：进入 ghost 补全模式（类似通义灵码：缓存优先 + 异步优化）
        try {
            final int triggerDocOffset = offset;

            // 保存触发时的caret位置
            final int[] triggerWidgetCaretBox = new int[] { -1 };
            Display.getDefault().syncExec(() -> {
                StyledText st0 = GhostCompletionManager.getStyledText(editor);
                if (st0 != null && !st0.isDisposed()) {
                    triggerWidgetCaretBox[0] = st0.getCaretOffset();
                }
            });
            final int triggerWidgetCaret = triggerWidgetCaretBox[0];

            int before = 800;
            int after  = 80;
            String contextText = ContextUtil.getContextWindow(doc, triggerDocOffset, before, after);
            int cursorInContext = ContextUtil.cursorInContext(doc, triggerDocOffset, before);

            // ===== 增强：获取 AST 上下文 =====
            astContext = getASTContext(editor, triggerDocOffset);

            // 使用语义化缓存Key，提高缓存命中率
            final String completionKey = AIHttpClient.generateSemanticCacheKey(contextText, cursorInContext);

            // 如果有正在进行的相同请求，跳过
            if (completionKey.equals(currentCompletionKey)) {
                System.out.println("AICompletionHandler: 请求去重，跳过");
                return null;
            }
            currentCompletionKey = completionKey;

            // ===== 步骤1：先检查缓存，命中则立即显示 =====
            String cachedResult = AIHttpClient.getCachedResult(completionKey);
            if (cachedResult != null && !cachedResult.isEmpty()) {
                System.out.println("AICompletionHandler: 缓存命中，立即显示！");
                Display.getDefault().asyncExec(() -> {
                    showGhostCompletion(editor, triggerDocOffset, triggerWidgetCaret, cachedResult);
                });
                // 继续后台调用AI更新缓存
            } else {
                // 缓存未命中，先尝试本地语法分析（立即响应）
                System.out.println("AICompletionHandler: 缓存未命中，尝试本地语法分析...");
                String localResult = LocalCodeAnalyzer.analyze(contextText, cursorInContext);
                if (localResult != null && !localResult.isEmpty()) {
                    System.out.println("AICompletionHandler: 本地分析成功，立即显示！");
                    Display.getDefault().asyncExec(() -> {
                        showGhostCompletion(editor, triggerDocOffset, triggerWidgetCaret, localResult);
                    });
                }
            }

            // ===== 步骤2：后台调用AI（无论缓存是否命中都调用，用于更新/优化结果） =====
            Job job = new Job("AI Assist Completion") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    try {
                        // 使用增强的上下文调用 AI
                        String aiResult = callAIWithEnhancedContext(editor, contextText, cursorInContext, triggerDocOffset, monitor);

                        // 去重检查：确保这个结果还是给当前光标位置的
                        if (!completionKey.equals(currentCompletionKey)) {
                            System.out.println("AICompletionHandler: 光标已移动，丢弃结果");
                            return Status.CANCEL_STATUS;
                        }

                        // 如果AI返回有效结果，在UI线程检查光标并显示
                        if (aiResult != null && !aiResult.trim().isEmpty() && !aiResult.startsWith("\n// AI")) {
                            final String finalAiResult = aiResult;

                            // 在UI线程中检查光标位置并显示
                            Display.getDefault().asyncExec(() -> {
                                // 再次检查光标是否还在原位置
                                ITextSelection sel = (ITextSelection) editor.getSelectionProvider().getSelection();
                                int currentOffset = sel.getOffset();
                                if (Math.abs(currentOffset - triggerDocOffset) > 1) {
                                    System.out.println("AICompletionHandler: caret moved, discard AI result");
                                    return;
                                }

                                showGhostCompletion(editor, triggerDocOffset, triggerWidgetCaret, finalAiResult);
                            });
                        }

                    } catch (Exception e) {
                        System.out.println("AICompletionHandler: AI调用失败 " + e.getMessage());
                    }
                    return Status.OK_STATUS;
                }
            };
            job.setUser(false); // 不弹出进度条，更流畅
            job.schedule();

        } catch (Exception e) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "AI Assist", "执行失败：" + e.getMessage());
        }

        return null;
    }

    /**
     * 获取 AST 上下文
     */
    private ASTAnalyzer.EnhancedContext getASTContext(ITextEditor editor, int offset) {
        try {
            IEditorPart javaEditor = JavaUI.openInEditor(
                    JavaCore.create(editor.getEditorInput().getAdapter(org.eclipse.core.resources.IFile.class)));

            if (javaEditor != null) {
                ICompilationUnit cu = (ICompilationUnit) javaEditor.getEditorInput().getAdapter(ICompilationUnit.class);
                if (cu != null) {
                    return ASTAnalyzer.getEnhancedContext(cu, offset);
                }
            }
        } catch (Exception e) {
            System.out.println("AICompletionHandler: 获取AST上下文失败 " + e.getMessage());
        }
        return null;
    }

    /**
     * 使用增强上下文调用 AI
     */
    private String callAIWithEnhancedContext(ITextEditor editor, String contextText,
                                            int cursorInContext, int triggerDocOffset,
                                            IProgressMonitor monitor) throws Exception {
        String enhancedPrompt;

        if (astContext != null) {
            // 使用增强的 prompt
            StringBuilder enhanced = new StringBuilder();
            enhanced.append("【代码上下文增强信息】\n");

            // 当前类信息
            if (astContext.currentClass.name != null) {
                enhanced.append("// 当前类: ").append(astContext.currentClass.qualifiedName).append("\n");

                if (astContext.currentClass.typeHierarchy.superclass != null) {
                    enhanced.append("// 父类: ").append(astContext.currentClass.typeHierarchy.qualifiedSuperclass).append("\n");
                }

                if (!astContext.currentClass.typeHierarchy.interfaces.isEmpty()) {
                    enhanced.append("// 实现的接口: ").append(String.join(", ", astContext.currentClass.typeHierarchy.qualifiedInterfaces)).append("\n");
                }
            }

            // 当前方法
            if (astContext.currentMethodSignature != null) {
                enhanced.append("// 当前方法: ").append(astContext.currentMethodSignature).append("\n");
            }

            // 可用变量
            if (!astContext.availableVariables.isEmpty()) {
                enhanced.append("// 可用局部变量:\n");
                for (ASTAnalyzer.VariableInfo var : astContext.availableVariables) {
                    enhanced.append("//   ").append(var.type).append(" ").append(var.name).append("\n");
                }
            }

            // 类字段
            if (!astContext.classFields.isEmpty()) {
                enhanced.append("// 类字段:\n");
                for (String field : astContext.classFields) {
                    enhanced.append("//   ").append(field).append("\n");
                }
            }

            // 类方法
            if (!astContext.classMethods.isEmpty()) {
                enhanced.append("// 类方法:\n");
                int count = 0;
                for (String method : astContext.classMethods) {
                    // 排除当前方法自身
                    if (!method.startsWith(astContext.currentClass.name + "(") || count < astContext.classMethods.size() - 1) {
                        enhanced.append("//   ").append(method).append("\n");
                    }
                    count++;
                    if (count > 15) {
                        enhanced.append("//   ... (更多方法)\n");
                        break;
                    }
                }
            }

            enhanced.append("\n【待补全代码】\n");
            enhanced.append(contextText);
            enhanced.append("\n\n请根据上述上下文信息，生成准确的代码补全。直接返回补全内容，不需要解释。");

            enhancedPrompt = enhanced.toString();
        } else {
            // 降级使用原始上下文
            enhancedPrompt = PromptTemplates.buildBasicCompletionPrompt(contextText, cursorInContext);
        }

        // 调用 AI
        return AIHttpClient.callAIWithPrompt(enhancedPrompt, monitor);
    }

    /**
     * 显示ghost补全（统一方法）
     */
    private void showGhostCompletion(ITextEditor editor, int triggerDocOffset,
                                     int triggerWidgetCaret, String aiResult) {
        if (aiResult == null || aiResult.trim().isEmpty())
            return;

        StyledText st = GhostCompletionManager.getStyledText(editor);
        if (st == null || st.isDisposed())
            return;

        // 光标已移动则取消
        if (!isCaretStillAtDocOffset(editor, triggerDocOffset)) {
            System.out.println("Ghost canceled: caret moved");
            return;
        }

        // 展示ghost
        GhostCompletionManager.show(editor, triggerDocOffset, aiResult);

        // 恢复光标位置
        restoreCaret(editor, st, triggerDocOffset, triggerWidgetCaret);

        // 延迟再恢复一次，防止被Eclipse覆盖
        Display.getDefault().timerExec(20, () -> {
            if (!st.isDisposed()) {
                restoreCaret(editor, st, triggerDocOffset, triggerWidgetCaret);
            }
        });
    }

    private static void restoreCaret(ITextEditor editor, StyledText st, int triggerDocOffset, int triggerWidgetCaret) {
        try {
            editor.getSelectionProvider().setSelection(new TextSelection(triggerDocOffset, 0));
        } catch (Exception ignore) {}

        try {
            if (triggerWidgetCaret >= 0 && triggerWidgetCaret <= st.getCharCount()) {
                st.setCaretOffset(triggerWidgetCaret);
                st.setSelection(triggerWidgetCaret);
            }
        } catch (Exception ignore) {}
    }

    private static boolean isCaretStillAtDocOffset(ITextEditor editor, int triggerOffset) {
        ITextSelection sel = (ITextSelection) editor.getSelectionProvider().getSelection();
        int currentOffset = sel.getOffset();
        return Math.abs(currentOffset - triggerOffset) <= 1;
    }
}
