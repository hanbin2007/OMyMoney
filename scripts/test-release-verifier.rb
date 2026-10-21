#!/usr/bin/env ruby
require 'tmpdir'
require 'open3'
require 'rexml/document'

Dir.mktmpdir('omymoney-release-guard') do |directory|
  base = REXML::Document.new('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.omymoney.android" android:versionName="0.2.0" android:versionCode="2"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><application android:allowBackup="false" android:usesCleartextTraffic="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules"><receiver android:name="app.omymoney.platform.updates.UpdateInstallReceiver" android:exported="false"/></application></manifest>')
  variants = {
    valid: ->(_document) {},
    debug: ->(document) { document.root.elements['application'].add_attribute('android:debuggable', 'true') },
    cleartext: ->(document) { document.root.elements['application'].add_attribute('android:usesCleartextTraffic', 'true') },
    no_network: ->(document) { document.root.delete_element('uses-permission') },
    public_installer: ->(document) { document.root.elements['application/receiver'].add_attribute('android:exported', 'true') },
    fixture: ->(document) { document.root.elements['application'].add_element('provider', { 'android:name' => 'app.omymoney.OcrFixtureProvider' }) },
    instrumentation: ->(document) { document.root.add_element('instrumentation') },
    backup: ->(document) { document.root.elements['application'].add_attribute('android:allowBackup', 'true') },
    minimum: ->(document) { document.root.elements['uses-sdk'].add_attribute('android:minSdkVersion', '25') },
    unknown_rules: ->(document) { document.root.elements['application'].add_attribute('android:dataExtractionRules', '@ref/0x00000000') },
  }
  variants.each do |name, mutate|
    document = REXML::Document.new(base.to_s)
    mutate.call(document)
    path = File.join(directory, "#{name}.xml")
    File.write(path, document.to_s)
    output, status = Open3.capture2e('ruby', File.join(__dir__, 'verify-release.rb'), path)
    abort "Unexpected #{name} result: #{output}" unless status.success? == (name == :valid)
  end
  puts "PASS: #{variants.length} release manifest guard cases"
end
