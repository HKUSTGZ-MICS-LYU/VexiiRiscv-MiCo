input_path = $input
output_path = $output
source_name = $source
target_name = $target
raise "missing KLayout alias arguments" if [input_path, output_path, source_name, target_name].any? { |v| v.nil? || v.empty? }
layout = RBA::Layout.new
layout.read(input_path)
source = layout.cell(source_name)
raise "GDS cell not found: #{source_name}" if source.nil?
layout.rename_cell(source.cell_index, target_name)
layout.write(output_path)
