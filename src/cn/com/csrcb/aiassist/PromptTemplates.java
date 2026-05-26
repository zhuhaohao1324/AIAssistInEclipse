package cn.com.csrcb.aiassist;

public class PromptTemplates {

	public enum SelectionMode {
		GENERATE_CODE,
		EXPLAIN_CN,
		SUMMARIZE_CN,
		REFACTOR
	}

	public static String buildSelectionTaskPrompt(String selectedCode, String userQuestion, SelectionMode mode) {
		String header = "你是资深Java工程师.请严格基于给定内容回答.条理清晰.不要废话.\n";
		String task;

		switch (mode) {
		case EXPLAIN_CN:
			task = "任务:用中文详细解释这段代码的作用、关键逻辑、依赖与调用关系，以及可能的边界情况或改进建议.\n";
			break;
		case SUMMARIZE_CN:
			task = "任务:用中文简要总结这段代码做什么(3-6要点),并指出1-2个潜在风险.\n";
			break;
		case GENERATE_CODE:
			task = "任务:根据用户描述生成对应的Java代码.优先复用上下文中的已有类型(必要时给出import提示).\n"
					+ "若用户描述与选中代码有关,请基于选中代码改进.\n";
			break;
		case REFACTOR:
		default:
			task = "任务:指出这段代码可优化点,并给出一份更好的Java实现(可伪代码),说明改动理由.\n";
			break;
		}

		return header + task + "\n[用户问题]\n" + userQuestion + "\n" + "\n[选中代码]\n" + selectedCode + "\n";
	}

	/**
	 * Alt+Q 行内补全：只补 1-2 行，提高采纳率
	 */
	public static String buildInlineCompletionPrompt(String contextText, int cursorInContext) {
		String currentLine = extractCurrentLine(contextText, cursorInContext);
		StringBuilder prompt = new StringBuilder();
		prompt.append("你是Java行内补全助手，类似IDE Copilot。\n");
		prompt.append("任务：在光标处只补「接下来要输入的一小段代码」，不要生成整段方法或整块逻辑。\n\n");
		prompt.append("硬性规则：\n");
		prompt.append("1. 最多输出1-2行代码，总行数不超过2行\n");
		prompt.append("2. 只输出纯代码，不要解释、不要markdown、不要```\n");
		prompt.append("3. 不要重复「当前行光标前已经写过的内容」，只输出光标之后的续写\n");
		prompt.append("4. 优先补全：当前语句剩余部分、方法调用的参数/分号、表达式后半段\n");
		prompt.append("5. 不要新开 if/for/while 整块、不要补全整个方法体\n");
		prompt.append("6. 与上下文缩进、命名、风格一致\n\n");
		if (!currentLine.isEmpty()) {
			prompt.append("当前行（光标前）: ").append(currentLine).append("\n");
		}
		prompt.append("\n上下文代码:\n");
		prompt.append(contextText);
		prompt.append("\n\n只输出光标处续写的1-2行代码:");
		return prompt.toString();
	}

	/**
	 * 行内补全 + 精简 AST 上下文
	 */
	public static String buildInlineCompletionPromptWithAst(
			String contextText,
			ASTAnalyzer.EnhancedContext astContext,
			int cursorInContext) {

		StringBuilder prompt = new StringBuilder();
		prompt.append("你是Java行内补全助手。\n");
		prompt.append("任务：光标处只补1-2行续写代码，不要整段实现。\n\n");
		prompt.append("硬性规则：最多2行；只输出光标后续写；不要markdown；不要重复当前行已有内容；不要新开大块结构。\n\n");

		if (astContext != null) {
			if (astContext.currentMethodSignature != null) {
				prompt.append("当前方法: ").append(astContext.currentMethodSignature).append("\n");
			}
			if (!astContext.availableVariables.isEmpty()) {
				prompt.append("可用变量: ");
				int n = 0;
				for (ASTAnalyzer.VariableInfo var : astContext.availableVariables) {
					if (n > 0) {
						prompt.append(", ");
					}
					prompt.append(var.type).append(" ").append(var.name);
					if (++n >= 8) {
						break;
					}
				}
				prompt.append("\n");
			}
			prompt.append("\n");
		}

		String currentLine = extractCurrentLine(contextText, cursorInContext);
		if (!currentLine.isEmpty()) {
			prompt.append("当前行（光标前）: ").append(currentLine).append("\n");
		}
		prompt.append("上下文:\n").append(contextText);
		prompt.append("\n\n只输出1-2行续写:");
		return prompt.toString();
	}

	private static String extractCurrentLine(String contextText, int cursorInContext) {
		if (contextText == null || cursorInContext < 0) {
			return "";
		}
		int lineStart = cursorInContext;
		while (lineStart > 0 && contextText.charAt(lineStart - 1) != '\n') {
			lineStart--;
		}
		return contextText.substring(lineStart, Math.min(cursorInContext, contextText.length())).trim();
	}

	public static String buildBasicCompletionPrompt(String contextText, int cursorInContext) {
		return buildInlineCompletionPrompt(contextText, cursorInContext);
	}

	public static String buildEnhancedCompletionPrompt(
			String contextText,
			ASTAnalyzer.EnhancedContext astContext,
			int cursorInContext) {
		return buildInlineCompletionPromptWithAst(contextText, astContext, cursorInContext);
	}
}
