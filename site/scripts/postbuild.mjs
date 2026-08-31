/**
 * Post-build script: 站点根路径 (`/token-mock/`) 智能重定向。
 *
 * VitePress 多语言构建只在根路径生成 404.html,而 GitHub Pages 在访问
 * `/token-mock/` 时需要一个 `index.html`。本脚本:
 *   1. 把构建根目录的 404.html 改写为跳转页(兜底坏链接);
 *   2. 生成按浏览器 Accept-Language 选择语言的 index.html,
 *      默认简体中文(zh-CN 为根 locale),英文浏览器跳 /en/。
 */
import { writeFileSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const distDir = join(__dirname, '..', '.vitepress', 'dist')
const basePath = '/token-mock'
const defaultLocale = '' // 根路径 = zh-CN
const rootUrl = `${basePath}/${defaultLocale}`

const redirect404 = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>token-mock</title>
<meta http-equiv="refresh" content="0; url=${rootUrl}">
<link rel="canonical" href="${rootUrl}">
</head>
<body><p>Redirecting to <a href="${rootUrl}">${rootUrl}</a>…</p></body>
</html>
`
const notFoundPath = join(distDir, '404.html')
if (existsSync(notFoundPath)) {
  writeFileSync(notFoundPath, redirect404, 'utf-8')
  console.log(`✓ Rewrote ${notFoundPath} as redirect to ${rootUrl}`)
}

const smartIndex = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>token-mock</title>
<meta http-equiv="refresh" content="0; url=${rootUrl}">
<link rel="canonical" href="${rootUrl}">
<script>
  (function () {
    var base = '${basePath}';
    var raw = (navigator.languages && navigator.languages.length)
      ? navigator.languages.join(',')
      : (navigator.language || '');
    var langs = raw.split(',').map(function (s) {
      var i = s.indexOf(';');
      var tag = (i >= 0 ? s.substring(0, i) : s).trim();
      var dash = tag.indexOf('-');
      return (dash >= 0 ? tag.substring(0, dash) : tag).toLowerCase();
    });
    var target = langs.indexOf('en') !== -1 ? '/en/' : '${defaultLocale}/';
    var url = base + target;
    window.__mockTarget = url;
    document.querySelector('meta[http-equiv="refresh"]').setAttribute('content', '0; url=' + url);
    var link = document.querySelector('link[rel="canonical"]');
    if (link) link.setAttribute('href', url);
  })();
</script>
</head>
<body>
<p>Redirecting to <a id="lnk" href="${rootUrl}">${rootUrl}</a>…</p>
<script>if (window.__mockTarget) { document.getElementById('lnk').href = window.__mockTarget; }</script>
</body>
</html>
`
const indexPath = join(distDir, 'index.html')
writeFileSync(indexPath, smartIndex, 'utf-8')
console.log(`✓ Wrote ${indexPath} with Accept-Language aware redirect`)
