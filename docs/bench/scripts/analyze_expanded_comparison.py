#!/usr/bin/env python3
"""Reproducible descriptive analysis, no network or credentials. New samples are not pooled with old runs."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import random
import statistics


def percentile(values, probability):
    data = sorted(values)
    point = (len(data)-1)*probability
    lower = int(point)
    upper = min(lower+1,len(data)-1)
    return data[lower]+(data[upper]-data[lower])*(point-lower)


def describe(values):
    if not values:
        return None
    return {'n':len(values),'median':statistics.median(values),'mean':statistics.mean(values),
            'min':min(values),'q25':percentile(values,.25),'q75':percentile(values,.75),'max':max(values)}


def paired_distribution(values):
    generator = random.Random(288)
    samples = [statistics.median(generator.choices(values,k=len(values))) for _ in range(5000)]
    return {'medianPairedDifference':statistics.median(values),'bootstrapMedianCentral95':
            [percentile(samples,.025),percentile(samples,.975)],'seed':288,'resamples':5000,
            'interpretation':'Conditional descriptive paired bootstrap; time correlations/backend variation unmodelled, not causal system effect'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client',required=True)
    parser.add_argument('--production',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    client=json.loads(Path(args.client).read_text())
    production=json.loads(Path(args.production).read_text())
    assert client['samplesPerClient']==20 and len(client['results'])==42
    assert all(row['success'] for row in client['results'])
    assert production.get('complete') and production['restored'] and not production['errors']
    assert production['cleanup']['residualDrafts']==production['cleanup']['residualPlaces']==0
    assert len(production['results'])==15
    result={'scope':'New 2026-11-01 experiment only; no pooling of historical samples',
            'sourceSha256':{Path(p).name:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in [args.client,args.production]},
            'client':{},'production':{},'productionPaired':{},'productionPerVideo':{}}
    clients={}
    for name in ['rest','spring-ai']:
        rows=[row for row in client['results'] if row['client']==name and not row['warmup']]
        assert len(rows)==20
        clients[name]={row['round']:row['milliseconds'] for row in rows}
        result['client'][name]={'latencyMs':describe([row['milliseconds'] for row in rows]),'successes':len(rows),
                                'totalRecordedTokens':sum(row.get('tokens',0) for row in rows)}
    differences=[clients['spring-ai'][i]-clients['rest'][i] for i in sorted(clients['rest'])]
    result['client']['pairedSpringMinusRestMs']=paired_distribution(differences)
    result['client']['pairedSpringMinusRestMs']['observedDifferences']=differences
    modes={}
    metrics=['queueWaitMs','requestMs','processingSeconds','createdToAnalysisDoneSeconds','rssKiBAfterDrafts']
    for mode in ['async','kafka']:
        rows=[row for row in production['results'] if row['mode']==mode and not row['warmup']]
        assert len(rows)==6 and all(row['status']=='DONE' and row['observedPath']==mode for row in rows)
        modes[mode]={(row['videoId'],row['repetition']):row for row in rows}
        calls=[call for row in rows for call in row['apiLogRows'] if call['provider']=='gemini']
        result['production'][mode]={metric:describe([row[metric] for row in rows]) for metric in metrics}
        result['production'][mode]['geminiLatencyMsByOperation'] = {
            operation: describe([call['latencyMs'] for call in calls if call['operation']==operation])
            for operation in sorted({call['operation'] for call in calls})}
        result['production'][mode]['perJobGeminiLogLatencySumMs'] = describe([
            sum(call['latencyMs'] for call in row['apiLogRows'] if call['provider']=='gemini') for row in rows])
        result['production'][mode].update(jobIds=[row['jobId'] for row in rows],geminiLogicalLogRows=len(calls),
                                         knownAttempts=sum(call['attempts'] for call in calls if call['attempts'] is not None),
                                         unknownAttemptRows=sum(call['attempts'] is None for call in calls),
                                         knownWaitedMs=sum(call['waitedMs'] for call in calls if call['waitedMs'] is not None),
                                         unknownWaitedRows=sum(call['waitedMs'] is None for call in calls))
    assert modes['async'].keys()==modes['kafka'].keys()
    videos=sorted({key[0] for key in modes['async']})
    assert len(videos)==3
    for video in videos:
        result['productionPerVideo'][video]={mode:{metric:describe([row[metric] for (vid,rep),row in modes[mode].items() if vid==video]) for metric in metrics} for mode in modes}
    for metric in metrics:
        differences={key:modes['kafka'][key][metric]-modes['async'][key][metric] for key in modes['async']}
        # Three video clusters have only 3^3=27 ordered resampling outcomes.
        # Keep both repetitions together and enumerate them, rather than claim 6 independent videos.
        resampled=[]
        for draw in itertools.product(videos,repeat=3):
            values=[value for video in draw for (vid,rep),value in differences.items() if vid==video]
            resampled.append(statistics.median(values))
        result['productionPaired'][metric]={'medianPairedKafkaMinusAsync':statistics.median(differences.values()),
            'videoClusterResampleMedianCentral95':[percentile(resampled,.025),percentile(resampled,.975)],
            'orderedClusterDraws':27,'videoClusters':3,'pairs':6,
            'observedPairedDifferences':[{'videoId':key[0],'repetition':key[1],'difference':value} for key,value in sorted(differences.items())]}
    result['limitations']=['Client tiny JSON prompt, same identifier not pinned provider backend revision, local Mac only',
        'Production n=6/mode, 3 videos x 2 repeats, K-A-K blocks not randomized; process restart/cache/time confounding',
        'Cluster resampling range is descriptive with 3 clusters/27 outcomes; no general population interval or causal significance claim',
        'Processing seconds derived from DB analysis completion minus logged queue wait; excludes draft completion',
        'No production p90/failure-rate/throughput/general reliability estimate; original REST server not redeployed',
        'Retry logs do not expose all wire attempts; missing counts remain unknown; no forced broker outage',
        'Historical n=4, deployment smoke, and warmups excluded from new summaries']
    Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'client':result['client'],'production':result['production']},indent=2))


if __name__=='__main__':
    main()
