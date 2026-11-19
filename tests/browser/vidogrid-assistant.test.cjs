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
async function open(url='https://vidogrid.ai/app/video-gen/grok',init=''){
 const context=await browser.newContext({viewport:{width:392,height:844}});
 await context.route('**/*',route=>route.fulfill({contentType:'text/html',body:fixture}));
 await context.addInitScript({content:init+'\n'+preload+'\n'+assistant});
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
 const state=new RetryState();state.start(failure(),0);
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
test('unfinished lookahead moves into the site dialog without resetting its iframe',async()=>{
 const {context,page}=await open();try{
  await activate(page);await page.evaluate(()=>window.approve('1'));
  await page.waitForFunction(()=>window.widgets['2'].frame.contentDocument?.body.textContent.includes('Fixture'));
  await page.evaluate(()=>window.widgets['2'].config['before-interactive-callback']());
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().interactionVisible),false);
  await page.evaluate(()=>window.widgets['2'].frame.contentWindow.fixtureMarker=73);
  await page.evaluate(()=>window.finishRequest(503));
  await page.waitForFunction(()=>window.clicks===2);
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().assigned),true);
  assert.equal(await page.evaluate(()=>document.querySelector('#grok-turnstile-modal').contains(widgets['2'].frame)),true);
  assert.equal(await page.evaluate(()=>widgets['2'].frame.contentWindow.fixtureMarker),73);
  await page.evaluate(()=>window.widgets['2'].config['before-interactive-callback']());
  assert.equal(await page.evaluate(()=>window.__nexoraVidoPreload.status().interactionVisible),true);
  await page.evaluate(()=>window.approve('2'));
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
