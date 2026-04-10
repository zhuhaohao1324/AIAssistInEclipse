package cn.com.csrcb.aiassist;

import org.eclipse.core.commands.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.*;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class AICompletionHandler extends AbstractHandler {

    /** 正在进行的补全请求，用于去重 */
    private static volatile String currentCompletionKey = null;

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

            int before = 400;
            int after  = 40;
            String contextText = ContextUtil.getContextWindow(doc, triggerDocOffset, before, after);
            int cursorInContext = ContextUtil.cursorInContext(doc, triggerDocOffset, before);

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
                        String aiResult = AIHttpClient.callAICompletion(contextText, cursorInContext, monitor);

                        // 去重检查：确保这个结果还是给当前光标位置的
                        if (!completionKey.equals(currentCompletionKey)) {
                            System.out.println("AICompletionHandler: 光标已移动，丢弃结果");
                            return Status.CANCEL_STATUS;
                        }

                        // 如果AI返回有效结果，更新显示
                        if (aiResult != null && !aiResult.trim().isEmpty() && !aiResult.startsWith("\n// AI")) {
                            // 光标已移动，丢弃结果
                            if (!isCaretStillAtDocOffset(editor, triggerDocOffset)) {
                                System.out.println("AICompletionHandler: caret moved, discard AI result");
                                return Status.CANCEL_STATUS;
                            }

                            // 在UI线程更新显示
                            final String finalAiResult = aiResult;
                            Display.getDefault().asyncExec(() -> {
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
