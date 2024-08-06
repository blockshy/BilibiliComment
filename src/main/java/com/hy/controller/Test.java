package com.hy.controller;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;

public class Test {

    public static void main(String[] args) {
        try {
            // 获取网页内容
            Document document = Jsoup.connect("https://www.bilibili.com/opus/959069689021988901?spm_id_from=333.1365.0.0").get();
            // 打印网页内容
            System.out.println(document.html());

            // 示例：提取所有的 AJAX 请求 URL，假设它们在 script 标签中
            Elements scripts = document.getElementsByTag("script");
            for (Element script : scripts) {
                String scriptContent = script.html();
                // 这里你需要根据实际情况来提取 URL，比如使用正则表达式或字符串处理
                // 例如，提取 API 请求的 URL
                // 正则表达式可以匹配以 "https://api.example.com" 开头的 URL
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
