package cn.com.csrcb.aiassist;

import com.google.gson.Gson;
import org.eclipse.core.runtime.IProgressMonitor;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AIHttpClient {

    private static final Gson GSON = new Gson();
    private static final Pattern THINK_PATTERN = Pattern.compile("<[^>]*think[^>]*>.*?</[^>]*think[^>]*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // ========== 缓存相关 ==========
    private static final ConcurrentHashMap<String, CachedResult> memoryCache = new ConcurrentHashMap<>();
    private static final String CACHE_FILE = System.getProperty("user.home") + "/.aiassist_cache.ser";
    private static final int MAX_MEMORY_CACHE_SIZE = 200;
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    static {
        loadCacheFromDisk();
    }

    private static class CachedResult implements Serializable {
        private static final long serialVersionUID = 1L;
        String response;
        long timestamp;

        CachedResult(String response) {
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    private static String getCacheKeyInternal(String prompt, String mode) {
        return md5(mode + "|" + prompt);
    }

    private static String getFromCache(String cacheKey) {
        CachedResult cached = memoryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            System.out.println("AIHttpClient: Cache HIT for key=" + cacheKey.substring(0, 8) + "...");
            return cached.response;
        }
        System.out.println("AIHttpClient: Cache MISS for key=" + cacheKey.substring(0, 8) + "...");
        return null;
    }

    private static void putToCache(String cacheKey, String response) {
        if (response == null || response.trim().isEmpty()) return;

        if (memoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
            evictExpiredCache();
        }

        memoryCache.put(cacheKey, new CachedResult(response));
        saveCacheToDiskAsync();
    }

    private static void evictExpiredCache() {
        memoryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        if (memoryCache.size() >= MAX_MEMORY_CACHE_SIZE) {
            List<Map.Entry<String, CachedResult>> sorted = new ArrayList<>(memoryCache.entrySet());
            sorted.sort(Comparator.comparingLong(e -> e.getValue().timestamp));
            int toRemove = MAX_MEMORY_CACHE_SIZE / 2;
            for (int i = 0; i < toRemove && i < sorted.size(); i++) {
                memoryCache.remove(sorted.get(i).getKey());
            }
        }
    }

    private static void saveCacheToDiskAsync() {
        new Thread(() -> {
            try {
                List<CacheEntry> entries = new ArrayList<>();
                for (Map.Entry<String, CachedResult> entry : memoryCache.entrySet()) {
                    entries.add(new CacheEntry(entry.getKey(), entry.getValue().response, entry.getValue().timestamp));
                }
                synchronized (AIHttpClient.class) {
                    try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CACHE_FILE))) {
                        oos.writeObject(entries);
                    }
                }
            } catch (Exception e) {
                System.out.println("AIHttpClient: Failed to save cache: " + e.getMessage());
            }
        }).start();
    }

    private static void loadCacheFromDisk() {
        File cacheFile = new File(CACHE_FILE);
        if (!cacheFile.exists()) return;

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(cacheFile))) {
            @SuppressWarnings("unchecked")
            List<CacheEntry> entries = (List<CacheEntry>) ois.readObject();
            for (CacheEntry entry : entries) {
                if (!entry.isExpired()) {
                    memoryCache.put(entry.key, new CachedResult(entry.response));
                }
            }
            System.out.println("AIHttpClient: Loaded " + memoryCache.size() + " cached entries from disk");
        } catch (Exception e) {
            System.out.println("AIHttpClient: Failed to load cache: " + e.getMessage());
        }
    }

    private static class CacheEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        String key;
        String response;
        long timestamp;

        CacheEntry(String key, String response, long timestamp) {
            this.key = key;
            this.response = response;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public static void clearCache() {
        memoryCache.clear();
        File f = new File(CACHE_FILE);
        if (f.exists()) f.delete();
    }

    public static int getCacheSize() {
        return memoryCache.size();
    }

    /**
     * 生成缓存key（公开给外部使用）
     */
    public static String getCacheKey(String prompt, String mode) {
        return md5(mode + "|" + prompt);
    }

    /**
     * 查询缓存结果（公开给外部使用，用于先检查缓存再显示）
     */
    public static String getCachedResult(String cacheKey) {
        return getFromCache(cacheKey);
    }

    /**
     * 预缓存结果（公开给外部使用）
     */
    public static void cacheResult(String cacheKey, String response) {
        putToCache(cacheKey, response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
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
            return json;
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

    public static String callAICompletion(String contextText, int cursorInContext, IProgressMonitor monitor) {
        String prompt = "你是java代码专家。根据上下文在光标位置继续补全可能需要的代码。只输出要插入的代码，不要解释，不要markdown。最多补全2行代码。上下文为:" + contextText + "\n光标位置在:" + cursorInContext + "\n";

        String cacheKey = getCacheKey(prompt, "completion");
        String cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        String result = callAI(prompt, monitor);
        if (result != null && !result.startsWith("\n// AI")) {
            putToCache(cacheKey, result);
        }
        return result;
    }

    public static String callAI(String prompt, IProgressMonitor monitor) {
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

            Map<String, Object> body = new HashMap<String, Object>();

            Map<String, String> systemMessage = new HashMap<String, String>();
            systemMessage.put("role", "system");
            systemMessage.put("content", "你是专业的Java代码专家，负责对用户问题进行准确回答。\n");

            Map<String, String> userMessage = new HashMap<String, String>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            body.put("messages", new Object[] { systemMessage, userMessage });
            body.put("temperature", 0);
            body.put("model", "Qwen3-235B");
            body.put("max_tokens", 2048);

            String jsonBody = GSON.toJson(body);
            System.out.print("send:"+jsonBody);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes("UTF-8");
                os.write(input);
            }

            int code = connection.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            String resp = readAllUtf8(is);
            System.out.print("recv:"+resp);

            if (code < 200 || code >= 300) {
                return "\n// AI HTTP error: " + code + "\n" + resp + "\n";
            }

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

            Map<String, Object> item = new HashMap<String, Object>();
            Map<String, String> userMessage = new HashMap<String, String>();
            String system_prompt = "# 角色 你现在是专业的java代码专家，你的核心职责对于询问的java代码问题进行解答。\n";

            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            Map<String, String> systemMessage = new HashMap<String, String>();
            systemMessage.put("role", "system");
            systemMessage.put("content", system_prompt);

            item.put("messages", new Object[] { userMessage, systemMessage });
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
                return null;
            }

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
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    public static String callAICachedWithTimeout(String prompt, String cacheMode, int timeoutMs, IProgressMonitor monitor) {
        String cacheKey = getCacheKey(prompt, cacheMode);
        String cached = getFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        String result = callAIWithTimeout(prompt, timeoutMs, monitor);
        if (result != null) {
            putToCache(cacheKey, result);
        }
        return result;
    }
}
