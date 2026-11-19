/**
 * drpy_compat.js — drpy2 等第三方 JS 运行时的宿主全局兜底
 *
 * drpy2 模块顶层 `const defaultParser={pdfh,pdfa,pd}` 直接引用宿主全局，
 * 原版由 spider jar 中的 com.github.catvod.js.Function（Java Jsoup）提供；
 * 配置未带 jar 时该类不存在，drpy2 加载即 ReferenceError，站点"不识别"。
 * 本文件基于 cheerio 提供同语义的 JS 实现（源自 drpy-node htmlParser.js，MIT），
 * 仅在未配置 spider jar 时注入，jar 存在时以 jar 的 Function 为准。
 */
import * as cheerio from "assets://js/lib/cheerio.min.js";

// 解析缓存开关
const PARSE_CACHE = true;
// 不自动加eq下标索引的选择器
const NOADD_INDEX = ':eq|:lt|:gt|:first|:last|:not|:even|:odd|:has|:contains|:matches|:empty|^body$|^#';
// 需要自动urljoin的属性
const URLJOIN_ATTR = '(url|src|href|-original|-src|-play|-url|style)$|^(data-|url-|src-)';
// 过滤特殊链接,不走urlJoin
const SPECIAL_URL = '^(ftp|magnet|thunder|ws):';

function urljoinSafe(base, url) {
    if (!url) return '';
    try {
        if (typeof urljoin === 'function') return urljoin(base, url);
    } catch (e) { /* fallthrough */ }
    try {
        return new URL(url, base || undefined).toString();
    } catch (e) {
        return url;
    }
}

class Jsoup {
    constructor(MY_URL = '') {
        this.MY_URL = MY_URL;
        this.pdfh_html = '';
        this.pdfa_html = '';
        this.pdfh_doc = null;
        this.pdfa_doc = null;
        this.docCache = new Map();
    }

    /**
     * 同一段 HTML 常被连续解析多次（一条影片调 pdfh 取标题、取图片、取链接），
     * 每次 cheerio.load 都要重建 DOM，在 QuickJS 里很贵，缓存最近 8 份。
     */
    doc(html) {
        if (!PARSE_CACHE || typeof html !== 'string' || html.length > 400000) return cheerio.load(html);
        const hit = this.docCache.get(html);
        if (hit) {
            this.docCache.delete(html);
            this.docCache.set(html, hit);
            return hit;
        }
        const doc = cheerio.load(html);
        this.docCache.set(html, doc);
        if (this.docCache.size > 8) this.docCache.delete(this.docCache.keys().next().value);
        return doc;
    }

    test(text, string) {
        return new RegExp(text, 'mi').exec(string) ? true : false;
    }

    contains(text, match) {
        return text.indexOf(match) !== -1;
    }

    parseHikerToJq(parse, first = false) {
        if (this.contains(parse, '&&')) {
            const parses = parse.split('&&');
            const new_parses = [];
            for (let i = 0; i < parses.length; i++) {
                const ps_list = parses[i].split(' ');
                const ps = ps_list[ps_list.length - 1];
                if (!this.test(NOADD_INDEX, ps)) {
                    if (!first && i >= parses.length - 1) {
                        new_parses.push(parses[i]);
                    } else {
                        new_parses.push(`${parses[i]}:eq(0)`);
                    }
                } else {
                    new_parses.push(parses[i]);
                }
            }
            parse = new_parses.join(' ');
        } else {
            const ps_list = parse.split(' ');
            const ps = ps_list[ps_list.length - 1];
            if (!this.test(NOADD_INDEX, ps) && first) {
                parse = `${parse}:eq(0)`;
            }
        }
        return parse;
    }

    getParseInfo(nparse) {
        let excludes = [];
        let nparse_index = 0;
        let nparse_rule = nparse;

        if (this.contains(nparse, ':eq')) {
            nparse_rule = nparse.split(':eq')[0];
            let nparse_pos = nparse.split(':eq')[1];
            if (this.contains(nparse_rule, '--')) {
                excludes = nparse_rule.split('--').slice(1);
                nparse_rule = nparse_rule.split('--')[0];
            } else if (this.contains(nparse_pos, '--')) {
                excludes = nparse_pos.split('--').slice(1);
                nparse_pos = nparse_pos.split('--')[0];
            }
            try {
                nparse_index = parseInt(nparse_pos.split('(')[1].split(')')[0]);
            } catch (e) {
            }
        } else if (this.contains(nparse, '--')) {
            nparse_rule = nparse.split('--')[0];
            excludes = nparse.split('--').slice(1);
        }

        return {nparse_rule, nparse_index, excludes};
    }

    reorderAdjacentLtAndGt(selector) {
        const adjacentPattern = /:gt\((\d+)\):lt\((\d+)\)/;
        let match;
        while ((match = adjacentPattern.exec(selector)) !== null) {
            const replacement = `:lt(${match[2]}):gt(${match[1]})`;
            selector = selector.substring(0, match.index) + replacement + selector.substring(match.index + match[0].length);
            adjacentPattern.lastIndex = match.index;
        }
        return selector;
    }

    parseOneRule(doc, nparse, ret) {
        let {nparse_rule, nparse_index, excludes} = this.getParseInfo(nparse);
        nparse_rule = this.reorderAdjacentLtAndGt(nparse_rule);
        if (!ret) ret = doc(nparse_rule);
        else ret = ret.find(nparse_rule);

        if (this.contains(nparse, ':eq')) ret = ret.eq(nparse_index);

        if (excludes.length > 0 && ret) {
            ret = ret.clone();
            for (let exclude of excludes) {
                ret.find(exclude).remove();
            }
        }

        return ret;
    }

    parseText(text) {
        text = text.replace(/[\s]+/gm, '\n');
        text = text.replace(/\n+/g, '\n').replace(/^\s+/, '');
        text = text.replace(/\n/g, ' ');
        return text;
    }

    pdfa(html, parse) {
        if (!html || !parse) return [];
        parse = this.parseHikerToJq(parse);

        const doc = this.doc(html);

        const parses = parse.split(' ');
        let ret = null;
        for (const nparse of parses) {
            ret = this.parseOneRule(doc, nparse, ret);
            if (!ret) return [];
        }

        return (ret?.toArray() ?? []).map((item) => {
            const res_html = `${doc(item)}`;
            return res_html ? res_html : '';
        });
    }

    pdfl(html, parse, list_text, list_url, MY_URL) {
        if (!html || !parse) return [];
        parse = this.parseHikerToJq(parse, false);
        const new_vod_list = [];

        const doc = this.doc(html);
        const parses = parse.split(' ');
        let ret = null;
        for (const pars of parses) {
            ret = this.parseOneRule(doc, pars, ret);
            if (!ret) return [];
        }

        ret.each((_, element) => {
            const _html = `${doc(element)}`;
            let _title = this.pdfh(_html, list_text);
            let _url = this.pd(_html, list_url, MY_URL);
            new_vod_list.push(`${_title}$${_url}`);
        });

        return new_vod_list;
    }

    pdfh(html, parse, baseUrl = '') {
        if (!html || !parse) return '';

        const doc = this.doc(html);

        if (parse === 'body&&Text' || parse === 'Text') {
            return this.parseText(doc.text());
        } else if (parse === 'body&&Html' || parse === 'Html') {
            return doc.html();
        }

        let option;
        if (this.contains(parse, '&&')) {
            const parts = parse.split('&&');
            option = parts.pop();
            parse = parts.join('&&');
        }

        parse = this.parseHikerToJq(parse, true);
        const parses = parse.split(' ');

        let ret = null;
        for (const nparse of parses) {
            ret = this.parseOneRule(doc, nparse, ret);
            if (!ret) return '';
        }

        if (option) {
            switch (option) {
                case 'Text':
                    ret = ret ? this.parseText(ret.text()) : '';
                    break;
                case 'Html':
                    ret = ret ? ret.html() : '';
                    break;
                default: {
                    const originalRet = ret.clone();
                    const options = option.split('||');
                    for (const opt of options) {
                        ret = originalRet?.attr(opt) || '';
                        if (this.contains(opt.toLowerCase(), 'style') && this.contains(ret, 'url(')) {
                            try {
                                ret = ret.match(/url\((.*?)\)/)[1];
                                ret = ret.replace(/^['"]|['"]$/g, '');
                            } catch (e) {
                            }
                        }
                        if (ret && baseUrl) {
                            const needAdd = this.test(URLJOIN_ATTR, opt) && !this.test(SPECIAL_URL, ret);
                            if (needAdd) {
                                ret = ret.includes('http') ? ret.slice(ret.indexOf('http')) : urljoinSafe(baseUrl, ret);
                            }
                        }
                        if (ret) break;
                    }
                    break;
                }
            }
        } else {
            ret = `${ret}`;
        }

        return ret;
    }

    pd(html, parse, baseUrl = '') {
        if (!baseUrl) baseUrl = this.MY_URL || globalThis.MY_URL || '';
        return this.pdfh(html, parse, baseUrl);
    }

    pq(html) {
        return cheerio.load(html);
    }
}

const compat = new Jsoup();

// 解析异常不能让整个分类/搜索失败（否则一页里只要一条规则写错，整页就是空的），
// 统一兜底成空值，让调用方按「没匹配到」继续处理。
function safe(name, fallback, fn) {
    return (...args) => {
        try {
            const ret = fn(...args);
            return ret === undefined || ret === null ? (typeof fallback === 'function' ? fallback() : fallback) : ret;
        } catch (e) {
            try {
                console.error(`[drpy_compat] ${name} error: ${e && e.message}`);
            } catch (_) {
            }
            return typeof fallback === 'function' ? fallback() : fallback;
        }
    };
}

globalThis.pdfh = safe('pdfh', '', (html, parse, baseUrl) => compat.pdfh(html, parse, baseUrl));
globalThis.pdfa = safe('pdfa', () => [], (html, parse) => compat.pdfa(html, parse));
globalThis.pd = safe('pd', '', (html, parse, baseUrl) => compat.pd(html, parse, baseUrl));
globalThis.pdfl = safe('pdfl', () => [], (html, parse, list_text, list_url, MY_URL) => compat.pdfl(html, parse, list_text, list_url, MY_URL));
globalThis.pq = safe('pq', () => null, (html) => compat.pq(html));
globalThis.jsoup = Jsoup;
