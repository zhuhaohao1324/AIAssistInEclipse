package cn.com.csrcb.aiassist;

public class PromptTemplates {

	public enum SelectionMode {
		EXPLAIN_CN, // 中文解释代码含义
		SUMMARIZE_CN, // 中文总结（更短）
		GENERATE_CODE, // 根据提问生成代码（通常替换选中或插入）
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
}
