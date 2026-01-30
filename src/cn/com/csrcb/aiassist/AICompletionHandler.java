package cn.com.csrcb.aiassist;

import org.eclipse.core.commands.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.*;
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

		// 1) 有选中：进入“提问/解释/生成”模式
		if (sel.getLength() > 0) {
			String selected = sel.getText();

			AIAskDialog.open(HandlerUtil.getActiveShell(event), selected, (ask, dialog) -> {

				String prompt = PromptTemplates.buildSelectionTaskPrompt(selected, ask.userQuestion, ask.mode);

				Job job = new Job("AI Assist") {
					@Override
					protected IStatus run(IProgressMonitor monitor) {

						// ✅ 60s 超时：AIHttpClient 内部会按超时返回 null/空/错误信息（见下一节）
						String answer = AIHttpClient.callAIWithTimeout(prompt, 60_000, monitor);

						Display.getDefault().asyncExec(() -> {
							// AskDialog 还开着的话：恢复按钮/状态
							dialog.setSendEnabled(true);

							if (answer == null) {
								dialog.setStatus(
										"\u8D85\u8FC760\u79D2\u672A\u8FD4\u56DE\uFF0C\u8BF7\u7A0D\u540E\u91CD\u8BD5\u3002");// 超过60秒未返回，请稍后重试。
								return;
							}

							String trimmed = answer.trim();
							if (trimmed.length() == 0) {
								dialog.setStatus(
										"\u8D85\u8FC760\u79D2\u672A\u8FD4\u56DE\uFF0C\u8BF7\u7A0D\u540E\u91CD\u8BD5\u3002");// 超过60秒未返回，请稍后重试。
								return;
							}

							dialog.setStatus("完成。");
							// ✅ 弹出结果框
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

		// 2) 没选中：进入 ghost 补全模式
		try {
			String contextText = ContextUtil.getContextWindow(doc, offset, 2500, 800);
			int cursorInContext = ContextUtil.cursorInContext(doc, offset, 2500);

			Job job = new Job("AI Assist Completion") {
				@Override
				protected IStatus run(IProgressMonitor monitor) {
					String aiResult = AIHttpClient.callAICompletion(contextText, cursorInContext, monitor);
					if (monitor.isCanceled())
						return Status.CANCEL_STATUS;

					Display.getDefault().asyncExec(() -> {
						if (aiResult == null || aiResult.trim().length() == 0)
							return;
						GhostCompletionManager.show(editor, offset, aiResult);
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
}
