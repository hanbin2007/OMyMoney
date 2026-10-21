#!/usr/bin/env ruby
require 'rexml/document'

abort 'Usage: ruby scripts/verify-release.rb <manifest.xml> [<extraction-resource-id> <decoded-rules.xml>]' unless [1, 3].include?(ARGV.length)
document = REXML::Document.new(File.read(ARGV.fetch(0)))
manifest = document.root
abort 'Wrong package' unless manifest.attributes['package'] == 'app.omymoney.android'
sdk = manifest.elements['uses-sdk']
abort 'Wrong minimum SDK' unless sdk.attributes['android:minSdkVersion'] == '26'
abort 'Wrong target SDK' unless sdk.attributes['android:targetSdkVersion'] == '36'
application = manifest.elements['application']
%w[debuggable testOnly].each do |attribute|
  abort "Release has #{attribute}" if application.attributes["android:#{attribute}"] == 'true'
end
abort 'Backup enabled' unless application.attributes['android:allowBackup'] == 'false'
abort 'Legacy backup enabled' unless application.attributes['android:fullBackupContent'] == 'false'
expected_rules = ARGV.length == 3 ? "@ref/#{ARGV.fetch(1)}" : '@xml/data_extraction_rules'
abort 'Missing extraction exclusions' unless application.attributes['android:dataExtractionRules'] == expected_rules
if ARGV.length == 3
  rules = REXML::Document.new(File.read(ARGV.fetch(2)))
  domains = %w[root file database sharedpref external device_root device_file device_database device_sharedpref]
  %w[cloud-backup device-transfer].each do |mode|
    section = rules.elements["data-extraction-rules/#{mode}"]
    abort "Missing #{mode}" unless section
    abort "Unexpected include in #{mode}" unless section.get_elements('include').empty?
    excluded = section.get_elements('exclude').select { |entry| entry.attributes['path'] == '.' }.map { |entry| entry.attributes['domain'] }
    abort "Incomplete exclusions for #{mode}" unless (domains - excluded).empty?
  end
end
abort 'Test instrumentation in release' unless manifest.get_elements('instrumentation').empty?
permissions = manifest.get_elements('uses-permission') + manifest.get_elements('uses-permission-sdk-23')
%w[android.permission.INTERNET android.permission.REQUEST_INSTALL_PACKAGES].each do |name|
  abort "Missing remote-tool permission: #{name}" unless permissions.any? { |entry| entry.attributes['android:name'] == name }
end
abort 'Cleartext transport enabled' unless application.attributes['android:usesCleartextTraffic'] == 'false'
installer = application.get_elements('receiver').find { |entry| entry.attributes['android:name'] == 'app.omymoney.platform.updates.UpdateInstallReceiver' }
abort 'Missing or exported install result receiver' unless installer && installer.attributes['android:exported'] == 'false'
application.elements.each do |entry|
  name = entry.attributes['android:name'].to_s
  abort "Test/debug component in release: #{name}" if name.match?(/Fixture|TestActivity|androidx\.activity\.ComponentActivity|androidx\.compose\.ui\.tooling/)
end
puts "PASS: release #{manifest.attributes['package']} #{manifest.attributes['android:versionName']} (#{manifest.attributes['android:versionCode']}); SDK 26-36, TLS-only remote tools, private backup, no debug/test entry."
