input_path = RBA::Application.instance.get_config("input")
input_path = $input if input_path.nil? || input_path.empty?
layout = RBA::Layout.new
layout.read(input_path)
puts "DBU #{layout.dbu}"
layout.each_cell do |cell|
  box = cell.bbox
  puts "#{cell.name} #{box.width} #{box.height} #{cell.child_cells.size}"
end
