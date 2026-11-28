export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>github 代理链接生成器</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8fa;--panel:#fff;--text:#1f2328;--muted:#59636e;--line:#d1d9e0;--accent:#0969da;--accent-hover:#0550ae;--accent-text:#fff;--danger:#cf222e;--warn-bg:#fff8c5;--warn-line:#d4a72c}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--text:#e6edf3;--muted:#9198a1;--line:#30363d;--accent:#2f81f7;--accent-hover:#4493f8;--danger:#f85149;--warn-bg:#3b2f00;--warn-line:#9e6a03}}
*{box-sizing:border-box}
[hidden]{display:none !important}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
main{max-width:820px;margin:0 auto;padding:44px 20px}
h1{margin:0 0 8px;font-size:26px}
.lead{margin:0 0 26px;color:var(--muted)}
label{display:block;margin-bottom:8px;font-weight:600}
input[type=url]{width:100%;padding:11px 13px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit}
input[type=url]:focus{outline:2px solid var(--accent);outline-offset:1px;border-color:var(--accent)}
.modes{display:flex;gap:18px;margin:18px 0 6px;flex-wrap:wrap}
.modes label{display:flex;align-items:center;gap:7px;font-weight:500;margin:0;cursor:pointer}
button,.button{display:inline-block;padding:9px 15px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit;text-decoration:none;cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-text);font-weight:600}
button.primary:hover{background:var(--accent-hover);border-color:var(--accent-hover)}
.actions{margin-top:14px}
.hint{margin:12px 0 0;min-height:1.6em;color:var(--muted)}
.hint.error{color:var(--danger)}
.warning{margin-top:18px;padding:12px 14px;border:1px solid var(--warn-line);border-radius:6px;background:var(--warn-bg);font-size:13px}
.results{margin-top:26px;display:grid;gap:14px}
.card{border:1px solid var(--line);border-radius:8px;background:var(--panel);padding:16px}
.label{font-weight:600;margin-bottom:8px}
.url{display:block;padding:9px 10px;border-radius:6px;background:var(--bg);border:1px solid var(--line);word-break:break-all;font-size:13px}
.card-actions{display:flex;gap:10px;margin-top:12px}
footer{margin-top:34px;color:var(--muted);font-size:13px}
code{background:var(--bg);padding:1px 4px;border-radius:4px}
</style>
</head>
<body>
<main>
<h1>github 代理链接生成器</h1>
<p class="lead">输入 GitHub 相关链接，生成代理链接。仅接受 GitHub 生态域名；普通模式原样转发，不改写 MIME、不强制 HTML。</p>
<label for="source">原始链接</label>
<input id="source" type="url" spellcheck="false" autocomplete="off" placeholder="https://github.com/owner/repo/releases/download/v1/app.zip">
<div class="modes">
<label><input id="mode-normal" type="radio" name="mode" value="normal" checked>普通代理（原样转发）</label>
<label><input id="mode-deep" type="radio" name="mode" value="deep">深度转换（改写正文中的 GitHub 链接）</label>
</div>
<div class="actions"><button id="generate" class="primary" type="button">生成链接</button></div>
<p id="hint" class="hint" role="status"></p>
<div id="warning" class="warning" hidden>深度转换只改文本与 JSON 中的绝对 GitHub 链接，且不保证覆盖运行时拼接、签名或压缩内容；不适合的响应会原样放行并带 <code>X-Deep-Convert: skipped</code>。</div>
<section id="results" class="results" hidden></section>
<footer>仅支持 GitHub 生态域名（github.com、*.github.com、*.githubusercontent.com、github.io / *.github.io、gh.io 等）。支持去掉协议的 <code>/github.com/...</code> 写法，以及 <code>owner/repo</code>、Raw、Blob、Release、Archive、API、Gist、Git clone 等简写。</footer>
</main>
<script>
(function(){
"use strict";
var source=document.getElementById("source");
var hint=document.getElementById("hint");
var results=document.getElementById("results");
var warning=document.getElementById("warning");
var origin=window.location.origin;

function analyze(text){
 var value=text.trim();
 if(!value)return null;
 var lower=value.toLowerCase();
 if(lower.indexOf("http://")!==0&&lower.indexOf("https://")!==0)value="https://"+value;
 try{return new URL(value);}catch(e){return null;}
}
function linksFor(url,deep){
 var prefix=origin+(deep?"/deep":"");
 var out=[];
 function add(label,path){
  if(!out.some(function(x){return x.path===path;}))out.push({label:label,path:path});
 }
 add("完整 URL 代理",prefix+"/"+url.href);
 add("去协议格式",prefix+"/"+url.host+url.pathname+url.search);
 var host=url.hostname.toLowerCase();
 var parts=url.pathname.replace(new RegExp("/+$"),"").split("/").filter(Boolean);
 if(host==="github.com"||host==="raw.githubusercontent.com"){
  add("GitHub 简写",prefix+"/"+parts.join("/")+url.search);
 }
 return out;
}
function flash(button,text){
 var old=button.textContent;
 button.textContent=text;
 window.setTimeout(function(){button.textContent=old;},1200);
}
function copy(text,button){
 function fallback(){
  var box=document.createElement("textarea");
  box.value=text;document.body.appendChild(box);box.select();
  try{document.execCommand("copy");flash(button,"已复制");}catch(e){flash(button,"复制失败");}
  document.body.removeChild(box);
 }
 if(navigator.clipboard&&navigator.clipboard.writeText){
  navigator.clipboard.writeText(text).then(function(){flash(button,"已复制");},fallback);
 }else fallback();
}
function render(items){
 results.innerHTML="";
 items.forEach(function(item){
  var card=document.createElement("div");card.className="card";
  var label=document.createElement("div");label.className="label";label.textContent=item.label;
  var code=document.createElement("code");code.className="url";code.textContent=item.path;
  var actions=document.createElement("div");actions.className="card-actions";
  var copyBtn=document.createElement("button");copyBtn.type="button";copyBtn.textContent="复制";
  copyBtn.addEventListener("click",function(){copy(item.path,copyBtn);});
  var open=document.createElement("a");open.className="button";open.textContent="打开";open.href=item.path;open.target="_blank";open.rel="noopener noreferrer";
  actions.appendChild(copyBtn);actions.appendChild(open);
  card.appendChild(label);card.appendChild(code);card.appendChild(actions);
  results.appendChild(card);
 });
 results.hidden=false;
}
function generate(){
 hint.className="hint";hint.textContent="";
 var url=analyze(source.value);
 if(!url){hint.className="hint error";hint.textContent="无法识别这个链接，请检查输入。";results.hidden=true;return;}
 var deep=document.getElementById("mode-deep").checked;
 warning.hidden=!deep;
 render(linksFor(url,deep));
}
document.getElementById("generate").addEventListener("click",generate);
source.addEventListener("keydown",function(e){if(e.key==="Enter")generate();});
["mode-normal","mode-deep"].forEach(function(id){
 document.getElementById(id).addEventListener("change",function(){if(source.value.trim())generate();});
});
})();
</script>
</body>
</html>`;
