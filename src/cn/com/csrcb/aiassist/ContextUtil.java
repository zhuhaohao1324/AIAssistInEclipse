package cn.com.csrcb.aiassist;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

public class ContextUtil {

	public static String getContextWindow(IDocument doc, int offset, int before, int after)
			throws BadLocationException {
		int len = doc.getLength();
		int start = Math.max(0, offset - before);
		int end = Math.min(len, offset + after);
		return doc.get(start, end - start);
	}

	public static int cursorInContext(IDocument doc, int offset, int before) {
		int start = Math.max(0, offset - before);
		return offset - start;
	}
}
