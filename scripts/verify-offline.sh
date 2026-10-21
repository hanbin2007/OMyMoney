#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
MANIFEST=app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
if [ ! -f "$MANIFEST" ]; then
    printf '%s\n' 'Build :app:processDebugManifest before running this check.' >&2
    exit 1
fi
ruby -rrexml/document - "$MANIFEST" app/src/main/res/xml/data_extraction_rules.xml <<'RUBY'
manifest = REXML::Document.new(File.read(ARGV[0]))
permissions = manifest.get_elements('//uses-permission') + manifest.get_elements('//uses-permission-sdk-23')
abort 'Missing INTERNET permission for remote tools' unless permissions.any? { |p| p.attributes['android:name'] == 'android.permission.INTERNET' }
application = manifest.elements['manifest/application']
abort 'Cleartext transport enabled' unless application.attributes['android:usesCleartextTraffic'] == 'false'
abort 'Backup is enabled' unless application.attributes['android:allowBackup'] == 'false'
abort 'Legacy full backup is enabled' unless application.attributes['android:fullBackupContent'] == 'false'
abort 'Missing extraction rules' unless application.attributes['android:dataExtractionRules'] == '@xml/data_extraction_rules'
rules = REXML::Document.new(File.read(ARGV[1]))
domains = %w[root file database sharedpref external device_root device_file device_database device_sharedpref]
%w[cloud-backup device-transfer].each do |mode|
  section = rules.elements["data-extraction-rules/#{mode}"]
  abort "Missing #{mode}" unless section
  abort "Unexpected include in #{mode}" unless section.get_elements('include').empty?
  excluded = section.get_elements('exclude').select { |e| e.attributes['path'] == '.' }.map { |e| e.attributes['domain'] }
  abort "Incomplete exclusions for #{mode}" unless (domains - excluded).empty?
end
puts 'PASS: remote tools require TLS; legacy, cloud and device-transfer backups are disabled. This compatibility-named script no longer claims a network-disabled APK.'
RUBY
