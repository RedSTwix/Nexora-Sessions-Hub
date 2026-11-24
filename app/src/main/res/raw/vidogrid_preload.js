(function (root) {
  'use strict';
  if (root !== root.top || location.origin !== 'https://vidogrid.ai') return;
  root.__nexoraInstallVidoPreload = function () {
  if(window!==window.top||location.origin!=='https://vidogrid.ai'||!/^\/app\/video-gen(?:\/|$)/.test(location.pathname))return {installed:false};
  if(window.__nexoraVidoPreload)return window.__nexoraVidoPreload.status();
  const api=window.turnstile;
  if(!api||typeof api.render!=='function')return {installed:false};
  const originalRender=api.render,originalRemove=api.remove;
  let current=null,configTemplate=null,stopped=false,created=0,used=0,ready=0,expired=0,cancelledChecks=0,cancelledReason='';
  const capacity=2,prepared=[],events=[],ownedWidgets=new Set();
  const enabled=()=>!stopped&&!!window.__nexoraVidoAssistant?.isActive?.();
  const record=type=>{events.push({type,at:Math.round(performance.now())});if(events.length>80)events.shift();
    root.dispatchEvent(new Event('nexora-vidogrid-change'));};
  function remove(item){
    if(!item||item.removed)return;
    item.removed=true;item.approval=null;
    const position=prepared.indexOf(item);if(position!==-1)prepared.splice(position,1);
    if(current===item)current=null;
    try{if(item.id)Reflect.apply(originalRemove,api,[item.id]);}catch{}
    if(item.panel?.parentElement===item.host&&item.panelParent?.isConnected)
      item.panelParent.insertBefore(item.panel,item.panelNext?.parentNode===item.panelParent?item.panelNext:null);
    if(item.host.open)item.host.close();
    item.host.remove();
  }
  function requestInteraction(item){
    item.needsInteraction=true;record('interaction-required');
  }
  function reveal(item){
    // Keep the provider's frame and its browsing context in the original DOM
    // position. The native dialog changes only presentation, never its parent.
    item.host.style.cssText='display:block;position:fixed;inset:0;margin:auto;width:340px;max-width:calc(100vw - 24px);height:max-content;max-height:calc(100dvh - 24px);overflow:visible;padding:0;border:0;outline:none;box-shadow:none;background:transparent;pointer-events:auto;z-index:2147483647;';
    item.visible=true;item.host.dataset.nexoraQueueRole='current';
    // A modal makes nodes outside it inert. Keep the existing panel usable;
    // only this ordinary UI node moves, never the verification container/frame.
    item.panel=document.getElementById('nexora-vidogrid-assistant');
    if(item.panel){
      item.panelParent=item.panel.parentNode;item.panelNext=item.panel.nextSibling;
      item.host.appendChild(item.panel);
    }
    if(typeof item.host.showModal==='function')item.host.showModal();
  }
  function fail(item,kind,callback,args){
    if(item.removed||item.delivered)return;
    const consumer=item.consumer;
    if(consumer){cancelledChecks++;cancelledReason='A checagem da vez '+kind+'. Retome para iniciar uma nova verificação.';}
    remove(item);
    try{if(consumer&&typeof consumer[callback]==='function')Reflect.apply(consumer[callback],item.receiver,args);}
    finally{if(consumer)closeSiteDialog();}
  }
  function assign(item,config,receiver){
    if(current||!item.host.isConnected)return false;
    item.consumer=config;item.receiver=receiver;current=item;
    prepared.splice(prepared.indexOf(item),1);record('prepared-widget-assigned');
    if(!item.approval)reveal(item);
    queueMicrotask(()=>{deliver(item);prepare();});
    return true;
  }
  function deliver(item){
    if(item.removed||item.delivered||!item.consumer||!item.approval)return;
    if(!enabled()){remove(item);return;}
    if(item.id&&typeof api.isExpired==='function'&&api.isExpired(item.id)){
      expired++;fail(item,'expirou','expired-callback',[]);return;
    }
    const approval=item.approval,callback=item.consumer.callback;
    item.approval=null;item.delivered=true;used++;record('prepared-approval-delivered');
    try{Reflect.apply(callback,item.receiver,approval);}
    finally{remove(item);queueMicrotask(prepare);}
  }
  function prepare(){
    if(!enabled()||!configTemplate)return;
    while(enabled()&&prepared.length<capacity){if(!prepareOne())break;}
  }
  function prepareOne(){
    const host=document.createElement('dialog');
    host.dataset.nexoraPrecheck='true';
    host.dataset.nexoraQueueRole='next';
    host.style.cssText='display:block;position:fixed;left:12px;bottom:12px;width:340px;max-width:calc(100vw - 24px);margin:0;opacity:0;pointer-events:none;z-index:2147483647;';
    const style=document.createElement('style');style.textContent='dialog[data-nexora-precheck]::backdrop{background:transparent}';host.appendChild(style);
    const container=document.createElement('div');host.appendChild(container);document.body.appendChild(host);
    host.setAttribute('aria-label','Verificação da vez');
    const pause=()=>root.__nexoraVidoAssistant?.pause();
    host.addEventListener('cancel',event=>{event.preventDefault();pause();});
    const item={host,id:null,approval:null,consumer:null,receiver:null,delivered:false,removed:false,visible:false,needsInteraction:false};
    prepared.push(item);created++;record('preparation-started');
    // A separate normal widget performs its own verification on this same page.
    // Only a fresh genuine approval is passed once to the next normal site submission.
    const options={...configTemplate,callback:function(...values){
      if(item.removed||item.delivered)return;
      item.approval=values;ready++;record('prepared-approval-ready');
      if(item.consumer)queueMicrotask(()=>deliver(item));
    },'before-interactive-callback':()=>requestInteraction(item),
    'after-interactive-callback':()=>{item.needsInteraction=false;},
    'expired-callback':()=>{if(item.removed)return;expired++;record('prepared-approval-expired');
      fail(item,'expirou','expired-callback',[]);},
    'error-callback':function(...args){if(item.removed)return;record('prepared-verification-error');
      fail(item,'falhou','error-callback',args);},
    'timeout-callback':function(...args){if(item.removed)return;record('prepared-verification-timeout');
      fail(item,'atingiu o tempo permitido pelo site','timeout-callback',args);}};
    item.options=options;
    try{item.id=Reflect.apply(originalRender,api,[container,options]);}
    catch{record('preparation-failed');remove(item);return false;}
    if(item.removed){try{if(item.id)Reflect.apply(originalRemove,api,[item.id]);}catch{}return false;}
    return true;
  }
  const wrappedRender=new Proxy(originalRender,{apply(target,receiver,args){
    const [selector,config]=args;
    if(!enabled()||selector!=='#grok-turnstile-modal'||!config||typeof config.callback!=='function')return Reflect.apply(target,receiver,args);
    configTemplate={...config};
    while(prepared.length){
      const item=prepared[0];
      const sameContext=['sitekey','action','cData','chlPageData'].every(key=>item.options[key]===config[key]);
      if(!item.removed&&item.id&&sameContext&&!(typeof api.isExpired==='function'&&api.isExpired(item.id))
        &&assign(item,config,receiver))return item.id;
      record('invalid-precheck-fallback-to-site');
      remove(item);
    }
    const owned={id:null,selector,cancelled:false};
    ownedWidgets.add(owned);
    const options={...config,callback:function(...values){
      if(owned.cancelled||!enabled())return;
      ownedWidgets.delete(owned);
      const result=Reflect.apply(config.callback,this,values);
      record('normal-approval-delivered');queueMicrotask(prepare);return result;
    }};
    try{owned.id=Reflect.apply(target,receiver,[selector,options,...args.slice(2)]);queueMicrotask(prepare);return owned.id;}
    catch(error){ownedWidgets.delete(owned);throw error;}
  }});
  api.render=wrappedRender;
  if(api.render!==wrappedRender)return {installed:false};
  const wrappedRemove=typeof originalRemove==='function'?new Proxy(originalRemove,{apply(target,receiver,args){
    if(current&&(args[0]===current.id||args[0]==='#grok-turnstile-modal'||args[0]===document.querySelector('#grok-turnstile-modal'))){
      if(!current.delivered){cancelledChecks++;cancelledReason='A verificação do site foi fechada. Retome para tentar novamente.';}
      remove(current);return;
    }
    const result=Reflect.apply(target,receiver,args);
    for(const item of ownedWidgets){
      if(args[0]!==item.id&&args[0]!==item.selector&&args[0]!==document.querySelector(item.selector))continue;
      item.cancelled=true;ownedWidgets.delete(item);cancelledChecks++;cancelledReason='A verificação do site foi fechada. Retome para tentar novamente.';record('site-verification-closed');
    }
    return result;
  }}):null;
  if(wrappedRemove)api.remove=wrappedRemove;
  function closeSiteDialog(){
    const dialog=document.querySelector('#grok-turnstile-modal')?.closest('[role="dialog"]');
    const close=dialog&&[...dialog.querySelectorAll('button')].find(button=>
      /^(close|fechar)$/i.test(button.getAttribute('aria-label')||'')||button.querySelector('[class*="i-lucide-x"]'));
    close?.click();
  }
  function cancelPending(){
    const hadDialog=!!current||ownedWidgets.size>0;
    if(current)remove(current);
    for(const item of [...prepared])remove(item);
    for(const item of ownedWidgets){item.cancelled=true;try{if(item.id)api.remove(item.id);}catch{}}
    ownedWidgets.clear();
    if(hadDialog)closeSiteDialog();
  }
  function sync(){
    if(!enabled())cancelPending();
  }
  const timer=setInterval(sync,500);
  function status(){const next=prepared[0];return {installed:true,enabled:enabled(),created,ready,used,expired,
    queueCapacity:capacity,queued:prepared.length,readyCount:prepared.filter(item=>!!item.approval).length,
    queue:prepared.map(item=>({approvalReady:!!item.approval,needsInteraction:item.needsInteraction})),
    preparing:!!next&&!next.approval,approvalReady:!!next?.approval,assigned:!!current,
    awaitingSiteVerification:ownedWidgets.size>0,
    interactionVisible:!!current?.visible,currentNeedsInteraction:!!current?.needsInteraction,
    needsInteraction:!!next?.needsInteraction,events:[...events]};}
  function restore(){stopped=true;clearInterval(timer);if(api.render===wrappedRender)api.render=originalRender;
    cancelPending();
    if(api.remove===wrappedRemove)api.remove=originalRemove;
    if(window.__nexoraVidoPreload===controller)delete window.__nexoraVidoPreload;
    return {restored:true,status:status()};}
  const controller={status,sync,cancelPending,restore,isSiteVerificationPending:()=>!!current||ownedWidgets.size>0,
    cancelledSiteVerifications:()=>cancelledChecks,cancelledSiteVerificationReason:()=>cancelledReason};
  window.__nexoraVidoPreload=controller;
  return status();
  };
})(globalThis);
