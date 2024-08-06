package com.hy.utils;

public class StringUtils {

    /**
     * 判断字符串是否为空（null或空字符串）
     *
     * @param str 要检查的字符串
     * @return 如果字符串为null或空字符串，则返回true；否则返回false
     */
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 判断字符串是否非空（非null且非空字符串）
     *
     * @param str 要检查的字符串
     * @return 如果字符串非null且非空，则返回true；否则返回false
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 去除字符串的前后空白字符
     *
     * @param str 要处理的字符串
     * @return 去除前后空白字符后的字符串
     */
    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    /**
     * 比较两个字符串是否相等
     *
     * @param str1 第一个字符串
     * @param str2 第二个字符串
     * @return 如果两个字符串相等，则返回true；否则返回false
     */
    public static boolean equals(String str1, String str2) {
        return (str1 == null && str2 == null) || (str1 != null && str1.equals(str2));
    }

    /**
     * 替换字符串中的子串
     *
     * @param str     要处理的字符串
     * @param target  要替换的子串
     * @param replace 替换成的子串
     * @return 替换后的字符串
     */
    public static String replace(String str, String target, String replace) {
        if (str == null || target == null || replace == null) {
            return str;
        }
        return str.replace(target, replace);
    }

    /**
     * 将字符串转换为大写
     *
     * @param str 要转换的字符串
     * @return 转换为大写后的字符串
     */
    public static String toUpperCase(String str) {
        return str == null ? null : str.toUpperCase();
    }

    /**
     * 将字符串转换为小写
     *
     * @param str 要转换的字符串
     * @return 转换为小写后的字符串
     */
    public static String toLowerCase(String str) {
        return str == null ? null : str.toLowerCase();
    }

    /**
     * 判断一个字符串是否包含另一个字符串
     *
     * @param str     要检查的字符串
     * @param target  要查找的子串
     * @return 如果目标字符串包含子串，则返回true；否则返回false
     */
    public static boolean contains(String str, String target) {
        return str != null && target != null && str.contains(target);
    }

    /**
     * 连接字符串数组
     *
     * @param delimiter 分隔符
     * @param elements  要连接的字符串数组
     * @return 连接后的字符串
     */
    public static String join(String delimiter, String... elements) {
        if (elements == null || elements.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(elements[i]);
        }
        return sb.toString();
    }

    // 示例主函数
    public static void main(String[] args) {
        String str = " Hello, World! ";
        System.out.println("Is empty: " + isEmpty(str));
        System.out.println("Is not empty: " + isNotEmpty(str));
        System.out.println("Trimmed: '" + trim(str) + "'");
        System.out.println("To uppercase: '" + toUpperCase(str) + "'");
        System.out.println("To lowercase: '" + toLowerCase(str) + "'");
        System.out.println("Replaced: '" + replace(str, "World", "Java") + "'");
        System.out.println("Contains 'Java': " + contains(str, "World"));
        System.out.println("Joined: '" + join(", ", "Hello", "World", "Java") + "'");
    }
}
