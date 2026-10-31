const assert = require('node:assert/strict');
const {score} = require('./scoring.cjs');
const run = (got, expected) => score(JSON.stringify(got), {vars:{expected:JSON.stringify(expected)}});
assert.equal(run(['해운대역'], ['해운대']).recall, 0);
assert.equal(run(['테라로사커피공장'], ['테라로사커피공장강릉점']).recall, 0);
assert.equal(run(['가야랜드', '가야랜드'], ['가야랜드']).precision, 0.5);
assert.equal(run(['가야랜드'], ['가야랜드','가야랜드']).recall, 0.5);
assert.equal(run(['가야 랜드'], ['가야랜드']).recall, 1);
assert.equal(run([], []).recall, 1);
assert.equal(run(['부산'], []).recall, 0);
assert.throws(() => run([''], []));
assert.throws(() => run([null], []));
console.log('scoring regression checks passed');
const {rescore} = require('./rescore.cjs');
const record = (observed, expected) => ({testCase:{description:'fixture', vars:{expected:JSON.stringify(expected)}}, response:{output:observed}});
const partial = rescore({results:{results:[record('["가야랜드"]',['가야랜드']),record('invalid JSON',[]),{}]}});
assert.equal(partial.status, 'partial');
assert.equal(partial.cases, 1);
assert.equal(partial.unscorableCases, 2);
assert.equal(partial.recall, 1);
const empty = rescore({results:{results:[record('null',[])]}});
assert.equal(empty.status, 'no_valid_cases');
assert.equal(empty.recall, null);
assert.equal(empty.precision, null);
assert.equal(rescore({results:{results:[]}}).status, 'no_valid_cases');
// assertion 실패로 r.error가 있어도 유효한 예측은 채점에서 빼지 않는다.
const assertionFailed = {...record('["다른장소"]',['가야랜드']), error:'assertion failure'};
assert.equal(rescore({results:{results:[assertionFailed]}}).cases, 1);
assert.equal(rescore({results:{results:[assertionFailed]}}).recall, 0);
// NFKC로 동일한 이름은 별칭 검토 후보로 중복 제시하지 않는다.
assert.deepEqual(rescore({results:{results:[record('["Ａ카페"]',['A카페'])]}}).results[0].possibleAliases, []);
console.log('rescore error isolation checks passed');
const {legacyRecall, legacyPrecision} = require('./scoring.cjs');
const ctx = {vars:{expected:'["해운대"]'}};
const diagnostic = legacyRecall('["해운대역"]',ctx);
assert.equal(diagnostic.pass, true);
assert.equal(diagnostic.score, 1);
assert.equal(diagnostic.namedScores.strict_recall, 0);
assert.equal(legacyPrecision('["해운대역"]',ctx).namedScores.strict_precision, 0);
assert.equal(legacyRecall('["부산"]',{vars:{expected:'[]'}}).score, 0);
assert.equal(legacyPrecision('[]',{vars:{expected:'["가야랜드"]'}}).score, 1);
console.log('legacy/diagnostic separation checks passed');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const dir = fs.mkdtempSync(path.join(os.tmpdir(),'trova-rescore-test-'));
try {
    const file = path.join(dir,'invalid.json');
    fs.writeFileSync(file,JSON.stringify({results:{results:[record('invalid JSON',[])]}}));
    const cli = spawnSync(process.execPath,[path.join(__dirname,'rescore.cjs'),file],{encoding:'utf8'});
    assert.equal(cli.status,2);
    assert.equal(JSON.parse(cli.stdout).status,'no_valid_cases');
} finally {
    fs.rmSync(dir,{recursive:true,force:true});
}
console.log('rescore CLI empty-coverage checks passed');
