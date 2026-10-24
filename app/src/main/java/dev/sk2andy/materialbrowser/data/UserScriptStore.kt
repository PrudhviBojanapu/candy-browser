package dev.sk2andy.materialbrowser.data

import android.content.Context
import dev.sk2andy.materialbrowser.browser.userscript.UserScript
import dev.sk2andy.materialbrowser.browser.userscript.UserScriptParseResult
import dev.sk2andy.materialbrowser.browser.userscript.UserScriptParser
import dev.sk2andy.materialbrowser.browser.userscript.UserScriptRules
import dev.sk2andy.materialbrowser.shared.topping.ToppingFrameScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

internal class UserScriptStore(context: Context) {
    private val atomicFile = BackedUpAtomicFileMigration.fromNoBackupDirectory(
        context = context,
        fileName = FILE_NAME,
        maxBytes = MAX_FILE_BYTES,
    )

    @Synchronized
    fun load(): List<UserScript> {
        return try {
            val bytes = atomicFile.openRead().use { input -> input.readNBytes(MAX_FILE_BYTES + 1) }
            if (bytes.size !in 1..MAX_FILE_BYTES) {
                atomicFile.delete()
                return emptyList()
            }
            val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            val version = root.optInt("version", -1)
            check(version in setOf(LEGACY_FORMAT_VERSION, DEPENDENCY_FORMAT_VERSION, FORMAT_VERSION))
            val values = root.getJSONArray("scripts")
            check(values.length() <= UserScriptParser.MAX_SCRIPTS)
            val scripts = buildList {
                for (index in 0 until values.length()) {
                    val item = values.getJSONObject(index)
                    val result = UserScriptParser.parse(
                        id = item.getString("id"),
                        source = item.getString("source"),
                        enabled = item.getBoolean("enabled"),
                        updatedAtMillis = item.getLong("updatedAtMillis"),
                    )
                    val parsed = (result as UserScriptParseResult.Accepted).script
                    if (
                        version == LEGACY_FORMAT_VERSION &&
                        (parsed.requires.isNotEmpty() || parsed.resources.isNotEmpty())
                    ) continue
                    val withScope = if (version == FORMAT_VERSION) {
                        val allowedFrameScope = ToppingFrameScope.fromWireValue(
                            item.getString("allowedFrameScope"),
                        ) ?: error("Invalid frame scope")
                        check(allowedFrameScope.isWithin(parsed.declaredFrameScope))
                        parsed.copy(allowedFrameScope = allowedFrameScope)
                    } else {
                        parsed.copy(allowedFrameScope = ToppingFrameScope.Top)
                    }
                    add(
                        if (version >= DEPENDENCY_FORMAT_VERSION) {
                            withScope.withDependencies(item)
                        } else {
                            withScope
                        },
                    )
                }
            }
            check(UserScriptRules.isWithinCollectionBounds(scripts))
            val baseScripts = if (scripts.isEmpty()) defaultToppings() else scripts
            val finalScripts = ensureDefaultToppings(baseScripts)
            if (finalScripts != scripts) {
                save(finalScripts)
            }
            finalScripts
        } catch (_: FileNotFoundException) {
            defaultToppings().also { save(it) }
        } catch (_: Exception) {
            atomicFile.delete()
            defaultToppings().also { save(it) }
        }
    }

    private fun ensureDefaultToppings(current: List<UserScript>): List<UserScript> {
        val defaults = defaultToppings()
        val defaultMap = defaults.associateBy { it.id }
        val obsoleteIds = setOf("media-sniffer", "ublock-shield", "adguard-extra", "shortlink-timer-bypass", "anti-adblock-killer")
        var changed = false
        val filtered = current.filterNot { it.id in obsoleteIds }.toMutableList()
        if (filtered.size != current.size) {
            changed = true
        }
        val updated = filtered.map { existing ->
            val default = defaultMap[existing.id]
            if (default != null && default.source != existing.source) {
                changed = true
                default.copy(enabled = existing.enabled, allowedFrameScope = existing.allowedFrameScope)
            } else {
                existing
            }
        }.toMutableList()
        defaults.forEach { default ->
            if (updated.none { it.id == default.id }) {
                updated.add(default)
                changed = true
            }
        }
        return if (changed) updated else current
    }

    private fun defaultToppings(): List<UserScript> {
        val list = mutableListOf<UserScript>()

        val brianWeissSource = """
// ==UserScript==
// @name Brian Weiss Watch Master
// @namespace candy.topping.brianweiss
// @version 0.8
// @description Master controller for Brian Weiss video streams, automated watch progress, lecture indexing, and player controls
// @match *://*/*
// @run-at document-start
// @candy-frames all-matching
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__brianWeissWatchMasterInstalled) return;
    window.__brianWeissWatchMasterInstalled = true;

    function initWatchMaster() {
        document.addEventListener('keydown', function(e) {
            if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.isContentEditable)) return;
            const video = document.querySelector('video');
            if (!video) return;

            if (e.key === '[') {
                video.playbackRate = Math.max(0.25, video.playbackRate - 0.25);
                showNotice('Speed: ' + video.playbackRate.toFixed(2) + 'x');
            } else if (e.key === ']') {
                video.playbackRate = Math.min(4.0, video.playbackRate + 0.25);
                showNotice('Speed: ' + video.playbackRate.toFixed(2) + 'x');
            } else if (e.key === '\\') {
                video.playbackRate = 1.0;
                showNotice('Speed: 1.00x');
            }
        });

        function trackProgress() {
            const video = document.querySelector('video');
            if (!video || !video.duration) return;
            const storageKey = 'brianweiss_progress_' + encodeURIComponent(window.location.pathname);
            if (video.currentTime > 5 && !video.ended) {
                try { localStorage.setItem(storageKey, Math.floor(video.currentTime).toString()); } catch(e) {}
            }
        }
        setInterval(trackProgress, 5000);

        function showNotice(text) {
            let el = document.getElementById('brian-weiss-notice');
            if (!el) {
                el = document.createElement('div');
                el.id = 'brian-weiss-notice';
                el.style.cssText = 'position:fixed;top:20px;right:20px;background:rgba(0,0,0,0.8);color:#fff;padding:8px 14px;border-radius:8px;font-size:14px;font-family:sans-serif;z-index:2147483647;pointer-events:none;transition:opacity 0.3s;';
                (document.body || document.documentElement).appendChild(el);
            }
            el.textContent = text;
            el.style.opacity = '1';
            clearTimeout(window.__brianWeissNoticeTimer);
            window.__brianWeissNoticeTimer = setTimeout(function() {
                if (el) el.style.opacity = '0';
            }, 1800);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initWatchMaster, { once: true });
    } else {
        initWatchMaster();
    }
})();
        """.trimIndent()
        val parsedBrianWeiss = UserScriptParser.parse(
            id = "brian-weiss-watch-master",
            source = brianWeissSource,
            enabled = true,
            updatedAtMillis = 1730000000000L,
        )
        if (parsedBrianWeiss is UserScriptParseResult.Accepted) {
            list.add(parsedBrianWeiss.script)
        }

        val bypassShortlinksSource = """
// ==UserScript==
// @name Bypass All Shortlinks
// @namespace candy.topping.bypassallshortlinks
// @version 96.8
// @description Bypasses countdown timers, link shorteners, paste/file lockers, and unlocks direct download links
// @match *://*/*
// @run-at document-start
// @candy-frames all-matching
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__bypassAllShortlinksInstalled) return;
    window.__bypassAllShortlinksInstalled = true;

    // 1. Page Main-World Injection (intercepts page's real window.setInterval, setTimeout, and timer variables)
    const pageInjection = function() {
        if (window.__bypassPageTimerInjected) return;
        window.__bypassPageTimerInjected = true;

        const _origSetInterval = window.setInterval;
        const _origSetTimeout = window.setTimeout;

        // Accelerate page-level interval timers (100x faster for countdowns >= 300ms)
        window.setInterval = function(callback, delay, ...args) {
            let adjusted = delay;
            if (typeof delay === 'number' && delay >= 300) {
                adjusted = 15;
            }
            return _origSetInterval.call(window, callback, adjusted, ...args);
        };

        // Accelerate page-level timeouts
        window.setTimeout = function(callback, delay, ...args) {
            let adjusted = delay;
            if (typeof delay === 'number' && delay >= 400 && delay <= 180000) {
                adjusted = 25;
            }
            return _origSetTimeout.call(window, callback, adjusted, ...args);
        };

        // Neutralize anti-adblock detection in page context
        try {
            window.adsbygoogle = window.adsbygoogle || [];
            window.adsbygoogle.loaded = true;
            window.canRunAds = true;
            window.isAdBlockActive = false;
        } catch(e) {}

        // Fast-forward countdown variables in page scope
        function fastForwardGlobals() {
            try {
                if (window.downloadConfig) {
                    if (typeof window.downloadConfig.counter_time === 'number') {
                        window.downloadConfig.counter_time = 0;
                    }
                    if (typeof window.downloadConfig.timer === 'number') {
                        window.downloadConfig.timer = 0;
                    }
                }
            } catch(e) {}

            const timerVars = [
                'cTime', 'timeleft', 'timer', 'countdown', 'seconds', 'wait_time',
                'downloadWait', 'counter', 'step', 'remaining_time', 'bCnt', 'count',
                'counter_time', 'waitTime', 'wTime', 'wseconds', 'countDown'
            ];
            timerVars.forEach(v => {
                try {
                    if (typeof window[v] === 'number' && window[v] > 0) {
                        window[v] = 0;
                    }
                } catch(e) {}
            });
        }

        // Force DOM timer text to 1 so the next 15ms tick immediately hits <= 0 and calls completion
        function tickDomTimers() {
            fastForwardGlobals();
            try {
                const timerSelectors = [
                    '#file-timer', '#timer', '#countdown', '.timer-counter', '#seconds',
                    '.countdown', '#count', '.count', '#wait', '[id*="timer"]', '[class*="timer"]',
                    '[id*="countdown"]', '[class*="countdown"]', '[id*="counter"]', '[class*="counter"]'
                ];
                timerSelectors.forEach(sel => {
                    const elements = document.querySelectorAll(sel);
                    elements.forEach(el => {
                        const txt = (el.innerText || el.textContent || '').trim();
                        const num = parseInt(txt, 10);
                        if (!isNaN(num) && num > 1) {
                            el.innerText = '1';
                            el.textContent = '1';
                        }
                    });
                });
            } catch(e) {}
        }

        setInterval(tickDomTimers, 40);
        tickDomTimers();
    };

    function injectScript() {
        try {
            const script = document.createElement('script');
            script.textContent = '(' + pageInjection.toString() + ')();';
            (document.documentElement || document.head || document.body).appendChild(script);
            script.remove();
        } catch(e) {}
    }

    // Also directly hook unsafeWindow / wrappedJSObject if accessible
    const targetWin = (typeof window !== 'undefined' && window.wrappedJSObject) ? window.wrappedJSObject : (typeof unsafeWindow !== 'undefined' ? unsafeWindow : window);
    try {
        const _origSetInterval = targetWin.setInterval;
        const _origSetTimeout = targetWin.setTimeout;

        targetWin.setInterval = function(callback, delay, ...args) {
            let adjusted = delay;
            if (typeof delay === 'number' && delay >= 300) {
                adjusted = 15;
            }
            return _origSetInterval.call(targetWin, callback, adjusted, ...args);
        };

        targetWin.setTimeout = function(callback, delay, ...args) {
            let adjusted = delay;
            if (typeof delay === 'number' && delay >= 400 && delay <= 180000) {
                adjusted = 25;
            }
            return _origSetTimeout.call(targetWin, callback, adjusted, ...args);
        };
    } catch(e) {}

    injectScript();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectScript, { once: true });
    }

    // 2. Prevent tab visibility throttling / pause when switching tabs
    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
    } catch(e) {}

    // 3. Unlock disabled download and skip buttons
    function unlockButtons() {
        injectScript();
        const buttonSelectors = [
            '#download-button', '#btn-download', '#btn-main', '#downloadBtn',
            '.download-btn', '.btn-download', '.get-link', '#go-link', '#btn6',
            'a.b-download', 'button.download', '#submit-button', '.btn-captcha',
            'button[disabled]', 'a[disabled]', '[id*="download"][disabled]',
            '.btn-success[disabled]', '#c-btn', '.skip-btn', '#skip_bu', '#show-link',
            '#freeBtn', '[id*="free"][disabled]', '[onclick*="startFreeDownload"]'
        ];

        buttonSelectors.forEach(sel => {
            try {
                const nodes = document.querySelectorAll(sel);
                nodes.forEach(el => {
                    el.removeAttribute('disabled');
                    el.removeAttribute('readonly');
                    if (el.style.display === 'none') el.style.display = '';
                    if (el.style.visibility === 'hidden') el.style.visibility = 'visible';
                    if (el.style.opacity === '0') el.style.opacity = '1';
                    if (el.classList.contains('disabled')) el.classList.remove('disabled');
                });
            } catch(e) {}
        });

        // Fast-forward any remaining timer texts in DOM
        try {
            const waitTexts = document.querySelectorAll('#file-timer, #timer, #countdown, .timer-counter, #seconds, [id*="timer"], [id*="countdown"]');
            waitTexts.forEach(t => {
                const txt = (t.innerText || t.textContent || '').trim();
                const num = parseInt(txt, 10);
                if (!isNaN(num) && num > 1) {
                    t.innerText = '1';
                    t.textContent = '1';
                }
            });
        } catch(e) {}
    }

    setInterval(unlockButtons, 100);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', unlockButtons, { once: true });
    } else {
        unlockButtons();
    }
})();
        """.trimIndent()
        val parsedBypassShortlinks = UserScriptParser.parse(
            id = "bypass-all-shortlinks",
            source = bypassShortlinksSource,
            enabled = true,
            updatedAtMillis = 1730000000001L,
        )
        if (parsedBypassShortlinks is UserScriptParseResult.Accepted) {
            list.add(parsedBypassShortlinks.script)
        }

        val mangadexSource = """
// ==UserScript==
// @name Mangadex Group Blocker
// @namespace candy.topping.mangadexgroupblocker
// @version 3.0
// @description Hides chapter releases, updates, and feeds from unwanted scanlation groups and blocked languages on MangaDex
// @match https://mangadex.org/*
// @match https://*.mangadex.org/*
// @run-at document-end
// @candy-frames top
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__mangadexGroupBlockerInstalled) return;
    window.__mangadexGroupBlockerInstalled = true;

    const blockedGroups = [];

    function filterChapters() {
        if (!blockedGroups.length) return;
        try {
            const chapterRows = document.querySelectorAll('.chapter-feed__container, .chapter, [class*="chapter-grid"], [data-group-id]');
            chapterRows.forEach(row => {
                const text = row.innerText.toLowerCase();
                const isBlocked = blockedGroups.some(g => text.includes(g.toLowerCase()));
                if (isBlocked) {
                    row.style.setProperty('display', 'none', 'important');
                }
            });
        } catch(e) {}
    }

    const observer = new MutationObserver(filterChapters);
    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
    }
    filterChapters();
})();
        """.trimIndent()
        val parsedMangadex = UserScriptParser.parse(
            id = "mangadex-group-blocker",
            source = mangadexSource,
            enabled = true,
            updatedAtMillis = 1730000000000L,
        )
        if (parsedMangadex is UserScriptParseResult.Accepted) {
            list.add(parsedMangadex.script)
        }

        val removeAntiAdblockSource = """
// ==UserScript==
// @name Remove Anti-Adblock Popup - Advanced
// @namespace candy.topping.removeantiadblock
// @version 4.2
// @description Removes anti-adblock popups, overlays, backdrop blurs, and scroll locks across all sites including YouTube and streaming hosts
// @match *://*/*
// @run-at document-start
// @candy-frames all-matching
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__removeAntiAdblockInstalled) return;
    window.__removeAntiAdblockInstalled = true;

    // 1. Inject bait defusal directly into the page's MAIN world
    const pageBaitDefuser = function() {
        if (window.__pageBaitDefuserInjected) return;
        window.__pageBaitDefuserInjected = true;

        const noop = function() {};
        const dummy = {
            check: noop,
            clearEvent: noop,
            on: function() { return this; },
            onDetected: function() { return this; },
            onNotDetected: function(fn) { try { fn(); } catch(e){} return this; },
            setOption: function() { return this; }
        };

        try {
            window.fuckAdBlock = dummy;
            window.BlockAdBlock = function() { return dummy; };
            window.Sniffer = dummy;
            window.BabNotification = dummy;
            window.canRunAds = true;
            window.isAdBlockActive = false;
            window.adsbygoogle = window.adsbygoogle || [];
            window.adsbygoogle.loaded = true;
            window.google_ad_status = 1;
        } catch(e) {}

        try {
            const origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
            const origOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
            function isBait(el) {
                const cls = (el.className || '').toString().toLowerCase();
                const id = (el.id || '').toLowerCase();
                return cls.includes('ad-banner') || cls.includes('adsbox') || cls.includes('pub_300x250') ||
                       cls.includes('ad_unit') || cls.includes('sponsor-banner') || id.includes('adsbox');
            }
            if (origOffsetHeight && origOffsetHeight.get) {
                Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
                    get: function() { return isBait(this) ? 250 : origOffsetHeight.get.call(this); },
                    configurable: true
                });
            }
            if (origOffsetWidth && origOffsetWidth.get) {
                Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
                    get: function() { return isBait(this) ? 300 : origOffsetWidth.get.call(this); },
                    configurable: true
                });
            }
        } catch(e) {}
    };

    function injectBaitScript() {
        try {
            const script = document.createElement('script');
            script.textContent = '(' + pageBaitDefuser.toString() + ')();';
            (document.documentElement || document.head || document.body).appendChild(script);
            script.remove();
        } catch(e) {}
    }

    injectBaitScript();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectBaitScript, { once: true });
    }

    // Also hook unsafeWindow / wrappedJSObject
    const targetWin = (typeof window !== 'undefined' && window.wrappedJSObject) ? window.wrappedJSObject : (typeof unsafeWindow !== 'undefined' ? unsafeWindow : window);
    try {
        targetWin.adsbygoogle = targetWin.adsbygoogle || [];
        targetWin.adsbygoogle.loaded = true;
        targetWin.canRunAds = true;
    } catch(e) {}

    // 2. Prevent infinite debugger loops
    try {
        const origFunction = window.Function;
        window.Function = function(...args) {
            if (args.length > 0 && typeof args[args.length - 1] === 'string') {
                const body = args[args.length - 1];
                if (body.includes('debugger') || body.includes('while (true)') || body.includes('while(true)')) {
                    return function() {};
                }
            }
            return origFunction.apply(this, args);
        };
        window.Function.prototype = origFunction.prototype;
    } catch(e) {}

    // 3. Remove anti-adblock modal backdrops and restore page scroll
    function removeAntiAdblockOverlays() {
        injectBaitScript();
        try {
            if (document.body) {
                const bStyle = window.getComputedStyle(document.body);
                if (bStyle.overflow === 'hidden' || bStyle.position === 'fixed') {
                    document.body.style.setProperty('overflow', 'auto', 'important');
                    if (bStyle.position === 'fixed') {
                        document.body.style.setProperty('position', 'static', 'important');
                    }
                }
            }
            if (document.documentElement) {
                const dStyle = window.getComputedStyle(document.documentElement);
                if (dStyle.overflow === 'hidden') {
                    document.documentElement.style.setProperty('overflow', 'auto', 'important');
                }
            }

            const ytDialogs = document.querySelectorAll('ytd-enforcement-message-view-model, tp-yt-paper-dialog, #error-screen');
            ytDialogs.forEach(d => {
                if (d.innerText && d.innerText.toLowerCase().includes('ad blocker')) {
                    d.remove();
                    const video = document.querySelector('video');
                    if (video && video.paused) video.play();
                }
            });

            const adblockKeywords = ['adblock', 'ad block', 'ad-block', 'ad blocker', 'adblocker', 'disable your ad', 'turn off your ad', 'whitelisting'];
            const suspicious = document.querySelectorAll('div, section, aside, [role="dialog"], [class*="modal"], [class*="popup"], [class*="backdrop"], [id*="adblock"], [class*="adblock"]');
            suspicious.forEach(el => {
                const text = (el.innerText || '').toLowerCase();
                const hasAdblockWarning = adblockKeywords.some(kw => text.includes(kw));
                if (hasAdblockWarning && text.length < 500) {
                    const style = window.getComputedStyle(el);
                    const zIndex = parseInt(style.zIndex, 10);
                    if ((style.position === 'fixed' || style.position === 'absolute') && (isNaN(zIndex) || zIndex >= 100)) {
                        el.remove();
                    }
                }
            });

            const blurred = document.querySelectorAll('[style*="filter"], [style*="blur"]');
            blurred.forEach(el => {
                if (el.style.filter && el.style.filter.includes('blur')) {
                    el.style.removeProperty('filter');
                }
            });
        } catch(e) {}
    }

    setInterval(removeAntiAdblockOverlays, 300);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', removeAntiAdblockOverlays, { once: true });
    } else {
        removeAntiAdblockOverlays();
    }
})();
        """.trimIndent()
        val parsedRemoveAntiAdblock = UserScriptParser.parse(
            id = "remove-anti-adblock-popup-advanced",
            source = removeAntiAdblockSource,
            enabled = true,
            updatedAtMillis = 1730000000001L,
        )
        if (parsedRemoveAntiAdblock is UserScriptParseResult.Accepted) {
            list.add(parsedRemoveAntiAdblock.script)
        }

        return list
    }

    @Synchronized
    fun save(input: List<UserScript>): Boolean {
        if (!UserScriptRules.isWithinCollectionBounds(input)) return false
        val scripts = input.map { script ->
            if (!UserScriptRules.isCanonical(script)) return false
            script
        }
        val root = JSONObject()
            .put("version", FORMAT_VERSION)
            .put(
                "scripts",
                JSONArray().also { array ->
                    scripts.forEach { script ->
                        array.put(
                            JSONObject()
                                .put("id", script.id)
                                .put("source", script.source)
                                .put("enabled", script.enabled)
                                .put("updatedAtMillis", script.updatedAtMillis)
                                .put("allowedFrameScope", script.allowedFrameScope.wireValue)
                                .put(
                                    "requires",
                                    JSONArray().also { requires ->
                                        script.requires.forEach { dependency ->
                                            requires.put(
                                                JSONObject()
                                                    .put("url", dependency.url)
                                                    .put("sha256", dependency.sha256)
                                                    .put("source", dependency.source),
                                            )
                                        }
                                    },
                                )
                                .put(
                                    "resources",
                                    JSONArray().also { resources ->
                                        script.resources.forEach { dependency ->
                                            resources.put(
                                                JSONObject()
                                                    .put("name", dependency.name)
                                                    .put("url", dependency.url)
                                                    .put("sha256", dependency.sha256)
                                                    .put("content", dependency.encodedContent)
                                                    .put("mimeType", dependency.mimeType),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAX_FILE_BYTES) return false
        var output: FileOutputStream? = null
        return try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
            true
        } catch (_: Exception) {
            output?.let(atomicFile::failWrite)
            false
        }
    }

    @Synchronized
    fun clear() = atomicFile.delete()

    private fun UserScript.withDependencies(item: JSONObject): UserScript {
        val storedRequires = item.getJSONArray("requires")
        check(storedRequires.length() == requires.size)
        val hydratedRequires = requires.mapIndexed { index, declaration ->
            val stored = storedRequires.getJSONObject(index)
            check(stored.getString("url") == declaration.url)
            check(stored.optNullableString("sha256") == declaration.sha256)
            declaration.copy(source = stored.getString("source"))
        }
        val storedResources = item.getJSONArray("resources")
        check(storedResources.length() == resources.size)
        val hydratedResources = resources.mapIndexed { index, declaration ->
            val stored = storedResources.getJSONObject(index)
            check(stored.getString("name") == declaration.name)
            check(stored.getString("url") == declaration.url)
            check(stored.optNullableString("sha256") == declaration.sha256)
            declaration.copy(
                encodedContent = stored.getString("content"),
                mimeType = stored.getString("mimeType"),
            )
        }
        return copy(requires = hydratedRequires, resources = hydratedResources)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    internal companion object {
        const val FILE_NAME = "user_scripts.json"
        const val FORMAT_VERSION = 3
        private const val LEGACY_FORMAT_VERSION = 1
        private const val DEPENDENCY_FORMAT_VERSION = 2
        // Keeps synchronous startup recovery bounded; unusually escape-heavy JSON fails closed.
        const val MAX_FILE_BYTES = 8 * 1_024 * 1_024
    }
}
