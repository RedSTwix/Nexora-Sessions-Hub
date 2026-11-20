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
async function open(url='https://vidogrid.ai/app/video-gen/grok',init='',limit=5){
 const context=await browser.newContext({viewport:{width:392,height:844}});
 await context.route('**/*',route=>route.fulfill({contentType:'text/html',body:fixture}));
 await context.addInitScript({content:init+'\n'+preload+'\n'+assistant+'\nwindow.__nexoraSiteAssistant?.setAcceptedLimit('+limit+');'});
 const page=await context.newPage();await page.goto(url);
 return {context,page};
}
async function activate(page){
 assert.equal(await page.evaluate(()=>window.__nexoraSiteAssistant.toggle()),true);
 await page.evaluate(()=>window.__nexoraVidoAssistant.toggle());
 assert.equal(await page.evaluate(()=>window.clicks),1);
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
 for(const extra of [{free:false},{hidden:true},{route:false},{otherError:true,failure503:false}]){
  const other=new RetryState();other.start(failure(extra),0);assert.equal(other.active,false);
 }
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
  assert.equal(await page.evaluate(()=>window.sequence),3);
 }finally{await context.close();}
});
test('unfinished precheck uses the site verification instead of attaching its pending iframe',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.waitForFunction(()=>window.widgets['2'].frame.contentDocument?.body.textContent.includes('Fixture'));
  await page.evaluate(()=>window.widgets['2'].config['before-interactive-callback']());
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'3');
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),true);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().assigned),false);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().awaitingSiteVerification),true);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'checking');
  assert.equal(await page.evaluate(()=>window.panelShadow.querySelector('#preload').textContent),
    'Verificação normal do site em andamento');
  await page.evaluate(()=>window.approve('2'));
  assert.equal(await page.evaluate(()=>window.sent),1);
  await page.waitForTimeout(650);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  await page.evaluate(()=>window.approve('3'));
  assert.equal(await page.evaluate(()=>window.sent),2);
  await page.waitForFunction(()=>!window.__nexoraVidoPreload.status().awaitingSiteVerification);
 }finally{await context.close();}
});
test('pending normal site verification never submits without approval, even with stale 503 visible',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'3');
  await page.waitForTimeout(1000);
  assert.equal(await page.evaluate(()=>window.sent),1);
  assert.equal(await page.evaluate(()=>window.clicks),2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoAssistant.status().phase),'checking');
  await page.evaluate(()=>window.approve('3'));
  assert.equal(await page.evaluate(()=>window.sent),2);
 }finally{await context.close();}
});
test('older browsers fall back to the original widget when an unfinished preparation cannot be moved safely',async()=>{
 const {context,page}=await open(undefined,'Element.prototype.moveBefore=undefined;');try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.evaluate(()=>window.finishRequest(503));await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'3');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),0);
  assert.equal(await page.evaluate(()=>window.widgets['2'].removed),true);
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
test('an expired approval falls back to a fresh original verification',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));await page.evaluate(()=>window.approve('2'));
  await page.evaluate(()=>window.expire('2'));await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.siteWidget),'3');
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().used),0);
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
