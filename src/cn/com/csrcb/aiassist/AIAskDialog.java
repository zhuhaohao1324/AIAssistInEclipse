package cn.com.csrcb.aiassist;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import cn.com.csrcb.aiassist.PromptTemplates.SelectionMode;

/**
 * AIAskDialog: - 选中代码后 Alt+Q 弹出本对话框 - 点击“发送”不关闭窗口，而是显示“正在思考中...”，并回调给外部去发起后台请求
 * - 外部请求结束后可调用 dialog.setStatus(...) / dialog.setSendEnabled(true) 来恢复
 *
 * 注意：为避免编码/导出环境导致中文乱码，这里按钮文本使用 Unicode 转义。
 */
public class AIAskDialog extends Dialog {

	/** 用户点击发送后的结果 */
	public static class AskResult {
		public final String userQuestion;
		public final SelectionMode mode;

		public AskResult(String userQuestion, SelectionMode mode) {
			this.userQuestion = userQuestion;
			this.mode = mode;
		}
	}

	/** 发送回调：外部负责启动 Job / HTTP 调用 */
	public interface SendCallback {
		void onSend(AskResult ask, AIAskDialog dialog);
	}

	private final String selectedPreview;
	private final SendCallback callback;

	private Combo modeCombo;
	private Text previewText;
	private Text questionText;
	private Button sendBtn;
	private Label statusLabel;

	private AIAskDialog(Shell parentShell, String selectedText, SendCallback cb) {
		super(parentShell);
		this.selectedPreview = buildPreview(selectedText);
		this.callback = cb;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** 打开对话框（非阻塞回调式） */
	public static void open(Shell shell, String selectedText, SendCallback cb) {
		new AIAskDialog(shell, selectedText, cb).open();
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		// AI 提问
		newShell.setText("\u0041\u0049\u0020\u63D0\u95EE"); // "AI 提问"
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);

		Composite c = new Composite(area, SWT.NONE);
		c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		c.setLayout(new GridLayout(2, false));

		// 模式
		Label modeLabel = new Label(c, SWT.NONE);
		modeLabel.setText("\u6A21\u5F0F\uFF1A"); // 模式：
		modeCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
		modeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		modeCombo.setItems(new String[] { "\u4E2D\u6587\u89E3\u91CA", // 中文解释
				"\u4E2D\u6587\u603B\u7ED3", // 中文总结
				"\u751F\u6210\u4EE3\u7801", // 生成代码
				"\u91CD\u6784\u5EFA\u8BAE" // 重构建议
		});
		modeCombo.select(0);

		// 选中预览
		Label previewLabel = new Label(c, SWT.NONE);
		previewLabel.setText("\u9009\u4E2D\u9884\u89C8\uFF1A"); // 选中预览：
		previewText = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
		GridData previewGd = new GridData(SWT.FILL, SWT.FILL, true, true);
		previewGd.heightHint = 110;
		previewText.setLayoutData(previewGd);
		previewText.setText(selectedPreview);

		// 提问
		Label qLabel = new Label(c, SWT.NONE);
		qLabel.setText("\u63D0\u95EE\uFF1A"); // 提问：
		questionText = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData qGd = new GridData(SWT.FILL, SWT.FILL, true, true);
		qGd.heightHint = 130;
		questionText.setLayoutData(qGd);
		questionText.setText("\u8BF7\u7528\u4E2D\u6587\u89E3\u91CA\u8FD9\u6BB5\u4EE3\u7801\u7684\u4F5C\u7528\u3002"); // 默认问题

		// 状态栏（跨两列）
		statusLabel = new Label(c, SWT.NONE);
		GridData statusGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
		statusGd.horizontalSpan = 2;
		statusLabel.setLayoutData(statusGd);
		statusLabel.setText(""); // 初始为空

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		// 发送 / 关闭 —— 用 Unicode 避免乱码
		sendBtn = createButton(parent, 2001, "\u53D1\u9001", true); // 发送
		createButton(parent, 2002, "\u5173\u95ED", false); // 关闭
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == 2002) { // 关闭
			close();
			return;
		}

		if (buttonId == 2001) { // 发送
			String q = questionText.getText() == null ? "" : questionText.getText().trim();
			if (q.length() == 0) {
				q = "\u8BF7\u7528\u4E2D\u6587\u89E3\u91CA\u8FD9\u6BB5\u4EE3\u7801\u7684\u4F5C\u7528\u3002";
			}
			SelectionMode mode = toMode(modeCombo.getSelectionIndex());

			// 不关闭，显示状态并禁用发送按钮（外部请求结束后再恢复）
			setStatus("\u6B63\u5728\u601D\u8003\u4E2D..."); // 正在思考中...
			setSendEnabled(false);

			if (callback != null) {
				callback.onSend(new AskResult(q, mode), this);
			}
			return;
		}

		super.buttonPressed(buttonId);
	}

	/** 外部调用：恢复/禁用“发送”按钮 */
	public void setSendEnabled(boolean enabled) {
		if (sendBtn != null && !sendBtn.isDisposed()) {
			sendBtn.setEnabled(enabled);
		}
	}

	/** 外部调用：更新状态文本 */
	public void setStatus(String text) {
		if (statusLabel != null && !statusLabel.isDisposed()) {
			statusLabel.setText(text == null ? "" : text);
		}
	}

	/** 外部调用：快速恢复到“可再次发送”的状态 */
	public void resetToIdle() {
		setStatus("");
		setSendEnabled(true);
	}

	private static SelectionMode toMode(int idx) {
		switch (idx) {
		case 0:
			return SelectionMode.EXPLAIN_CN;
		case 1:
			return SelectionMode.SUMMARIZE_CN;
		case 2:
			return SelectionMode.GENERATE_CODE;
		case 3:
			return SelectionMode.REFACTOR;
		default:
			return SelectionMode.EXPLAIN_CN;
		}
	}

	private static String buildPreview(String s) {
		if (s == null)
			return "";
		String t = s;
		if (t.length() > 1200) {
			t = t.substring(0, 1200) + "\n...(truncated)";
		}
		return t;
	}
}
