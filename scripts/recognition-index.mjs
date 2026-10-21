import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = path => JSON.parse(readFileSync(resolve(root, path), 'utf8'));
const apps = new Map([
  ['com.tencent.mm', 'wechat'], ['com.eg.android.AlipayGphone', 'alipay'],
  ['com.xunmeng.pinduoduo', 'pdd'], ['com.unionpay', 'unionpay'],
  ['com.jingdong.app.mall', 'jd'], ['com.sankuai.meituan', 'meituan'],
  ['com.ss.android.ugc.aweme', 'douyin'],
]);
const source = read('docs/qianji-static-inventory.json');
const connected = source.extractors.filter(item => item.status === 'statically_connected');
const scenarios = connected.map(item => {
  const app = apps.get(item.matcher.package_name);
  assert.ok(app, `Unmapped package: ${item.matcher.package_name}`);
  const fixture = `core/src/test/resources/recognition/${app}/scenarios.json`;
  const cases = read(fixture).filter(sample => sample.ruleId === item.page_type);
  assert.ok(cases.some(sample => sample.positive), `Missing positive: ${item.page_type}`);
  assert.ok(cases.some(sample => !sample.positive), `Missing negative: ${item.page_type}`);
  return {
    id: item.page_type, package: item.matcher.package_name, priority: item.matcher.priority_in_app,
    original_extractor: item.extractor_class, original_matcher: item.matcher.matcher_class,
    evidence: 'static_source_and_independent_synthetic_fixtures',
    fixture, cases: cases.map(sample => ({ name: sample.name, positive: sample.positive })),
    live_app_version: null, device_payment_test: 'not_tested',
    acceptance_test: 'RuleRegistryTest.everyEnabledRuleHasPassingIndependentPositiveAndNegativeEvidence',
    notes: `docs/recognition-${app}.md`,
  };
});
assert.equal(scenarios.length, 56);
assert.equal(new Set(scenarios.map(item => item.id)).size, 56);
const index = {
  schema_version: 1,
  scope: 'Parser evidence only. Registration and fixture presence do not prove live compatibility, queue insertion or ledger save.',
  inventory: 'docs/qianji-static-inventory.json',
  source_apk_sha256: source.apk.sha256,
  verification_command: 'sh work/run-gradle.sh :core:test',
  generated_from_sources_not_runtime_results: true,
  configured_but_unregistered_packages: source.configured_but_not_registered,
  registered_scenarios: scenarios.length,
  scenarios,
};
const output = `${JSON.stringify(index, null, 2)}\n`;
if (process.argv.includes('--write')) {
  writeFileSync(resolve(root, 'docs/recognition-rule-index.json'), output);
  process.stdout.write(`Indexed ${scenarios.length} rules with ${scenarios.reduce((sum, item) => sum + item.cases.length, 0)} independent fixture cases.\n`);
} else process.stdout.write(output);
