package org.example.canteenreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling：词库热更新的定时检查（SensitiveWordFilter#checkForUpdate）依赖它
@SpringBootApplication
@EnableScheduling
public class CanteenReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanteenReviewApplication.class, args);
    }

}
