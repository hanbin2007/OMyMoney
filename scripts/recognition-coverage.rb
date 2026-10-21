#!/usr/bin/env ruby
require 'json'

root = File.expand_path('..', __dir__)
inventory = JSON.parse(File.read(File.join(root, 'docs/qianji-static-inventory.json')))
fixtures = Dir.glob(File.join(root, 'core/src/test/resources/recognition/*/scenarios.json')).to_h do |path|
  [path.delete_prefix(root + '/'), JSON.parse(File.read(path))]
end
scenarios = inventory.fetch('extractors').select { |row| row.fetch('status') == 'statically_connected' }.map do |row|
  page = row.fetch('page_type')
  path, cases = fixtures.find { |_, values| values.any? { |fixture| fixture.fetch('ruleId') == page } }
  raise "Missing fixture: #{page}" unless cases
  matching = cases.select { |fixture| fixture.fetch('ruleId') == page }
  positive, negative = matching.partition { |fixture| fixture.fetch('positive') }
  raise "Incomplete fixture: #{page}" if positive.empty? || negative.empty?
  {
    pageType: page, package: row.fetch('matcher').fetch('package_name'),
    staticSource: { inventory: 'docs/qianji-static-inventory.json', extractor: row.fetch('extractor_class'),
      registryLine: row.fetch('registry_line'), matcher: row.fetch('matcher').fetch('matcher_class'),
      priority: row.fetch('matcher').fetch('priority_in_app') },
    fixture: path, positiveCases: positive.size, negativeCases: negative.size,
    fixtureNames: matching.map { |fixture| fixture.fetch('name') },
    evidenceLevel: 'synthetic_fixtures', test: 'RuleRegistryTest.everyEnabledRuleHasPassingIndependentPositiveAndNegativeEvidence',
    livePaymentApp: { status: 'not_tested', appVersion: nil, device: nil, api: nil },
    conclusion: 'Independent bounded fixture coverage, not real payment-app compatibility or save-rate evidence'
  }
end
raise 'Inventory mismatch' unless scenarios.size == 56 && scenarios.map { |row| row[:pageType] }.uniq.size == 56
report = {
  product: 'OMyMoney', scope: 'Stage B deterministic local recognition',
  sourceApk: inventory.fetch('apk').slice('sha256', 'version_name', 'version_code'),
  generation: 'ruby scripts/recognition-coverage.rb; fixture counts are generated, test outcomes are verified separately',
  counts: { staticConnected: scenarios.size, packages: scenarios.map { |row| row[:package] }.uniq.size,
    positiveFixtureCases: scenarios.sum { |row| row[:positiveCases] }, negativeFixtureCases: scenarios.sum { |row| row[:negativeCases] },
    livePaymentAppVerified: 0 },
  stateBoundary: 'Recognized, queued, editing, ignored, failed and saved are distinct queue events; scenario coverage is not a count of persisted bills',
  platformEvidence: { kind: 'API 34 emulator', device: 'ledger34',
    scope: ['Real accessibility grant/revoke and Settings node adapter', 'Synthetic-node capture to durable queue and manual review',
      'Secure overlay with IME and Back', 'Bundled OCR without active network'],
    references: ['docs/accessibility-capture.md', 'docs/draft-review.md', 'docs/offline-ocr.md', 'docs/acceptance-B.md'],
    notVerified: ['Physical payment apps', 'Live payment execution', 'API 26/36 runtime in Stage B', 'TalkBack service in Stage B'] },
  excluded: inventory.fetch('configured_but_not_registered'),
  scenarios: scenarios
}
File.write(File.join(root, 'docs/recognition-coverage.json'), JSON.pretty_generate(report) + "\n")
puts "Generated #{scenarios.size} scenarios from seven independent fixture groups; no live-support claims."
