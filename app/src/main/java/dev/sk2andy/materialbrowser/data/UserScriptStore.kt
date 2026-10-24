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
        val missing = defaults.filter { default -> current.none { it.id == default.id } }
        return if (missing.isNotEmpty()) current + missing else current
    }

    private fun defaultToppings(): List<UserScript> {
        val list = mutableListOf<UserScript>()
        val defaultSource = """
// ==UserScript==
// @name Media Sniffer & Enhancer
// @namespace candy.topping.mediasniffer
// @version 1.0.0
// @description Sniffs HTML5 video streams and forwards to mpvRx
// @match *://*/*
// @run-at document-start
// ==/UserScript==

(function() {
    function notifyMedia(url, title) {
        if (!url || typeof url !== 'string') return;
        const lower = url.toLowerCase();
        if (lower.includes('.gif') || lower.includes('.png') || lower.includes('.jpg') || lower.includes('.ts') || lower.includes('.m4s')) return;
        if (lower.includes('.m3u8') || lower.includes('.mpd') || lower.includes('.mp4') || lower.includes('.webm') || lower.includes('googlevideo.com') || lower.includes('/videoplayback')) {
            if (window.CandyMediaBridge && window.CandyMediaBridge.onStreamFound) {
                window.CandyMediaBridge.onStreamFound(url, title || document.title || document.location.href);
            }
        }
    }

    function scanVideos() {
        const videos = document.querySelectorAll('video, audio, source');
        videos.forEach(el => {
            const src = el.src || el.currentSrc || el.getAttribute('data-src') || el.getAttribute('data-hls');
            if (src) notifyMedia(src, document.title);
        });
    }

    document.addEventListener('play', (e) => {
        if (e.target && (e.target.src || e.target.currentSrc)) {
            notifyMedia(e.target.currentSrc || e.target.src, document.title);
        }
    }, true);

    const observer = new MutationObserver(() => scanVideos());
    if (document.documentElement) {
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }
    window.addEventListener('DOMContentLoaded', scanVideos);
})();
        """.trimIndent()
        val parsedMedia = UserScriptParser.parse(
            id = "media-sniffer",
            source = defaultSource,
            enabled = true,
            updatedAtMillis = System.currentTimeMillis(),
        )
        if (parsedMedia is UserScriptParseResult.Accepted) {
            list.add(parsedMedia.script)
        }

        val ublockSource = """
// ==UserScript==
// @name uBlock Shield & Anti-Redirect
// @namespace candy.topping.ublockshield
// @version 1.0.0
// @description Advanced adblock, anti-popup, anti-redirect and clickjacking protection
// @match *://*/*
// @run-at document-start
// ==/UserScript==

(function() {
    'use strict';
    if (window.__uBlockShieldInstalled) return;
    window.__uBlockShieldInstalled = true;

    // 1. Defuse abusive window.open popunders and ads
    const originalOpen = window.open;
    const fakeWin = {
        closed: false,
        focus: function() {},
        blur: function() {},
        close: function() { this.closed = true; },
        location: {
            href: 'about:blank',
            replace: function() {},
            assign: function() {}
        },
        document: {
            location: { href: 'about:blank', replace: function() {} },
            write: function() {},
            close: function() {}
        }
    };

    const isBlockedHost = function(url) {
        if (!url) return false;
        try {
            const u = String(url).toLowerCase();
            return u.includes('godkc.com') || u.includes('ruddy-pass.com') ||
                   u.includes('mayzaent.com') || u.includes('smartpop') ||
                   u.includes('exoclick.com') || u.includes('adsterra.com') ||
                   u.includes('trafficjunky.com') || u.includes('syndication') ||
                   u.includes('mnaspm.com') || u.includes('clckh.com') ||
                   u.includes('stripchat.com') || u.includes('bongacams.com') ||
                   u.includes('chaturbate.com') || u.includes('ptnetcdn.com') ||
                   u.includes('xlviirdr.com') || u.includes('adcash.com') ||
                   u.includes('propeller') || u.includes('bet365') ||
                   u.includes('1xbet') || u.includes('casino');
        } catch(e) {
            return false;
        }
    };

    window.open = function(url, target, features) {
        if (!url || url === 'about:blank' || url === '') {
            return fakeWin;
        }
        if (isBlockedHost(url)) {
            console.log('[uBlock Shield] Blocked popup:', url);
            return null;
        }
        return originalOpen.apply(this, arguments);
    };

    // 2. Prevent dynamic script/iframe injection for known ad hosts
    try {
        const _origCreateElement = document.createElement;
        document.createElement = function(tagName, options) {
            const el = _origCreateElement.call(document, tagName, options);
            if (typeof tagName === 'string') {
                const tag = tagName.toLowerCase();
                if (tag === 'script' || tag === 'iframe') {
                    const _origSetAttribute = el.setAttribute;
                    el.setAttribute = function(name, value) {
                        if (name && name.toLowerCase() === 'src' && isBlockedHost(value)) {
                            return;
                        }
                        return _origSetAttribute.apply(this, arguments);
                    };
                    try {
                        Object.defineProperty(el, 'src', {
                            set: function(val) {
                                if (isBlockedHost(val)) return;
                                el.setAttribute('src', val);
                            },
                            get: function() { return el.getAttribute('src') || ''; },
                            configurable: true
                        });
                    } catch(e) {}
                }
            }
            return el;
        };
    } catch(e) {}

    // 3. Anti-Adblock Defusers (Bypass Adblock Walls)
    try {
        Object.defineProperties(window, {
            'canRunAds': { value: true, writable: true, configurable: true },
            'isAdBlockActive': { value: false, writable: true, configurable: true },
            'adblock': { value: false, writable: true, configurable: true },
            'adblocker': { value: false, writable: true, configurable: true },
            'google_ad_client': { value: true, writable: true, configurable: true }
        });
    } catch(e) {}

    // 4. Remove transparent click-trapping overlays and bad elements
    function removeClickTraps() {
        try {
            const badNodes = document.querySelectorAll('#ad-slot, #SCSpotScript, [id*="SCSpotScript"], aside.widget_custom_html iframe, iframe[src*="smartpop"], aside#custom_html-60, aside#custom_html-76, aside#custom_html-62, [class*="rootContent--"], [class*="closeButtonMinimal--"]');
            badNodes.forEach(el => el.remove());

            const elements = document.querySelectorAll('div, a, span, section');
            const w = window.innerWidth;
            const h = window.innerHeight;
            for (let i = 0; i < elements.length; i++) {
                const el = elements[i];
                const rect = el.getBoundingClientRect();
                if (rect.width >= w * 0.75 && rect.height >= h * 0.75) {
                    const style = window.getComputedStyle(el);
                    if ((style.position === 'fixed' || style.position === 'absolute') && parseInt(style.zIndex, 10) >= 500) {
                        const opacity = parseFloat(style.opacity);
                        const isTransparent = opacity < 0.15 || style.backgroundColor === 'rgba(0, 0, 0, 0)' || style.background === 'transparent';
                        if (isTransparent && (!el.innerText || el.innerText.trim().length === 0)) {
                            el.remove();
                            console.log('[uBlock Shield] Removed transparent click trap overlay');
                        }
                    }
                }
            }
        } catch(e) {}
    }

    // 5. Cosmetic ad container hiding
    const css = `
        .adsbygoogle, [class*="ad-banner"], [id*="google_ads"], [class*="popunder"],
        [class*="native-ad"], [id*="banner-ad"], [id*="ad_banner"], [class*="ad_banner"],
        [class*="sponsored-post"], div[id^="ad_"], div[class^="ad_"],
        iframe[src*="doubleclick.net"], iframe[src*="adnxs.com"], iframe[src*="exoclick.com"],
        a[href*="syndication.exoclick.com"], a[href*="adsterra.com"],
        #ad-slot, #SCSpotScript, [id*="SCSpotScript"], [class*="rootContent--"],
        [class*="closeButtonMinimal--"], aside.widget_custom_html iframe,
        iframe[src*="smartpop"], aside#custom_html-60, aside#custom_html-76,
        aside#custom_html-62, div[style*="z-index: 2147483647"],
        div[style*="z-index: 999999"] {
            display: none !important;
            visibility: hidden !important;
            opacity: 0 !important;
            pointer-events: none !important;
            height: 0 !important;
            min-height: 0 !important;
        }
    `;

    function injectStyle() {
        if (!document.head && !document.documentElement) return;
        if (document.getElementById('ublock-shield-style')) return;
        const style = document.createElement('style');
        style.id = 'ublock-shield-style';
        style.textContent = css;
        (document.head || document.documentElement).appendChild(style);
    }

    injectStyle();
    try {
        const observer = new MutationObserver(function() {
            injectStyle();
            removeClickTraps();
        });
        observer.observe(document.documentElement || document, {
            childList: true,
            subtree: true
        });
    } catch(e) {}

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            injectStyle();
            removeClickTraps();
        }, { once: true });
    } else {
        removeClickTraps();
    }
    window.addEventListener('load', removeClickTraps, { once: true });
})();
        """.trimIndent()
        val parsedShield = UserScriptParser.parse(
            id = "ublock-shield",
            source = ublockSource,
            enabled = true,
            updatedAtMillis = System.currentTimeMillis(),
        )
        if (parsedShield is UserScriptParseResult.Accepted) {
            list.add(parsedShield.script)
        }

        val adguardExtraSource = """
// ==UserScript==
// @name AdGuard Extra (Anti-Adblock Defuser)
// @namespace candy.topping.adguardextra
// @version 1.0.0
// @description Neutralizes anti-adblock scripts, bait element traps, and dynamic script re-insertion
// @match *://*/*
// @run-at document-start
// @candy-frames all
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__adguardExtraInstalled) return;
    window.__adguardExtraInstalled = true;

    // 1. Defuse global anti-adblock detection libraries and objects
    const noopFn = function() {};
    const dummyObj = {
        check: noopFn,
        clearEvent: noopFn,
        on: function(a, b) { if (a === false || a === 'no') { try { b(); } catch(e){} } return this; },
        onDetected: function() { return this; },
        onNotDetected: function(fn) { try { fn(); } catch(e){} return this; },
        setOption: function() { return this; }
    };

    try {
        window.fuckAdBlock = dummyObj;
        window.BlockAdBlock = function() { return dummyObj; };
        window.Sniffer = dummyObj;
        window.BabNotification = dummyObj;
        window.canRunAds = true;
        window.isAdBlockActive = false;
        window.adsbygoogle = window.adsbygoogle || [];
        window.adsbygoogle.loaded = true;
        window.google_ad_status = 1;
    } catch(e) {}

    // 2. Bait Element Protection: Fool scripts probing offsetHeight / clientHeight / getBoundingClientRect
    try {
        const origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
        const origOffsetWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
        const origClientHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
        const origClientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth');

        function isBaitElement(el) {
            if (!el || !el.className || typeof el.className !== 'string') return false;
            const cls = el.className.toLowerCase();
            const id = (el.id || '').toLowerCase();
            return cls.includes('ad-banner') || cls.includes('adsbox') || cls.includes('pub_300x250') ||
                   cls.includes('ad_unit') || cls.includes('ad-zone') || cls.includes('sponsor-banner') ||
                   id.includes('adsbox') || id.includes('ad-banner') || id.includes('banner-ad');
        }

        if (origOffsetHeight && origOffsetHeight.get) {
            Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
                get: function() {
                    if (isBaitElement(this)) return 250;
                    return origOffsetHeight.get.call(this);
                },
                configurable: true
            });
        }
        if (origOffsetWidth && origOffsetWidth.get) {
            Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
                get: function() {
                    if (isBaitElement(this)) return 300;
                    return origOffsetWidth.get.call(this);
                },
                configurable: true
            });
        }
        if (origClientHeight && origClientHeight.get) {
            Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
                get: function() {
                    if (isBaitElement(this)) return 250;
                    return origClientHeight.get.call(this);
                },
                configurable: true
            });
        }
        if (origClientWidth && origClientWidth.get) {
            Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
                get: function() {
                    if (isBaitElement(this)) return 300;
                    return origClientWidth.get.call(this);
                },
                configurable: true
            });
        }
    } catch(e) {}

    // 3. Network Probe Protection: Defuse fetch / XMLHttpRequest traps for Google Ads & Analytics
    try {
        const origFetch = window.fetch;
        if (typeof origFetch === 'function') {
            window.fetch = function(input, init) {
                const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                if (url && (url.includes('pagead2.googlesyndication.com') || url.includes('adservice.google.com') ||
                    url.includes('securepubads.g.doubleclick.net') || url.includes('/pagead/js/adsbygoogle.js'))) {
                    return Promise.resolve(new Response('window.adsbygoogle = window.adsbygoogle || []; window.adsbygoogle.loaded = true;', {
                        status: 200,
                        headers: { 'Content-Type': 'application/javascript' }
                    }));
                }
                return origFetch.apply(this, arguments);
            };
        }

        const origXhrOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            const urlStr = String(url || '');
            if (urlStr.includes('pagead2.googlesyndication.com') || urlStr.includes('adservice.google.com') ||
                urlStr.includes('/pagead/js/adsbygoogle.js')) {
                this.__isAdProbe = true;
            }
            return origXhrOpen.apply(this, arguments);
        };
        const origXhrSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function(body) {
            if (this.__isAdProbe) {
                Object.defineProperty(this, 'status', { value: 200, writable: false });
                Object.defineProperty(this, 'responseText', { value: '{"status":"ok"}', writable: false });
                Object.defineProperty(this, 'readyState', { value: 4, writable: false });
                if (typeof this.onreadystatechange === 'function') {
                    this.onreadystatechange(new Event('readystatechange'));
                }
                if (typeof this.onload === 'function') {
                    this.onload(new ProgressEvent('load'));
                }
                return;
            }
            return origXhrSend.apply(this, arguments);
        };
    } catch(e) {}

    // 4. Intercept dynamic script re-insertion
    try {
        const origAppend = Element.prototype.appendChild;
        Element.prototype.appendChild = function(child) {
            if (child && child.tagName && child.tagName.toLowerCase() === 'script') {
                const src = child.src || '';
                if (src && (src.includes('pagead2.') || src.includes('exoclick.') || src.includes('adsterra.') || src.includes('smartpop'))) {
                    return child;
                }
            }
            return origAppend.apply(this, arguments);
        };
    } catch(e) {}
})();
        """.trimIndent()
        val parsedAdGuard = UserScriptParser.parse(
            id = "adguard-extra",
            source = adguardExtraSource,
            enabled = true,
            updatedAtMillis = System.currentTimeMillis(),
        )
        if (parsedAdGuard is UserScriptParseResult.Accepted) {
            list.add(parsedAdGuard.script)
        }

        val shortlinkBypassSource = """
// ==UserScript==
// @name Bypass All Shortlinks & Fast-Forward Timers
// @namespace candy.topping.shortlinktimerbypass
// @version 1.0.0
// @description Fast-forwards countdown timers, skips shortlinks, and unlocks download buttons
// @match *://*/*
// @run-at document-start
// @candy-frames all
// @grant none
// ==/UserScript==

(function() {
    'use strict';
    if (window.__timerBypassInstalled) return;
    window.__timerBypassInstalled = true;

    // 1. Time-warping: Accelerate setInterval & setTimeout for countdowns
    const origSetInterval = window.setInterval;
    const origSetTimeout = window.setTimeout;

    window.setInterval = function(callback, delay, ...args) {
        if (typeof delay === 'number' && delay >= 500 && delay <= 1500) {
            return origSetInterval.call(window, callback, 20, ...args);
        }
        return origSetInterval.apply(window, arguments);
    };

    window.setTimeout = function(callback, delay, ...args) {
        if (typeof delay === 'number' && delay >= 2000 && delay <= 120000) {
            return origSetTimeout.call(window, callback, 50, ...args);
        }
        return origSetTimeout.apply(window, arguments);
    };

    // 2. Neutralize countdown state variables
    function fastForwardVariables() {
        const timerVars = ['cTime', 'timeleft', 'timer', 'countdown', 'seconds', 'wait_time', 'downloadWait', 'counter'];
        timerVars.forEach(v => {
            if (typeof window[v] === 'number' && window[v] > 0) {
                try { window[v] = 0; } catch(e) {}
            }
        });
    }

    // 3. Unlock download buttons and remove waiting overlays
    function unlockButtons() {
        fastForwardVariables();

        const buttonSelectors = [
            '#download-button', '#btn-download', '#btn-main', '#downloadBtn',
            '.download-btn', '.btn-download', '.get-link', '#go-link', '#btn6',
            'a.b-download', 'button.download', '#submit-button', '.btn-captcha',
            'button[disabled]', 'a[disabled]', '[id*="download"][disabled]'
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

        try {
            const waitTexts = document.querySelectorAll('#timer, #countdown, .timer-counter, #seconds');
            waitTexts.forEach(t => {
                if (t.innerText && /\d+/.test(t.innerText)) {
                    t.innerText = '0';
                }
            });
        } catch(e) {}
    }

    // 4. Auto-resolve common shortlink redirect parameters
    function checkDirectUrl() {
        try {
            const url = new URL(window.location.href);
            const target = url.searchParams.get('url') || url.searchParams.get('dest') ||
                           url.searchParams.get('destination') || url.searchParams.get('link') ||
                           url.searchParams.get('target');
            if (target && (target.startsWith('http://') || target.startsWith('https://'))) {
                const host = window.location.hostname;
                if (host.includes('link') || host.includes('short') || host.includes('fly') || host.includes('shrink')) {
                    window.location.replace(target);
                }
            }
        } catch(e) {}
    }

    setInterval(unlockButtons, 200);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            unlockButtons();
            checkDirectUrl();
        }, { once: true });
    } else {
        unlockButtons();
        checkDirectUrl();
    }
})();
        """.trimIndent()
        val parsedShortlink = UserScriptParser.parse(
            id = "shortlink-timer-bypass",
            source = shortlinkBypassSource,
            enabled = true,
            updatedAtMillis = System.currentTimeMillis(),
        )
        if (parsedShortlink is UserScriptParseResult.Accepted) {
            list.add(parsedShortlink.script)
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
