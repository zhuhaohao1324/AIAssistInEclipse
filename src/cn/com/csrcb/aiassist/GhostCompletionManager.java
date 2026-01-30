package cn.com.csrcb.aiassist;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Ghost (inline, gray) completion UI: - Show suggestion as "ghost text" at
 * caret without modifying document - Tab to accept (insert into document) - Esc
 * to cancel - Cancel on navigation keys / mouse click - Intelligent cancel on
 * document changes: - Changes after anchor: keep - Changes before anchor: shift
 * anchor by delta - Overlap anchor or edit at anchor: cancel
 *
 * ABIDE/封装IDE 兼容增强： - 优先从 ISourceViewer.getTextWidget() 获取编辑区 StyledText（更准确） -
 * 若存在 projection/folding/映射文档，使用 ITextViewerExtension5 做 modelOffset ->
 * widgetOffset 映射 - 增加 DEBUG 日志便于定位“拿不到控件 / offset 映射失败 / getLocationAtOffset
 * 异常”等问题
 */
public class GhostCompletionManager {

	// ===== Debug =====
	private static final boolean DEBUG = true;

	private static void debug(String s) {
		if (DEBUG)
			System.out.println(s);
	}

	private final ITextEditor editor;
	private final IDocument document;
	private final StyledText styledText;

	/** widget offset（不是 document offset） */
	private int anchorWidgetOffset;

	/** Ghost suggestion text (may contain newlines). */
	private String suggestion;

	private boolean active = false;

	private PaintListener paintListener;
	private VerifyKeyListener keyListener;
	private IDocumentListener docListener;
	private MouseListener mouseListener;

	private Color ghostColor;

	private GhostCompletionManager(ITextEditor editor, StyledText styledText, IDocument doc) {
		this.editor = editor;
		this.styledText = styledText;
		this.document = doc;
	}

	/**
	 * Show ghost suggestion in the given editor at document offset. Internally maps
	 * document/model offset to widget offset if possible.
	 */
	public static GhostCompletionManager show(ITextEditor editor, int documentOffset, String suggestion) {
		if (editor == null)
			return null;

		StyledText st = getStyledText(editor);
		if (st == null || st.isDisposed()) {
			debug("Ghost: StyledText is null/disposed");
			return null;
		}

		IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (doc == null) {
			debug("Ghost: Document is null");
			return null;
		}

		// 关键：把 document offset 映射为 widget offset
		int widgetOffset = mapDocOffsetToWidgetOffset(editor, documentOffset);
		if (widgetOffset < 0) {
			debug("Ghost: offset mapping failed. docOffset=" + documentOffset);
			return null;
		}

		GhostCompletionManager mgr = new GhostCompletionManager(editor, st, doc);
		mgr.internalShow(widgetOffset, suggestion);
		return mgr.active ? mgr : null;
	}

	/** Returns whether ghost is currently active. */
	public boolean isActive() {
		return active;
	}

	/** Programmatically cancel ghost (does not modify document). */
	public void cancelProgrammatically() {
		cancel();
	}

	private void internalShow(int widgetOffset, String text) {
		if (text == null || text.trim().isEmpty())
			return;

		// Clamp to StyledText range.
		int charCount = styledText.getCharCount();
		if (widgetOffset < 0)
			widgetOffset = 0;
		if (widgetOffset > charCount)
			widgetOffset = charCount;

		this.anchorWidgetOffset = widgetOffset;
		this.suggestion = text;
		this.active = true;

		hookListeners();

		// 让光标位置可见，避免画到不可见区域用户以为没显示
		try {
			styledText.setSelection(anchorWidgetOffset);
			styledText.showSelection();
		} catch (Exception ignore) {
		}

		styledText.redraw();
		debug("Ghost: show at widgetOffset=" + anchorWidgetOffset + ", charCount=" + charCount);
	}

	/** Accept: insert suggestion into document, then remove ghost. */
	private void accept() {
		if (!active)
			return;

		final String insertText = suggestion;

		// 注意：document.replace 需要 document offset
		// 我们当前只存了 widget offset，需映射回 model offset（若映射失败，退化用 widget offset）
		int insertModelOffset = mapWidgetOffsetToDocOffset(editor, anchorWidgetOffset);
		if (insertModelOffset < 0) {
			debug("Ghost: widget->doc mapping failed, fallback to widgetOffset");
			insertModelOffset = anchorWidgetOffset;
		}

		cancel(); // 先取消 UI 监听，避免 documentChanged 回调重入

		try {
			document.replace(insertModelOffset, 0, insertText);

			// 光标移到插入末尾（尽力）
			int newCaretModel = insertModelOffset + insertText.length();
			int newCaretWidget = mapDocOffsetToWidgetOffset(editor, newCaretModel);
			if (newCaretWidget < 0)
				newCaretWidget = Math.min(styledText.getCharCount(), newCaretModel);

			if (!styledText.isDisposed()) {
				styledText.setSelection(newCaretWidget);
				styledText.showSelection();
			}
		} catch (BadLocationException e) {
			debug("Ghost: document.replace BadLocation: " + e.getMessage());
		}
	}

	/** Cancel: clear ghost and unhook listeners without touching document. */
	private void cancel() {
		if (!active)
			return;
		active = false;
		unhookListeners();
		if (styledText != null && !styledText.isDisposed()) {
			styledText.redraw();
		}
		debug("Ghost: canceled");
	}

	private void hookListeners() {
		ghostColor = Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);

		paintListener = e -> {
			if (!active)
				return;
			if (styledText.isDisposed())
				return;

			int count = styledText.getCharCount();
			int safeOffset = Math.max(0, Math.min(anchorWidgetOffset, count));

			Point p;
			try {
				p = styledText.getLocationAtOffset(safeOffset);
			} catch (IllegalArgumentException ex) {
				debug("Ghost: getLocationAtOffset failed. anchorWidgetOffset=" + safeOffset + ", ex="
						+ ex.getMessage());
				return;
			} catch (Exception ex) {
				debug("Ghost: getLocationAtOffset unexpected ex: " + ex.getMessage());
				return;
			}

			drawGhost(e.gc, p.x, p.y);
		};

		keyListener = event -> {
			if (!active)
				return;

			// ESC cancels
			if (event.keyCode == SWT.ESC) {
				event.doit = false;
				cancel();
				return;
			}

			// TAB accepts
			if (event.keyCode == SWT.TAB) {
				event.doit = false;
				accept();
				return;
			}

			// Navigation keys cancel
			if (event.keyCode == SWT.ARROW_LEFT || event.keyCode == SWT.ARROW_RIGHT || event.keyCode == SWT.ARROW_UP
					|| event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.PAGE_UP || event.keyCode == SWT.PAGE_DOWN
					|| event.keyCode == SWT.HOME || event.keyCode == SWT.END) {
				cancel();
				return;
			}
		};

		// 文档变化：这里拿到的是 model/document offset
		docListener = new IDocumentListener() {
			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}

			@Override
			public void documentChanged(DocumentEvent event) {
				if (!active)
					return;

				// 变化发生的位置（model/doc offset）
				int changeOffsetModel = event.getOffset();
				int replacedLen = event.getLength();
				String newText = event.getText() == null ? "" : event.getText();
				int insertedLen = newText.length();
				int delta = insertedLen - replacedLen;

				// 把当前 anchor（widget）映射回 model，以便与 documentChanged 的 offset 比较
				int anchorModel = mapWidgetOffsetToDocOffset(editor, anchorWidgetOffset);
				if (anchorModel < 0) {
					// 映射失败时，保守处理：直接取消，避免错位插入
					debug("Ghost: widget->doc mapping failed during docChanged -> cancel");
					cancel();
					return;
				}

				int changeStart = changeOffsetModel;
				int changeEnd = changeOffsetModel + replacedLen;

				// 1) Change strictly after anchor: keep
				if (changeStart > anchorModel) {
					return;
				}

				// 2) Overlap anchor: cancel
				if (changeEnd > anchorModel) {
					cancel();
					return;
				}

				// 3) Change before anchor: shift anchor (in model space), then remap to widget
				if (changeEnd <= anchorModel) {
					int newAnchorModel = anchorModel + delta;
					int newAnchorWidget = mapDocOffsetToWidgetOffset(editor, newAnchorModel);
					if (newAnchorWidget < 0) {
						debug("Ghost: doc->widget mapping failed after shift -> cancel");
						cancel();
						return;
					}
					anchorWidgetOffset = clamp(newAnchorWidget, 0, styledText.getCharCount());
					styledText.redraw();
					return;
				}

				// Fallback
				cancel();
			}
		};

		mouseListener = new MouseAdapter() {
			@Override
			public void mouseDown(MouseEvent e) {
				cancel();
			}
		};

		styledText.addPaintListener(paintListener);
		styledText.addVerifyKeyListener(keyListener);
		styledText.addMouseListener(mouseListener);
		document.addDocumentListener(docListener);
	}

	private void unhookListeners() {
		if (styledText != null && !styledText.isDisposed()) {
			if (paintListener != null)
				styledText.removePaintListener(paintListener);
			if (keyListener != null)
				styledText.removeVerifyKeyListener(keyListener);
			if (mouseListener != null)
				styledText.removeMouseListener(mouseListener);
		}
		if (document != null && docListener != null) {
			document.removeDocumentListener(docListener);
		}

		paintListener = null;
		keyListener = null;
		docListener = null;
		mouseListener = null;
		suggestion = null;
	}

	/** Draw ghost text at the given (x,y) in the StyledText coordinate system. */
	private void drawGhost(GC gc, int startX, int startY) {
		if (suggestion == null || suggestion.isEmpty())
			return;

		int oldAlpha = gc.getAlpha();
		Color oldFg = gc.getForeground();
		Font oldFont = gc.getFont();

		gc.setAlpha(120);
		gc.setForeground(ghostColor);
		gc.setFont(styledText.getFont());

		String normalized = suggestion.replace("\r\n", "\n").replace('\r', '\n');
		String[] lines = normalized.split("\n", -1);

		int lineHeight = styledText.getLineHeight();
		int x = startX;
		int y = startY;

		int leftMargin = styledText.getLeftMargin();

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (!line.isEmpty()) {
				gc.drawText(line, x, y, true);
			}
			y += lineHeight;
			x = leftMargin;
		}

		gc.setFont(oldFont);
		gc.setForeground(oldFg);
		gc.setAlpha(oldAlpha);
	}

	// ===== Helpers =====

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	/**
	 * 优先从 ISourceViewer 获取编辑区 StyledText（最准确） 若拿不到，退化到 Control adapter，然后再递归
	 * Composite 查找。
	 */
	private static StyledText getStyledText(ITextEditor editor) {
		try {
			ISourceViewer viewer = editor.getAdapter(ISourceViewer.class);
			if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
				StyledText st = viewer.getTextWidget();
				debug("Ghost: got StyledText from ISourceViewer, hash=" + System.identityHashCode(st) + ", charCount="
						+ st.getCharCount());
				return st;
			}
		} catch (Exception e) {
			debug("Ghost: getStyledText via ISourceViewer ex: " + e.getMessage());
		}

		try {
			Control c = editor.getAdapter(Control.class);
			if (c instanceof StyledText) {
				StyledText st = (StyledText) c;
				debug("Ghost: got StyledText from Control adapter, hash=" + System.identityHashCode(st) + ", charCount="
						+ st.getCharCount());
				return st;
			}
			if (c instanceof Composite) {
				StyledText st = findStyledText((Composite) c);
				if (st != null) {
					debug("Ghost: found StyledText in Composite, hash=" + System.identityHashCode(st) + ", charCount="
							+ st.getCharCount());
				} else {
					debug("Ghost: no StyledText found in Composite children");
				}
				return st;
			}
		} catch (Exception e) {
			debug("Ghost: getStyledText via Control ex: " + e.getMessage());
		}

		return null;
	}

	private static StyledText findStyledText(Composite parent) {
		for (Control child : parent.getChildren()) {
			if (child instanceof StyledText)
				return (StyledText) child;
			if (child instanceof Composite) {
				StyledText st = findStyledText((Composite) child);
				if (st != null)
					return st;
			}
		}
		return null;
	}

	/**
	 * model/document offset -> widget offset（用于绘制） 如果 viewer 支持
	 * ITextViewerExtension5，就用它；否则退化返回 docOffset。
	 */
	private static int mapDocOffsetToWidgetOffset(ITextEditor editor, int docOffset) {
		try {
			ISourceViewer sv = editor.getAdapter(ISourceViewer.class);
			if (sv == null) {
				debug("Ghost: ISourceViewer adapter is null (doc->widget fallback)");
				return docOffset;
			}

			if (sv instanceof ITextViewerExtension5) {
				int w = ((ITextViewerExtension5) sv).modelOffset2WidgetOffset(docOffset);
				debug("Ghost: mapped docOffset " + docOffset + " -> widgetOffset " + w);
				return w;
			}

			debug("Ghost: viewer not ITextViewerExtension5 (doc->widget fallback)");
			return docOffset;
		} catch (Exception e) {
			debug("Ghost: doc->widget mapping ex: " + e.getMessage());
			return -1;
		}
	}

	/**
	 * widget offset -> model/document offset（用于真正插入 document）
	 */
	private static int mapWidgetOffsetToDocOffset(ITextEditor editor, int widgetOffset) {
		try {
			ISourceViewer sv = editor.getAdapter(ISourceViewer.class);
			if (sv == null) {
				debug("Ghost: ISourceViewer adapter is null (widget->doc fallback)");
				return widgetOffset;
			}

			if (sv instanceof ITextViewerExtension5) {
				int m = ((ITextViewerExtension5) sv).widgetOffset2ModelOffset(widgetOffset);
				debug("Ghost: mapped widgetOffset " + widgetOffset + " -> docOffset " + m);
				return m;
			}

			debug("Ghost: viewer not ITextViewerExtension5 (widget->doc fallback)");
			return widgetOffset;
		} catch (Exception e) {
			debug("Ghost: widget->doc mapping ex: " + e.getMessage());
			return -1;
		}
	}
}
