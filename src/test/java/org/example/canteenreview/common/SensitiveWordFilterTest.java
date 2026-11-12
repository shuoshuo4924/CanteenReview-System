package org.example.canteenreview.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤器单元测试：不启动 Spring 容器，直接构造实例并手动灌词，
 * 避免测试依赖 resources 里的词库内容（运营随时会改那个文件）。
 */
class SensitiveWordFilterTest {

    private SensitiveWordFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SensitiveWordFilter();
        filter.addWord("加微信");
        filter.addWord("刷单");
        filter.addWord("vx");
        filter.addWord("赌博");
    }

    @Test
    void traditionalChineseDoesNotBypass() {
        // 词库是简体，繁体写法在归一化阶段会被折成简体
        assertTrue(filter.contains("賭博網站推薦"));
        assertEquals("词库", filter.check("賭博網站推薦").category());
    }

    @Test
    void repeatedCharsDoNotBypass() {
        // 复读单字试图拉开与词条的距离
        assertTrue(filter.contains("赌赌赌博"));
        assertTrue(filter.contains("加加加微信"));
        assertTrue(filter.contains("刷 刷 刷 单"));
        assertEquals("****", filter.filter("加加微信"), "重复的字应一起被打码");
    }

    @Test
    void structuredContentIsBlocked() {
        // 手机号被空格/横线拆开也认得出来
        assertEquals("手机号", filter.check("电话 138 1234 5678").category());
        assertEquals("手机号", filter.check("138-1234-5678").category());
        // 链接与域名
        assertEquals("链接", filter.check("详情见 https://example.com/a").category());
        assertEquals("域名", filter.check("加我 abc123.top").category());
        // 扫码引流与社交账号
        assertEquals("二维码", filter.check("扫码进群有优惠").category());
        assertEquals("联系方式", filter.check("vx：abcd1234").category());
    }

    @Test
    void normalContentWithNumbersIsNotBlocked() {
        // 价格、日期、普通数字串不能被手机号规则误伤
        assertFalse(filter.contains("这份饭12.5元，2026年11月12日吃的，很划算"));
        assertFalse(filter.contains("第3次来了，出餐大概8分钟"));
        assertFalse(filter.check("味道不错").hit());
    }

    @Test
    void normalTextCanBeDetected() {
        assertTrue(filter.contains("想买的话加微信私聊"));
        assertTrue(filter.contains("可以刷单返现"));
        assertFalse(filter.contains("这道菜分量很足，味道也不错"));
    }

    @Test
    void noisySeparatorsDoNotBypass() {
        // 夹符号、夹空格、夹中文标点，都应该还原成同一个词
        assertTrue(filter.contains("加*微*信"));
        assertTrue(filter.contains("加 微 信 详聊"));
        assertTrue(filter.contains("加微-信"));
        assertTrue(filter.contains("加。微。信"));
    }

    @Test
    void fullWidthAndCaseDoNotBypass() {
        assertTrue(filter.contains("ＶＸ：abc123"));
        assertTrue(filter.contains("VX: abc123"));
    }

    @Test
    void emojiAndSymbolsDoNotBypass() {
        // 只保留汉字和字母数字后，夹在中间的 emoji / 符号会被直接丢掉
        assertTrue(filter.contains("加🌿微🌿信"));
        assertTrue(filter.contains("加😀微😀信"));
        assertTrue(filter.contains("加·微·信"));
    }

    @Test
    void homoglyphLettersDoNotBypass() {
        // 小型大写 ᴠ、希腊 ν 都会被折回 ASCII 的 v，再与后面的 x 拼成 vx
        assertTrue(filter.contains("\u1D20x：来聊"));
        assertTrue(filter.contains("\u03BDx：来聊"));
        // 西里尔 х 折回 x，与前面的 v 拼成 vx
        assertTrue(filter.contains("v\u0445"));
    }

    @Test
    void filterMasksOnlyMatchedCharsAndKeepsLength() {
        String text = "加微信，一份12.5元。";
        String masked = filter.filter(text);

        assertEquals(text.length(), masked.length());
        assertEquals("***，一份12.5元。", masked, "只打码命中的三个字，标点和数字保持原样");
    }

    @Test
    void filterKeepsSeparatorsInsideTheWord() {
        // 词中间本来就夹着符号时，只有真正的字被打码，原有的 * 位置不变
        String text = "加*微*信 好吃";
        assertEquals("***** 好吃", filter.filter(text));
    }

    @Test
    void blankInputIsSafe() {
        assertNull(filter.filter(null));
        assertFalse(filter.contains(null));
        assertFalse(filter.contains(""));
        assertFalse(filter.contains("   "));
        assertEquals("好吃", filter.filter("好吃"));
    }

    @Test
    void pinyinDoesNotBypass() {
        // 加词时"加微信"会自动生成全拼变体，只打拼音也拦得住
        assertTrue(filter.contains("jiaweixin"));
        assertTrue(filter.contains("jia wei xin"));
        assertTrue(filter.contains("JIAWEIXIN"));
        assertTrue(filter.contains("想吃的话jiaweixin聊"));
        // 纯英文词条不会平白多生成拼音，正常英文句子不受影响
        assertFalse(filter.contains("this dish is really good"));
    }

    @Test
    void bundledDictionaryCanBeLoaded() {
        // 只校验"资源能被读到并解析出词条"这条链路，不断言具体词，
        // 否则运营每改一次词库测试就得跟着改
        SensitiveWordFilter loaded = new SensitiveWordFilter();
        loaded.load();
        assertTrue(loaded.size() > 0, "classpath:sensitive-words.txt 应至少解析出一条词");
    }

    @Test
    void emptyDictionaryNeverBlocks() {
        SensitiveWordFilter empty = new SensitiveWordFilter();
        assertFalse(empty.contains("加微信"));
        assertEquals(0, empty.size());
        // 没有命中时返回同一个对象，调用方可以据此判断是否被改动
        String text = "加微信";
        assertTrue(text == empty.filter(text));
    }
}
