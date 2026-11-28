export const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Docker 镜像加速代理</title>
<style>
:root{color-scheme:light dark;--bg:#f6f8fa;--panel:#fff;--text:#1f2328;--muted:#59636e;--line:#d1d9e0;--accent:#0969da;--accent-hover:#0550ae;--accent-text:#fff;--danger:#cf222e;--chip:#ddf4ff}
@media(prefers-color-scheme:dark){:root{--bg:#0d1117;--panel:#161b22;--text:#e6edf3;--muted:#9198a1;--line:#30363d;--accent:#2f81f7;--accent-hover:#4493f8;--danger:#f85149;--chip:#0d419d}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
main{max-width:860px;margin:0 auto;padding:44px 20px}
h1{margin:0 0 8px;font-size:26px}
h2{font-size:18px;margin-top:32px}
.lead,.muted{color:var(--muted)}
label{display:block;margin-bottom:8px;font-weight:600}
input[type=text]{width:100%;padding:11px 13px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit}
button,.button{display:inline-block;padding:8px 14px;border:1px solid var(--line);border-radius:6px;background:var(--panel);color:var(--text);font:inherit;text-decoration:none;cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-text);font-weight:600}
button.primary:hover{background:var(--accent-hover);border-color:var(--accent-hover)}
button:disabled{opacity:.5;cursor:not-allowed}
.actions{display:flex;gap:10px;margin-top:14px;flex-wrap:wrap}
.hint{min-height:1.6em;color:var(--muted)}
.hint.error{color:var(--danger)}
.results,.tags{margin-top:18px;display:grid;gap:12px}
.card{border:1px solid var(--line);border-radius:8px;background:var(--panel);padding:16px}
.title{font-weight:700;font-size:16px}
.chip{display:inline-block;margin-left:8px;padding:1px 7px;border-radius:10px;background:var(--chip);color:var(--accent);font-size:12px;font-weight:600}
.meta{margin:6px 0;color:var(--muted);font-size:13px}
code,.url{display:block;padding:9px 10px;border-radius:6px;background:var(--bg);border:1px solid var(--line);word-break:break-all;font:13px ui-monospace,SFMono-Regular,Consolas,monospace}
pre{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:14px;overflow:auto;font-size:13px}
.tag-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid var(--line)}
.tag-row code{flex:1;display:block;padding:6px 8px;font-size:12px}
.filter-row{display:flex;gap:10px;margin-bottom:10px}
.filter-row input{flex:1}
</style>
</head>
<body>
<main>
<h1>Docker 镜像加速代理</h1>
<p class="lead">搜索 Docker Hub 镜像，生成走本代理的拉取命令。镜像请求使用你的 Docker Hub 账号令牌转发。</p>

<h2>搜索镜像</h2>
<label for="q">关键词</label>
<input id="q" type="text" placeholder="例如 nginx、redis、alpine" autocomplete="off">
<div class="actions"><button id="search" class="primary" type="button">搜索</button></div>
<p id="hint" class="hint" role="status"></p>
<section id="results" class="results" hidden></section>

<h2>配置方式</h2>
<pre><code>{
  "registry-mirrors": [
    "https://__HOST__"
  ]
}</code></pre>
<p class="muted">改完重启 Docker，之后照常 <code>docker pull nginx</code>；也可直接 <code>docker pull __HOST__/library/nginx:tag</code>。</p>
</main>
<script>
(function(){
"use strict";
var origin=window.location.origin;
var host=window.location.host;
document.body.innerHTML=document.body.innerHTML.split("__HOST__").join(host);
var q=document.getElementById("q");
var hint=document.getElementById("hint");
var results=document.getElementById("results");

function api(path){
 return fetch(path).then(function(r){
  if(!r.ok)throw new Error("HTTP "+r.status);
  return r.json();
 });
}
function el(tag,cls,text){
 var node=document.createElement(tag);
 if(cls)node.className=cls;
 if(text!==undefined)node.textContent=text;
 return node;
}
function button(text,cls,onClick){
 var b=el("button",cls,text);
 b.type="button";
 b.addEventListener("click",onClick);
 return b;
}
function flash(b,text){
 var old=b.textContent;b.textContent=text;
 window.setTimeout(function(){b.textContent=old;},1200);
}
function copy(text,b){
 function fallback(){
  var box=document.createElement("textarea");box.value=text;
  document.body.appendChild(box);box.select();
  try{document.execCommand("copy");flash(b,"已复制");}catch(e){flash(b,"复制失败");}
  document.body.removeChild(box);
 }
 if(navigator.clipboard&&navigator.clipboard.writeText){
  navigator.clipboard.writeText(text).then(function(){flash(b,"已复制");},fallback);
 }else fallback();
}

function renderTags(box,repo,filter,page,size){
 box.innerHTML="";
 var params=new URLSearchParams({repo:repo,page:String(page),page_size:String(size)});
 if(filter)params.set("name",filter);
 api("/tags?"+params.toString()).then(function(data){
  box.innerHTML="";
  var row=el("div","filter-row");
  var input=el("input",null);
  input.type="text";input.value=filter;input.placeholder="按版本名过滤，例如 1.27";
  var go=button("过滤","primary",function(){renderTags(box,repo,input.value.trim(),1,size);});
  row.appendChild(input);row.appendChild(go);box.appendChild(row);

  var list=el("div","tags");
  data.results.forEach(function(tag){
   var line=el("div","tag-row");
   var name=repo+":"+tag.name;
   var cmd="docker pull "+origin+"/"+name;
   line.appendChild(el("code",null,cmd));
   var copyBtn=button("复制",null,function(){copy(cmd,copyBtn);});
   line.appendChild(copyBtn);
   if(tag.digest)line.appendChild(el("span","muted",tag.digest.slice(0,20)+"…"));
   list.appendChild(line);
  });
  if(data.results.length===0)list.appendChild(el("p","muted","没有匹配的版本。"));
  box.appendChild(list);

  var nav=el("div","actions");
  var prev=button("上一页",null,function(){renderTags(box,repo,filter,page-1,size);});
  prev.disabled=page<=1;
  var next=button("下一页",null,function(){renderTags(box,repo,filter,page+1,size);});
  next.disabled=page*size>=data.count;
  nav.appendChild(prev);nav.appendChild(el("span","muted","第 "+page+" 页 / 共 "+Math.max(1,Math.ceil(data.count/size))+" 页"));
  nav.appendChild(next);box.appendChild(nav);
 }).catch(function(err){
  box.innerHTML="";box.appendChild(el("p","hint error","版本加载失败："+err.message));
 });
}

function renderSearch(data){
 results.innerHTML="";
 if(data.results.length===0){results.appendChild(el("p","muted","没有找到匹配的镜像。"));results.hidden=false;return;}
 data.results.forEach(function(item){
  var card=el("div","card");
  var title=el("div","title",item.name);
  if(item.official)title.appendChild(el("span","chip","Official"));
  card.appendChild(title);
  card.appendChild(el("div","meta","收藏 "+item.stars+" · 下载 "+item.pulls+(item.description?" · "+item.description:"")));
  var cmd="docker pull "+origin+"/"+item.name;
  var code=el("code",null,cmd);
  card.appendChild(code);
  card.appendChild(el("p","muted","未指定标签时 Docker 会尝试 latest；若该镜像没有 latest，请查看版本并选择具体标签。"));
  var actions=el("div","actions");
  var copyBtn=button("复制命令",null,function(){copy(cmd,copyBtn);});
  actions.appendChild(copyBtn);
  var tagsBox=el("div","tags");
  var toggle=button("查看版本","primary",function(){
   if(tagsBox.hidden){tagsBox.hidden=false;renderTags(tagsBox,item.name,"",1,20);toggle.textContent="收起版本";}
   else{tagsBox.hidden=true;toggle.textContent="查看版本";}
  });
  actions.appendChild(toggle);
  card.appendChild(actions);
  tagsBox.hidden=true;card.appendChild(tagsBox);
  results.appendChild(card);
 });
 results.hidden=false;
}

function search(){
 hint.className="hint";hint.textContent="";
 var value=q.value.trim();
 if(!value){hint.className="hint error";hint.textContent="请输入关键词。";return;}
 results.hidden=true;
 api("/search?q="+encodeURIComponent(value)+"&page=1&page_size=20").then(function(data){
  hint.textContent="找到 "+data.count+" 个仓库，当前显示前 "+data.results.length+" 个。";
  renderSearch(data);
 }).catch(function(err){
  hint.className="hint error";hint.textContent="搜索失败："+err.message;
 });
}
document.getElementById("search").addEventListener("click",search);
q.addEventListener("keydown",function(e){if(e.key==="Enter")search();});
})();
</script>
</body>
</html>`;
