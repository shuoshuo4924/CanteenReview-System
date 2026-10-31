package org.example.canteenreview.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力：调用 DeepSeek。
 * 1) summarizeReviews：把一堆评价压成一句话（详情页"生成总结"按钮）
 * 2) chat：多轮对话（首页右下角 AI 对话框）
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    // Spring 自带的发送网络请求的工具；必须设超时，否则接口卡住时页面线程会一直挂着
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return new RestTemplate(factory);
    }

    /**
     * 启动时打一条日志，方便确认环境变量是否真的被读到
     * （环境变量改完必须重启应用，运行中的进程不会自动刷新）。
     */
    @PostConstruct
    private void checkApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未读到 deepseek.api-key：请设置环境变量 DEEPSEEK_API_KEY 后重启应用，AI 功能将不可用");
            return;
        }
        String tail = apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "";
        log.info("DeepSeek 配置就绪：model={}, api-key=****{}", model, tail);
    }

    /**
     * 一句话总结某道菜的评价。
     */
    public String summarizeReviews(String dishName, String reviewsText) {
        String prompt = "你是一个校园美食评论家。这道菜叫【" + dishName + "】。\n" +
                "以下是同学们的评价：\n" + reviewsText + "\n" +
                "请用一句话（不超过40个字）总结这道菜的优缺点，语气要像大学生一样接地气。";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        return send(messages, 100);
    }

    /**
     * 多轮对话。
     * @param history      前端传上来的对话记录（只含 user / assistant）
     * @param systemPrompt 背景设定（比如给 AI 一份菜单）
     */
    public String chat(List<Map<String, String>> history, String systemPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(history);
        return send(messages, 300);
    }

    /**
     * 统一的请求发送 + 返回解析。
     */
    private String send(List<Map<String, String>> messages, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 deepseek.api-key，AI 功能不可用");
            return "AI 还没配置密钥，暂时用不了~";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("max_tokens", maxTokens); // 限制回复长度，省钱
        requestBody.put("temperature", 0.7);      // 稍微活泼一点

        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    API_URL, new HttpEntity<>(requestBody, headers), Map.class);

            String content = extractContent(response.getBody());
            if (content != null && !content.isBlank()) {
                return content;
            }
            log.warn("AI 返回内容为空：{}", response.getBody());
        } catch (Exception e) {
            log.warn("调用 DeepSeek 失败：{}", e.getMessage());
            return "AI 老师傅正在休息，请稍后再试~";
        }
        return "暂时无法生成回复。";
    }

    /**
     * 从 DeepSeek 返回的 JSON 里取出 choices[0].message.content。
     * 用 instanceof 逐层判断，避免返回结构异常时直接 NPE。
     */
    private String extractContent(Map<?, ?> body) {
        if (body == null || !(body.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return null;
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            return null;
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        Object content = message.get("content");
        return content == null ? null : content.toString().trim();
    }
}
