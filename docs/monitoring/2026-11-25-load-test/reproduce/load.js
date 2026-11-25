import http from 'k6/http';
import {sleep, check} from 'k6';
import exec from 'k6/execution';
import {Trend, Rate, Counter} from 'k6/metrics';
const tokens=JSON.parse(open('/private/tokens.json'));
const latency=new Trend('measured_latency',true), errors=new Rate('measured_errors'), requests=new Counter('measured_requests');
const statusCounters={};for(const code of [0,200,400,401,403,404,409,429,500,502,503,504])statusCounters[code]=new Counter('status_'+code);
const mode=__ENV.MODE, external=['alternatives','gap','conversation'].includes(mode);
export const options={scenarios:{load:{executor:'constant-vus',vus:Number(__ENV.VUS),duration:__ENV.DURATION||'35s',gracefulStop:'30s'}},summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],tags:{mode},thresholds:{measured_errors:['rate<0.01']}};
export default function(){
 const u=exec.vu.idInTest, shared=mode==='live',trip=shared?301:u, place=shared?1201:(u-1)*4+1;
 const headers={Authorization:`Bearer ${tokens[u-1]}`,'Content-Type':'application/json'};
 let path=`/api/trips/${trip}`,method='GET',body=null;
 let selected=mode;
 if(mode==='mixed'){const x=exec.scenario.iterationInTest%20;selected=x<13?'detail':x<16?'memo':x<18?'order':x===18?'alternatives':'gap';}
 if(selected==='memo'||shared){path=`/api/trip-places/${place}/details`;method='PATCH';body=JSON.stringify({memo:`synthetic-vu${u}-iteration${exec.vu.iterationInScenario}`});}
 if(selected==='order'){path=`/api/trip-places/${place}/order`;method='PATCH';body=JSON.stringify({direction:exec.vu.iterationInScenario%2?'UP':'DOWN'});}
 if(selected==='alternatives')path=`/api/trip-places/${place}/alternatives`;
 if(selected==='gap')path=`/api/trips/${trip}/days/1/gap-recommendations`;
 if(selected==='conversation'){path=`/api/conversations/load-${u}-${Date.now()}/messages`;method='POST';body=JSON.stringify({message:'근처 박물관 대안을 찾아줘',tripId:trip,tripPlaceId:place});}
 const requestStarted=Date.now();
 const eligible=external||Date.now()-exec.scenario.startTime>=10000;
 const r=http.request(method,`http://app:8080${path}`,body,{headers,timeout:'25s',tags:{name:selected}});
 if(statusCounters[r.status])statusCounters[r.status].add(1);
 let valid=r.status===200;
 try{const b=r.json();if(selected==='detail')valid=valid&&b.id===trip;if(selected==='alternatives')valid=valid&&Array.isArray(b)&&b.length>0;if(selected==='gap')valid=valid&&Array.isArray(b)&&b.some(g=>Array.isArray(g.recommendations)&&g.recommendations.length>0);if(selected==='conversation')valid=valid&&b.reply&&!b.reply.includes('지금 답변을 가져오지 못했어요');}catch(e){valid=false;}
 check(r,{'successful response and content':()=>valid});
 if(eligible&&(r.timings.duration>=1000||!valid))console.log('SLOW_SAMPLE '+JSON.stringify({start:requestStarted,end:Date.now(),mode:selected,vu:u,status:r.status,duration_ms:r.timings.duration,valid}));
 if(eligible){latency.add(r.timings.duration,{path:selected});errors.add(!valid,{path:selected});requests.add(1,{path:selected});}
 if(r.status===401||r.status===403)exec.test.abort('local synthetic authentication failed');
 sleep(external?3:1);
}
