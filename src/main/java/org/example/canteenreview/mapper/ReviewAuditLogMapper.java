package org.example.canteenreview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.canteenreview.entity.ReviewAuditLog;

@Mapper
public interface ReviewAuditLogMapper extends BaseMapper<ReviewAuditLog> {
    // 查询条件都写在 Service 的 QueryWrapper 里，这里保持空接口
}
