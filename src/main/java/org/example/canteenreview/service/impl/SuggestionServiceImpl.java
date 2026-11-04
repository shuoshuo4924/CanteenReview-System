package org.example.canteenreview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.canteenreview.entity.Suggestion;
import org.example.canteenreview.mapper.SuggestionMapper;
import org.example.canteenreview.service.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 建议箱实现：字段清洗 / 状态流转 / 排序规则都在这里。
 * 公开墙只放已回复的留言，用户自己的留言（含待处理）在「我的建议」里单独查。
 */
@Service
public class SuggestionServiceImpl implements SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionServiceImpl.class);

    @Autowired
    private SuggestionMapper suggestionMapper;

    @Override
    @Transactional
    public boolean submit(Suggestion suggestion) {
        if (suggestion == null || suggestion.getUserId() == null) {
            return false;
        }

        String content = suggestion.getContent() == null ? "" : suggestion.getContent().trim();
        if (content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            log.warn("建议内容非法，已拒绝：userId={}, length={}", suggestion.getUserId(), content.length());
            return false;
        }

        // 类型只认白名单，前端传什么奇怪的值都归到「其他」
        String type = suggestion.getType();
        if (type == null || !VALID_TYPES.contains(type)) {
            type = TYPE_OTHER;
        }

        // 前端传来的 id / 状态 / 回复一律不采信，避免被伪造
        suggestion.setId(null);
        suggestion.setType(type);
        suggestion.setContent(content);
        suggestion.setStatus(STATUS_PENDING);
        suggestion.setReply(null);
        suggestion.setReplyTime(null);
        suggestion.setCreateTime(LocalDateTime.now());

        return suggestionMapper.insert(suggestion) > 0;
    }

    @Override
    public Page<Suggestion> pageReplied(Integer page, Integer size) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 6 : Math.min(size, 50);

        QueryWrapper<Suggestion> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_REPLIED)
                // 回复时间可能为空（历史数据），用 id 兜底保证排序稳定
                .orderByDesc("reply_time")
                .orderByDesc("id");
        return suggestionMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
    }

    @Override
    public List<Suggestion> listByUserId(Long userId) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<Suggestion> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .orderByDesc("create_time")
                .orderByDesc("id")
                .last("LIMIT 50");
        return suggestionMapper.selectList(wrapper);
    }

    @Override
    public List<Suggestion> listForAdmin(String status) {
        QueryWrapper<Suggestion> wrapper = new QueryWrapper<>();
        if (STATUS_PENDING.equals(status) || STATUS_REPLIED.equals(status)) {
            wrapper.eq("status", status);
        }
        // 待处理的排前面（布尔排序：PENDING = 1），同状态内按提交时间倒序
        wrapper.last("ORDER BY (status = '" + STATUS_PENDING + "') DESC, create_time DESC, id DESC");
        return suggestionMapper.selectList(wrapper);
    }

    @Override
    public long countPending() {
        QueryWrapper<Suggestion> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PENDING);
        return suggestionMapper.selectCount(wrapper);
    }

    @Override
    @Transactional
    public boolean reply(Long id, String replyContent) {
        if (id == null || replyContent == null || replyContent.isBlank()) {
            return false;
        }
        String text = replyContent.trim();
        if (text.length() > MAX_REPLY_LENGTH) {
            log.warn("回复内容超长，已拒绝：suggestionId={}, length={}", id, text.length());
            return false;
        }
        if (suggestionMapper.selectById(id) == null) {
            log.warn("建议不存在，回复被拒绝：suggestionId={}", id);
            return false;
        }

        // 只更新回复相关字段，其余列保持原样
        Suggestion update = new Suggestion();
        update.setId(id);
        update.setReply(text);
        update.setStatus(STATUS_REPLIED);
        update.setReplyTime(LocalDateTime.now());
        return suggestionMapper.updateById(update) > 0;
    }

    @Override
    public boolean delete(Long id) {
        return id != null && suggestionMapper.deleteById(id) > 0;
    }
}
