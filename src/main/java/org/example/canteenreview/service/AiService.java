package org.example.canteenreview.service;

import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.entity.Review;
import org.example.canteenreview.entity.Suggestion;

import java.util.List;
import java.util.Map;

/**
 * AI 能力：一句话评价总结 + 多轮对话 + 建议箱后台助手。
 * 只接收"素材"（菜名、评价列表、建议列表、对话历史），拼 Prompt、调接口、解析结果都在实现类里。
 */
public interface AiService {

    // 一句话总结某道菜的评价
    String summarizeReviews(String dishName, List<Review> reviews);

    // 多轮对话，返回 AI 的回复文本
    String chat(List<Map<String, String>> history);

    // 建议箱后台对话：以最近的留言为背景，帮管理员归纳问题或起草回复
    String chatAboutSuggestions(List<Suggestion> suggestions, List<Map<String, String>> history);

    /**
     * 根据菜品信息 + 评价生成一句健康建议（营养角度、20~40 字）。
     * AI 不可用或未配置密钥时返回 null，调用方据此跳过而不是把错误文案存进库。
     */
    String generateHealthTip(Dish dish, List<Review> reviews);
}
