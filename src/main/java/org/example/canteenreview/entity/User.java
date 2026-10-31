package org.example.canteenreview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String role; // 真实字段（原来那个空的 setRole 方法已删除）

    // 密保问题（明文，注册时自填）
    private String securityQuestion;
    // 密保答案（BCrypt 密文，和密码一样绝不存明文）
    private String securityAnswer;
}
