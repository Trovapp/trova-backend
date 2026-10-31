// 이미 저장한 결과를 오프라인 재채점한다. API 호출과 정답 수정은 하지 않는다.
const fs = require('node:fs');
const {score, norm} = require('./scoring.cjs');

function rescore(data) {
    if (!Array.isArray(data?.results?.results)) throw new Error('results.results 배열이 필요합니다');
    const rows = data.results.results.map(r => {
        const name = r?.testCase?.description;
        if (!r?.response || typeof r.response.output !== 'string' || r.response.error) {
            return {case: name, error:'실행 오류: 출력 없음', errorKind:'missing_output'};
        }
        try {
            const expected = r.testCase.vars.expected;
            const result = score(r.response.output, {vars:{expected}});
            const got = JSON.parse(r.response.output);
            const exp = JSON.parse(expected);
            const possibleAliases = [];
            for (const e of exp) for (const g of got) {
                const a = norm(e), b = norm(g);
                if (a !== b && (a.includes(b) || b.includes(a))) {
                    possibleAliases.push({expected:e, observed:g, status:'needs_human_review'});
                }
            }
            return {case:name, ...result, possibleAliases};
        } catch (error) {
            // 응답 원문/예외 메시지는 재출력하지 않는다. 다른 유효한 사례는 계속 채점한다.
            return {case:name, error:'이름 배열 또는 기대값을 채점할 수 없음', errorKind:'invalid_scoring_input'};
        }
    });
    const valid = rows.filter(r => !r.error);
    return {scope:'AI raw names before geocoding; strict exact match; no aliases approved',
        status:valid.length === 0 ? 'no_valid_cases' : valid.length < rows.length ? 'partial' : 'complete',
        totalCases:rows.length, unscorableCases:rows.length-valid.length, cases:valid.length,
        recall:valid.length ? valid.reduce((a,r) => a+r.recall, 0)/valid.length : null,
        precision:valid.length ? valid.reduce((a,r) => a+r.precision, 0)/valid.length : null,
        results:rows};
}
exports.rescore = rescore;
if (require.main === module) {
    const report = rescore(JSON.parse(fs.readFileSync(process.argv[2], 'utf8')));
    console.log(JSON.stringify(report, null, 2));
    if (!report.cases) process.exitCode = 2;
}
