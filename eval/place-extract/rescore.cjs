// 이미 저장한 결과를 오프라인 재채점한다. API 호출과 정답 수정은 하지 않는다.
const fs = require('node:fs');
const {score} = require('./scoring.cjs');
const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const rows = data.results.results.map(r => {
    if (!r.response || typeof r.response.output !== 'string') {
        return {case:r.testCase?.description, error:'실행 오류: 정확도 집계 제외'};
    }
    const expected = r.testCase.vars.expected;
    const result = score(r.response.output, {vars:{expected}});
    const got = JSON.parse(r.response.output);
    const exp = JSON.parse(expected);
    const norm = s => s.replace(/\s+/g, '');
    const possibleAliases = [];
    for (const e of exp) for (const g of got) {
        const a=norm(e), b=norm(g);
        if (a !== b && (a.includes(b) || b.includes(a))) possibleAliases.push({expected:e, observed:g, status:'needs_human_review'});
    }
    return {case:r.testCase.description, ...result, possibleAliases};
});
const valid=rows.filter(r=>!r.error);
console.log(JSON.stringify({scope:'AI raw names before geocoding; strict exact match; no aliases approved',
    cases:valid.length, recall:valid.reduce((a,r)=>a+r.recall,0)/valid.length,
    precision:valid.reduce((a,r)=>a+r.precision,0)/valid.length, results:rows},null,2));
