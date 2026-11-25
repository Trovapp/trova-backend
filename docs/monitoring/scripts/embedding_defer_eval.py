#!/usr/bin/env python3
"""T45 CSV → Java 서비스 재생 입력, 재생 출력 → 비교 지표. 외부 호출 없음."""
import argparse, collections, csv, hashlib, json, math, re, statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'docs/monitoring/2026-11-25-embedding-defer-eval'
def dump(path,obj): path.write_text(json.dumps(obj,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
def mean(xs): return statistics.mean(xs) if xs else None
def prepare():
    groups=collections.defaultdict(list)
    for r in csv.DictReader((BASE/'data/candidates.csv').open()):groups[r['request_alias']].append(r)
    requests=[]
    for alias,rs in groups.items():
        first=rs[0]; cats=json.loads(first['signal_categories']); candidates=[]
        for r in rs:
            if not r['candidate_alias']:continue
            sims=[float(v) for v in json.loads(r['similarities'])]
            if len(sims)>5:raise ValueError('top-5 초과')
            category=r['category'] or None
            candidates.append(dict(candidate_alias=r['candidate_alias'],input_order=int(r['input_order']),
                primary=r['primary']=='t',category=category,
                rating=float(r['rating']) if r['rating'] else None,
                user_rating_count=int(r['user_rating_count']) if r['user_rating_count'] else None,
                distance_target_km=float(r['distance_target_km']),
                embedded_before=r['embedded_before']=='t',embedded_now=r['embedded_now']=='t',
                similarities=[v for v in sims if math.isfinite(v)],
                # 신규 대체 점수: 임베딩 있는 신호 중 category 정확일치 비율(신호 중복 유지).
                category_proxy=sum(c==category for c in cats)/len(cats) if category and cats else 0.0,
                visible_indices=json.loads(r['visible_indices'])))
        requests.append(dict(request_alias=alias,user_alias=first['user_alias'],target_category=first['target_category'] or None,
            exact_name=first['target_exact_name_match']=='t',target_match_count=int(first['target_match_count'] or 0),
            semantic_suspect=alias=='R27',complete_embeddings=all(c['embedded_now'] for c in candidates),signal_count=int(first['signal_count']),
            embedded_signal_count=int(first['embedded_signal_count']),visible_count=int(first['visible_count']),candidates=candidates))
    dump(BASE/'data/replay-input.json',requests)
    print(f'재생 입력: {len(requests)}요청, {sum(len(r["candidates"]) for r in requests)}후보')
def metrics(a,b):
    if len(set(a))!=len(a) or set(a)!=set(b):raise ValueError('순위 모집합 불일치')
    n=len(a); rank={c:i for i,c in enumerate(b)}
    out=dict(n=n,top1=int(bool(a) and a[0]==b[0]))
    for k in (5,10):
        x=set(a[:k]);y=set(b[:k]); inter=len(x&y)
        out.update({f'intersection{k}':inter,f'effective_k{k}':min(k,n),f'overlap{k}':inter/min(k,n) if n else None,
                    f'jaccard{k}':inter/len(x|y) if x|y else None})
    out['spearman']=1-6*sum((i-rank[c])**2 for i,c in enumerate(a))/(n*(n*n-1)) if n>1 else None
    discord=sum(rank[a[i]]>rank[a[j]] for i in range(n) for j in range(i+1,n))
    out['kendall']=1-2*discord/(n*(n-1)/2) if n>1 else None
    return out

def analyze():
    reqs=json.loads((BASE/'data/replay-input.json').read_text())
    runs={r['request_alias']:r for r in json.loads((BASE/'data/replay-output.json').read_text())}
    details=[]; cases=[]
    for r in reqs:
        alias=r['request_alias']; run=runs[alias]; a=[x['candidate_alias'] for x in run['A']]; by={c['candidate_alias']:c for c in r['candidates']}
        for mode in ('B1','B2','B3'):
            for mask in ('mixed','all-new'):
                key=mode+'/'+mask; b=[x['candidate_alias'] for x in run[key]]
                row=dict(request_alias=alias,variant=mode,mask=mask,user_alias=r['user_alias'],exact_name=r['exact_name'],semantic_suspect=r['semantic_suspect'],complete_embeddings=r['complete_embeddings'],**metrics(a,b))
                row['top5_changed']=set(a[:5])!=set(b[:5]); row['ranking_changed']=a!=b
                row['removed']=[by[c] for c in a[:5] if c not in b[:5]]
                row['added']=[by[c] for c in b[:5] if c not in a[:5]]
                row['reason_changed_count']=sum(x.get('reason')!=y.get('reason') for x,y in zip(run['A'][:2],run[key][:2]))
                details.append(row)
                if row['top5_changed'] or not row['top1']:
                    cases.append(dict(request_alias=alias,variant=mode,mask=mask,A=a[:5],B=b[:5],removed=row['removed'],added=row['added']))
    summaries=[]
    scopes={'complete23':lambda r:r['exact_name'] and r['complete_embeddings'] and not r['semantic_suspect'],'exact27':lambda r:r['exact_name'],'exact26_without_suspect':lambda r:r['exact_name'] and not r['semantic_suspect'],'all30':lambda r:True,
            'U1_complete':lambda r:r['exact_name'] and r['complete_embeddings'] and not r['semantic_suspect'] and r['user_alias']=='U1','U2_complete':lambda r:r['exact_name'] and r['complete_embeddings'] and not r['semantic_suspect'] and r['user_alias']=='U2','U1_exact':lambda r:r['exact_name'] and r['user_alias']=='U1','U2_exact':lambda r:r['exact_name'] and r['user_alias']=='U2'}
    for scope,pred in scopes.items():
        for mode in ('B1','B2','B3'):
            for mask in ('mixed','all-new'):
                selected=[d for d in details if pred(d) and d['variant']==mode and d['mask']==mask and d['n']]
                summary=dict(scope=scope,variant=mode,mask=mask,requests=len(selected))
                for field in ('n','top1','intersection5','intersection10','overlap5','overlap10','jaccard5','jaccard10','spearman','kendall','top5_changed','ranking_changed','reason_changed_count'):
                    summary[field]=mean([d[field] for d in selected if d[field] is not None])
                removed=[c for d in selected for c in d['removed']]; added=[c for d in selected for c in d['added']]
                summary['removed_count']=len(removed);summary['added_count']=len(added)
                for attr in ('rating','distance_target_km','user_rating_count'):
                    summary['removed_'+attr]=mean([c[attr] for c in removed if c[attr] is not None]);summary['added_'+attr]=mean([c[attr] for c in added if c[attr] is not None])
                summary['removed_categories']=dict(collections.Counter(c['category'] for c in removed));summary['added_categories']=dict(collections.Counter(c['category'] for c in added))
                summaries.append(summary)
    validation=[]
    for r in reqs:
        candidates=r['candidates'];run=runs[r['request_alias']];a=[x['candidate_alias'] for x in run['A']]
        visible={i:c['candidate_alias'] for c in candidates for i in c['visible_indices']}
        validation.append(dict(request_alias=r['request_alias'],exact_name=r['exact_name'],target_category=r['target_category'],
            semantic_suspect=r['semantic_suspect'],complete_embeddings=r['complete_embeddings'],target_match_count=r['target_match_count'],candidates=len(candidates),
            mixed_missing=sum(not c['embedded_before'] for c in candidates),visible_count=r['visible_count'],
            visible_found=len(visible),visible_in_A_top5=sum(c in a[:5] for c in visible.values()),
            visible_top1_in_A_top1=(visible.get(1)==a[0]) if 1 in visible and a else None))
    stages=[]
    for line in (BASE/'data/source-stage.log').read_text().splitlines():
        if '대안 찾기 구간별 시간:' in line:
            m=re.search(r'검색=(\d+)ms 카탈로그저장=(\d+)ms\((\d+)곳\) 실내태그=(\d+)ms 임베딩=(\d+)ms 혼잡도=(\d+)ms 개인화조회=(\d+)ms\((\d+)곳\) 추천이유=(\d+)ms',line)
            if m:stages.append(dict(zip(('search_ms','upsert_ms','raw_count','tag_ms','embedding_ms','congestion_ms','personalization_ms','result_count','reason_ms'),map(int,m.groups()))))
    assert len(stages)==30
    stage_summary={k:dict(mean=mean([s[k] for s in stages]),max=max(s[k] for s in stages)) for k in stages[0]}
    dump(BASE/'data/metrics.json',dict(summaries=summaries,validation=validation,stages=stage_summary,details=details,cases=cases,
        source_sha256=hashlib.sha256((BASE/'data/candidates.csv').read_bytes()).hexdigest()))
    with (BASE/'data/metrics.csv').open('w') as f:
        fields=['scope','variant','mask','requests','intersection5','overlap5','jaccard5','intersection10','overlap10','jaccard10','spearman','kendall','top1','top5_changed']
        writer=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore',lineterminator='\n');writer.writeheader();writer.writerows(summaries)
    print(json.dumps([s for s in summaries if s['scope']=='exact27'],ensure_ascii=False,indent=2))

def self_test():
    assert metrics(['a','b','c'],['a','b','c'])['spearman']==1
    assert metrics(['a','b','c'],['c','b','a'])['spearman']==-1
    assert metrics(['a','b','c'],['c','b','a'])['kendall']==-1
    x=metrics(list('abcdef'),list('bcdefa'));assert x['intersection5']==4 and abs(x['jaccard5']-2/3)<1e-12
    assert metrics(['a'],['a'])['spearman'] is None
    print('지표 독립 검산 통과')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--prepare',action='store_true');p.add_argument('--self-test',action='store_true');a=p.parse_args()
    if a.self_test:self_test()
    elif a.prepare:prepare()
    else:analyze()
