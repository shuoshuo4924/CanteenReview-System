package org.example.canteenreview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CanteenReviewApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    /**
     * 评价提交在同一个地址上挂了两份映射：带 X-Requested-With 的回 JSON（页面不刷新），
     * 不带的回 302（普通表单/脚本）。这里确认分流生效、两份映射不冲突。
     * 未登录由拦截器拦下，顺带验证它给 AJAX 回的是 JSON 而不是登录页 HTML。
     */
    @Test
    void reviewSubmitWithoutLoginReturnsJsonForAjax() throws Exception {
        mockMvc.perform(post("/dish/review")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("dishId", "1")
                        .param("rating", "5")
                        .param("comment", "好吃"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.redirect").value("/login"));
    }

    /**
     * 安全响应头必须出现在每个页面上。
     * 顺带验证 GET /login 能正常渲染模板 —— 过滤器加了头不该影响正常响应。
     */
    @Test
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                // 本地是 http，不能下发 HSTS，否则浏览器会把开发环境锁死在 https
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void hstsIsSentOnlyOverHttps() throws Exception {
        mockMvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }

    /**
     * CSRF：不带 token 的 POST 一律拒绝。
     * 这是 Origin 校验挡不住的那部分（无 Origin 的请求会被放行），必须靠 token 兜底。
     */
    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "someone")
                        .param("password", "whatever"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ajaxPostWithoutCsrfTokenGetsJson() throws Exception {
        mockMvc.perform(post("/ai/chat")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** 页面里带上了 token，提交就能正常走完（这里用错误的账号密码验证走到了登录逻辑） */
    @Test
    void postWithCsrfTokenFromPagePasses() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));

        Object token = session.getAttribute("csrfToken");
        assertNotNull(token, "GET 页面时就应该生成 token");

        mockMvc.perform(post("/login").session(session)
                        .param("_csrf", token.toString())
                        .param("username", "no-such-user-for-test")
                        .param("password", "wrong-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户名或密码不正确")));
    }

    /**
     * 账号维度锁定：同一账号连错 5 次后，第 6 次直接拒绝。
     * IP 维度的限流挡不住"换 IP 撞同一个账号"，这一层才是关键。
     */
    @Test
    void loginIsLockedAfterFiveFailures() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/login").session(session)).andExpect(status().isOk());
        String token = session.getAttribute("csrfToken").toString();
        // 用随机用户名，避免和别的用例共用一个限流 key
        String username = "lock-test-" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login").session(session)
                            .param("_csrf", token)
                            .param("username", username)
                            .param("password", "bad-password"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("用户名或密码不正确")));
        }

        mockMvc.perform(post("/login").session(session)
                        .param("_csrf", token)
                        .param("username", username)
                        .param("password", "bad-password"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("密码错误次数过多")));
    }

    @Test
    void reviewSubmitWithoutLoginStillRedirectsForPlainForm() throws Exception {
        mockMvc.perform(post("/dish/review")
                        .param("dishId", "1")
                        .param("rating", "5")
                        .param("comment", "好吃"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
