package com.gfish.anticheat.mod.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 读取器，只覆盖 DeepGuard 的 {@code config.yml} 用到的语法子集：
 * 注释、嵌套映射、以及标量（布尔 / 整数 / 小数 / 带引号或不带引号的字符串）。
 * <p>
 * 为什么不用 SnakeYAML：Fabric 侧要 {@code include}（jar-in-jar）、NeoForge 侧要
 * {@code jarJar} 才能把它打进去，两套打包配置都得单独调通；而我们的配置结构
 * 是固定的，不值得为它引入两处打包风险。遇到不支持的写法会<b>直接抛异常并报行号</b>，
 * 不会静默读错。
 * <p>
 * 不支持（也检测不出来就会走错）的写法：多行标量（{@code |} / {@code >}）、
 * 锚点与别名（{@code &} / {@code *}）、行内流式集合（{@code {}} / {@code []}）、
 * 列表项（{@code - }）。请勿在 config.yml 里使用。
 */
public final class SimpleYaml {

    private final Map<String, Object> root;

    private SimpleYaml(Map<String, Object> root) {
        this.root = root;
    }

    public static SimpleYaml parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        // 栈元素：[打开该块的键所在行的缩进, 该块对应的 map]
        List<Object[]> stack = new ArrayList<>();
        stack.add(new Object[]{-1, root});

        int lineNo = 0;
        for (String raw : text.split("\r?\n", -1)) {
            lineNo++;
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();

            if (trimmed.startsWith("- ")) {
                throw new IllegalArgumentException(
                        "config.yml 第 " + lineNo + " 行用了列表项（- ），本读取器不支持");
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(
                        "config.yml 第 " + lineNo + " 行不是 key: value 形式: " + trimmed);
            }
            String key = trimmed.substring(0, colon).trim();
            String rest = trimmed.substring(colon + 1).trim();

            // 回退到比当前缩进更浅的层级
            while (stack.size() > 1 && (int) stack.get(stack.size() - 1)[0] >= indent) {
                stack.remove(stack.size() - 1);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parent = (Map<String, Object>) stack.get(stack.size() - 1)[1];

            if (rest.isEmpty()) {
                Map<String, Object> child = new LinkedHashMap<>();
                parent.put(key, child);
                stack.add(new Object[]{indent, child});
            } else {
                parent.put(key, parseScalar(rest));
            }
        }
        return new SimpleYaml(root);
    }

    // ------------------------------------------------------------------
    // 取值：路径用 "." 分隔，缺失或类型不符一律回退到默认值
    // ------------------------------------------------------------------

    public boolean getBoolean(String path, boolean def) {
        Object v = lookup(path);
        return (v instanceof Boolean b) ? b : def;
    }

    public double getDouble(String path, double def) {
        Object v = lookup(path);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    public int getInt(String path, int def) {
        Object v = lookup(path);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    public String getString(String path, String def) {
        Object v = lookup(path);
        return (v instanceof String s) ? s : def;
    }

    private Object lookup(String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    // ------------------------------------------------------------------
    // 语法细节
    // ------------------------------------------------------------------

    /** 行首缩进（空格数）。制表符一律当作 1 列 —— YAML 本身禁止用 tab 缩进。 */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * 去掉行尾注释。
     * <p>
     * 只有<b>前面是空白</b>的 {@code #} 才算注释 —— 否则 {@code %code%#1} 这种
     * 值里的井号会被误删。引号内的 {@code #} 也一律保留。
     */
    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static Object parseScalar(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return unescapeDouble(v.substring(1, v.length() - 1));
        }
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            // 单引号里只有 '' 是转义
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        if (v.equals("true")) return Boolean.TRUE;
        if (v.equals("false")) return Boolean.FALSE;
        if (v.equals("null") || v.equals("~")) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException ignored) {
            // 不是整数，继续试小数
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException ignored) {
            // 不是数字，当字符串
        }
        return v;
    }

    /** 处理双引号字符串里的反斜杠转义 —— 只需要覆盖 YAML 约定的常见几个。 */
    private static String unescapeDouble(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}
