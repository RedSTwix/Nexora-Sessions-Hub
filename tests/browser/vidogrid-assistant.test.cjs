const {before,after,test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const assistant=fs.readFileSync(path.join(__dirname,'../../app/src/main/res/raw/vidogrid_assistant.js'),'utf8');
const preload=fs.readFileSync(path.join(__dirname,'../../app/src/main/res/raw/vidogrid_preload.js'),'utf8');
const fixture=fs.readFileSync(path.join(__dirname,'fixtures/vidogrid.html'),'utf8');
const {RetryState}=require('../../app/src/main/res/raw/vidogrid_assistant.js');
let browser;
before(async()=>{
 const options={headless:true};
 if(process.env.NEXORA_TEST_BROWSER)options.executablePath=process.env.NEXORA_TEST_BROWSER;
 else if(process.platform==='win32')options.channel='msedge';
 browser=await chromium.launch(options);
});
after(async()=>await browser?.close());
async function open(url='https://vidogrid.ai/app/video-gen/grok',init='',limit=5,device={},queueSize=2){
 const context=await browser.newContext({viewport:{width:392,height:844},...device});
 await context.route('**/*',route=>route.fulfill({contentType:'text/html',body:fixture}));
 await context.addInitScript({content:init+'\n'+preload+'\n'+assistant+'\nwindow.__nexoraSiteAssistant?.setAcceptedLimit('+limit+');\nwindow.__nexoraSiteAssistant?.setQueueSize('+queueSize+');'});
 const page=await context.newPage();await page.goto(url);
 return {context,page};
}
$1
async function waitQueue(page,count=2){
 await page.waitForFunction(count=>window.__nexoraVidoPreload?.status().queued===count,count,{timeout:6000});
}
const failure=(extra={})=>({route:true,hidden:false,challenge:false,processing:false,success:false,enabled:true,free:true,
 errorToken:1,failure503:true,otherError:false,...extra});

test('fresh 503 outcomes retry immediately without a cycle limit, while stale errors never double-submit',()=>{
 const state=new RetryState(0);state.start(failure(),0);
 assert.equal(state.takeAttempt(failure(),0),true);
 assert.equal(state.takeAttempt(failure(),1),false);
 for(let i=1;i<=200;i++){
  assert.equal(state.takeAttempt(failure({challenge:true,errorToken:null,failure503:false}),i),false);
  assert.equal(state.takeAttempt(failure({errorToken:i+1}),i),true);
 }
 assert.equal(state.tries,201);assert.equal(state.active,true);
});
test('a long check has no timeout and a hidden old error never becomes a new outcome',()=>{
 const state=new RetryState();state.start(failure(),0);state.takeAttempt(failure(),0);
 state.observe(failure({challenge:true,errorToken:null,failure503:false}),86400000);
 assert.equal(state.active,true);assert.equal(state.phase,'checking');
 assert.equal(state.takeAttempt(failure(),86400001),false);
 for(const extra of [{free:false},{hidden:true},{route:false}]){
  const other=new RetryState();other.start(failure(extra),0);assert.equal(other.active,false);
 }
});
test('explicit activation or resume can retry an old failure once without repeating a newly returned failure',()=>{
 const state=new RetryState(),failed=failure({otherError:true,failure503:false});
 state.start(failed,0);assert.equal(state.takeAttempt(failed,0),true);
 assert.equal(state.takeAttempt(failed,1),false);
 state.observe(failure({processing:true,errorToken:null,failure503:false,otherError:false}),2);
 state.observe(failure({errorToken:2,otherError:true,failure503:false}),3);
 assert.equal(state.active,false);
 const latest=failure({errorToken:2,otherError:true,failure503:false});
 state.resume(latest,4);assert.equal(state.takeAttempt(latest,4),true);
 assert.equal(state.takeAttempt(latest,5),false);
 const paid=new RetryState();paid.start({...failed,free:false},0);
 assert.equal(paid.active,false);
});
test('an unchanged old 503 never triggers an unconfirmed retry and eventually pauses safely',()=>{
 const state=new RetryState(0);
 state.start(failure(),0);assert.equal(state.takeAttempt(failure(),0),true);
 state.observe(failure(),89999);
 assert.equal(state.active,true);assert.equal(state.phase,'waiting');
 assert.equal(state.takeAttempt(failure(),90000),false);
 assert.equal(state.active,false);assert.equal(state.phase,'paused');
 assert.match(state.reason,/sem confirmação/);
 assert.equal(state.tries,1);
});
test('pending requests suppress stale error rewrites; only a new completed 503 confirms a retry',()=>{
 const state=new RetryState(0);
 state.start(failure({request:{id:1,pending:false,status:503}}),0);
 assert.equal(state.takeAttempt(failure({request:{id:1,pending:false,status:503}}),0),true);
 assert.equal(state.takeAttempt(failure({errorToken:2,request:{id:2,pending:true,status:null}}),100000),false);
 assert.equal(state.active,true);
 assert.equal(state.takeAttempt(failure({request:{id:2,pending:false,status:503}}),100001),true);
 assert.equal(state.takeAttempt(failure({request:{id:2,pending:false,status:503}}),100002),false);
 assert.equal(state.takeAttempt(failure({request:{id:3,pending:true,status:null}}),100003),false);
});
test('resuming joins a pending request instead of treating its old error as a finished attempt',()=>{
 const state=new RetryState(0), pending=failure({request:{id:1,pending:true,status:null}});
 state.start(pending,0);assert.equal(state.takeAttempt(pending,0),false);
 state.stop('Pausa');state.resume(pending,10);assert.equal(state.takeAttempt(pending,10),false);
 assert.equal(state.takeAttempt(failure({request:{id:1,pending:false,status:503}}),11),true);
});
test('reused error DOM nodes can report a new 503 without an observable intermediate state',async()=>{
 const {context,page}=await open();try{
  // Use the ordinary site verification so the test isolates result tracking.
  await page.evaluate(()=>{window.__nexoraInstallVidoPreload=()=>({installed:false});});
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  const before=await page.evaluate(()=>window.__nexoraVidoAssistant.status());
  assert.equal(before.tries,2);
  // Neither a timer nor an unrelated DOM change may treat the old 503 as fresh.
  await page.evaluate(()=>document.querySelector('aside').setAttribute('data-refresh','1'));
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  await page.evaluate(()=>{
    const card=document.querySelector('#card');
    const oldError=card.firstElementChild;
    const status=oldError.querySelectorAll('p')[1];
    window.approve('2');
    // The website has completed the second request but restores the same old nodes
    // and the same status text in the very same JavaScript task.
    card.replaceChildren(oldError);
    status.textContent='Status 503';
    document.querySelector('#generate').disabled=false;
  });
  await page.waitForFunction(()=>window.clicks===3);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().tries),3);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),3);
 }finally{await context.close();}
});
for(const transport of ['xhr','fetch'])test(`${transport}: a confirmed 503 retries with the next check ready even when the error DOM never changes`,async()=>{
 const apiBase='https://api.fixture.test'+(transport==='xhr'?'/api/v2':'');
 const {context,page}=await open(undefined,`window.__NUXT__={config:{public:{api:{imagenproService:${JSON.stringify(apiBase)}}}}};`);
 const requests=[];
 await context.route('https://api.fixture.test/**',route=>{requests.push(route);});
 try{
  await page.evaluate(({transport,apiBase})=>{
   window.originalTransports=[fetch,XMLHttpRequest.prototype.open,XMLHttpRequest.prototype.send,turnstile.remove];
   window.retainFailureWhenSubmitting=true;
   window.onGenerationRequest=()=>{
    const url=apiBase+(transport==='xhr'?'/video-gen/grok-imagine-video-1.5-lite':'/video-gen/grok-stream');
    const done=()=>{document.querySelector('#generate').disabled=false;};
    if(transport==='fetch')fetch(url,{method:'POST',body:'fixture'}).then(done);
    else{const xhr=new XMLHttpRequest();xhr.open('POST',url);xhr.addEventListener('load',done);xhr.send('fixture');}
   };
  },{transport,apiBase});
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.approve('2'));
  await page.waitForFunction(()=>window.panelShadow.querySelector('#preload').textContent.startsWith('Próxima checagem pronta'));
  assert.equal(requests.length,1);
  assert.equal(requests[0].request().method(),'POST');
  assert.equal(requests[0].request().postData(),'fixture');
  await page.waitForTimeout(600);
  assert.equal(await page.evaluate(()=>window.sent),1);
  await requests[0].fulfill({status:503,headers:{'access-control-allow-origin':'*'},body:'busy'});
  await page.waitForFunction(()=>window.sent===2,null,{timeout:3000});
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.sent),2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),1);
  assert.equal(requests.length,2);
  assert.deepEqual(await page.evaluate(()=>{
   window.__nexoraSiteAssistant.hide();
   return [fetch,XMLHttpRequest.prototype.open,XMLHttpRequest.prototype.send,turnstile.remove]
    .map((method,index)=>method===window.originalTransports[index]);
  }),[true,true,true,true]);
  await requests[1].fulfill({status:503,headers:{'access-control-allow-origin':'*'},body:'busy'});
  await page.waitForTimeout(600);
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('unrelated requests, successful responses and network errors cannot confirm a stale 503',async()=>{
 const {context,page}=await open(undefined,`window.__NUXT__={config:{public:{api:{imagenproService:'https://api.fixture.test'}}}};`);
 try{
  await context.route('https://api.fixture.test/**',route=>route.request().url().includes('network-error')
   ?route.abort():route.fulfill({status:route.request().url().includes('success')?200:503,
    headers:{'access-control-allow-origin':'*'},body:'fixture'}));
  await context.route('https://other.fixture.test/**',route=>route.fulfill({status:503,
   headers:{'access-control-allow-origin':'*'},body:'fixture'}));
  await page.evaluate(()=>{window.retainFailureWhenSubmitting=true;});
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(async()=>{
   const cases=[
    ['https://api.fixture.test/video-gen/grok-stream','GET'],
    ['https://api.fixture.test/account','POST'],
    ['https://other.fixture.test/video-gen/grok-stream','POST'],
    ['https://api.fixture.test/video-gen/grok-stream?success','POST'],
    ['https://api.fixture.test/video-gen/grok-stream?network-error','POST']
   ];
   for(const [url,method] of cases){
    await fetch(url,{method}).catch(()=>{});
    await new Promise(resolve=>{const xhr=new XMLHttpRequest();xhr.open(method,url);
     xhr.addEventListener('loadend',resolve);xhr.send();});
   }
   document.querySelector('#generate').disabled=false;
  });
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().active),true);
 }finally{await context.close();}
});
test('closing the site verification stops the cycle instead of leaving a permanent pending check',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.locator('#close-site-dialog').click();
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant.status().active,null,{timeout:3000});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().awaitingSiteVerification),false);
  assert.match(await page.evaluate(()=>window.__nexoraVidoAssistant.status().reason),/verificação.*fechada/);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  await page.evaluate(()=>window.approve('1'));
  assert.equal(await page.evaluate(()=>window.sent),0);
 }finally{await context.close();}
});
test('the icon opens a paused panel; repeated injections and reload never start it',async()=>{
 const {context,page}=await open();try{
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  assert.equal(await page.evaluate(()=>window.sequence),0);
  await page.evaluate(()=>window.__nexoraSiteAssistant.toggle());
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().active),false);
  await page.evaluate(preload+'\n'+assistant);
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),1);
  assert.equal(await page.evaluate(()=>window.clicks),0);
  await page.reload();
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
 }finally{await context.close();}
});
for(const touch of [false,true])test(`${touch?'touch':'mouse'}: only an intentional control click changes repetition; panel space never leaks clicks to the site`,async()=>{
 const {context,page}=await open(undefined,'',5,touch?{hasTouch:true,isMobile:true}:{});try{
  await page.evaluate(()=>{
   window.outsidePanelClicks=0;
   document.addEventListener('click',event=>{
    if(event.composedPath().some(node=>node.id==='nexora-vidogrid-assistant'))window.outsidePanelClicks++;
   });
   window.__nexoraSiteAssistant.toggle();
  });
  const click=async selector=>{
   const point=await page.evaluate(selector=>{
    const rect=window.panelShadow.querySelector(selector).getBoundingClientRect();
    return {x:rect.x+rect.width/2,y:rect.y+rect.height/2};
   },selector);
   if(touch)await page.touchscreen.tap(point.x,point.y);else await page.mouse.click(point.x,point.y);
  };
  for(const selector of ['header span','#title','#detail','#preload'])await click(selector);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),false);
  assert.equal(await page.evaluate(()=>window.clicks),0);
  await click('#toggle');assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),true);
  for(const selector of ['header span','#title','#detail','#preload'])await click(selector);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),true);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  await click('#toggle');assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),false);
  assert.equal(await page.evaluate(()=>window.outsidePanelClicks),0);
  if(!touch){
   await page.evaluate(()=>window.panelShadow.querySelector('#toggle').focus());
   await page.keyboard.press('Space');
   assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),true);
  }
 }finally{await context.close();}
});
async function animateUnrelatedCaption(page){
 return page.evaluate(async()=>{
  let queries=0;const original=document.querySelectorAll;
  document.querySelectorAll=function(...args){queries++;return Reflect.apply(original,this,args);};
  const caption=document.createElement('span');document.body.appendChild(caption);
  try{for(let i=0;i<30;i++)await new Promise(resolve=>requestAnimationFrame(()=>{caption.textContent=String(i);resolve();}));}
  finally{caption.remove();document.querySelectorAll=original;}
  return queries;
 });
}
test('unrelated animated captions do not repeatedly scan the document while paused or checking',async()=>{
 for(const active of [false,true]){
  const {context,page}=await open();try{
   if(active)await activate(page);else await page.evaluate(()=>window.__nexoraSiteAssistant.toggle());
   const queries=await animateUnrelatedCaption(page);
   assert.ok(queries<30,`${active?'checking':'paused'}: ${queries} document scans for 30 animation frames`);
   assert.equal(await page.evaluate(()=>window.clicks),active?1:0);
  }finally{await context.close();}
 }
});
test('reading queue status does not rescan the page to check whether repetition is active',async()=>{
 const {context,page}=await open();try{
  await activate(page);
  const queries=await page.evaluate(()=>{
   let queries=0;const original=document.querySelectorAll;
   document.querySelectorAll=function(...args){queries++;return Reflect.apply(original,this,args);};
   try{for(let i=0;i<100;i++)window.__nexoraVidoPreload.status();}
   finally{document.querySelectorAll=original;}
   return queries;
  });
  assert.equal(queries,0);
 }finally{await context.close();}
});
test('replacing the site button and output refreshes cached elements before the next attempt',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>{
   const button=document.querySelector('#generate'),replacement=button.cloneNode(true);
   replacement.onclick=button.onclick;button.replaceWith(replacement);
   const output=document.querySelector('#output');output.replaceWith(output.cloneNode(true));
   window.finishRequest(503);
  });
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('ready lookahead is held during A and used once after a normal Queued outcome',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().preparing),true);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().approvalReady),true);
  await page.evaluate(()=>window.finishRequest(202));
  await page.waitForFunction(()=>window.sent===2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),1);
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),true);
  await waitQueue(page,2);
  assert.equal(await page.evaluate(()=>window.sequence),4);
 }finally{await context.close();}
});
test('a pending queue entry becomes current without reloading its frame and starts the next entry',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.waitForFunction(()=>window.widgets['2'].frame.contentDocument?.body.textContent.includes('Fixture'));
  await page.evaluate(()=>{
   window.queueWindow=window.widgets['2'].frame.contentWindow;
   window.queueParent=window.widgets['2'].frame.parentElement;
   window.queueWindow.progressMarker='verification-in-progress';
  });
  await page.evaluate(()=>window.widgets['2'].config['before-interactive-callback']());
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),false);
  assert.equal(await page.evaluate(()=>window.widgets['2'].frame.contentWindow===window.queueWindow
   &&window.queueWindow.progressMarker==='verification-in-progress'
   &&window.widgets['2'].frame.parentElement===window.queueParent),true);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().assigned),true);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queued),0);
  await page.waitForFunction(()=>window.__nexoraVidoAssistant.status().phase==='checking');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().interactionVisible),true);
  assert.equal(await page.evaluate(()=>window.sequence),2);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
  await waitQueue(page,1);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.sent===3);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),2);
  await waitQueue(page,2);
 }finally{await context.close();}
});
test('a pending promoted verification never submits without approval, even with stale 503 visible',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  await page.waitForTimeout(1000);
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'checking');
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('queue promotion preserves the pending widget even without the moveBefore browser API',async()=>{
 const {context,page}=await open(undefined,'Element.prototype.moveBefore=undefined;');try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),0);
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),false);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('queue zero disables anticipation but normal verification and repetition remain available',async()=>{
 const {context,page}=await open(undefined,'',5,{},0);try{
  await activate(page);
  assert.equal(await page.evaluate(()=>window.sequence),1);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queueCapacity),0);
  await page.waitForFunction(()=>window.panelShadow.querySelector('#preload').textContent.includes('Antecipação desligada'));
  await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  assert.equal(await page.evaluate(()=>window.sequence),2);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});

test('a saved queue size is applied on activation and survives panel reopening independently of accepted limits',async()=>{
 const {context,page}=await open(undefined,'',7,{},4);try{
  await activate(page);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().acceptedLimit),7);
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),0);
  await page.evaluate(()=>window.approve(window.siteWidget));
  await waitQueue(page,4);
  for(const value of [-1,11,1.5,'3'])assert.equal(await page.evaluate(value=>window.__nexoraSiteAssistant.setQueueSize(value),value),false);
  assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.queueSize()),4);
  await page.evaluate(()=>window.__nexoraSiteAssistant.hide());
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  await page.evaluate(()=>{window.__nexoraSiteAssistant.toggle();window.__nexoraVidoAssistant.toggle();});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queueCapacity),4);
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),0);
  await page.evaluate(()=>window.approve(window.siteWidget));
  await waitQueue(page,4);
  await page.reload();
  assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.queueSize()),4);
  assert.equal(await page.evaluate(()=>window.clicks),0);
 }finally{await context.close();}
});

test('resizing preserves the head, drops only the newest waiting checks and does not cancel the current check',async()=>{
 const {context,page}=await open();try{
  await activate(page);
  await page.evaluate(()=>window.__nexoraSiteAssistant.setQueueSize(4));
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),0);
  await page.evaluate(()=>window.approve('1'));
  await waitQueue(page,4);
  assert.equal(await page.evaluate(()=>window.sequence),5);
  await page.evaluate(()=>window.__nexoraSiteAssistant.setQueueSize(1));
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),false);
  assert.equal(await page.evaluate(()=>['3','4','5'].every(id=>window.widgets[id].removed)),true);
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),1);
  assert.equal(await page.evaluate(()=>window.widgets['1'].removed),false);
  await page.evaluate(()=>{window.approve('3');window.approve('4');window.approve('5');});
  assert.equal(await page.evaluate(()=>window.sent),0);
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),0);
  await page.evaluate(()=>window.__nexoraSiteAssistant.setQueueSize(0));
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),false);
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),0);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});

test('the initial verification takes priority; later checks join the queue gradually',async()=>{
 const {context,page}=await open();try{
  await activate(page);
  assert.equal(await page.evaluate(()=>window.sequence),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queued),0);
  await page.waitForTimeout(700);
  assert.equal(await page.evaluate(()=>window.sequence),1);
  assert.match(await page.evaluate(()=>window.panelShadow.querySelector('#preload').textContent),/primeira checagem tem prioridade/);
  await page.evaluate(()=>window.approve('1'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queued),1);
  assert.equal(await page.evaluate(()=>window.sequence),2);
  await waitQueue(page,2);
  assert.equal(await page.evaluate(()=>window.sequence),3);
  assert.equal(await page.evaluate(()=>getComputedStyle(document.querySelector('[data-nexora-queue-role="next"]')).opacity),'0');
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.sent===2);
 }finally{await context.close();}
});
for(const failure of ['expired-callback','error-callback','timeout-callback'])test(`${failure}: a failed current verification pauses and discards the current and both waiting entries`,async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await waitQueue(page,2);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.__nexoraVidoPreload.status().assigned);
  await page.evaluate(name=>window.widgets['2'].config[name](),failure);
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant.status().active);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  assert.equal(await page.evaluate(()=>['2','3'].every(id=>window.widgets[id].removed)),true);
  await page.evaluate(()=>{window.approve('2');window.approve('3');});
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.match(await page.evaluate(()=>window.__nexoraVidoAssistant.status().reason),/checagem da vez/);
 }finally{await context.close();}
});
for(const action of ['pause','close','navigate'])test(`${action}: existing panel controls or navigation cancel all three checks while the promoted widget is visible`,async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await waitQueue(page,2);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.__nexoraVidoPreload.status().assigned);
  assert.equal(await page.locator('[data-nexora-queue-role="current"] button').count(),0);
  if(action==='navigate')await page.evaluate(()=>{history.pushState({},'','/history');document.body.appendChild(document.createElement('div'));});
  else{
   const button=await page.evaluateHandle(action=>window.panelShadow.querySelector(action==='pause'?'#toggle':'#close'),action);
   // A real pointer click must reach the existing panel even with a modal open.
   await button.asElement().click();
  }
  await page.waitForFunction(()=>document.querySelectorAll('[data-nexora-precheck]').length===0);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant?.status().active??false),false);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  assert.equal(await page.evaluate(()=>document.querySelector('#modal').hidden),true);
  assert.equal(await page.evaluate(()=>['2','3'].every(id=>window.widgets[id].removed)),true);
  await page.evaluate(()=>{window.approve('2');window.approve('3');});
  assert.equal(await page.evaluate(()=>window.sent),1);
  if(action==='pause')assert.equal(await page.evaluate(()=>document.getElementById('nexora-vidogrid-assistant').parentElement===document.documentElement),true);
 }finally{await context.close();}
});
test('closing with the icon cancels an original check and later callbacks cannot send',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.__nexoraSiteAssistant.toggle());
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  assert.equal(await page.evaluate(()=>window.turnstile.render===window.originalRender),true);
  await page.evaluate(()=>window.approve('1'));
  assert.equal(await page.evaluate(()=>window.sent),0);
  assert.equal(await page.evaluate(()=>document.querySelector('#modal').hidden),true);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant),undefined);
 }finally{await context.close();}
});
test('panel X discards an assigned check and completely closes the integration',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  await page.evaluate(()=>window.panelShadow.querySelector('#close').click());
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.visible()),false);
 }finally{await context.close();}
});
test('pausing and editing the form discard unused approval without hiding the panel',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));await page.evaluate(()=>window.approve('2'));
  await page.locator('textarea').fill('Outro prompt');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().active),false);
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),true);
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),1);
  await page.evaluate(()=>window.finishRequest(503));
  assert.equal(await page.evaluate(()=>window.clicks),1);
 }finally{await context.close();}
});
test('an expired head is discarded and the second queued verification keeps its place',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));await waitQueue(page,2);await page.evaluate(()=>window.approve('2'));
  await page.evaluate(()=>window.expire('2'));await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'3');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),0);
  assert.equal(await page.evaluate(()=>window.widgets['3'].removed),false);
 }finally{await context.close();}
});

test('a ready second entry cannot overtake the first and both positions are replenished in FIFO order',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await waitQueue(page,2);
  await page.waitForFunction(()=>window.widgets['3'].frame.contentDocument?.body.textContent.includes('Fixture'));
  await page.evaluate(()=>{window.widgets['3'].frame.contentWindow.queueMarker='second';window.approve('3');});
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.deepEqual(await page.evaluate(()=>{const state=window.__nexoraVidoPreload.status();
   return [state.queued,state.readyCount,state.approvalReady];}),[2,1,false]);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.__nexoraVidoPreload.status().assigned);
  assert.equal(await page.evaluate(()=>window.siteWidget),'2');
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.locator('[data-nexora-queue-role="current"]').count(),1);
  assert.equal(await page.locator('[data-nexora-queue-role="next"]').count(),1);
  assert.equal(await page.evaluate(()=>window.widgets['3'].frame.contentWindow.queueMarker),'second');
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),2);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.sent===3);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),2);
  assert.equal(await page.evaluate(()=>window.widgets['3'].removed),true);
  await waitQueue(page,2);
  await page.evaluate(()=>{window.approve('2');window.approve('3');});
  assert.equal(await page.evaluate(()=>window.sent),3);
  await page.waitForFunction(()=>window.panelShadow.querySelector('#preload').textContent.includes('Fila: 2/2 antecipadas'));
  assert.match(await page.evaluate(()=>window.panelShadow.querySelector('#preload').textContent),/Fila: 2\/2 antecipadas/);
 }finally{await context.close();}
});

test('two ready entries stay held until separate outcomes and are each consumed only once',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));await waitQueue(page,2);
  await page.evaluate(()=>{window.approve('2');window.approve('3');});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().readyCount),2);
  assert.equal(await page.evaluate(()=>window.sent),1);
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.sent===2);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.sent),2);
  assert.equal(await page.evaluate(()=>window.widgets['3'].removed),false);
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.sent===3);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),2);
  await page.evaluate(()=>{window.approve('2');window.approve('3');});
  assert.equal(await page.evaluate(()=>window.sent),3);
 }finally{await context.close();}
});

test('when both waiting entries expire the next request uses a fresh original verification',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await waitQueue(page,2);
  await page.evaluate(()=>{window.expire('2');window.expire('3');});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().queued),0);
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'4');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),0);
  assert.equal(await page.evaluate(()=>window.sent),1);
  await page.evaluate(()=>window.approve('4'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('opening on a fresh form sends only after explicit activation',async()=>{
 const {context,page}=await open();try{
  await page.evaluate(()=>document.querySelector('#card').replaceChildren());
  await page.evaluate(()=>window.__nexoraSiteAssistant.toggle());
  assert.equal(await page.evaluate(()=>window.clicks),0);
  await page.evaluate(()=>window.__nexoraVidoAssistant.toggle());
  assert.equal(await page.evaluate(()=>window.clicks),1);
 }finally{await context.close();}
});
test('a closed panel cannot be opened from a frame or restarted by stale events',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.__nexoraSiteAssistant.hide());
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForTimeout(600);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  const frame=await page.evaluateHandle(()=>{const element=document.createElement('iframe');document.body.appendChild(element);return element;});
  const child=await frame.asElement().contentFrame();
  await child.evaluate(preload+'\n'+assistant);
  assert.equal(await child.evaluate(()=>typeof window.__nexoraSiteAssistant),'undefined');
 }finally{await context.close();}
});
test('two accepted jobs with the same displayed second are distinguished after the new request clears the result',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));await page.evaluate(()=>window.approve('2'));
  await page.evaluate(()=>window.finishRequest(202));await page.waitForFunction(()=>window.sent===2);
  await page.evaluate(()=>window.approve('3'));await page.evaluate(()=>window.finishRequest(202));
  await page.waitForFunction(()=>window.sent===3);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),2);
 }finally{await context.close();}
});

async function installSiteJobState(page){
 await page.evaluate(()=>{
  const container=document.createElement('div');container.id='__nuxt';document.body.appendChild(container);
  window.fixtureJobStore={textToVideoResult:null};
  container.__vue_app__={config:{globalProperties:{$pinia:{_s:new Map([['textToVideoStore',window.fixtureJobStore]])}}}};
  window.onGenerationRequest=()=>{window.fixtureJobStore.textToVideoResult={status:1};};
 });
}
test('Rendering without Created At is counted only after the site has a confirmed job identity',async()=>{
 const {context,page}=await open(undefined,'',1);try{
  await installSiteJobState(page);await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>{
   document.querySelector('#card').innerHTML='<span>Rendering</span><div>89% · Creating video</div><dl><dt>Model</dt><dd>Grok</dd><dt>Resolution</dt><dd>1080p</dd></dl>';
   document.querySelector('#generate').disabled=false;
  });
  // The website creates a status=1 placeholder before the server accepts it.
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  await page.evaluate(()=>{window.fixtureJobStore.textToVideoResult={uuid:'fixture-confirmed-job',status:1,created_at:'fixture-created'};});
  await page.waitForFunction(()=>window.__nexoraVidoAssistant.status().accepted===1,null,{timeout:3000});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'limit');
  assert.equal(await page.evaluate(()=>window.clicks),1);
 }finally{await context.close();}
});
test('the same server job is counted once as Rendering, Queued and metadata change',async()=>{
 const {context,page}=await open(undefined,'',2);try{
  await installSiteJobState(page);await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>{
   window.fixtureJobStore.textToVideoResult={uuid:'fixture-job-A',status:1};
   document.querySelector('#card').innerHTML='<span>Rendering</span><div>Creating video</div>';
  });
  await page.waitForFunction(()=>window.__nexoraVidoAssistant.status().accepted===1,null,{timeout:3000});
  await page.evaluate(()=>{
   window.fixtureJobStore.textToVideoResult={uuid:'fixture-job-A',status:0,queued:true,created_at:'later-metadata'};
   document.querySelector('#card').innerHTML='<span>Queued</span><dl><dt>Created At</dt><dd>later-metadata</dd></dl>';
  });
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),1);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  await page.evaluate(()=>{document.querySelector('#generate').disabled=false;});
  await page.waitForFunction(()=>window.clicks===2);
  await page.evaluate(()=>window.approve('2'));
  await page.evaluate(()=>{
   window.fixtureJobStore.textToVideoResult={uuid:'fixture-job-B',status:1};
   document.querySelector('#card').innerHTML='<span>Rendering</span><div>Creating video</div>';
   document.querySelector('#generate').disabled=false;
  });
  await page.waitForFunction(()=>window.__nexoraVidoAssistant.status().accepted===2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'limit');
  assert.equal(await page.evaluate(()=>window.clicks),2);
 }finally{await context.close();}
});
test('existing jobs, stale displayed metadata and a failed server job do not become new acceptances',async()=>{
 const {context,page}=await open(undefined,'',1);try{
  await installSiteJobState(page);
  await page.evaluate(()=>{
   window.fixtureJobStore.textToVideoResult={uuid:'existing-job',status:1};
   document.querySelector('#card').innerHTML='<span>Rendering</span><div>Creating video</div>';
  });
  await activate(page);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
  await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>{
   document.querySelector('#card').innerHTML='<span>Rendering</span><dl><dt>Created At</dt><dd>stale-metadata</dd></dl>';
   document.querySelector('#generate').disabled=false;
  });
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
  await page.evaluate(()=>{window.fixtureJobStore.textToVideoResult={uuid:'failed-job',status:-2};});
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
  assert.equal(await page.evaluate(()=>window.clicks),1);
 }finally{await context.close();}
});
test('a newly returned non-503 failure pauses instead of consuming the next prepared check',async()=>{
 const {context,page}=await open();try{
  await installSiteJobState(page);await activate(page);
  await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.approve('2'));
  await page.evaluate(()=>{
   window.fixtureJobStore.textToVideoResult={uuid:'rejected-job',status:-2};
   document.querySelector('#generate').disabled=false;
   document.querySelector('#card').innerHTML='<div><p>Generation failed</p><p>The content violates moderation policies and cannot be processed. Please try a different idea</p></div>';
  });
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant.isActive());
  const state=await page.evaluate(()=>window.__nexoraVidoAssistant.status());
  assert.equal(state.accepted,0);assert.equal(state.phase,'paused');assert.match(state.reason,/falha diferente de 503/);
  assert.equal(await page.evaluate(()=>window.clicks),1);
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),true);
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),1);
 }finally{await context.close();}
});
test('Resume starts a new normal attempt even while the previous failed video is still displayed',async()=>{
 const {context,page}=await open(undefined,'',1);try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(400));
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant.isActive());
  assert.equal(await page.evaluate(()=>window.clicks),1);
  assert.equal(await page.evaluate(()=>window.panelShadow.querySelector('#toggle').textContent),'Retomar repetição');
  await page.evaluate(()=>window.panelShadow.querySelector('#toggle').click());
  assert.equal(await page.evaluate(()=>window.clicks),2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.isActive()),true);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  await page.evaluate(()=>{window.approve(window.siteWidget);window.finishRequest(202);});
  await page.waitForFunction(()=>window.__nexoraVidoAssistant.status().accepted===1);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'limit');
 }finally{await context.close();}
});
test('a new non-503 response reusing the same error nodes is not mistaken for the previous failure',async()=>{
 const {context,page}=await open();try{
  await page.evaluate(()=>window.finishRequest(400));await activate(page);
  await page.evaluate(()=>{
   const card=document.querySelector('#card'),previous=card.firstElementChild;
   window.approve('1');card.replaceChildren(previous);
   previous.querySelectorAll('p')[1].textContent='Status 400';
   document.querySelector('#generate').disabled=false;
  });
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant.isActive());
  assert.equal(await page.evaluate(()=>window.clicks),1);
  assert.match(await page.evaluate(()=>window.__nexoraVidoAssistant.status().reason),/falha diferente de 503/);
 }finally{await context.close();}
});
test('unsupported sites and routes never open the panel; changing route closes it',async()=>{
 for(const url of ['https://example.test/app/video-gen/grok','https://vidogrid.ai/history','https://vidogrid.ai/app/video-gen/veo']){
  const {context,page}=await open(url);try{
   assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant?.toggle()??false),false);
   assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  }finally{await context.close();}
 }
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>{history.pushState({},'','/history');document.querySelector('#card').textContent='History';});
  await page.waitForFunction(()=>!window.__nexoraVidoAssistant);
  assert.equal(await page.locator('#nexora-vidogrid-assistant').count(),0);
  assert.equal(await page.evaluate(()=>window.turnstile.render===window.originalRender),true);
 }finally{await context.close();}
});

async function acceptCurrent(page,number){
 await page.evaluate(()=>window.approve(window.siteWidget));
 await page.waitForFunction(n=>window.sent===n,number);
 await page.evaluate(n=>window.finishRequest(202,'fixture-accepted-'+n),number);
 await page.waitForFunction(n=>window.__nexoraVidoAssistant.status().accepted===n,number);
}

test('the default fifth acceptance stops without a sixth submission and discards the prepared check',async()=>{
 const {context,page}=await open();try{
  await activate(page);
  for(let i=1;i<=5;i++)await acceptCurrent(page,i);
  const state=await page.evaluate(()=>window.__nexoraVidoAssistant.status());
  assert.equal(state.acceptedLimit,5);assert.equal(state.phase,'limit');assert.equal(state.active,false);
  assert.equal(await page.evaluate(()=>window.clicks),5);assert.equal(await page.evaluate(()=>window.sent),5);
  assert.equal(await page.locator('[data-nexora-precheck]').count(),0);
  assert.equal(await page.evaluate(()=>window.panelShadow.querySelector('#toggle').textContent),'Iniciar novo ciclo');
 }finally{await context.close();}
});
test('zero permits more than five acceptances without stopping',async()=>{
 const {context,page}=await open(undefined,'',0);try{
  await activate(page);for(let i=1;i<=8;i++)await acceptCurrent(page,i);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().active),true);
  assert.equal(await page.evaluate(()=>window.clicks),9);
 }finally{await context.close();}
});
test('existing results are excluded from a new cycle and pause/resume preserves its accepted count',async()=>{
 const {context,page}=await open(undefined,'',2);try{
  await page.evaluate(()=>window.finishRequest(202,'older-result'));
  await activate(page);assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
  await acceptCurrent(page,1);
  await page.evaluate(()=>window.__nexoraVidoAssistant.toggle());
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),1);
  await page.evaluate(()=>window.__nexoraVidoAssistant.toggle());
  await acceptCurrent(page,2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'limit');
  assert.equal(await page.evaluate(()=>window.sent),2);
  await page.evaluate(()=>window.__nexoraVidoAssistant.toggle());
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().accepted),0);
 }finally{await context.close();}
});
test('the gear pauses and sends only the settings command; a saved value survives panel close/reopen',async()=>{
 const {context,page}=await open(undefined,'window.commands=[];window.__nexoraSiteSettings={postMessage(value){commands.push(value)}};');try{
  await activate(page);await page.evaluate(()=>window.panelShadow.querySelector('#settings').click());
  assert.deepEqual(await page.evaluate(()=>window.commands),['settings']);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().active),false);
  assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.setAcceptedLimit(3)),true);
  await page.evaluate(()=>{window.__nexoraSiteAssistant.hide();window.__nexoraSiteAssistant.toggle();});
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().acceptedLimit),3);
  assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.setAcceptedLimit(-1)),false);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().acceptedLimit),3);
 }finally{await context.close();}
});

test('lowering the limit stops immediately; increasing a reached limit permits continuing that cycle',()=>{
 const state=new RetryState(5);state.runStarted=true;state.active=true;state.accepted=3;
 state.setLimit(2);assert.equal(state.active,false);assert.equal(state.phase,'limit');
 state.setLimit(4);assert.equal(state.phase,'paused');assert.equal(state.accepted,3);
 assert.equal(state.setLimit(-1),false);assert.equal(state.limit,4);
});
