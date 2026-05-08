package cn.com.csrcb.aiassist;

public class PromptTemplates {

	public enum SelectionMode {
		GENERATE_CODE, // 根据提问生成代码（通常替换选中或插入）
		EXPLAIN_CN, // 中文解释代码含义
		SUMMARIZE_CN, // 中文总结（更短）
		REFACTOR // 重构/优化建议
	}

	public static String buildSelectionTaskPrompt(String selectedCode, String userQuestion, SelectionMode mode) {
		String header = "你是资深Java工程师。请严格基于给定内容回答。中文输出。不要编造。\n";
		String task;

		switch (mode) {
		case EXPLAIN_CN:
			task = "任务：用中文详细解释这段代码的作用、关键逻辑、输入输出、可能的边界情况；如有问题给出改进建议。\n";
			break;
		case SUMMARIZE_CN:
			task = "任务：用中文简要总结这段代码做什么（3-6条要点），并指出1-2个潜在风险。\n";
			break;
		case GENERATE_CODE:
			task = "任务：根据用户问题生成对应的Java代码。优先给出可运行的完整代码（含必要import与示例）。\n" + "如果用户的问题与选中代码有关，请结合选中代码生成。\n";
			break;
		case REFACTOR:
		default:
			task = "任务：指出这段代码可优化点，并给出一份更好的Java实现（或伪代码），说明改动理由。\n";
			break;
		}
		

		return header + task + "\n【用户问题】\n" + userQuestion + "\n" + "\n【选中代码】\n" + selectedCode + "\n";
	}

	/**
	 * 构建基础补全 Prompt
	 */
	public static String buildBasicCompletionPrompt(String contextText, int cursorInContext) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("你是Java代码专家。在光标位置生成完整、可直接使用的代码补全。\n\n");
		prompt.append("要求：\n");
		prompt.append("1. 只输出纯代码，不要任何解释，不要markdown代码块标记\n");
		prompt.append("2. 生成完整的代码语句或代码块（包含完整的{和}），不要截断\n");
		prompt.append("3. 代码要符合Java语法，与上下文风格完全一致\n");
		prompt.append("4. 如果是方法体，补全完整的方法实现\n");
		prompt.append("5. 如果是语句，补全完整的语句（包含分号）\n\n");
		prompt.append("【代码上下文】\n");
		prompt.append(contextText);
		prompt.append("\n\n只输出补全的代码：");
		return prompt.toString();
	}

	/**
	 * 构建增强补全 Prompt（使用 AST 信息）
	 */
	public static String buildEnhancedCompletionPrompt(
			String contextText,
			ASTAnalyzer.EnhancedContext astContext,
			int cursorInContext) {

		StringBuilder prompt = new StringBuilder();
		prompt.append("你是Java代码专家。在光标位置生成完整、可直接使用的代码补全。\n\n");

		prompt.append("要求：\n");
		prompt.append("1. 只输出纯代码，不要任何解释，不要markdown代码块标记\n");
		prompt.append("2. 生成完整的代码语句或代码块（包含完整的{和}），不要截断\n");
		prompt.append("3. 代码要符合Java语法，与上下文风格完全一致\n");
		prompt.append("4. 如果是方法体，补全完整的方法实现\n");
		prompt.append("5. 如果是语句，补全完整的语句（包含分号）\n\n");

		// AST 上下文信息
		if (astContext != null) {
			prompt.append("【代码结构信息】\n");

			// 当前类
			if (astContext.currentClass.name != null) {
				prompt.append("当前类: ").append(astContext.currentClass.qualifiedName).append("\n");

				if (astContext.currentClass.typeHierarchy.superclass != null) {
					prompt.append("父类: ").append(astContext.currentClass.typeHierarchy.qualifiedSuperclass).append("\n");
				}

				if (!astContext.currentClass.typeHierarchy.interfaces.isEmpty()) {
					prompt.append("实现的接口: ").append(String.join(", ", astContext.currentClass.typeHierarchy.qualifiedInterfaces)).append("\n");
				}
			}

			// 当前方法
			if (astContext.currentMethodSignature != null) {
				prompt.append("当前方法: ").append(astContext.currentMethodSignature).append("\n");
			}

			// 可用变量
			if (!astContext.availableVariables.isEmpty()) {
				prompt.append("可用变量:\n");
				for (ASTAnalyzer.VariableInfo var : astContext.availableVariables) {
					prompt.append("  - ").append(var.type).append(" ").append(var.name).append("\n");
				}
			}

			// 类字段
			if (!astContext.classFields.isEmpty()) {
				prompt.append("类字段:\n");
				for (String field : astContext.classFields) {
					prompt.append("  - ").append(field).append("\n");
				}
			}

			// 类方法
			if (!astContext.classMethods.isEmpty()) {
				prompt.append("类方法:\n");
				int count = 0;
				for (String method : astContext.classMethods) {
					prompt.append("  - ").append(method).append("\n");
					count++;
					if (count >= 10) {
						prompt.append("  - ... (更多方法)\n");
						break;
					}
				}
			}

			prompt.append("\n");
		}

		// 代码上下文
		prompt.append("【待补全代码】\n");
		prompt.append(contextText);
		prompt.append("\n\n只输出补全的代码：");
		return prompt.toString();
	}
}
