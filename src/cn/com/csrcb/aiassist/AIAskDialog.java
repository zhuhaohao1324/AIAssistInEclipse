package cn.com.csrcb.aiassist;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import cn.com.csrcb.aiassist.PromptTemplates.SelectionMode;

/**
 * AIAskDialog: - ???????? Alt+Q ??????????? - ?????????????????????????????????????...????????????????????????
 * - ??????????????? dialog.setStatus(...) / dialog.setSendEnabled(true) ?????
 *
 * ???????????/??????????????????????????????? Unicode ???^
 */
public class AIAskDialog extends Dialog {

	/** ?????????????? */
	public static class AskResult {
		public final String userQuestion;
		public final SelectionMode mode;

		public AskResult(String userQuestion, SelectionMode mode) {
			this.userQuestion = userQuestion;
			this.mode = mode;
		}
	}

	/** ?????????????????? Job / HTTP ???? */
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
	
	private int animationDotCount = 0;
	private Runnable animationRunnable;

	private AIAskDialog(Shell parentShell, String selectedText, SendCallback cb) {
		super(parentShell);
		this.selectedPreview = buildPreview(selectedText);
		this.callback = cb;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** ????????????????? */
	public static void open(Shell shell, String selectedText, SendCallback cb) {
		new AIAskDialog(shell, selectedText, cb).open();
	}

	@Override
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		// AI ????
		newShell.setText("\u0041\u0049\u0020\u63D0\u95EE"); // "AI ????"
		newShell.setSize(650, 550);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);

		Composite c = new Composite(area, SWT.NONE);
		c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		c.setLayout(new GridLayout(2, false));

		// ??
		Label modeLabel = new Label(c, SWT.NONE);
		modeLabel.setText("\u6A21\u5F0F\uFF1A"); // ????
		modeCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
		modeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		modeCombo.setItems(new String[] { "\u751F\u6210\u4EE3\u7801", // ???????
				"\u4E2D\u6587\u89E3\u91CA", // ???????
				"\u4E2D\u6587\u603B\u7ED3", // ???????			
				"\u91CD\u6784\u5EFA\u8BAE" // ???????
		});
		modeCombo.select(0);

		// ??????
		Label previewLabel = new Label(c, SWT.NONE);
		previewLabel.setText("\u9009\u4E2D\u9884\u89C8\uFF1A"); // ????????
		previewText = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
		GridData previewGd = new GridData(SWT.FILL, SWT.FILL, true, true);
		previewGd.heightHint = 180;
		previewText.setLayoutData(previewGd);
		previewText.setText(selectedPreview);

		// ????
		Label qLabel = new Label(c, SWT.NONE);
		qLabel.setText("\u63D0\u95EE\uFF1A"); // ?????
		questionText = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData qGd = new GridData(SWT.FILL, SWT.FILL, true, true);
		qGd.heightHint = 200;
		questionText.setLayoutData(qGd);
		questionText.setText(""); // ???????

		// ??????????????
		statusLabel = new Label(c, SWT.NONE);
		GridData statusGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
		statusGd.horizontalSpan = 2;
		statusLabel.setLayoutData(statusGd);
		statusLabel.setText(""); // ??????

		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		// ???? / ??? ???? ?? Unicode ????????
		sendBtn = createButton(parent, 2001, "\u53D1\u9001", true); // ????
		createButton(parent, 2002, "\u5173\u95ED", false); // ???
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == 2002) { // ???
			close();
			return;
		}

		if (buttonId == 2001) { // ????
			String q = questionText.getText() == null ? "" : questionText.getText().trim();
			if (q.length() == 0) {
				q = "\u8BF7\u7528\u4E2D\u6587\u89E3\u91CA\u8FD9\u6BB5\u4EE3\u7801\u7684\u4F5C\u7528\u3002";
			}
			SelectionMode mode = toMode(modeCombo.getSelectionIndex());

			// ??????????????????????????????????????????
			startLoadingAnimation("\u6B63\u5728\u601D\u8003\u4E2D"); // ?????????...
			setSendEnabled(false);

			if (callback != null) {
				callback.onSend(new AskResult(q, mode), this);
			}
			return;
		}

		super.buttonPressed(buttonId);
	}

	/** ??????????/?????????????? */
	public void setSendEnabled(boolean enabled) {
		if (sendBtn != null && !sendBtn.isDisposed()) {
			sendBtn.setEnabled(enabled);
		}
	}

	/** ???????????????? */
	public void setStatus(String text) {
		if (statusLabel != null && !statusLabel.isDisposed()) {
			statusLabel.setText(text == null ? "" : text);
		}
	}

	/** ?????????? (?????????.) */
	public void startLoadingAnimation(String baseText) {
		stopLoadingAnimation();
		animationDotCount = 0;
		animationRunnable = new Runnable() {
			@Override
			public void run() {
				if (statusLabel == null || statusLabel.isDisposed()) {
					return;
				}
				StringBuilder dots = new StringBuilder();
				for (int i = 0; i < animationDotCount; i++) {
					dots.append('.');
				}
				statusLabel.setText(baseText + dots.toString());
				animationDotCount = (animationDotCount % 3) + 1;
				statusLabel.getDisplay().timerExec(400, this);
			}
		};
		statusLabel.getDisplay().timerExec(400, animationRunnable);
	}

	/** ????????? */
	public void stopLoadingAnimation() {
		if (animationRunnable != null) {
			statusLabel.getDisplay().timerExec(-1, animationRunnable);
			animationRunnable = null;
		}
	}

	/** ??????????????????????????????? */
	public void resetToIdle() {
		stopLoadingAnimation();
		setStatus("");
		setSendEnabled(true);
	}

	private static SelectionMode toMode(int idx) {
		switch (idx) {
		
		case 0:
			return SelectionMode.GENERATE_CODE;
		case 1:
			return SelectionMode.EXPLAIN_CN;
		case 2:
			return SelectionMode.SUMMARIZE_CN;
		case 3:
			return SelectionMode.REFACTOR;
		default:
			return SelectionMode.GENERATE_CODE;
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
