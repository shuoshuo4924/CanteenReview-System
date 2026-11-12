package org.example.canteenreview.config;

import org.example.canteenreview.interceptor.AdminInterceptor;
import org.example.canteenreview.interceptor.CsrfInterceptor;
import org.example.canteenreview.interceptor.LoginInterceptor;
import org.example.canteenreview.interceptor.OriginCheckInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 图片真实保存目录，从 application.yaml 的 app.image-dir 读取
    @Value("${app.image-dir}")
    private String imageDir;

    // 拦截器改成 Spring Bean：它们需要查数据库（校验账号是否还存在、角色是否被改）
    @Autowired
    private OriginCheckInterceptor originCheckInterceptor;

    @Autowired
    private AdminInterceptor adminInterceptor;

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private CsrfInterceptor csrfInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 意思是：只要浏览器访问 /images/**，就去配置的文件夹里找
        // 注意：addResourceLocations 的路径必须以 file: 开头、以斜杠结尾
        String location = "file:" + ensureTrailingSlash(imageDir);
        registry.addResourceHandler("/images/**")
                .addResourceLocations(location);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 跨站写操作防护：所有 POST 请求校验 Origin/Referer 是否同源，放在最前面
        registry.addInterceptor(originCheckInterceptor)
                .addPathPatterns("/**");

        // 管理员拦截：/admin/** 需要登录且角色为 ADMIN（角色以数据库为准）
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**");

        // 登录拦截：真正需要登录的是提交评价。
        // 之前配的是 /user/**，但项目里没有任何 /user/** 接口，等于没生效。
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/dish/review");

        // CSRF token：放在最后，未登录的请求先被上面的拦截器挡掉，
        // 用户不会看到"页面过期"这种与登录无关的提示。
        // 静态资源和探针不需要 token，排除掉可以少创建一堆没用的 Session。
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/favicon.ico", "/actuator/**");
    }

    private String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
