#!/usr/bin/env python3
"""Offline, same human gold/scorer; never invokes AI or changes expected answers."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();raw=json.loads(args.raw.read_text())
    assert raw['completed']
    summaries={}
    for phase in ['before','after']:
        rows=[x for x in raw['results'] if x['phase']==phase and x['status']=='ok']
        assert len(rows)==10 and len({x['videoNo'] for x in rows})==10
        summaries[phase]={'cases':10,'strictRecall':statistics.mean(x['scores']['strict']['recall'] for x in rows),
            'strictPrecision':statistics.mean(x['scores']['strict']['precision'] for x in rows),
            'legacyRecall':statistics.mean(x['scores']['legacyRecall'] for x in rows),
            'legacyPrecision':statistics.mean(x['scores']['legacyPrecision'] for x in rows),
            'emptyGoldFalsePositives':sum(bool(x['names']) for x in rows if not x['expected'])}
    before,after=summaries['before'],summaries['after'];reasons=[]
    for metric in ['strictRecall','strictPrecision','legacyRecall','legacyPrecision']:
        if after[metric]<before[metric]:reasons.append(metric+' decreased')
    if after['emptyGoldFalsePositives']>before['emptyGoldFalsePositives']:reasons.append('empty-gold false positives increased')
    improved=after['strictRecall']>before['strictRecall'] or after['strictPrecision']>before['strictPrecision']
    adopted=not reasons and improved
    if not improved:reasons.append('no strict metric improvement')
    result={'scope':'Pre-geocoding names; conditional 10-case experiment, no historical pooling',
            'scores':summaries,'candidateAdopted':adopted,'reason':reasons,'attemptsStarted':raw['attemptsStarted'],
            'transportErrorsRetained':sum(x['status']=='error' for x in raw['results']),
            'newDeterministicGuard':'limited exact broad-region/interior labels, business/address context preserved; separate production component replay and unit checks, no full-population accuracy claim',
            'rawSha256':hashlib.sha256(args.raw.read_bytes()).hexdigest()}
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
