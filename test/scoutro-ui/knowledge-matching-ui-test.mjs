#!/usr/bin/env node
/* GPL-2.0-or-later. Actual UI with offline archive/contribution API fixtures. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..'), base='http://127.0.0.1:8090';
const origin='kge_'+'a'.repeat(20), target='kge_'+'b'.repeat(20), proposal='kgd_'+'c'.repeat(20);
const at='2020-10-09T10:00:00Z', processed='2026-10-09T10:00:00Z';
const ref={id:target,name:'Architecture B',type:'organization',target_collection:'kgb',collections:['kgb','regional'],other_collections:['kgb','regional']};
const obs={id:'kgo_'+'a'.repeat(20),schema:'scoutro.kg.history.v1',subject:target,subject_type:'job',organization:target,
  predicate:'system_signal',value:{product:'revit',context:'internal_use'},quote:'Wir nutzen Revit intern. <img src=x onerror=alert(1)>',
  identity_context:{organization:[{predicate:'name',value:ref.name}],employer_assignment:'source_declared'},
  observed_at:at,recorded_at:processed,source:{status:'removed',url:'https://candidate.example/jobs',revision:'old-content'},
  locator:'/description',extractor:'jsonld/5',vocabulary_version:'3/products-1',assertion_status:'recorded',certainty:'stated',
  collections:['kgb'],live_statement:null,job_search:{status:'unknown'}};
const reasons=Array.from({length:31},(_,i)=>({id:'kgd_'+String.fromCharCode(97+i%26).repeat(19)+(i<26?'a':'b'),proposal_id:proposal,
  rule:'cad-bim',rule_version:'1',service:'revit support',product:'revit',context:'internal_use',evidence_strength:'stated',
  fit:'explicit_rule',temporal_status:'historical_not_reconfirmed',uncertainties:['company_role_unknown','external_purchase_or_cooperation_unconfirmed'],
  score:.6,observed_at:at,computed_at:processed,collection_a:'kga',collection_b:'kgb',origin_collection:'kga',target_collection:'kgb',
  evidence_complete:true,evidence:[{...obs,quote:obs.quote+' Evidence '+i}]}));
const suggestion={id:proposal,kind:'suggested_customer',direction:'out',other:ref,score:.6,fact:false,label:'suggestion',target_collection:'kgb',
  contributions:reasons.slice(0,25),contributions_total:31,next_contribution_offset:25,
  contributions_path:'entities/'+origin+'/suggestions/'+proposal+'/contributions'};
const edge={...suggestion,id:'suggestion:suggested_customer:'+origin+':'+target,from:origin,to:target,type:'suggested_customer',status:'suggested',evidence:25};
const browser=await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH||'/usr/bin/chromium',args:['--no-sandbox']});
let checks=0;const check=(v,m)=>{assert(v,m);checks++;};
try {
  for(const [language,width] of [['en',1280],['de',390]]) {
    const context=await browser.newContext({viewport:{width,height:900},locale:language}),page=await context.newPage(),requests=[],errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.route(base+'/**',async route=>{
      const u=new URL(route.request().url());
      if(u.pathname==='/ScoutroKnowledge_p.html') {
        let html=fs.readFileSync(path.join(root,'htroot/ScoutroKnowledge_p.html'),'utf8').replace(/#%[^\n]*?%#/g,'');
        if(language==='de') {
          html=html.replace('lang="en"','lang="de"');
          const section=fs.readFileSync(path.join(root,'locales/de.lng'),'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
          for(const [key,value] of section.split('\n').filter(l=>l.includes('==')&&!l.startsWith('#')).map(l=>l.split('==')).sort((a,b)=>b[0].length-a[0].length))html=html.split(key).join(value);
        }return route.fulfill({contentType:'text/html; charset=utf-8',body:html});
      }
      if(u.pathname==='/env/scoutro/collections.js')return route.fulfill({contentType:'application/javascript',body:"window.ScoutroCollections={load:async()=>['kga','kgb','regional'],fill:(el,ids)=>{for(const id of ids){const o=document.createElement('option');o.value=id;o.textContent=id;el.append(o);}el.disabled=false;}};"});
      if(u.pathname.startsWith('/env/scoutro/'))return route.fulfill({contentType:u.pathname.endsWith('.css')?'text/css':'application/javascript',body:fs.readFileSync(path.join(root,'htroot',u.pathname.slice(1)),'utf8')});
      if(!u.pathname.startsWith('/scoutro/api/v1/kg/'))return route.fulfill({status:404,body:''});
      requests.push(u);const r=u.pathname.slice('/scoutro/api/v1/kg/'.length),current=r.startsWith('entities/'+origin)?{id:origin,name:'Provider A'}:ref;
      let data;
      if(r.endsWith('/business'))data={...current,overview:{...current,type:'organization'},suggestions:{items:current.id===origin?[suggestion]:[],total:current.id===origin?1:0,next_offset:null}};
      else if(r.endsWith('/contributions'))data={items:reasons.slice(25),total:31,offset:25,limit:25,next_offset:null};
      else if(r.endsWith('/statements'))data={items:[],total:0};
      else if(r.endsWith('/neighborhood')){const on=u.searchParams.get('suggested')==='true';data={center:origin,nodes:[{id:origin,label:'Provider A',type:'organization',depth:0,collections:['kga']},...(on?[{...ref,label:ref.name,depth:1}]:[])],edges:on?[edge]:[],neighbours:on?1:0,truncated:false,next_offset:null};}
      else if(r.startsWith('observations/'))data=r.endsWith('/history')?{items:[],has_more:false,next_after:null}:obs;
      else if(r.startsWith('entities/'))data={...current,type:'organization',quality:'supported',aliases:[],identifiers:[],counts:{statements:0,sources:0}};
      else data={};
      return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
    });
    await page.goto(base+'/ScoutroKnowledge_p.html?view=object&id='+origin+'&collection=kga');
    const item=page.locator('#skg-sec-matches .skg-derived').first();await item.waitFor();
    const text=await item.textContent();
    check(text.includes(language==='de'?'Möglicher Kunde für CAD-/BIM-Unterstützung':'Possible customer for CAD/BIM support'),'localized rule explanation: '+language+' '+text.slice(0,1200));
    check(text.includes(language==='de'?'Andere Collection: kgb':'Other collection: kgb'),'authorized collection tag');
    check(text.includes('2020')&&text.includes('2026'),'observation and computation dates are distinct');
    check(text.includes(language==='de'?'heutiger Einsatz nicht erneut bestätigt':'present use has not been reconfirmed'),'historical use qualifier');
    check(!text.includes('%'),'no probability presentation');
    check(await item.locator('img').count()===0,'archive quotation is escaped');
    await item.locator('details > summary').first().click();
    check(await item.locator('.skg-contribution').count()===25,'bounded preview');
    await item.locator('details > button').click();await page.waitForFunction(()=>document.querySelectorAll('#skg-sec-matches .skg-contribution').length===31);
    check(requests.some(u=>u.pathname.endsWith('/contributions')&&u.searchParams.get('offset')==='25'&&u.searchParams.get('collection')==='kga'),'more reasons preserve origin context');
    const history=item.locator('a[href*="view=history"]').first();check(new URL(await history.getAttribute('href'),base).searchParams.get('collection')==='kgb','archive link uses signal context');
    await history.click();await page.locator('#skg-history-items article').waitFor();
    check(new URL(page.url()).searchParams.get('observation')===obs.id,'history opens without a live statement');
    await page.goBack();await item.waitFor();
    const open=item.locator('a[href*="view=object"]').first();await open.click();await page.waitForFunction(()=>document.querySelector('#skg-object-name').textContent==='Architecture B');
    check(new URL(page.url()).searchParams.get('collection')==='kgb','target opens authorized context');
    await page.goBack();await item.waitFor();check(new URL(page.url()).searchParams.get('collection')==='kga','return navigation');
    await page.goto(base+'/ScoutroKnowledge_p.html?view=network&id='+origin+'&collection=kga&f=business,structure,offers,places,industry,audiences,jobs,derived,suggested');
    await page.locator('#skg-net-svg .skg-edge.skg-e-suggested').waitFor();
    check((await page.locator('#skg-net-table').textContent()).includes('Revit'),'list retains explicit matching reasons');
    const json=JSON.parse(await page.locator('#skg-net-json').evaluate(async a=>(await fetch(a.href)).text()));
    check(json.edges[0].contributions[0].evidence[0].quote===obs.quote+' Evidence 0','JSON graph export retains original archive evidence');
    check(json.edges[0].contributions_path===suggestion.contributions_path&&json.edges[0].contributions_total===31,'export links full reasons');
    const xml=await page.locator('#skg-net-graphml').evaluate(async a=>(await fetch(a.href)).text());
    check(xml.includes('cad-bim')&&xml.includes('kgo_'),'GraphML export retains explicit rule and archive references');
    await page.locator('#skg-f-suggested').uncheck();await page.locator('#skg-net-form button[type=submit]').click();
    await page.waitForFunction(()=>document.querySelectorAll('#skg-net-svg .skg-edge.skg-e-suggested').length===0);
    check(errors.length===0,'no browser errors: '+errors.join(', '));
    check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'no mobile overflow');
    await context.close();
  }
} finally {await browser.close();}
console.log('PASS: '+checks+' offline matching UI checks (EN/DE, history, pagination, graph/export/navigation)');
