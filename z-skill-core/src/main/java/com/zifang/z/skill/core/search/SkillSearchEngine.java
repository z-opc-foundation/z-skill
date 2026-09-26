package com.zifang.z.skill.core.search;

import com.zifang.z.skill.api.dto.SkillDto;
import com.zifang.z.skill.api.dto.SkillSearchResultDto;
import com.zifang.z.skill.api.exception.SkillException;
import com.zifang.z.skill.api.spec.SkillSpec;
import com.zifang.z.skill.core.registry.SkillRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索与分面 — 抄的是 skills.sh 的检索口径(单词 fuzzy、多词 semantic、回显 query/searchType/durationMs),
 * 再补上它没有的两件事: 中文可搜、来源可筛.
 *
 * <p>中文必须单独处理: 聚合进来的 skill 描述大量是中文, 按空白切词等于搜不到.
 * 这里对 CJK 串按二元组(bigram)展开, 代价是零依赖分词也能命中"视频剪辑"这类词.
 */
public class SkillSearchEngine {

    private static final Pattern WORD = Pattern.compile("[a-z0-9][a-z0-9._+-]*");
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]{1,}");
    private static final int MAX_TOKENS = 24;

    private final SkillRegistry registry;

    public SkillSearchEngine(SkillRegistry registry) {
        this.registry = registry;
    }

    public SkillSearchResultDto search(Query query) {
        long started = System.nanoTime();
        Query q = query == null ? new Query() : query;
        if (q.query != null && !q.query.trim().isEmpty() && q.query.trim().length() < SkillSpec.MIN_QUERY_LENGTH) {
            throw SkillException.badRequest("查询词至少 " + SkillSpec.MIN_QUERY_LENGTH + " 个字符");
        }
        List<Token> tokens = tokenize(q.query);
        List<Scored> scored = scored(q, tokens);

        int page = Math.max(0, q.page);
        int perPage = q.perPage <= 0 ? 20 : Math.min(q.perPage, 200);
        int from = Math.min(page * perPage, scored.size());
        int to = Math.min(from + perPage, scored.size());
        List<SkillDto> pageItems = new ArrayList<SkillDto>();
        for (Scored s : scored.subList(from, to)) {
            // installed 由注册表出口统一盖好(snapshot 已带), 这里不再二次 toBuilder:
            // 同一个事实有两处赋值时, 漏掉的那处就是一个只在一两个出口出现的假值.
            pageItems.add(s.dto);
        }
        Map<String, Map<String, Integer>> facets = new LinkedHashMap<String, Map<String, Integer>>();
        facets.put("category", facet(scored, true));
        facets.put("tag", facet(scored, false));
        Map<String, Integer> sourceFacet = new LinkedHashMap<String, Integer>();
        for (Scored s : scored) {
            bump(sourceFacet, s.dto.getSource());
        }
        facets.put("source", sortDesc(sourceFacet));
        long ms = (System.nanoTime() - started) / 1000000L;
        return new SkillSearchResultDto(pageItems, q.query == null ? "" : q.query.trim(),
                searchType(tokens), scored.size(), page, perPage, ms, facets.get("category"), facets);
    }

    /**
     * 全量取回, 不分页 — {@code /skill/list} 的语义是"整个目录",
     * 让它走检索层的 perPage 上限等于前端静默少一批 skill.
     */
    public List<SkillDto> filter(Query query) {
        Query q = query == null ? new Query() : query;
        List<SkillDto> out = new ArrayList<SkillDto>();
        for (Scored s : scored(q, tokenize(q.query))) {
            out.add(s.dto);
        }
        return out;
    }

    private List<Scored> scored(Query q, List<Token> tokens) {
        List<Scored> scored = new ArrayList<Scored>();
        for (SkillDto dto : registry.snapshot().values()) {
            if (!matchesFilters(dto, q)) continue;
            int score = tokens.isEmpty() ? 0 : score(dto, tokens);
            if (!tokens.isEmpty() && score <= 0) continue;
            scored.add(new Scored(dto, score));
        }
        sort(scored, q.sort);
        return scored;
    }

    /** 分类目录: 聚合后各平台的 category 语义混在一起, 所以要带计数而不是只给名字. */
    public Map<String, Integer> categoryCounts() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (SkillDto dto : registry.snapshot().values()) {
            bump(out, dto.getCategory());
        }
        return sortDesc(out);
    }

    public Map<String, Integer> tagCounts() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (SkillDto dto : registry.snapshot().values()) {
            for (String t : dto.getTags()) bump(out, t.toLowerCase(Locale.ROOT));
        }
        return sortDesc(out);
    }

    public List<String> listCategories() {
        return new ArrayList<String>(categoryCounts().keySet());
    }

    public List<String> listTags() {
        return new ArrayList<String>(tagCounts().keySet());
    }

    private static Map<String, Integer> facet(List<Scored> in, boolean category) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Scored s : in) {
            if (category) {
                bump(out, s.dto.getCategory());
            } else {
                for (String t : s.dto.getTags()) bump(out, t.toLowerCase(Locale.ROOT));
            }
        }
        return sortDesc(out);
    }

    private static void bump(Map<String, Integer> map, String key) {
        if (key == null || key.isEmpty()) return;
        Integer n = map.get(key);
        map.put(key, n == null ? 1 : n + 1);
    }

    private static Map<String, Integer> sortDesc(Map<String, Integer> in) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(in.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            }
        });
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    private boolean matchesFilters(SkillDto dto, Query q) {
        if (q.category != null && !q.category.equalsIgnoreCase(dto.getCategory())) return false;
        if (q.tag != null) {
            boolean hit = false;
            for (String t : dto.getTags()) {
                if (t.equalsIgnoreCase(q.tag)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        if (q.source != null && !q.source.equals(dto.getSource())) return false;
        if (q.format != null && !q.format.equals(dto.getFormatId())) return false;
        if (q.riskLevel != null && !q.riskLevel.equalsIgnoreCase(dto.getRiskLevel())) return false;
        return q.installedOnly == null || !q.installedOnly || registry.isInstalled(dto.getId());
    }

    private static int score(SkillDto dto, List<Token> tokens) {
        int total = 0;
        String slug = lower(dto.getSlug());
        String id = lower(dto.getId());
        String name = lower(dto.getName());
        String desc = lower(dto.getDescription());
        String category = lower(dto.getCategory());
        String author = lower(dto.getAuthor());
        List<String> tags = new ArrayList<String>();
        for (String t : dto.getTags()) tags.add(lower(t));
        StringBuilder meta = new StringBuilder();
        for (Map.Entry<String, String> e : dto.getMetadata().entrySet()) {
            meta.append(lower(e.getKey())).append(' ').append(lower(e.getValue())).append(' ');
        }
        String metaText = meta.toString();
        for (Token token : tokens) {
            String t = token.value;
            if (t.equals(slug) || t.equals(id)) {
                total += 1000;
            } else if (slug != null && slug.startsWith(t)) {
                total += 300;
            } else if (slug != null && slug.contains(t)) {
                total += 180;
            } else if (name != null && name.contains(t)) {
                total += 140;
            }
            if (tags.contains(t)) total += 120;
            if (t.equals(category)) total += 80;
            if (desc != null && desc.contains(t)) total += 40;
            if (metaText.contains(t)) total += 20;
            if (author != null && author.contains(t)) total += 20;
            if (token.cjk && desc != null && desc.contains(t)) total += 10;
        }
        return total;
    }

    private static void sort(List<Scored> in, final String sort) {
        Comparator<Scored> comparator;
        if ("name".equals(sort)) {
            comparator = new Comparator<Scored>() {
                @Override
                public int compare(Scored a, Scored b) {
                    return String.valueOf(a.dto.getId()).compareTo(String.valueOf(b.dto.getId()));
                }
            };
        } else if ("installs".equals(sort)) {
            comparator = new Comparator<Scored>() {
                @Override
                public int compare(Scored a, Scored b) {
                    int byInstalls = Integer.compare(b.dto.getInstallCount(), a.dto.getInstallCount());
                    return byInstalls != 0 ? byInstalls : String.valueOf(a.dto.getId()).compareTo(String.valueOf(b.dto.getId()));
                }
            };
        } else if ("updated".equals(sort)) {
            comparator = new Comparator<Scored>() {
                @Override
                public int compare(Scored a, Scored b) {
                    int byUpdated = Long.compare(b.dto.getUpdatedAt(), a.dto.getUpdatedAt());
                    return byUpdated != 0 ? byUpdated : String.valueOf(a.dto.getId()).compareTo(String.valueOf(b.dto.getId()));
                }
            };
        } else {
            comparator = new Comparator<Scored>() {
                @Override
                public int compare(Scored a, Scored b) {
                    int byScore = Integer.compare(b.score, a.score);
                    if (byScore != 0) return byScore;
                    int byInstalls = Integer.compare(b.dto.getInstallCount(), a.dto.getInstallCount());
                    if (byInstalls != 0) return byInstalls;
                    return String.valueOf(a.dto.getId()).compareTo(String.valueOf(b.dto.getId()));
                }
            };
        }
        Collections.sort(in, comparator);
    }

    private static String searchType(List<Token> tokens) {
        int words = 0;
        for (Token t : tokens) {
            if (!t.cjk) words++;
        }
        if (tokens.isEmpty()) return "browse";
        return words > 1 ? "semantic" : "fuzzy";
    }

    static List<Token> tokenize(String query) {
        List<Token> out = new ArrayList<Token>();
        if (query == null) return out;
        String s = query.toLowerCase(Locale.ROOT);
        Matcher w = WORD.matcher(s);
        while (w.find() && out.size() < MAX_TOKENS) {
            String v = w.group();
            if (v.length() >= 2 && !out.contains(new Token(v, false))) out.add(new Token(v, false));
        }
        Matcher c = CJK.matcher(s);
        while (c.find()) {
            String run = c.group();
            if (run.length() == 1) {
                if (out.size() < MAX_TOKENS) out.add(new Token(run, true));
                continue;
            }
            for (int i = 0; i + 2 <= run.length() && out.size() < MAX_TOKENS; i++) {
                out.add(new Token(run.substring(i, i + 2), true));
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static final class Token {
        final String value;
        final boolean cjk;

        Token(String value, boolean cjk) {
            this.value = value;
            this.cjk = cjk;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Token && value.equals(((Token) o).value) && cjk == ((Token) o).cjk;
        }

        @Override
        public int hashCode() {
            return value.hashCode() + (cjk ? 31 : 0);
        }
    }

    private static final class Scored {
        final SkillDto dto;
        final int score;

        Scored(SkillDto dto, int score) {
            this.dto = dto;
            this.score = score;
        }
    }

    /** 检索参数; 字段公开是为控制器直接赋值, 保持 Java 8 下不引入 setter 噪音. */
    public static final class Query {
        public String query;
        public String category;
        public String tag;
        public String source;
        public String format;
        public String riskLevel;
        public Boolean installedOnly;
        public String sort = "relevance";
        public int page = 0;
        public int perPage = 20;

        public Query q(String v) {
            this.query = v;
            return this;
        }

        public Query category(String v) {
            this.category = v;
            return this;
        }

        public Query tag(String v) {
            this.tag = v;
            return this;
        }

        public Query source(String v) {
            this.source = v;
            return this;
        }

        public Query format(String v) {
            this.format = v;
            return this;
        }

        public Query sort(String v) {
            this.sort = v;
            return this;
        }

        public Query page(int v) {
            this.page = v;
            return this;
        }

        public Query perPage(int v) {
            this.perPage = v;
            return this;
        }
    }
}
