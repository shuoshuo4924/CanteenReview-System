package org.example.canteenreview;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import org.example.canteenreview.entity.Dish;
import org.example.canteenreview.mapper.DishMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ExcelImportTest {

    @Autowired
    private DishMapper dishMapper;

    // 这是手动导数据专用，默认禁用，避免每次跑测试都往数据库插数据。
    // 需要导入时，去掉 @Disabled 再单独运行本方法即可。
    @Disabled("手动导入 Excel 用，默认不参与自动测试")
    @Test
    public void importExcel() {
        // ⚠️ 极其重要：这里的路径必须和你电脑上 Excel 的路径一模一样！
        // 如果你的 Excel 名字叫 "校园菜单.xlsx"，那就写 "D:/校园菜单.xlsx"
        String fileName = "D:/菜单(测试).xlsx";

        System.out.println("🚀 开始读取 Excel，请稍候...");

        try {
            // 使用 EasyExcel 读取文件
            EasyExcel.read(fileName, Dish.class, new ReadListener<Dish>() {
                @Override
                public void invoke(Dish dish, AnalysisContext context) {
                    // 每读到一行数据，就会执行一次这里的代码
                    // 1. 如果没有填评分，给个默认值 0.0
                    if (dish.getAvgRating() == null) {
                        dish.setAvgRating(0.0);
                    }

                    // 2. 将数据插入到数据库
                    dishMapper.insert(dish);

                    // 3. 在控制台打印出来，让你看到进度
                    System.out.println("✅ 成功导入：" + dish.getName() + " (" + dish.getCanteen() + ")");
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 所有数据读取完毕后执行
                    System.out.println("🎉 恭喜！Excel 数据全部导入完成！快去刷新网页看看！");
                }
            }).sheet().doRead(); // 默认读取第一个 Sheet 页

        } catch (Exception e) {
            System.out.println("❌ 导入失败了，错误原因：" + e.getMessage());
            e.printStackTrace();
        }
    }
}