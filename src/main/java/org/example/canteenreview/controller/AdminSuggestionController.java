package org.example.canteenreview.controller;

import org.example.canteenreview.service.SuggestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 建议箱后台：查看全部留言、回复、删除。
 * /admin/** 已被 AdminInterceptor 拦截，这里只处理业务。
 */
@Controller
@RequestMapping("/admin/suggestions")
public class AdminSuggestionController {

    @Autowired
    private SuggestionService suggestionService;

    @GetMapping
    public String list(@RequestParam(value = "status", required = false) String status,
                       Model model) {
        model.addAttribute("suggestions", suggestionService.listForAdmin(status));
        model.addAttribute("status", status);
        model.addAttribute("pendingCount", suggestionService.countPending());
        return "admin-suggestion";
    }

    @PostMapping("/reply")
    public String reply(@RequestParam("id") Long id,
                        @RequestParam("reply") String reply,
                        @RequestParam(value = "status", required = false) String status,
                        RedirectAttributes redirectAttributes) {
        boolean success = suggestionService.reply(id, reply);
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 回复已发布" : "❌ 回复失败：建议不存在，或回复内容为空/超过 "
                        + SuggestionService.MAX_REPLY_LENGTH + " 字");
        return "redirect:/admin/suggestions" + safeStatusQuery(status);
    }

    @PostMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "status", required = false) String status,
                         RedirectAttributes redirectAttributes) {
        boolean success = suggestionService.delete(id);
        redirectAttributes.addFlashAttribute(success ? "okMsg" : "errMsg",
                success ? "✅ 建议已删除" : "❌ 删除失败：这条建议可能已经被删掉了");
        return "redirect:/admin/suggestions" + safeStatusQuery(status);
    }

    /**
     * 只允许白名单里的状态拼回地址栏：
     * 直接把用户传的 status 拼进 redirect 会让人塞进任意查询参数。
     */
    private String safeStatusQuery(String status) {
        if (SuggestionService.STATUS_PENDING.equals(status) || SuggestionService.STATUS_REPLIED.equals(status)) {
            return "?status=" + status;
        }
        return "";
    }
}
