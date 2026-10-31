package org.example.canteenreview.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 关键：overflow 必须保持 false（默认值）。
        // 若为 true，页码超过总页数时插件会把 current 直接改成 1（handlerOverflow → setCurrent(1)），
        // 表现就是"只有 4 页却访问第 5 页时跳回第一页"，而不是停在第 4 页。
        // 越界纠正统一交给 DishController 做（重定向到最后一页）。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}