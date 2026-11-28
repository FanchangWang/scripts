export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ghview 链接生成器</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8fa;--panel:#fff;--text:#1f2328;--muted:#59636e;--line:#d1d9e0;--accent:#0969da;--accent-hover:#0550ae;--accent-text:#fff;--danger:#cf222e}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--text:#e6edf3;--muted:#9198a1;--line:#30363d;--accent:#2f81f7;--accent-hover:#4493f8;--accent-text:#fff;--danger:#f85149}}
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
<h1>ghview 链接生成器</h1>
<p class="lead">粘贴 GitHub Raw 或 GitHub Pages 链接，自动生成 ghview 支持的加速格式。</p>
<label for="source">原始链接</label>
<input id="source" type="url" spellcheck="false" autocomplete="off" placeholder="https://raw.githubusercontent.com/owner/repo/main/README.md">
<div class="actions"><button id="generate" class="primary" type="button">生成链接</button></div>
<p id="hint" class="hint" role="status"></p>
<section id="results" class="results" hidden></section>
<footer>支持 raw.githubusercontent.com、github.com/…/blob/…、*.github.io 以及本站已有的 /gh/、/ghpages/、/https:// 格式。</footer>
</main>
<script>
(function(){
"use strict";
var source=document.getElementById("source");
var hint=document.getElementById("hint");
var results=document.getElementById("results");
var origin=window.location.origin;

function valid(part,pattern){return pattern.test(part||"");}
function parse(text){
 var value=text.trim();
 if(!value)return null;
 var lower=value.toLowerCase();
 if(lower.indexOf("http://")!==0&&lower.indexOf("https://")!==0)value="https://"+value;
 var url;
 try{url=new URL(value);}catch(e){return null;}
 var host=url.hostname.toLowerCase();
 var path=url.pathname.replace(new RegExp("/+$"),"");
 var search=url.search;
 var parts=path.split("/").filter(Boolean);

 if(path.indexOf("/gh/")===0){
  var p=path.slice(4).split("/").filter(Boolean);
  if(p.length<4)return null;
  return {kind:"raw",owner:p[0],repo:p[1],branch:p[2],file:p.slice(3).join("/"),search:search};
 }
 if(path.indexOf("/ghpages/")===0){
  var p=path.slice(9).split("/").filter(Boolean);
  if(p.length<2)return null;
  return {kind:"pages",owner:p[0],repo:p[1],file:p.slice(2).join("/"),search:search};
 }
 if(path.indexOf("/https://")===0||path.indexOf("/http://")===0){
  return parse(path.slice(1)+search);
 }
 if(host==="raw.githubusercontent.com"){
  if(parts.length<4)return null;
  return {kind:"raw",owner:parts[0],repo:parts[1],branch:parts[2],file:parts.slice(3).join("/"),search:search};
 }
 if(host==="github.com"){
  if(parts.length>=5&&(parts[2]==="blob"||parts[2]==="raw")){
   return {kind:"raw",owner:parts[0],repo:parts[1],branch:parts[3],file:parts.slice(4).join("/"),search:search};
  }
  return null;
 }
 if(host.slice(-10)===".github.io"){
  var owner=host.slice(0,-10);
  if(!owner||parts.length<1)return null;
  return {kind:"pages",owner:owner,repo:parts[0],file:parts.slice(1).join("/"),search:search};
 }
 return null;
}

function links(item){
 var suffix=item.search||"";
 if(item.kind==="raw"){
  var short="/gh/"+[item.owner,item.repo,item.branch,item.file].join("/")+suffix;
  var full="/https://raw.githubusercontent.com/"+[item.owner,item.repo,item.branch,item.file].join("/")+suffix;
  return [{label:"简写格式  /gh/",path:short},{label:"完整 URL  /https://",path:full}];
 }
 var pShort="/ghpages/"+[item.owner,item.repo,item.file].join("/")+suffix;
 var pFull="/https://"+item.owner+".github.io/"+[item.repo,item.file].join("/")+suffix;
 return [{label:"Pages 简写  /ghpages/",path:pShort},{label:"Pages 完整 URL  /https://",path:pFull}];
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
  var absolute=origin+item.path;
  var card=document.createElement("div");card.className="card";
  var label=document.createElement("div");label.className="label";label.textContent=item.label;
  var code=document.createElement("code");code.className="url";code.textContent=absolute;
  var actions=document.createElement("div");actions.className="card-actions";
  var copyBtn=document.createElement("button");copyBtn.type="button";copyBtn.textContent="复制";
  copyBtn.addEventListener("click",function(){copy(absolute,copyBtn);});
  var open=document.createElement("a");open.className="button";open.textContent="打开";open.href=absolute;open.target="_blank";open.rel="noopener noreferrer";
  actions.appendChild(copyBtn);actions.appendChild(open);
  card.appendChild(label);card.appendChild(code);card.appendChild(actions);
  results.appendChild(card);
 });
 results.hidden=false;
}

function generate(){
 hint.className="hint";hint.textContent="";
 var item=parse(source.value);
 if(!item){hint.className="hint error";hint.textContent="无法识别这个链接，请检查输入。";results.hidden=true;return;}
 render(links(item));
}
document.getElementById("generate").addEventListener("click",generate);
source.addEventListener("keydown",function(e){if(e.key==="Enter")generate();});
})();
</script>
</body>
</html>`;
