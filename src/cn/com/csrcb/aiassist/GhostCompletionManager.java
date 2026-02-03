package cn.com.csrcb.aiassist;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.TextSelection;
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
 * Ghost (inline, gray) completion UI (ABIDE compatible):
 * - Paint at caret using widget offset
 * - Insert at stored model/doc offset
 * - Accept (Tab): move caret using widget anchor + inserted length (no doc->widget mapping)
 */
public class GhostCompletionManager {

    private static final boolean DEBUG = true;
    private static void debug(String s) { if (DEBUG) System.out.println(s); }

    private final ITextEditor editor;
    private final IDocument document;
    private final StyledText styledText;

    /** 绘制锚点：widget offset（caret） */
    private int anchorWidgetOffset;

    /** 插入锚点：model/doc offset */
    private int anchorModelOffset;

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

    public static GhostCompletionManager show(ITextEditor editor, int documentOffset, String suggestion) {
        if (editor == null) return null;

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

        // ✅ ABIDE：绘制锚点优先用 caret(widget)
        int widgetOffset = -1;
        try { widgetOffset = st.getCaretOffset(); } catch (Exception ignore) {}

        // 若 caret 拿不到，再尝试映射（不强依赖）
        if (widgetOffset < 0) widgetOffset = mapDocOffsetToWidgetOffset(editor, documentOffset);
        if (widgetOffset < 0) {
            debug("Ghost: cannot determine widget offset (caret & mapping failed)");
            return null;
        }

        GhostCompletionManager mgr = new GhostCompletionManager(editor, st, doc);
        mgr.internalShow(widgetOffset, documentOffset, suggestion);
        return mgr.active ? mgr : null;
    }

    public boolean isActive() { return active; }

    public void cancelProgrammatically() { cancel(); }

    private void internalShow(int widgetOffset, int modelOffset, String text) {
        if (text == null || text.trim().isEmpty()) return;

        int charCount = styledText.getCharCount();
        widgetOffset = clamp(widgetOffset, 0, charCount);

        this.anchorWidgetOffset = widgetOffset;
        this.anchorModelOffset = Math.max(0, modelOffset);
        this.suggestion = text;
        this.active = true;

        hookListeners();

        styledText.redraw();
        styledText.update();

        debug("Ghost: show at widgetOffset=" + anchorWidgetOffset + ", modelOffset=" + anchorModelOffset);
    }

//    /**
//     * ✅ 关键修复：Tab 接受后，光标位置不要用 doc->widget 映射（ABIDE 不准）
//     * 直接用：anchorWidgetOffset + insertText.length()
//     * 同时用 selectionProvider 设置 model offset，让 Eclipse/ABIDE selection 同步一致。
//     */
//    private void accept() {
//        if (!active) return;
//
//        final String insertText = suggestion;
//        final int insertModelOffset = anchorModelOffset;
//        final int insertLen = (insertText == null ? 0 : insertText.length());
//
//        // 先取消 UI 监听，避免 docChanged 重入导致错位
//        cancel();
//
//        try {
//            document.replace(insertModelOffset, 0, insertText);
//
//            // 1) model 侧光标：绝对准确
//            int newCaretModel = insertModelOffset + insertLen;
//            try {
//                editor.getSelectionProvider().setSelection(new TextSelection(newCaretModel, 0));
//            } catch (Exception ignore) {}
//
//            // 2) widget 侧光标：用“原 widget 锚点 + 插入长度”计算，不走映射
//            if (!styledText.isDisposed()) {
//                int newCaretWidget = clamp(anchorWidgetOffset + insertLen, 0, styledText.getCharCount());
//                styledText.setCaretOffset(newCaretWidget);
//                styledText.setSelection(newCaretWidget);
//                // showSelection 可能触发滚动，但这是用户主动 Tab 接受，通常期望看到插入位置
//                styledText.showSelection();
//            }
//        } catch (BadLocationException e) {
//            debug("Ghost: document.replace BadLocation: " + e.getMessage());
//        }
//    }

    private void cancel() {
        if (!active) return;
        active = false;
        unhookListeners();
        if (styledText != null && !styledText.isDisposed()) {
            styledText.redraw();
            styledText.update();
        }
        debug("Ghost: canceled");
    }

    private void hookListeners() {
        ghostColor = Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY);

        paintListener = e -> {
            if (!active) return;
            if (styledText.isDisposed()) return;

            int count = styledText.getCharCount();
            int safeOffset = clamp(anchorWidgetOffset, 0, count);

            Point p;
            try {
                p = styledText.getLocationAtOffset(safeOffset);
            } catch (IllegalArgumentException ex) {
                debug("Ghost: getLocationAtOffset failed. anchorWidgetOffset=" + safeOffset + ", ex=" + ex.getMessage());
                return;
            } catch (Exception ex) {
                debug("Ghost: getLocationAtOffset unexpected ex: " + ex.getMessage());
                return;
            }

            drawGhost(e.gc, p.x, p.y);
        };

        keyListener = event -> {
            if (!active) return;

            if (event.keyCode == SWT.ESC) {
                event.doit = false;
                cancel();
                return;
            }

            if (event.keyCode == SWT.TAB) {
                event.doit = false;
                accept();
                return;
            }

            if (event.keyCode == SWT.ARROW_LEFT || event.keyCode == SWT.ARROW_RIGHT
                    || event.keyCode == SWT.ARROW_UP || event.keyCode == SWT.ARROW_DOWN
                    || event.keyCode == SWT.PAGE_UP || event.keyCode == SWT.PAGE_DOWN
                    || event.keyCode == SWT.HOME || event.keyCode == SWT.END) {
                cancel();
                return;
            }
        };

        docListener = new IDocumentListener() {
            @Override public void documentAboutToBeChanged(DocumentEvent event) {}

            @Override
            public void documentChanged(DocumentEvent event) {
                if (!active) return;

                int changeOffsetModel = event.getOffset();
                int replacedLen = event.getLength();
                String newText = event.getText() == null ? "" : event.getText();
                int insertedLen = newText.length();
                int delta = insertedLen - replacedLen;

                int changeStart = changeOffsetModel;
                int changeEnd = changeOffsetModel + replacedLen;

                // Change after anchor: keep
                if (changeStart > anchorModelOffset) return;

                // Overlap anchor: cancel
                if (changeEnd > anchorModelOffset) {
                    cancel();
                    return;
                }

                // Change before anchor: shift anchor (model + widget)
                if (changeEnd <= anchorModelOffset) {
                    anchorModelOffset = anchorModelOffset + delta;
                    if (anchorModelOffset < 0) anchorModelOffset = 0;

                    // widget anchor：优先映射，失败就平移
                    int newAnchorWidget = mapDocOffsetToWidgetOffset(editor, anchorModelOffset);
                    if (newAnchorWidget >= 0) {
                        anchorWidgetOffset = clamp(newAnchorWidget, 0, styledText.getCharCount());
                    } else {
                        anchorWidgetOffset = clamp(anchorWidgetOffset + delta, 0, styledText.getCharCount());
                    }

                    styledText.redraw();
                    styledText.update();
                    return;
                }

                cancel();
            }
        };

        mouseListener = new MouseAdapter() {
            @Override public void mouseDown(MouseEvent e) { cancel(); }
        };

        styledText.addPaintListener(paintListener);
        styledText.addVerifyKeyListener(keyListener);
        styledText.addMouseListener(mouseListener);
        document.addDocumentListener(docListener);
    }

    private void unhookListeners() {
        if (styledText != null && !styledText.isDisposed()) {
            if (paintListener != null) styledText.removePaintListener(paintListener);
            if (keyListener != null) styledText.removeVerifyKeyListener(keyListener);
            if (mouseListener != null) styledText.removeMouseListener(mouseListener);
        }
        if (document != null && docListener != null) document.removeDocumentListener(docListener);

        paintListener = null;
        keyListener = null;
        docListener = null;
        mouseListener = null;
        suggestion = null;
    }

    private void drawGhost(GC gc, int startX, int startY) {
        if (suggestion == null || suggestion.isEmpty()) return;

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

        for (String line : lines) {
            if (!line.isEmpty()) gc.drawText(line, x, y, true);
            y += lineHeight;
            x = leftMargin;
        }

        gc.setFont(oldFont);
        gc.setForeground(oldFg);
        gc.setAlpha(oldAlpha);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static StyledText getStyledText(ITextEditor editor) {
        try {
            ISourceViewer viewer = editor.getAdapter(ISourceViewer.class);
            if (viewer != null && viewer.getTextWidget() != null && !viewer.getTextWidget().isDisposed()) {
                return viewer.getTextWidget();
            }
        } catch (Exception ignore) {}

        try {
            Control c = editor.getAdapter(Control.class);
            if (c instanceof StyledText) return (StyledText) c;
            if (c instanceof Composite) return findStyledText((Composite) c);
        } catch (Exception ignore) {}

        return null;
    }

    public static StyledText findStyledText(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof StyledText) return (StyledText) child;
            if (child instanceof Composite) {
                StyledText st = findStyledText((Composite) child);
                if (st != null) return st;
            }
        }
        return null;
    }

    /** 这个映射在 ABIDE 可能不准，所以只用于“尽力而为”，不要作为关键逻辑依赖 */
    public static int mapDocOffsetToWidgetOffset(ITextEditor editor, int docOffset) {
        try {
            ISourceViewer sv = editor.getAdapter(ISourceViewer.class);
            if (sv == null) return docOffset;

            if (sv instanceof ITextViewerExtension5) {
                return ((ITextViewerExtension5) sv).modelOffset2WidgetOffset(docOffset);
            }
            return docOffset;
        } catch (Exception e) {
            return -1;
        }
    }
    
    private void accept() {
        if (!active) return;

        final String insertText = suggestion;
        final int insertModelOffset = anchorModelOffset;
        final int insertLen = (insertText == null ? 0 : insertText.length());

        cancel(); // 先取消监听，避免 docChanged 重入

        try {
            document.replace(insertModelOffset, 0, insertText);

            final int newCaretModel = insertModelOffset + insertLen;

            // ✅ 只用 model/doc offset 定位光标（不要碰 StyledText caret）
            selectAtModelOffset(editor, newCaretModel);

            // ✅ ABIDE 常会在你这步之后再同步一次 selection/reveal，所以再“延迟二次压回去”
            Display.getDefault().timerExec(0, () -> selectAtModelOffset(editor, newCaretModel));
            Display.getDefault().timerExec(30, () -> selectAtModelOffset(editor, newCaretModel));

        } catch (BadLocationException e) {
            debug("Ghost: document.replace BadLocation: " + e.getMessage());
        }
    }

    private static void selectAtModelOffset(ITextEditor editor, int modelOffset) {
        try {
            // 优先用 selectAndReveal（让 editor 自己处理 model->widget 映射）
            editor.selectAndReveal(modelOffset, 0);
        } catch (Exception ignore) {}

        try {
            if (editor.getSelectionProvider() != null) {
                editor.getSelectionProvider().setSelection(new TextSelection(modelOffset, 0));
            }
        } catch (Exception ignore) {}
    }
}
