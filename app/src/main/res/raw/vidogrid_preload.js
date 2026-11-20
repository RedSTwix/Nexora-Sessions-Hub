(function (root) {
  'use strict';
  if (root !== root.top || location.origin !== 'https://vidogrid.ai') return;
  root.__nexoraInstallVidoPreload = function () {
  if(window!==window.top||location.origin!=='https://vidogrid.ai'||!/^\/app\/video-gen(?:\/|$)/.test(location.pathname))return {installed:false};
  if(window.__nexoraVidoPreload)return window.__nexoraVidoPreload.status();
  const api=window.turnstile;
  if(!api||typeof api.render!=='function')return {installed:false};
  const originalRender=api.render;
  let prepared=null,configTemplate=null,stopped=false,created=0,used=0,ready=0,expired=0;
  const events=[],ownedWidgets=new Set();
  const enabled=()=>!stopped&&!!window.__nexoraVidoAssistant?.status().active;
  const record=type=>{events.push({type,at:Math.round(performance.now())});if(events.length>80)events.shift();};
  function remove(item){
    if(!item||item.removed)return;
    item.removed=true;item.approval=null;
    try{if(item.id)api.remove(item.id);}catch{}
    item.host.remove();if(prepared===item)prepared=null;
  }
  function requestInteraction(item){
    item.needsInteraction=true;record('interaction-required');
    // The normal site dialog presents the widget when this preparation gets its turn.
  }
  function assign(item,config,receiver){
    // An unfinished verification must run in the site's own dialog. Moving a
    // pending iframe may leave the site waiting with no approval callback.
    if(!item.approval)return false;
    item.consumer=config;item.receiver=receiver;record('prepared-widget-assigned');
    queueMicrotask(()=>deliver(item));
    return true;
  }
  function deliver(item){
    if(item.removed||item.delivered||!item.consumer||!item.approval)return;
    if(!enabled()){remove(item);return;}
    if(item.id&&typeof api.isExpired==='function'&&api.isExpired(item.id)){
      expired++;item.consumer['expired-callback']?.();remove(item);return;
    }
    const approval=item.approval,callback=item.consumer.callback;
    item.approval=null;item.delivered=true;used++;record('prepared-approval-delivered');
    if(prepared===item)prepared=null;
    try{Reflect.apply(callback,item.receiver,approval);}
    finally{remove(item);queueMicrotask(prepare);}
  }
  function prepare(){
    if(!enabled()||!configTemplate||prepared)return;
    const host=document.createElement('div');
    host.dataset.nexoraPrecheck='true';
    host.style.cssText='position:fixed;left:12px;bottom:12px;width:340px;max-width:calc(100vw - 24px);opacity:0;pointer-events:none;z-index:2147483647;';
    const heading=document.createElement('p');heading.textContent='Nexora · verificação da próxima solicitação';host.appendChild(heading);
    const container=document.createElement('div');host.appendChild(container);document.body.appendChild(host);
    const item={host,id:null,approval:null,consumer:null,receiver:null,delivered:false,removed:false,visible:false,needsInteraction:false};
    prepared=item;created++;record('preparation-started');
    // A separate normal widget performs its own verification on this same page.
    // Only a fresh genuine approval is passed once to the next normal site submission.
    const options={...configTemplate,callback:function(...values){
      if(item.removed)return;
      item.approval=values;ready++;record('prepared-approval-ready');
      if(item.consumer)queueMicrotask(()=>deliver(item));
    },'before-interactive-callback':()=>requestInteraction(item),
    'after-interactive-callback':()=>{},
    'expired-callback':()=>{if(item.removed)return;expired++;record('prepared-approval-expired');
      item.approval=null;item.consumer?.['expired-callback']?.();remove(item);},
    'error-callback':()=>{if(item.removed)return;record('prepared-verification-error');
      item.consumer?.['error-callback']?.();remove(item);},
    'timeout-callback':()=>{if(item.removed)return;record('prepared-verification-timeout');
      item.consumer?.['timeout-callback']?.();remove(item);}};
    item.options=options;
    try{item.id=Reflect.apply(originalRender,api,[container,options]);}
    catch{record('preparation-failed');remove(item);}
  }
  const wrappedRender=new Proxy(originalRender,{apply(target,receiver,args){
    const [selector,config]=args;
    if(!enabled()||selector!=='#grok-turnstile-modal'||!config||typeof config.callback!=='function')return Reflect.apply(target,receiver,args);
    configTemplate={...config};
    const item=prepared;
    const sameContext=item&&['sitekey','action','cData','chlPageData'].every(key=>item.options[key]===config[key]);
    if(item&&!item.removed&&item.id&&sameContext&&!(typeof api.isExpired==='function'&&api.isExpired(item.id))
      &&assign(item,config,receiver)){
      return item.id;
    }
    if(item){
      if(!item.approval)record('pending-precheck-fallback-to-site');
      remove(item);
    }
    const owned={id:null,cancelled:false};
    ownedWidgets.add(owned);
    const options={...config,callback:function(...values){
      if(owned.cancelled||!enabled())return;
      ownedWidgets.delete(owned);
      const result=Reflect.apply(config.callback,this,values);
      record('normal-approval-delivered');queueMicrotask(prepare);return result;
    }};
    try{owned.id=Reflect.apply(target,receiver,[selector,options,...args.slice(2)]);return owned.id;}
    catch(error){ownedWidgets.delete(owned);throw error;}
  }});
  api.render=wrappedRender;
  if(api.render!==wrappedRender)return {installed:false};
  function cancelPending(){
    const hadDialog=!!prepared?.consumer||ownedWidgets.size>0;
    if(prepared)remove(prepared);
    for(const item of ownedWidgets){item.cancelled=true;try{if(item.id)api.remove(item.id);}catch{}}
    ownedWidgets.clear();
    if(hadDialog){
      const dialog=document.querySelector('#grok-turnstile-modal')?.closest('[role="dialog"]');
      const close=dialog&&[...dialog.querySelectorAll('button')].find(button=>
        /^(close|fechar)$/i.test(button.getAttribute('aria-label')||'')||button.querySelector('[class*="i-lucide-x"]'));
      close?.click();
    }
  }
  function sync(){
    if(!enabled())cancelPending();
  }
  const timer=setInterval(sync,500);
  function status(){return {installed:true,enabled:enabled(),created,ready,used,expired,
    preparing:!!prepared&&!prepared.approval,approvalReady:!!prepared?.approval,assigned:!!prepared?.consumer,
    awaitingSiteVerification:ownedWidgets.size>0,
    interactionVisible:!!prepared?.visible,needsInteraction:!!prepared?.needsInteraction,events:[...events]};}
  function restore(){stopped=true;clearInterval(timer);if(api.render===wrappedRender)api.render=originalRender;
    cancelPending();
    if(window.__nexoraVidoPreload===controller)delete window.__nexoraVidoPreload;
    return {restored:true,status:status()};}
  const controller={status,sync,cancelPending,restore};
  window.__nexoraVidoPreload=controller;
  return status();
  };
})(globalThis);
