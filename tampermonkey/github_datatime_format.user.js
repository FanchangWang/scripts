// ==UserScript==
// @name         Github 显示 24 小时时间格式
// @namespace    http://tampermonkey.net/
// @version      1.7.0
// @description  使用北京时间 24 小时格式显示时间
// @icon         https://github.com/fluidicon.png
// @author       guyuexuan
// @license      MIT
// @updateURL    https://mirror.ghproxy.com/https://raw.githubusercontent.com/FanchangWang/tampermonkey_script/main/github_datatime_format.user.js
// @downloadURL  https://mirror.ghproxy.com/https://raw.githubusercontent.com/FanchangWang/tampermonkey_script/main/github_datatime_format.user.js
// @match        https://github.com/*
// @run-at       document-idle
// ==/UserScript==

(function () {
    'use strict';

    let isProcessing = false;

    /**
     * 格式化日期时间
     * 
     * @param {string} datetimeString 
     * @returns 
     */
    function formatDateTime(datetimeString) {
        const dateTime = new Date(datetimeString);
        const now = new Date();

        // 确保日期有效
        if (isNaN(dateTime.getTime())) {
            return datetimeString; // 返回原始字符串
        }

        const hour = dateTime.getHours().toString().padStart(2, "0");
        const minute = dateTime.getMinutes().toString().padStart(2, "0");
        const second = dateTime.getSeconds().toString().padStart(2, "0");
        const timeStr = `${hour}:${minute}:${second}`;

        // 使用 Date 对象的方法来判断是否是同一天
        const isToday = dateTime.toDateString() === now.toDateString();
        const isYesterday = new Date(now - 86400000).toDateString() === dateTime.toDateString();

        if (isToday) {
            return `今天 ${timeStr}`;
        } else if (isYesterday) {
            return `昨天 ${timeStr}`;
        }

        const year = dateTime.getFullYear();
        const month = (dateTime.getMonth() + 1).toString().padStart(2, "0");
        const day = dateTime.getDate().toString().padStart(2, "0");

        if (year === now.getFullYear()) {
            return `${month}-${day} ${timeStr}`;
        } else {
            return `${year}-${month}-${day} ${timeStr}`;
        }
    }

    /**
     * 遍历 relative-time 元素并修改显示时间格式
     */
    const applyDateTimeFormat = () => {
        if (isProcessing) return;

        isProcessing = true;
        setTimeout(() => {
            try {
                document.querySelectorAll(`relative-time`).forEach((item) => {
                    if (item.shadowRoot) {
                        item.shadowRoot.textContent = formatDateTime(item.datetime.toString());
                    }
                });
                document.querySelectorAll('h3[data-testid="commit-group-title"]').forEach((item) => {
                    if (item.textContent.includes('Commits on ')) {
                        item.textContent = '提交时间 ' + formatDateTime(item.textContent.replace('Commits on ', ''))
                    }
                })
            } catch (error) {
                console.error('格式化时间出错:', error);
            } finally {
                isProcessing = false;
            }
        }, 1000);
    }

    applyDateTimeFormat();

    // 监听 Github 页面的主体区域，动态添加的评论等内容会在这里
    const observer = new MutationObserver((mutations) => {
        if (mutations.some(mutation => mutation.addedNodes.length > 0)) {
            applyDateTimeFormat();
        }
    });

    observer.observe(document, {
        childList: true,
        subtree: true
    });
})();
