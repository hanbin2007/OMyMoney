#!/usr/bin/env ruby
require 'json'
require 'rexml/document'
require 'open3'
require 'fileutils'
require 'tmpdir'
require 'digest'

root = File.expand_path('..', __dir__)
cache = File.join(ENV.fetch('GRADLE_USER_HOME', File.expand_path('~/.gradle')), 'caches/modules-2/files-2.1')
modules = JSON.parse(File.read(File.join(root, 'outputs/release-dependencies.json')))
destination = File.join(root, 'app/src/main/assets/licenses/third-party')
FileUtils.mkdir_p(destination)

def pom_licenses(cache, group, name, version, seen = [])
  key = [group, name, version]
  abort "Cyclic POM parent: #{key}" if seen.include?(key)
  path = Dir[File.join(cache, *key, '*', '*.pom')].first
  abort "Missing cached POM: #{key.join(':')}" unless path
  document = REXML::Document.new(File.read(path))
  licenses = document.get_elements('project/licenses/license').map do |license|
    { 'name' => license.elements['name']&.text.to_s, 'url' => license.elements['url']&.text.to_s }
  end
  return [licenses, key.join(':')] unless licenses.empty?
  parent = document.elements['project/parent']
  abort "Unresolved license metadata: #{key.join(':')}" unless parent
  pom_licenses(cache, *%w[groupId artifactId version].map { |field| parent.elements[field]&.text.to_s }, seen + [key])
end

def entries(path)
  output, status = Open3.capture2e('unzip', '-Z1', path)
  abort "Cannot inspect archive #{path}: #{output}" unless status.success?
  output.lines.map(&:strip)
end

def extract(path, entry)
  output, status = Open3.capture2('unzip', '-p', path, entry, binmode: true)
  abort "Cannot read notice #{path}:#{entry}" unless status.success?
  output
end

modules.each do |item|
  key = item.values_at('group', 'name', 'version')
  item['licenses'], item['licenseMetadataFrom'] = pom_licenses(cache, *key)
  slug = key.join('-').gsub(/[^A-Za-z0-9_.-]/, '_')
  item['notices'] = []
  item['artifacts'] = []
  paths = Dir[File.join(cache, *key, '*', '*.{jar,aar}')].reject { |path| path.match?(/-(sources|javadoc)\.(jar|aar)$/) }
  paths.each do |path|
    item['artifacts'] << { 'file' => File.basename(path), 'sha256' => Digest::SHA256.file(path).hexdigest }
    Dir.mktmpdir('omymoney-notices') do |temporary|
      archives = [[path, '']]
      if File.extname(path) == '.aar' && entries(path).include?('classes.jar')
        nested = File.join(temporary, 'classes.jar')
        File.binwrite(nested, extract(path, 'classes.jar'))
        archives << [nested, 'classes.jar/']
      end
      archives.each do |archive, prefix|
        entries(archive).select { |entry| entry.match?(%r{(?:^|/)(?:LICENSE(?:[._-].*)?|NOTICE(?:[._-].*)?|COPYING(?:[._-].*)?|AL2\.0|LGPL2\.1)$}i) }.each do |entry|
          next if entry.end_with?('/')
          bytes = extract(archive, entry)
          next if bytes.empty?
          relative = "#{slug}/#{(prefix + entry).gsub(/[^A-Za-z0-9_.-]/, '_')}"
          output = File.join(destination, relative)
          FileUtils.mkdir_p(File.dirname(output))
          File.binwrite(output, bytes)
          item['notices'] << relative
        end
      end
    end
  end
  if key == %w[javax.xml.stream stax-api 1.0-2]
    item['selectedLicense'] = 'CDDL-1.0'
    item['unmodifiedSourceArchive'] = 'stax-api-1.0-2-sources.jar'
    item['sourceUrl'] = 'https://repo.maven.apache.org/maven2/javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2-sources.jar'
  end
end
File.write(File.join(root, 'app/src/main/assets/licenses/dependencies.json'), JSON.pretty_generate(modules) + "\n")
File.write(File.join(root, 'docs/release-dependencies.json'), JSON.pretty_generate(modules) + "\n")
puts "Recorded #{modules.length} resolved modules and #{modules.sum { |item| item['notices'].length }} original license/notice entries."
