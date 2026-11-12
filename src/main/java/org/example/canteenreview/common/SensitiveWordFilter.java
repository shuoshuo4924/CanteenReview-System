package org.example.canteenreview.common;

import jakarta.annotation.PostConstruct;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 评论内容过滤：基于 DFA（字典树）的敏感词检测与掩码。
 *
 * <p>为什么是 DFA 而不是一堆 {@code contains}：
 * 词典扩到几百条时，逐词 contains 会变成 O(词数 × 文本长度)，
 * 而字典树只需要扫一遍文本，代价是 O(文本长度 × 最长词长)，
 * 长评论也不会拖慢提交接口。
 *
 * <p>为什么要归一化：
 * 直接匹配很容易被"加*微*信""加 微 信""ＶＸ"这类写法绕过，所以匹配前会做几步处理：
 * <ol>
 *   <li>同形字折叠：把外形与拉丁字母一致的海量 Unicode 字符（西里尔 а、希腊 ν、小型大写 ᴠ）
 *       折回 ASCII，挡掉用"长得一样的外语字母"冒充英文的玩法；</li>
 *   <li>繁体转简体：词库是简体，遇到「賭博」「賣淫」这种写法本来完全匹配不上，
 *       先折成简体再查词；</li>
 *   <li>全角转半角、统一小写；</li>
 *   <li>只保留汉字与 ASCII 字母数字，空白、标点、emoji、符号一律丢弃 ——
 *       于是"加🌿微🌿信"会还原成"加微信"；</li>
 *   <li>连续重复字折叠："赌赌赌博"收敛成"赌博"，挡住靠复读单字逃避匹配的写法。</li>
 * </ol>
 *
 * <p>词库管不到的还有"形状"类内容：手机号、链接域名、扫码引流、社交账号。
 * 这些靠加词是加不完的（数字和域名永远能换），所以另有一组正则规则 {@link GuardRule}，
 * 命中后给出可区分的类别，方便运营判断该补词库还是该调规则。
 *
 * <p>含汉字的词条会额外生成一条全拼变体（"加微信" → "jiaweixin"），
 * 用来挡住"只打拼音不打字"的绕过。
 *
 * <p>词库支持热更新：默认每 60 秒检查一次文件修改时间，变了就重建字典树；
 * 也可以在后台点"重载词库"立即生效，不需要重启应用。
 */
@Component
public class SensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordFilter.class);

    /** 默认词库位置（classpath） */
    private static final String DEFAULT_DICT_PATH = "sensitive-words.txt";

    /** 命中字符统一替换成这个符号 */
    private static final char MASK = '*';

    /**
     * 同形字折叠表：key 是"看起来像英文字母"的非 ASCII 字符，value 是它冒充的 ASCII 字母。
     * 只收外观高度接近的字符，避免把正常的外语文本碾成乱码式的误判。
     */
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
            // 西里尔字母：与拉丁字母外形几乎一致，是最常见的冒充手段
            Map.entry('\u0430', 'a'), Map.entry('\u0435', 'e'), Map.entry('\u043E', 'o'),
            Map.entry('\u0440', 'p'), Map.entry('\u0441', 'c'), Map.entry('\u0445', 'x'),
            Map.entry('\u0443', 'y'), Map.entry('\u043A', 'k'), Map.entry('\u043C', 'm'),
            Map.entry('\u0442', 't'), Map.entry('\u0432', 'b'), Map.entry('\u043D', 'h'),
            Map.entry('\u0456', 'i'), Map.entry('\u0458', 'j'), Map.entry('\u0455', 's'),
            Map.entry('\u04BB', 'h'), Map.entry('\u0501', 'd'), Map.entry('\u051B', 'q'),
            Map.entry('\u051D', 'w'),
            // 希腊字母
            Map.entry('\u03B1', 'a'), Map.entry('\u03B2', 'b'), Map.entry('\u03B5', 'e'),
            Map.entry('\u03B9', 'i'), Map.entry('\u03BA', 'k'), Map.entry('\u03BD', 'v'),
            Map.entry('\u03BF', 'o'), Map.entry('\u03C1', 'p'), Map.entry('\u03C4', 't'),
            Map.entry('\u03C5', 'u'), Map.entry('\u03C7', 'x'), Map.entry('\u03B3', 'y'),
            // 拉丁小型大写字母：ᴠx 这类在文本框里和 vx 几乎看不出区别
            Map.entry('\u1D00', 'a'), Map.entry('\u0299', 'b'), Map.entry('\u1D04', 'c'),
            Map.entry('\u1D05', 'd'), Map.entry('\u1D07', 'e'), Map.entry('\u0262', 'g'),
            Map.entry('\u029C', 'h'), Map.entry('\u026A', 'i'), Map.entry('\u1D0B', 'k'),
            Map.entry('\u029F', 'l'), Map.entry('\u1D0D', 'm'), Map.entry('\u0274', 'n'),
            Map.entry('\u1D0F', 'o'), Map.entry('\u1D18', 'p'), Map.entry('\u0280', 'r'),
            Map.entry('\u1D1B', 't'), Map.entry('\u1D1C', 'u'), Map.entry('\u1D20', 'v'),
            Map.entry('\u1D21', 'w'), Map.entry('\u028F', 'y'), Map.entry('\u1D22', 'z')
    );

    /**
     * 常见繁体 → 简体映射。
     * 词库是简体，遇到「賭博」「賣淫」这类繁体写法就匹配不上了，
     * 所以在归一化阶段先折成简体。只收评论里真正可能出现、且会绕过词库的字，
     * 全量简繁表几千字，这里用不上。
     */
    private static final Map<Character, Character> TRADITIONAL_TO_SIMPLIFIED = Map.ofEntries(
            Map.entry('賭', '赌'), Map.entry('賣', '卖'), Map.entry('黃', '黄'),
            Map.entry('視', '视'), Map.entry('頻', '频'), Map.entry('約', '约'),
            Map.entry('養', '养'), Map.entry('門', '门'), Map.entry('務', '务'),
            Map.entry('購', '购'), Map.entry('單', '单'), Map.entry('賺', '赚'),
            Map.entry('腦', '脑'), Map.entry('殘', '残'), Map.entry('廢', '废'),
            Map.entry('滾', '滚'), Map.entry('該', '该'), Map.entry('賤', '贱'),
            Map.entry('東', '东'), Map.entry('騙', '骗'), Map.entry('發', '发'),
            Map.entry('證', '证'), Map.entry('貸', '贷'), Map.entry('現', '现'),
            Map.entry('錢', '钱'), Map.entry('鏈', '链'), Map.entry('結', '结'),
            Map.entry('點', '点'), Map.entry('擊', '击'), Map.entry('殺', '杀'),
            Map.entry('槍', '枪'), Map.entry('彈', '弹'), Map.entry('屍', '尸'),
            Map.entry('綁', '绑'), Map.entry('價', '价'), Map.entry('貪', '贪'),
            Map.entry('贏', '赢'), Map.entry('壞', '坏'), Map.entry('髒', '脏'),
            Map.entry('噁', '恶'), Map.entry('藥', '药'), Map.entry('醫', '医'),
            Map.entry('們', '们'), Map.entry('這', '这'), Map.entry('個', '个'),
            Map.entry('說', '说'), Map.entry('時', '时'), Map.entry('會', '会'),
            Map.entry('對', '对'), Map.entry('開', '开'), Map.entry('買', '买'),
            Map.entry('見', '见'), Map.entry('過', '过'), Map.entry('還', '还'),
            Map.entry('沒', '没'), Map.entry('嗎', '吗'), Map.entry('麼', '么'),
            Map.entry('誰', '谁'), Map.entry('給', '给'), Map.entry('媽', '妈'),
            Map.entry('雞', '鸡'), Map.entry('鴨', '鸭'), Map.entry('麵', '面'),
            Map.entry('飯', '饭'), Map.entry('湯', '汤'), Map.entry('頭', '头'),
            Map.entry('塊', '块'), Map.entry('醜', '丑'), Map.entry('蟲', '虫')
    );

    /**
     * 结构化规则：词库管不到的"形状"，靠正则挡。
     * 这些内容一旦出现基本没有正当理由，误伤概率远低于加词。
     */
    private static final List<GuardRule> RULES = List.of(
            // 手机号：11 位、1 开头、第二位 3-9
            new GuardRule("手机号", Pattern.compile("1[3-9]\\d{9}")),
            // 链接与域名
            new GuardRule("链接", Pattern.compile("(?i)(https?://|www\\.)")),
            new GuardRule("域名", Pattern.compile("(?i)[a-z0-9-]{2,}\\.(com|cn|net|org|top|xyz|vip)(\\b|/)")),
            // 二维码 / 扫码引流话术
            new GuardRule("二维码", Pattern.compile("(扫码|扫一扫|二维码|扫描下方)")),
            // 账号引流：vx/wx/qq/扣扣 + 一串字母数字
            new GuardRule("联系方式", Pattern.compile("(?i)(vx|wx|qq|扣扣|v信)\\s*[:：]?\\s*[a-z0-9]{4,}"))
    );

    /** 一条内置规则：命中后 category 会写进日志，方便运营判断该补词还是该调规则 */
    private record GuardRule(String category, Pattern pattern) {
    }

    /**
     * 拦截结果。category 为 null 表示放行，否则是拦截原因：
     * 词库命中记为"词库"，其余为规则名（"手机号""链接""域名""二维码""联系方式"）。
     */
    public record GuardResult(boolean hit, String category) {

        static final GuardResult PASS = new GuardResult(false, null);
    }

    /** 拼音输出格式：小写、无声调、ü 写成 v，与键盘输入习惯一致 */
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();

    static {
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        PINYIN_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    /** 词库文件的外部路径；留空则读 classpath，配了就能在 jar 外面改词库 */
    @Value("${app.sensitive-words-path:}")
    private String externalDictPath;

    /** 字典树整体替换，热更新时用 volatile 保证读到的永远是一棵完整的树 */
    private volatile Map<Character, Node> roots = new HashMap<>();

    private int wordCount;

    private int entryCount;

    /** 上次成功加载时词库文件的修改时间，用来判断要不要重载 */
    private volatile long loadedAt = -1L;

    /** 词条节点：children 指向下一个字符，end 表示"到这里是一个完整词" */
    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private boolean end;
    }

    /** 归一化结果：text 是清洗后的文本，starts/ends 记录每个字符覆盖的原文区间（含被折叠掉的重复字） */
    private record Normalized(String text, int[] starts, int[] ends) {
    }

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * 重新读取词库并原子替换字典树。
     * 构建期间用的是一棵新的空树，构建完成后一次性替换引用，
     * 所以并发匹配不会看到"删了一半的词库"。
     *
     * @return true 表示加载成功
     */
    public boolean reload() {
        Resource resource = resolveResource();
        if (resource == null || !resource.exists()) {
            log.warn("未找到敏感词库 {}，本次不会拦截任何评论", describeDictSource());
            return false;
        }

        Map<Character, Node> fresh = new HashMap<>();
        int[] counters = new int[2]; // [0]=词条数 [1]=匹配串数
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim();
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                addTo(fresh, word, counters);
            }
        } catch (IOException e) {
            // 读不到不应该让整个应用起不来，保留旧词库继续跑
            log.error("敏感词库加载失败，继续使用上一版词库", e);
            return false;
        }

        synchronized (this) {
            this.roots = fresh;
            this.wordCount = counters[0];
            this.entryCount = counters[1];
            this.loadedAt = lastModifiedQuietly(resource);
        }
        log.info("敏感词库加载完成：{} 条词，{} 个匹配串（含拼音变体），来源={}",
                wordCount, entryCount, describeDictSource());
        return true;
    }

    /**
     * 定时检查词库文件有没有改动。
     * 默认 60 秒一次，间隔可用 app.sensitive-words-reload-ms 调整。
     * 开发环境直接改 target/classes 或外部文件即可生效，不用重启。
     */
    @Scheduled(fixedDelayString = "${app.sensitive-words-reload-ms:60000}", initialDelay = 60_000)
    public void checkForUpdate() {
        Resource resource = resolveResource();
        if (resource == null || !resource.exists()) {
            return;
        }
        long modified = lastModifiedQuietly(resource);
        if (modified > loadedAt) {
            log.info("检测到词库文件变化，正在重新加载：{}", describeDictSource());
            reload();
        }
    }

    /**
     * 加入一个词条（也会走归一化，所以词库里写"加 微 信"同样能匹配到原文）。
     * 含汉字的词条会额外生成一条全拼形式："加微信" → "jiaweixin"，
     * 这样评论里只打拼音也拦得住。
     */
    public void addWord(String word) {
        if (word == null) {
            return;
        }
        int[] counters = new int[2];
        synchronized (this) {
            addTo(roots, word, counters);
            wordCount += counters[0];
            entryCount += counters[1];
        }
    }

    /** 把词条写进指定字典树；counters 由调用方提供，便于 reload 时先算完再替换 */
    private void addTo(Map<Character, Node> tree, String word, int[] counters) {
        String normalized = normalize(word).text();
        if (normalized.isEmpty()) {
            return;
        }
        counters[0]++;
        insert(tree, normalized, counters);
        // 只做整词全拼，不做 jwx / wx 这类首字母缩写：
        // 两三个字母的组合在正常英文里太常见，误伤率会高到没法用
        if (hasChinese(normalized)) {
            insert(tree, toPinyin(normalized), counters);
        }
    }

    /** 把一条匹配串插入字典树，已存在则不重复计数 */
    private void insert(Map<Character, Node> tree, String entry, int[] counters) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        Node node = tree.computeIfAbsent(entry.charAt(0), key -> new Node());
        for (int i = 1; i < entry.length(); i++) {
            node = node.children.computeIfAbsent(entry.charAt(i), key -> new Node());
        }
        if (!node.end) {
            node.end = true;
            counters[1]++;
        }
    }

    /** 文本是否需要拦截（内部走 {@link #check}，只关心结论时用这个） */
    public boolean contains(String text) {
        return check(text).hit();
    }

    /**
     * 完整校验：先过结构化规则（手机号、链接、域名、扫码话术、社交账号），
     * 再查敏感词库（含拼音变体、繁简折叠、重复字折叠）。
     *
     * <p>规则比词库优先，是因为规则命中的内容基本没有正当理由，
     * 且类别能直接写进日志，运营看日志就知道是"词不够"还是"规则没覆盖"。
     */
    public GuardResult check(String text) {
        if (text == null || text.isEmpty()) {
            return GuardResult.PASS;
        }
        // 规则看两种形态：保留标点的（域名要靠点号）和去掉干扰字符的（手机号可能被空格拆开）
        String visible = foldVisible(text);
        String flat = stripNoise(visible);
        for (GuardRule rule : RULES) {
            if (rule.pattern().matcher(visible).find() || rule.pattern().matcher(flat).find()) {
                return new GuardResult(true, rule.category());
            }
        }

        Map<Character, Node> tree = roots;
        if (tree.isEmpty()) {
            return GuardResult.PASS;
        }
        String source = normalize(text).text();
        for (int i = 0; i < source.length(); i++) {
            if (matchAt(tree, source, i) > i) {
                return new GuardResult(true, "词库");
            }
        }
        return GuardResult.PASS;
    }

    /**
     * 把命中的词替换成 *，长度与原文一致，方便直接落库或展示。
     * 没有命中时原样返回同一个对象，调用方可以用 == 判断是否被改动。
     */
    public String filter(String text) {
        Map<Character, Node> tree = roots;
        if (text == null || text.isEmpty() || tree.isEmpty()) {
            return text;
        }
        Normalized normalized = normalize(text);
        String source = normalized.text();
        char[] masked = null;
        int i = 0;
        while (i < source.length()) {
            int end = matchAt(tree, source, i);
            if (end > i) {
                if (masked == null) {
                    masked = text.toCharArray();
                }
                // 归一化后的 [i, end) 对应原文的若干区间，逐字符打码
                for (int k = i; k < end; k++) {
                    for (int p = normalized.starts()[k]; p < normalized.ends()[k]; p++) {
                        masked[p] = MASK;
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        return masked == null ? text : new String(masked);
    }

    /** 词库里的词条数（不含自动生成的拼音变体） */
    public int size() {
        return wordCount;
    }

    /** 字典树里可匹配的串数（含拼音变体），后台展示用 */
    public int entrySize() {
        return entryCount;
    }

    /** 词库来源描述，日志和后台提示用 */
    public String describeDictSource() {
        return (externalDictPath == null || externalDictPath.isBlank())
                ? "classpath:" + DEFAULT_DICT_PATH
                : externalDictPath;
    }

    /**
     * 从 start 开始能匹配到的最长词，返回结束下标（左闭右开）；没匹配到返回 -1。
     * 取最长是为了让"加我微信"这类包含关系命中更完整的那个词。
     */
    private int matchAt(Map<Character, Node> tree, String source, int start) {
        Node node = tree.get(source.charAt(start));
        if (node == null) {
            return -1;
        }
        int matched = node.end ? start + 1 : -1;
        for (int i = start + 1; i < source.length(); i++) {
            node = node.children.get(source.charAt(i));
            if (node == null) {
                break;
            }
            if (node.end) {
                matched = i + 1;
            }
        }
        return matched;
    }

    /** 外部路径优先，没配就用 classpath */
    private Resource resolveResource() {
        if (externalDictPath != null && !externalDictPath.isBlank()) {
            return new FileSystemResource(externalDictPath);
        }
        return new ClassPathResource(DEFAULT_DICT_PATH);
    }

    private static long lastModifiedQuietly(Resource resource) {
        try {
            return resource.lastModified();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** 只有含汉字的词条才值得生成拼音变体 */
    private static boolean hasChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    /**
     * 汉字转无声调全拼，多音字只取第一个读音。
     * 不做全读音笛卡尔积：词库里的词基本都是常用单音字，
     * 展开所有读音组合会让词条数指数膨胀，收益远小于成本。
     */
    private static String toPinyin(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                sb.append(Character.toLowerCase(c));
                continue;
            }
            try {
                String[] readings = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
                if (readings != null && readings.length > 0) {
                    sb.append(readings[0]);
                }
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                // 输出格式是常量，正常不会抛；万一抛了跳过这个字，不影响其它字符
            }
        }
        return sb.toString();
    }

    /**
     * 归一化：同形字折叠 → 繁转简 → 全角转半角 → 转小写 → 丢掉一切非汉字/非字母数字的字符
     * → 折叠连续重复字，同时记录每个保留字符覆盖的原文区间，供 {@link #filter} 回写掩码。
     */
    private Normalized normalize(String raw) {
        int length = raw.length();
        StringBuilder sb = new StringBuilder(length);
        int[] starts = new int[length];
        int[] ends = new int[length];
        int count = 0;
        for (int i = 0; i < length; i++) {
            char ch = foldChar(raw.charAt(i));
            // 走到这里还没被丢掉的字符才有资格参与匹配
            if (!isMeaningful(ch)) {
                continue;
            }
            // 复读同一个字（"赌赌赌博"）不该换来一次漏网，压成一个字再匹配
            if (count > 0 && sb.charAt(count - 1) == ch) {
                ends[count - 1] = i + 1;
                continue;
            }
            starts[count] = i;
            ends[count] = i + 1;
            sb.append(ch);
            count++;
        }
        return new Normalized(sb.toString(),
                Arrays.copyOf(starts, count), Arrays.copyOf(ends, count));
    }

    /** 折叠但不丢字符：规则匹配用，域名等还得靠标点判断 */
    private static String foldVisible(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            sb.append(foldChar(raw.charAt(i)));
        }
        return sb.toString();
    }

    /** 在已折叠的文本上再丢掉干扰字符，只留汉字与字母数字 */
    private static String stripNoise(String folded) {
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (isMeaningful(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 单个字符的四步折叠：同形字 → 繁转简 → 全角转半角 → 小写 */
    private static char foldChar(char c) {
        return Character.toLowerCase(toHalfWidth(foldTraditional(foldHomoglyph(c))));
    }

    /** 繁体转简体；表里没有的原样返回 */
    private static char foldTraditional(char c) {
        Character simplified = TRADITIONAL_TO_SIMPLIFIED.get(c);
        return simplified == null ? c : simplified;
    }

    /**
     * 只保留汉字与 ASCII 字母数字。
     * 空白、标点、emoji、符号、其他语言字母都视为"干扰字符"直接丢弃，
     * 这样"加-微-信""加🌿微🌿信""加 微 信"会收敛成同一个串。
     */
    private static boolean isMeaningful(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')       // 基本汉字
                || (c >= '\u3400' && c <= '\u4DBF')   // 汉字扩展 A 区
                || (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z');
    }

    /** 把"长得像英文"的 Unicode 字符折回 ASCII；表里没有的原样返回 */
    private static char foldHomoglyph(char c) {
        Character ascii = HOMOGLYPHS.get(c);
        return ascii == null ? c : ascii;
    }

    /** 全角字符转半角：中文输入法打出的"ＶＸ"要和"vx"命中同一棵树 */
    private static char toHalfWidth(char c) {
        if (c == '\u3000') {
            return ' ';
        }
        if (c >= '\uFF01' && c <= '\uFF5E') {
            return (char) (c - 0xFEE0);
        }
        return c;
    }
}
