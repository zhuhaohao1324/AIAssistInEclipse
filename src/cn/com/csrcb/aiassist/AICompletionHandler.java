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

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {

        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        if (!(editorPart instanceof ITextEditor))
            return null;
        ITextEditor editor = (ITextEditor) editorPart;

        IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        ITextSelection sel = (ITextSelection) editor.getSelectionProvider().getSelection();
        int offset = sel.getOffset();

        // 1) 有选中：进入“提问/解释/生成”模式（保持你原来的逻辑）
        if (sel.getLength() > 0) {
            String selected = sel.getText();

            AIAskDialog.open(HandlerUtil.getActiveShell(event), selected, (ask, dialog) -> {

                String prompt = PromptTemplates.buildSelectionTaskPrompt(selected, ask.userQuestion, ask.mode);

                Job job = new Job("AI Assist") {
                    @Override
                    protected IStatus run(IProgressMonitor monitor) {

                        String answer = AIHttpClient.callAIWithTimeout(prompt, 60_000, monitor);

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

        // 2) 没选中：进入 ghost 补全模式（关键：保存 + 延迟二次恢复）
        try {
            final int triggerDocOffset = offset;

            // ✅ 保存触发时 docOffset / widgetCaret（双保险）
            final int[] triggerWidgetCaretBox = new int[] { -1 };
            Display.getDefault().syncExec(() -> {
                StyledText st0 = GhostCompletionManager.getStyledText(editor);
                if (st0 != null && !st0.isDisposed()) {
                    triggerWidgetCaretBox[0] = st0.getCaretOffset();
                }
            });
            final int triggerWidgetCaret = triggerWidgetCaretBox[0];
            int before = 400;  // 往前多给一点，包含变量/方法签名
            int after  = 40;   // 往后很少，避免模型扩写
            String contextText = ContextUtil.getContextWindow(doc, triggerDocOffset, before, after);
            int cursorInContext = ContextUtil.cursorInContext(doc, triggerDocOffset, before);

            Job job = new Job("AI Assist Completion") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {

                    String aiResult = AIHttpClient.callAICompletion(contextText, cursorInContext, monitor);
                    if (monitor.isCanceled())
                        return Status.CANCEL_STATUS;

                    Display.getDefault().asyncExec(() -> {
                        if (aiResult == null || aiResult.trim().isEmpty())
                            return;

                        // 1️⃣ 用户在等待期间动过光标 -> 取消本次 ghost
                        if (!isCaretStillAtDocOffset(editor, triggerDocOffset)) {
                            System.out.println("Ghost canceled: caret moved (doc offset changed)");
                            return;
                        }

                        StyledText st = GhostCompletionManager.getStyledText(editor);
                        if (st == null || st.isDisposed())
                            return;

                        // === DEBUG：打印变化（你可以先观察到底是 caret 变还是视野变）===
                        int beforeDoc = ((ITextSelection) editor.getSelectionProvider().getSelection()).getOffset();
                        int beforeWidget = st.getCaretOffset();
                        System.out.println("Before show: docOffset=" + beforeDoc + ", widgetCaret=" + beforeWidget);

                        // 2️⃣ 展示 ghost（GhostCompletionManager 内部必须不要 setSelection/showSelection/topIndex）
                        GhostCompletionManager.show(editor, triggerDocOffset, aiResult);

                        // 3️⃣ 立即恢复一次
                        restoreCaret(editor, st, triggerDocOffset, triggerWidgetCaret);

                        // 4️⃣ ✅ 延迟再恢复一次：压过 Eclipse 后续 reveal/同步（非常关键）
                        Display.getDefault().timerExec(20, () -> {
                            if (st.isDisposed())
                                return;
                            restoreCaret(editor, st, triggerDocOffset, triggerWidgetCaret);

                            int afterDoc = ((ITextSelection) editor.getSelectionProvider().getSelection()).getOffset();
                            int afterWidget = st.getCaretOffset();
                            System.out.println("After restore: docOffset=" + afterDoc + ", widgetCaret=" + afterWidget);
                        });
                    });

                    return Status.OK_STATUS;
                }
            };
            job.setUser(true);
            job.schedule();

        } catch (Exception e) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "AI Assist", "执行失败：" + e.getMessage());
        }

        return null;
    }

    private static void restoreCaret(ITextEditor editor, StyledText st, int triggerDocOffset, int triggerWidgetCaret) {
        try {
            // doc 侧恢复
            editor.getSelectionProvider().setSelection(new TextSelection(triggerDocOffset, 0));
        } catch (Exception ignore) {}

        try {
            // widget 侧恢复（避免 viewer 同步导致 caret 跑）
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
