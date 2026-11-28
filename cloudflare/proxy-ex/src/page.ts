export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>通用代理链接生成器</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8fa;--panel:#fff;--text:#1f2328;--muted:#59636e;--line:#d1d9e0;--accent:#0969da;--accent-hover:#0550ae;--accent-text:#fff;--danger:#cf222e}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--text:#e6edf3;--muted:#9198a1;--line:#30363d;--accent:#2f81f7;--accent-hover:#4493f8;--danger:#f85149}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
main{max-width:760px;margin:0 auto;padding:48px 20px}
h1{margin:0 0 8px;font-size:26px}
.lead{margin:0 0 28px;color:var(--muted)}
label{display:block;margin-bottom:8px;font-weight:600}
input[type=url]{width:100%;padding:11px 13px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit}
input[type=url]:focus{outline:2px solid var(--accent);outline-offset:1px;border-color:var(--accent)}
button,.button{display:inline-block;padding:9px 15px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit;text-decoration:none;cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-text);font-weight:600}
button.primary:hover{background:var(--accent-hover);border-color:var(--accent-hover)}
.actions{margin-top:14px}
.hint{margin:12px 0 0;min-height:1.6em;color:var(--muted)}
.hint.error{color:var(--danger)}
.results{margin-top:28px;display:grid;gap:14px}
.card{border:1px solid var(--line);border-radius:8px;background:var(--panel);padding:16px}
.label{font-weight:600;margin-bottom:8px}
.url{display:block;padding:9px 10px;border-radius:6px;background:var(--bg);border:1px solid var(--line);word-break:break-all;font-size:13px}
.card-actions{display:flex;gap:10px;margin-top:12px}
footer{margin-top:36px;color:var(--muted);font-size:13px}
</style>
</head>
<body>
<main>
<h1>通用代理链接生成器</h1>
<p class="lead">输入任意 http(s) 链接，生成代理地址。仅原样转发，不改写页面内容。</p>
<label for="source">原始链接</label>
<input id="source" type="url" spellcheck="false" autocomplete="off" placeholder="https://example.com/path/to/file">
<div class="actions"><button id="generate" class="primary" type="button">生成链接</button></div>
<p id="hint" class="hint" role="status"></p>
<section id="results" class="results" hidden></section>
<footer>只支持 GET / HEAD；禁私网与环回地址。相对资源会通过 Referer 还原后继续走代理。</footer>
</main>
<script>
(function(){
"use strict";
var source=document.getElementById("source");
var hint=document.getElementById("hint");
var results=document.getElementById("results");
var origin=window.location.origin;

function analyze(text){
 var value=text.trim();
 if(!value)return null;
 var lower=value.toLowerCase();
 if(lower.indexOf("http://")!==0&&lower.indexOf("https://")!==0)value="https://"+value;
 try{return new URL(value);}catch(e){return null;}
}
function linksFor(url){
 return [
  {label:"完整 URL 格式",path:origin+"/"+url.href},
  {label:"去协议格式",path:origin+"/"+url.host+url.pathname+url.search}
 ];
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
 render(linksFor(url));
}
document.getElementById("generate").addEventListener("click",generate);
source.addEventListener("keydown",function(e){if(e.key==="Enter")generate();});
})();
</script>
</body>
</html>`;
