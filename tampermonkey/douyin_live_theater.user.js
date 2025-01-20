// ==UserScript==
// @name         抖音直播网页全屏、原画
// @namespace    http://tampermonkey.net/
// @version      1.5.0
// @description  抖音直播自动开启网页全屏、自动切换原画、关闭礼物特效、关闭弹幕、关闭礼物面板
// @icon         https://p-pc-weboff.byteimg.com/tos-cn-i-9r5gewecjs/favicon.png
// @author       guyuexuan
// @license      MIT
// @updateURL    https://mirror.ghproxy.com/https://raw.githubusercontent.com/FanchangWang/tampermonkey_script/main/douyin_live_theater.user.js
// @downloadURL  https://mirror.ghproxy.com/https://raw.githubusercontent.com/FanchangWang/tampermonkey_script/main/douyin_live_theater.user.js
// @match        https://live.douyin.com/*
// @run-at       document-idle
// @grant        GM_registerMenuCommand
// @grant        GM_getValue
// @grant        GM_setValue
// ==/UserScript==

(function () {
    'use strict';

    /** @type [{id: string | number | null, key: string, title: string, val: boolean }] */
    let menuAll = [
        { id: null, key: "menu_theater", title: "自动网页全屏", val: true },
        { id: null, key: "menu_gift", title: "关闭礼物特效", val: true },
        { id: null, key: "menu_danmu", title: "关闭所有弹幕", val: true },
        { id: null, key: "menu_quality", title: "自动原画", val: true },
        { id: null, key: "menu_gift_panel", title: "关闭礼物面板", val: true },
    ];

    /**
     * 遍历注册菜单
     */
    menuAll.forEach(item => {
        item.val = GM_getValue(item.key, item.val);
        item.id = GM_registerMenuCommand(`${item.val ? '✅' : '❌'} ${item.title}`, () => callbackMenu(item.key, item.val));
    });

    /**
     * 菜单点击回调
     * 
     * @param {string} key 
     * @param {boolean} val 
     */
    function callbackMenu(key, val) {
        menuAll.forEach(item => {
            if (item.key === key) {
                item.val = !val;
                GM_setValue(key, item.val);
                item.id = GM_registerMenuCommand(`${item.val ? '✅' : '❌'} ${item.title}`, () => callbackMenu(item.key, item.val), { id: item.id });
                return;
            }
        });
    }

    /** @type {{ theater: Element | null, gift: Element | null, danmu: Element | null, quality: Element | null, giftPanel: Element | null }} */
    let buttonList = { theater: null, gift: null, danmu: null, quality: null, giftPanel: null };

    /**
     * 获取网页全屏按钮
     * 
     * @returns {Element|null}
     */
    function getButtonList() {
        let isTheaterNode = false;
        let isGiftNode = false;
        let targetNode = null;

        if (!buttonList.quality) {
            const qualityParent = document.querySelector('div[data-e2e="quality-selector"]');
            if (qualityParent) {
                for (const divNode of qualityParent.childNodes) {
                    if (divNode.textContent.trim() === '原画') {
                        buttonList.quality = divNode;
                        break;
                    }
                }
            }
        }
        if (!buttonList.quality) {
            return false;
        }
        const giftPanelEntranceElement = document.querySelector('div#giftPanelEntrance');
        if (giftPanelEntranceElement) {
            const giftPanelBarElement = giftPanelEntranceElement.closest('div.gitBarOptimizeEnabled');
            if (giftPanelBarElement) {
                buttonList.giftPanel = giftPanelBarElement.parentElement;
            }
        }
        if (!buttonList.giftPanel) {
            return false;
        }
        const xgIconElements = document.querySelectorAll('xg-right-grid xg-icon');
        for (const xgIconNode of xgIconElements) {
            isTheaterNode = false;
            isGiftNode = false;
            targetNode = null;
            const divElements = xgIconNode.querySelectorAll('div');
            for (const divNode of divElements) {
                if (divNode.textContent.trim().includes('网页全屏')) {
                    isTheaterNode = true;
                }
                if (divNode.textContent.trim() === '屏蔽礼物特效') {
                    isGiftNode = true;
                }
                if (divNode.textContent.trim() === '关闭弹幕') {
                    buttonList.danmu = divNode;
                    break;
                }
                if (divNode.firstElementChild && divNode.firstElementChild.tagName === 'svg') {
                    targetNode = divNode;
                }
            }
            if (isTheaterNode && targetNode) {
                buttonList.theater = targetNode;
            }
            if (isGiftNode && targetNode) {
                buttonList.gift = targetNode;
            }
        }
        if (buttonList.theater && buttonList.gift) {
            return true;
        }
        return false;
    }

    /** @type {RegExp} */
    const regex = /^\/\d+/;
    if (regex.test(window.location.pathname)) {
        /** @type {number} 计数器，防止检测不到元素一直循环 */
        let counter = 0;
        const timerButtonList = setInterval(() => {
            if (getButtonList()) {
                clearInterval(timerButtonList);
                menuAll.forEach(item => {
                    switch (item.key) {
                        case 'menu_theater':
                            if (item.val) {
                                buttonList.theater.click();
                            }
                            break;
                        case 'menu_gift':
                            if (item.val) {
                                buttonList.gift.click();
                            }
                            break;
                        case 'menu_danmu':
                            if (item.val && buttonList.danmu) {
                                buttonList.danmu.click();
                            }
                            break;
                        case 'menu_quality':
                            if (item.val) {
                                buttonList.quality.click();
                            }
                            break;
                        case 'menu_gift_panel':
                            if (item.val) {
                                buttonList.giftPanel.style.display = 'none';
                            }
                        default:
                            break;
                    }
                });
            } else {
                if (counter++ > 20) {
                    clearInterval(timerButtonList);
                }
            }
        }, 300);
    }
})();

