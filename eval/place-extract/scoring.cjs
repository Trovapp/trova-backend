// 엄격 채점: 확인되지 않은 별칭이나 부분 문자열은 같은 장소로 간주하지 않는다.
const norm = s => s.normalize('NFKC').replace(/\s+/gu, '');
function score(output, context) {
    const got = JSON.parse(output);
    const expected = JSON.parse(context.vars.expected);
    if (![got, expected].every(a => Array.isArray(a) && a.every(n => typeof n === 'string' && norm(n)))) {
        throw new Error('이름은 비어 있지 않은 문자열 배열이어야 합니다');
    }
    const remaining = expected.map(norm);
    let hits = 0;
    for (const name of got.map(norm)) {
        const i = remaining.indexOf(name);
        if (i >= 0) { hits++; remaining.splice(i, 1); }
    }
    return {recall: expected.length ? hits / expected.length : (got.length ? 0 : 1),
        precision: got.length ? hits / got.length : 1, missing: remaining, hits};
}
exports.score = score;
exports.norm = norm;
for (const metric of ['recall', 'precision']) {
    exports[metric] = (output, context) => {
        const s = score(output, context);
        return {pass: s[metric] === 1, score: s[metric], reason: `엄격 ${metric}: ${s.hits}곳 일치, 누락 ${s.missing.join(',') || '없음'}`};
    };
}

// 기존 두 assertion의 pass/score는 유지하고 strict는 별도 namedScores에만 실어 보낸다.
function legacy(output, context, metric) {
    const normalize = s => String(s).replace(/\s+/g, '');
    const same = (a,b) => a === b || a.includes(b) || b.includes(a);
    const got = JSON.parse(output).map(normalize);
    const expected = JSON.parse(context.vars.expected).map(normalize);
    let result;
    if (metric === 'recall') {
        const hits = expected.filter(e => got.some(g => same(e,g))).length;
        const value = expected.length ? hits / expected.length : (got.length ? 0 : 1);
        result = {pass:value === 1, score:value, reason:`기존 recall: ${hits}/${expected.length}`};
    } else {
        const hits = got.filter(g => expected.some(e => same(e,g))).length;
        const value = got.length ? hits / got.length : 1;
        result = {pass:hits === got.length, score:value, reason:`기존 precision: 오탐 ${got.length-hits}개`};
    }
    try {
        result.namedScores = {[`strict_${metric}`]:score(output,context)[metric]};
    } catch (error) {
        result.metadata = {strictScoringError:'invalid_names'};
    }
    return result;
}
exports.legacyRecall = (output, context) => legacy(output, context, 'recall');
exports.legacyPrecision = (output, context) => legacy(output, context, 'precision');
