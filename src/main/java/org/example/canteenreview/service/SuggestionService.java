package org.example.canteenreview.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.canteenreview.entity.Suggestion;

import java.util.List;
import java.util.Set;

/**
 * 建议箱业务：用户提交 / 公开查看 / 我的建议 / 管理员回复与删除。
 * 字段校验和状态流转都在实现类，Controller 只负责接参数和跳转。
 */
public interface SuggestionService {

    // 类型码（页面显示用 Suggestion#getTypeLabel 转换）
    String TYPE_DISH = "DISH";
    String TYPE_CANTEEN = "CANTEEN";
    String TYPE_SYSTEM = "SYSTEM";
    String TYPE_OTHER = "OTHER";

    // 状态码
    String STATUS_PENDING = "PENDING";
    String STATUS_REPLIED = "REPLIED";

    // 与表字段长度保持一致，超长在入库时会抛 SQL 异常
    int MAX_CONTENT_LENGTH = 500;
    int MAX_REPLY_LENGTH = 500;

    Set<String> VALID_TYPES = Set.of(TYPE_DISH, TYPE_CANTEEN, TYPE_SYSTEM, TYPE_OTHER);

    /**
     * 提交建议：调用方需先填好 userId / author。
     * 内容为空、超长、类型非法时返回 false（不会写库）。
     */
    boolean submit(Suggestion suggestion);

    /** 公开墙：只展示已回复的建议，按回复时间倒序 */
    Page<Suggestion> pageReplied(Integer page, Integer size);

    /** 「我的建议」：登录用户自己的全部留言（含待处理），按提交时间倒序 */
    List<Suggestion> listByUserId(Long userId);

    /** 后台列表：status 为空表示全部 */
    List<Suggestion> listForAdmin(String status);

    /** 待处理条数，用于后台导航提示 */
    long countPending();

    /** 管理员回复：内容为空或建议不存在时返回 false */
    boolean reply(Long id, String replyContent);

    /** 删除建议（管理员） */
    boolean delete(Long id);
}
