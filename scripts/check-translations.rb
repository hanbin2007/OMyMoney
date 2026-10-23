require 'rexml/document'

root = File.expand_path('../app/src/main/res', __dir__)
def strings(directory)
  Dir.glob(File.join(directory, '*.xml')).each_with_object({}) do |path, result|
    REXML::Document.new(File.read(path)).get_elements('resources/string').each do |entry|
      key = entry.attributes.fetch('name').to_s
      abort "Duplicate string #{key} in #{directory}" if result.key?(key)
      result[key] = entry
    end
  end
end
def placeholders(entry)
  entry.to_s.scan(/%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z%]/)
end

base = strings(File.join(root, 'values'))
abort 'Default strings are empty' if base.empty?
%w[values-en values-b+zh+Hans values-b+zh+Hant].each do |locale|
  entries = strings(File.join(root, locale))
  missing = base.keys - entries.keys
  extra = entries.keys - base.keys
  abort "#{locale}: missing=#{missing.inspect}; extra=#{extra.inspect}" unless missing.empty? && extra.empty?
  base.each do |key, original|
    translated = entries.fetch(key)
    abort "#{locale}/#{key}: format placeholders differ" unless placeholders(original) == placeholders(translated)
    abort "#{locale}/#{key}: empty translation" if translated.texts.map(&:value).join.strip.empty?
  end
  puts "PASS: #{locale}, #{entries.size} strings, matching format placeholders"
end
