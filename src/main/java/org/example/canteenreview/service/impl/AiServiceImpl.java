package org.example.canteenreview.service.impl;

import jakarta.annotation.PostConstruct;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.Suggestion;
import org.example.canteenreview.service.AiService;
import org.example.canteenreview.service.DishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * DeepSeek 调用实现。
 * 除了 HTTP 细节，Prompt 组装、对话历史裁剪、评价文本截断这些规则也都收在这里，
 * Controller 只负责传素材和返回结果。
 */
@Service
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    // 对话历史最多带 10 条、单条最多 300 字，防止有人拼超长文本刷 token
    private static final int MAX_HISTORY = 10;
    private static final int MAX_MESSAGE_LENGTH = 300;

    // 评价素材最多 20 条、总长 2000 字
    private static final int MAX_REVIEWS = 20;
    private static final int MAX_REVIEW_TEXT = 2000;

    // 建议箱助手最多喂 20 条留言，单条内容截断到 100 字
    private static final int MAX_SUGGESTIONS_FOR_AI = 20;
    private static final int MAX_SUGGESTION_TEXT = 100;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    // 对话需要给 AI 一份菜单背景
    @Autowired
    private DishService dishService;

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

    @Override
    public String summarizeReviews(String dishName, List<Review> reviews) {
        String reviewsText = buildReviewText(reviews);
        if (reviewsText.isBlank()) {
            return "还没有人写评价，AI 也没法总结这道菜~";
        }

        String prompt = "你是一个校园美食评论家。这道菜叫【" + dishName + "】。\n" +
                "以下是同学们的评价：\n" + reviewsText + "\n" +
                "请用一句话（不超过40个字）总结这道菜的优缺点，语气要像大学生一样接地气。";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        return send(messages, 100);
    }

    @Override
    public String chat(List<Map<String, String>> history) {
        List<Map<String, String>> safeHistory = sanitizeHistory(history);
        if (safeHistory.isEmpty()) {
            return "想问点什么呢？比如「今天吃什么」";
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildMenuPrompt()));
        messages.addAll(safeHistory);
        return send(messages, 300);
    }

    @Override
    public String chatAboutSuggestions(List<Suggestion> suggestions, List<Map<String, String>> history) {
        List<Map<String, String>> safeHistory = sanitizeHistory(history);
        if (safeHistory.isEmpty()) {
            return "想问点什么呢？比如「帮我总结一下大家的意见」或「给这条建议起草一条回复」";
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSuggestionPrompt(suggestions)));
        messages.addAll(safeHistory);
        return send(messages, 400);
    }

    /**
     * 建议箱助手的背景资料：只喂类型、状态和内容（单条截断、总数限制）。
     */
    private String buildSuggestionPrompt(List<Suggestion> suggestions) {
        StringBuilder sb = new StringBuilder(
                "你是「吃啥饭」校园食堂评价系统的后台助手。语气专业、直接，回答控制在 150 字以内。"
                        + "管理员可能让你归纳共性问题、判断优先级，或者为某条建议起草一条回复。");
        if (suggestions == null || suggestions.isEmpty()) {
            sb.append("目前还没有收到建议。");
            return sb.toString();
        }

        sb.append("最近的留言（从上到下）：\n");
        int count = 0;
        for (Suggestion suggestion : suggestions) {
            if (suggestion == null || suggestion.getContent() == null || suggestion.getContent().isBlank()) {
                continue;
            }
            String content = suggestion.getContent().trim();
            if (content.length() > MAX_SUGGESTION_TEXT) {
                content = content.substring(0, MAX_SUGGESTION_TEXT) + "…";
            }
            sb.append("- [")
                    .append(suggestion.isReplied() ? "已回复" : "待处理")
                    .append("][")
                    .append(suggestion.getTypeLabel())
                    .append("] ")
                    .append(content)
                    .append('\n');
            if (++count >= MAX_SUGGESTIONS_FOR_AI) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 裁剪对话历史：只保留最近 10 条、只允许 user/assistant、单条截断到 300 字。
     */
    private List<Map<String, String>> sanitizeHistory(List<Map<String, String>> history) {
        List<Map<String, String>> safe = new ArrayList<>();
        if (history == null || history.isEmpty()) {
            return safe;
        }
        List<Map<String, String>> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size());
        for (Map<String, String> msg : recent) {
            if (msg == null) {
                continue;
            }
            String role = msg.get("role");
            String content = msg.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String text = content.trim();
            safe.add(Map.of("role", role,
                    "content", text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text));
        }
        return safe;
    }

    /**
     * 给 AI 的菜单背景：带上当前评分最高的 10 道菜，回答才不会瞎编。
     */
    private String buildMenuPrompt() {
        StringBuilder sb = new StringBuilder(
                "你是「吃啥饭」校园食堂的 AI 点菜助手，语气轻松、像大学生平时说话，回答控制在 80 字以内。");
        List<Dish> top = dishService.getTop10Dishes();
        if (!top.isEmpty()) {
            sb.append("当前评分较高的菜品有：");
            for (Dish dish : top) {
                sb.append(dish.getName())
                        .append("（")
                        .append(dish.getCanteen() == null ? "食堂未知" : dish.getCanteen())
                        .append("，")
                        .append(dish.getAvgRating() == null ? "暂无评分" : dish.getAvgRating() + "分")
                        .append("，")
                        .append(dish.getPrice() == null ? "价格未知" : dish.getPrice() + "元")
                        .append("）；");
            }
            sb.append("推荐时优先从这些菜里挑。");
        }
        return sb.toString();
    }

    /**
     * 把评价拼成给 AI 的文本：只取有文字内容的，最多 20 条、总长 2000 字。
     */
    private String buildReviewText(List<Review> reviews) {
        StringBuilder sb = new StringBuilder();
        if (reviews == null) {
            return "";
        }
        int count = 0;
        for (Review review : reviews) {
            if (review.getComment() == null || review.getComment().isBlank()) {
                continue;
            }
            String line = "- " + review.getRating() + " 星：" + review.getComment().trim() + "\n";
            if (sb.length() + line.length() > MAX_REVIEW_TEXT) {
                break;
            }
            sb.append(line);
            if (++count >= MAX_REVIEWS) {
                break;
            }
        }
        return sb.toString();
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
