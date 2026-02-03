package cn.com.csrcb.aiassist;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.texteditor.ITextEditor;

import cn.com.csrcb.aiassist.PromptTemplates.SelectionMode;

public class AIResultDialog extends Dialog {

	private final ITextEditor editor;
	private final int offset;
	private final int length;
	private final String resultText;
	private final SelectionMode mode;

	private Text resultBox;

	private AIResultDialog(Shell parentShell, ITextEditor editor, int offset, int length, String resultText,
			SelectionMode mode) {
		super(parentShell);
		this.editor = editor;
		this.offset = offset;
		this.length = length;
		this.resultText = resultText == null ? "" : resultText;
		this.mode = mode;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	public static void openForSelection(ITextEditor editor, int offset, int length, String resultText,
			SelectionMode mode) {
		Shell shell = editor.getSite().getShell();
		new AIResultDialog(shell, editor, offset, length, resultText, mode).open();
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);

		Composite c = new Composite(area, SWT.NONE);
		c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		c.setLayout(new GridLayout(1, false));

		Label title = new Label(c, SWT.NONE);
		title.setText(modeTitle(mode));

		resultBox = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL);
		resultBox.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		((GridData) resultBox.getLayoutData()).heightHint = 420;
		resultBox.setText(resultText);

		// ✅ Ctrl+A / Ctrl+C 快捷键（强制）
		hookCopyShortcuts(resultBox);

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
//    	发送	\u53D1\u9001
//    	关闭	\u5173\u95ED
//    	复制	\u590D\u5236
//    	插入到光标处	\u63D2\u5165\u5230\u5149\u6807\u5904
//    	替换选中	\u66FF\u6362\u9009\u4E2D
//    	插入为注释	\u63D2\u5165\u4E3A\u6CE8\u91CA
//    	正在思考中…	\u6B63\u5728\u601D\u8003\u4E2D...
		createButton(parent, 2001, "\u590D\u5236", false);

		createButton(parent, 2002, "\u63D2\u5165\u5230\u5149\u6807\u5904", false);
		createButton(parent, 2003, "\u66FF\u6362\u9009\u4E2D", false);

		createButton(parent, 2004, "\u63D2\u5165\u4E3A\u6CE8\u91CA", false);

		// ✅ 用 OK_ID 作为关闭按钮，Dialog 默认就会关闭
		createButton(parent, IDialogConstants.OK_ID, "Close", true);
	}

	@Override
	protected void buttonPressed(int buttonId) {
		switch (buttonId) {
		case 2001:
			copyToClipboard(getCopyText());
			return;

		case 2002:
			insertAtOffset(resultBox.getText(), offset, 0);
			return;

		case 2003:
			insertAtOffset(resultBox.getText(), offset, length);
			return;

		case 2004:
			insertAsCommentAligned(resultBox.getText());
			return;

		case IDialogConstants.OK_ID:
			close();
			return;

		default:
			super.buttonPressed(buttonId);
		}
	}

	// ------------------------
	// 1) Ctrl+A / Ctrl+C
	// ------------------------
	private void hookCopyShortcuts(Text text) {
		text.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				boolean isMac = SWT.getPlatform().toLowerCase().contains("cocoa");
				boolean modDown = isMac ? ((e.stateMask & SWT.COMMAND) != 0) : ((e.stateMask & SWT.CTRL) != 0);

				if (!modDown)
					return;

				// Ctrl/Command + A
				if (e.keyCode == 'a' || e.keyCode == 'A') {
					e.doit = false;
					text.selectAll();
					return;
				}
				// Ctrl/Command + C
				if (e.keyCode == 'c' || e.keyCode == 'C') {
					e.doit = false;
					copyToClipboard(getCopyText());
				}
			}
		});
	}

	private String getCopyText() {
		String sel = resultBox.getSelectionText();
		if (sel != null && !sel.isEmpty())
			return sel;
		return resultBox.getText();
	}

	private void copyToClipboard(String text) {
		if (text == null)
			text = "";
		Clipboard cb = new Clipboard(getShell().getDisplay());
		try {
			cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			cb.dispose();
		}
	}

	// ------------------------
	// 2) 插入为注释（自动缩进对齐）
	// ------------------------
	private void insertAsCommentAligned(String explanation) {
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (doc == null)
			return;

		String indent = getLineIndent(doc, offset);
		String comment = toBlockCommentWithIndent(explanation, indent);

		// 插入到选中代码之前（offset 处）
		insertAtOffset(comment, offset, 0);
	}

	private static String getLineIndent(IDocument doc, int off) {
		try {
			int line = doc.getLineOfOffset(off);
			int lineOffset = doc.getLineOffset(line);
			int lineLen = doc.getLineLength(line);

			String lineText = doc.get(lineOffset, lineLen);
			int i = 0;
			while (i < lineText.length()) {
				char ch = lineText.charAt(i);
				if (ch != ' ' && ch != '\t')
					break;
				i++;
			}
			return lineText.substring(0, i);
		} catch (BadLocationException e) {
			return "";
		}
	}

	private static String toBlockCommentWithIndent(String s, String indent) {
		String body = s == null ? "" : s.trim();
		body = body.replace("*/", "* /");

		String[] lines = body.split("\r\n|\r|\n", -1);

		StringBuilder sb = new StringBuilder();
		sb.append(indent).append("/**\n");
		for (String line : lines) {
			sb.append(indent).append(" * ");
			sb.append(line);
			sb.append("\n");
		}
		sb.append(indent).append(" */\n");
		return sb.toString();
	}

	private void insertAtOffset(String text, int off, int len) {
		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (doc == null)
			return;
		try {
			doc.replace(off, len, text);
		} catch (BadLocationException e) {
			// ignore
		}
	}

	private static String modeTitle(SelectionMode mode) {
		switch (mode) {
//            case EXPLAIN_CN: return "AI result";
//            case SUMMARIZE_CN: return "AI result";
//            case GENERATE_CODE: return "AI result";
//            case REFACTOR: return "AI result";
		default:
			return "AI Result";
		}
	}
}