package cn.com.csrcb.aiassist;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;

import com.google.gson.Gson;

public class AIHttpClient {

	private static final Gson GSON = new Gson();
	private static final Pattern THINK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

	// 兼容：choices[0].message.content 或 choices[0].text
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static String tryExtractContentFromJson(String json) {
		try {
			Map m = GSON.fromJson(json, HashMap.class);
			Object choicesObj = m.get("choices");
			if (!(choicesObj instanceof List) || ((List) choicesObj).isEmpty())
				return null;

			Object c0 = ((List) choicesObj).get(0);
			if (!(c0 instanceof Map))
				return null;

			Map c0m = (Map) c0;
			Object msgObj = c0m.get("message");
			if (msgObj instanceof Map) {
				Object content = ((Map) msgObj).get("content");
				return content == null ? null : String.valueOf(content);
			}
			Object text = c0m.get("text");
			return text == null ? null : String.valueOf(text);
		} catch (Exception ex) {
			// 如果不是 JSON（比如 data: 直接是文本），就直接返回原文
			return json;
		}
	}

	public static String callAICompletion(String contextText, int cursorInContext, IProgressMonitor monitor) {
		// prompt：给补全用
		String prompt = "" + "你是java代码专家。根据上下文在光标位置继续补全可能需要的代码。" + "只输出要插入的代码，不要解释，不要markdown。最多补全2行代码" + "上下文为:" + contextText
				+ "\n" + "光标位置在:" + cursorInContext + "\n";
		return callAI(prompt, monitor);
	}

	public static String callAI(String prompt, IProgressMonitor monitor) {
		// 如果你后端是 chat 协议，通常更像 /v1/chat/completions（看你网关是否兼容）
		String urlStr = "http://170.100.147.31:8080/v1/completions";
		String apiKey = "f6173988-16ff-4ba4-9aa8-0a44eb1c341c";

		HttpURLConnection connection = null;
		try {
			if (monitor != null && monitor.isCanceled())
				return "";

			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(30000);
			connection.setDoOutput(true);

			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("Authorization", "Bearer " + apiKey);

			// ---- body（按你现在的 choices[0].message.content 解析，继续用 messages 结构）----
			Map<String, Object> body = new HashMap<String, Object>();

			Map<String, String> systemMessage = new HashMap<String, String>();
			systemMessage.put("role", "system");
			systemMessage.put("content", "你是专业的Java代码专家，负责对用户问题进行准确回答。\n");

			Map<String, String> userMessage = new HashMap<String, String>();
			userMessage.put("role", "user");
			userMessage.put("content", prompt);

			// 建议：system 放前面
			body.put("messages", new Object[] { systemMessage, userMessage });

			body.put("temperature", 0);
			body.put("model", "Qwen3-235B");
			body.put("max_tokens", 2048);

			String jsonBody = GSON.toJson(body);

			try (OutputStream os = connection.getOutputStream()) {
				byte[] input = jsonBody.getBytes("UTF-8");
				os.write(input);
				
			}
			System.out.print("send:"+jsonBody);
			int code = connection.getResponseCode();
			InputStream is = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			String resp = readAllUtf8(is);
			System.out.print("recv:"+resp);
			if (code < 200 || code >= 300) {
				return "\n// AI HTTP error: " + code + "\n" + resp + "\n";
			}

			// 解析：choices[0].message.content
			@SuppressWarnings("unchecked")
			Map<String, Object> resultMap = GSON.fromJson(resp, HashMap.class);

			Object choicesObj = resultMap.get("choices");
			if (!(choicesObj instanceof java.util.List) || ((java.util.List) choicesObj).isEmpty()) {
				return "\n// AI response missing choices\n" + resp + "\n";
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> choice0 = (Map<String, Object>) ((java.util.List) choicesObj).get(0);

			@SuppressWarnings("unchecked")
			Map<String, Object> message = (Map<String, Object>) choice0.get("message");
			if (message == null) {
				// 有些接口是 choice0.get("text")
				Object text = choice0.get("text");
				if (text instanceof String) {
					return removeThinkTags((String) text);
				}
				return "\n// AI response missing message\n" + resp + "\n";
			}

			Object content = message.get("content");
			if (!(content instanceof String)) {
				return "\n// AI response missing content\n" + resp + "\n";
			}

			return removeThinkTags((String) content);

		} catch (Exception e) {
			return "\n// AI exception: " + e.getClass().getName() + ": " + e.getMessage() + "\n";
		} finally {
			if (connection != null)
				connection.disconnect();
		}
	}

	private static String removeThinkTags(String input) {
		if (input == null)
			return "";
		String s = THINK_PATTERN.matcher(input).replaceAll("");
		return s.trim();
	}

	private static String readAllUtf8(InputStream is) throws IOException {
		if (is == null)
			return "";
		StringBuilder sb = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line).append("\n");
			}
		}
		return sb.toString();
	}

	public static String callAIWithTimeout(String prompt, int timeoutMs, IProgressMonitor monitor) {
		HttpURLConnection connection = null;
		try {
			if (monitor != null && monitor.isCanceled())
				return null;

			String urlStr = "http://170.100.147.31:8080/v1/completions";
			String apiKey = "f6173988-16ff-4ba4-9aa8-0a44eb1c341c";

			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(Math.min(5000, timeoutMs));
			connection.setReadTimeout(timeoutMs);
			connection.setDoOutput(true);

			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("Authorization", "Bearer " + apiKey);

			// 构造 body（你原来的）
//            Map<String, Object> body = new HashMap<String, Object>();
//            Map<String, String> systemMessage = new HashMap<String, String>();
//            systemMessage.put("role", "system");
//            systemMessage.put("content", "# 角色\n你是专业的Java代码专家，负责对用户问题进行准确回答。\n");
//
//            Map<String, String> userMessage = new HashMap<String, String>();
//            userMessage.put("role", "user");
//            userMessage.put("content", prompt);
//
//            body.put("messages", new Object[] { systemMessage, userMessage });
//            body.put("temperature", 0);
//            body.put("model", "Qwen3-235B");
//            body.put("max_tokens", 2048);
			// 设置请求体
			Map<String, Object> item = new HashMap<String, Object>();
			Map<String, String> userMessage = new HashMap<String, String>();
			String user_prompt = "";
			String system_prompt = "# 角色 你现在是专业的java代码专家，你的核心职责对于询问的java代码问题进行解答。\n";

			userMessage.put("role", "user");
			userMessage.put("content", prompt);

			Map<String, String> systemMessage = new HashMap<String, String>();
			systemMessage.put("role", "system");
			systemMessage.put("content", system_prompt);

			item.put("messages", new Object[] { userMessage, systemMessage });
//            item.put("model", "Qwen3-235B");
			item.put("temperature", 0);
			item.put("model", "Qwen3-235B");
			item.put("max_tokens", 2048);

			String jsonBody = GSON.toJson(item);
			System.out.print("send:"+jsonBody);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(jsonBody.getBytes("UTF-8"));
			}

			int code = connection.getResponseCode();
			InputStream is = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
			String resp = readAllUtf8(is);
			System.out.print("recv:"+resp);
			if (code < 200 || code >= 300) {
				return null; // 你也可以返回错误信息给 UI
			}

			// 解析 choices[0].message.content
			@SuppressWarnings("unchecked")
			Map<String, Object> resultMap = GSON.fromJson(resp, HashMap.class);
			Object choicesObj = resultMap.get("choices");
			if (!(choicesObj instanceof java.util.List) || ((java.util.List) choicesObj).isEmpty())
				return null;

			@SuppressWarnings("unchecked")
			Map<String, Object> choice0 = (Map<String, Object>) ((java.util.List) choicesObj).get(0);

			@SuppressWarnings("unchecked")
			Map<String, Object> message = (Map<String, Object>) choice0.get("message");
			if (message != null && message.get("content") instanceof String) {
				return removeThinkTags((String) message.get("content"));
			}

			Object text = choice0.get("text");
			if (text instanceof String)
				return removeThinkTags((String) text);

			return null;

		} catch (java.net.SocketTimeoutException te) {
			return null; // 超时 -> 上层显示“稍后重试”
		} catch (Exception e) {
			return null;
		} finally {
			if (connection != null)
				connection.disconnect();
		}
	}

}
