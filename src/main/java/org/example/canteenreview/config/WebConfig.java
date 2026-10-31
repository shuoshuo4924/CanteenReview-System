package org.example.canteenreview.config;

import org.example.canteenreview.interceptor.AdminInterceptor;
import org.example.canteenreview.interceptor.LoginInterceptor;
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
        // 管理员拦截：/admin/** 需要登录且角色为 ADMIN
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns("/admin/**");

        // 登录拦截：评价提交等需要登录的路径（示例，可按需扩展）
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/user/**");
    }

    private String ensureTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
