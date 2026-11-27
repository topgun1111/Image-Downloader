const fs=require('node:fs');
const assert=require('node:assert/strict');
const html=fs.readFileSync('src/main/assets/index.html','utf8');
assert.doesNotMatch(html,/id="more"|el\('more'\)|autoScroll|pauseAuto/);
assert.doesNotMatch(html,/commons:'Wikimedia|wikimedia:'Wikipedia'/);
assert.match(html,/if\(partial\.rows\)\{for\(const item of partial\.rows\)ingest\(item\)/);
assert.match(html,/for\(const id of provisional\)/);
assert.match(html,/id="shareFloating"/);
assert.match(html,/id="sharePreset"/);
assert.match(html,/api\('prepareShare',\{ids:shareIds,prompt\}/);
assert.match(html,/launch&&result.count===result.requested/);
new Function(html.match(/<script>([\s\S]*?)<\/script>/)[1]);
const start=html.indexOf('function cancelAutoTimer(){');
const end=html.indexOf('const feedWatcher=',start);
assert.ok(start>=0&&end>start);
new Function('assert',`
 let busy=false,hasMore=true,autoPaused=false,searchState={},feedErrors=0,emptyBatches=0,autoTimer=null;
 let calls=0,callback=null,bottom=1800,dialog=false;
 const window={innerHeight:700},document={hidden:false,querySelector:()=>dialog?{}:null};
 const el=()=>({getBoundingClientRect:()=>({top:bottom})});
 const setTimeout=(fn)=>{callback=fn;return 1;},clearTimeout=()=>{callback=null;};
 const loadPage=()=>{calls++;};
 ${html.slice(start,end)}
 function fire(){const f=callback;callback=null;if(f)f();}
 scheduleAuto();assert.ok(callback,'prefetch before bottom');
 scheduleAuto();fire();assert.equal(calls,1,'one queued request');
 bottom=5000;scheduleAuto();assert.equal(callback,null,'no far-away prefetch');
 bottom=100;busy=true;scheduleAuto();assert.equal(callback,null,'no concurrent request');
 busy=false;autoPaused=true;scheduleAuto();assert.equal(callback,null,'stop prevents prefetch');
 autoPaused=false;scheduleAuto();autoPaused=true;fire();assert.equal(calls,1,'stop prevents queued request');
 autoPaused=false;document.hidden=true;scheduleAuto();assert.equal(callback,null,'background guard');
 document.hidden=false;dialog=true;scheduleAuto();assert.equal(callback,null,'dialog guard');
 dialog=false;emptyBatches=4;scheduleAuto();fire();assert.equal(calls,2,'continues after empty pages');
 hasMore=false;scheduleAuto();assert.equal(callback,null,'exhaustion guard');
 hasMore=true;scheduleAuto();cancelAutoTimer();assert.equal(callback,null);assert.equal(autoTimer,null);
`)(assert);
console.log('Infinite-scroll regression checks passed.');
