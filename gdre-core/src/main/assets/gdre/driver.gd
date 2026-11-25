extends SceneTree

# 仅执行工具自带驱动，目标项目通过 GDRE 的兼容加载器解析。
var bridge: Object
var request: Dictionary
var last_progress: int = 0
var host_mode: bool = false
var stopped: bool = false
var original_paths: Dictionary = {}
var model_dependencies: Dictionary = {}
func _initialize() -> void:
	call_deferred("_run")

func _emit(data: Dictionary) -> void:
	if host_mode:
		print("GODOTTOOL_EVENT=" + JSON.stringify(data))
	else:
		bridge.emitEvent(JSON.stringify(data))

func _cancelled() -> bool:
	return false if host_mode else bridge.isCanceled()

func _finish() -> void:
	stopped = true
	if not host_mode:
		bridge.finish()
	else:
		quit()

func _fail(code: String, detail: String = "") -> void:
	_emit({"type": "error", "code": code, "detail": detail})
	_finish()

## 写入文本并确认刷新成功，失败时发送终态。
## path 为输出文件，text 为内容，append 表示追加。
func _write_text(path: String, text: String, append: bool = false) -> bool:
	var mode: FileAccess.ModeFlags = FileAccess.READ_WRITE if append and FileAccess.file_exists(path) else FileAccess.WRITE
	var file: FileAccess = FileAccess.open(path, mode)
	if file == null:
		_fail("WRITE_FAILED", "%s: %s" % [path, error_string(FileAccess.get_open_error())])
		return false
	if append:
		file.seek_end()
	var stored: bool = file.store_string(text)
	var expected: int = file.get_position()
	file.flush()
	var actual: int = file.get_length()
	var error: Error = file.get_error()
	file.close()
	if not stored or error != OK or actual != expected:
		_fail("WRITE_FAILED", "%s: expected=%d, actual=%d, %s" % [path, expected, actual, error_string(error)])
		return false
	return true

func _relative(path: String) -> String:
	var value: String = path.trim_prefix("res://").replace("\\", "/")
	if value.is_empty() or value.length() > 16384 or value.begins_with("/") or ":" in value or value.to_utf8_buffer().has(0):
		return ""
	for part: String in value.split("/"):
		if part.is_empty() or part == "." or part == "..":
			return ""
	return value

# 原始资源路径优先，编译缓存通过 remap/import 关联回源码位置。
func _source_path(path: String) -> String:
	var canonical: String = "res://" + _relative(path)
	return str(original_paths.get(canonical, canonical))

func _selected(path: String, depth: int = 0) -> bool:
	if depth > 8:
		return false
	var relative: String = _relative(_source_path(path))
	if relative.is_empty():
		return false
	if not request.has("selection"):
		var ext: String = relative.get_extension().to_lower()
		return (bool(request.get("scripts", true)) and ext in ["gd", "gdc", "gde", "cs"]) or (bool(request.get("scenes", true)) and ext in ["tscn", "scn", "tres", "res", "remap", "godot", "binary"])
	var selection: Dictionary = request["selection"]
	if model_dependencies.has(relative):
		return true
	if relative.begins_with(".godot/") or relative.begins_with(".import/"):
		return false
	if relative.begins_with("addons/"):
		return bool(selection.get("plugins", true))
	for folder: String in relative.get_base_dir().split("/"):
		if folder.get_extension().to_lower() in ["mlpackage", "mlmodelc"]:
			return bool(selection.get("assets", true))
	if relative in ["project.godot", "project.binary", "engine.cfg", "export_presets.cfg"]:
		return bool(selection.get("config", true))
	var extension: String = relative.get_extension().to_lower()
	if extension in ["remap", "import", "uid"]:
		return _selected(relative.get_basename(), depth + 1)
	if extension in ["gd", "gdc", "gde", "cs", "gdshader", "gdshaderinc", "shader", "shaderinc"]:
		return bool(selection.get("scripts", true))
	if extension in ["tscn", "scn", "tres", "res"]:
		return bool(selection.get("scenes", true))
	if extension in [
		"png", "jpg", "jpeg", "webp", "svg", "svgz", "bmp", "gif", "ico", "tga", "exr", "hdr", "dds", "ktx", "ktx2", "basis",
		"wav", "ogg", "mp3", "flac", "aac", "m4a", "opus", "wma", "aiff", "aif",
		"mp4", "webm", "ogv", "mov", "avi", "mkv", "mpeg", "mpg", "m4v",
		"ttf", "otf", "woff", "woff2", "fnt", "font", "fontdata",
		"glb", "gltf", "obj", "fbx", "dae", "blend", "3ds", "stl", "ply",
		"onnx", "tflite", "mlmodel", "mlpackage", "ncnnpack",
		"ctex", "stex", "tex", "atlastex", "sample", "oggstr", "mp3str",
	]:
		return bool(selection.get("assets", true))
	return bool(selection.get("config", true))

## 为选中的 glTF 读取本地缓冲区和图片依赖。
## infos 为资源清单，返回是否完成解析。
func _read_model_dependencies(infos: Array) -> bool:
	for info: Object in infos:
		var path: String = info.get_path()
		if path.get_extension().to_lower() != "gltf" or not _selected(path):
			continue
		if info.get_size() > 8388608:
			_fail("SIZE_LIMIT", "Model metadata exceeds limit: " + path)
			return false
		var model: Variant = JSON.parse_string(FileAccess.get_file_as_string(path))
		if not (model is Dictionary):
			continue
		for key: String in ["buffers", "images"]:
			var entries: Variant = model.get(key, [])
			if not (entries is Array):
				continue
			for entry: Variant in entries:
				if not (entry is Dictionary):
					continue
				var uri: String = str(entry.get("uri", ""))
				if uri.is_empty() or ":" in uri or uri.begins_with("//"):
					continue
				var decoded: String = uri.get_slice("#", 0).get_slice("?", 0).replace("+", "%2B").uri_decode().replace("\\", "/")
				if decoded.is_empty():
					continue
				if decoded.begins_with("/"):
					_fail("UNSAFE_PATH", "Absolute model dependency: " + uri)
					return false
				var dependency: String = _relative(_relative(path).get_base_dir().path_join(decoded).simplify_path())
				if dependency.is_empty():
					_fail("UNSAFE_PATH", "Model dependency escaped project: " + uri)
					return false
				model_dependencies[dependency] = true
	return true

func _export_needed(path: String) -> bool:
	if not request.has("selection"):
		return true
	if _selected(path):
		return true
	var relative: String = _relative(path)
	if relative in ["project.godot", "project.binary", "engine.cfg", ".godot/global_script_class_cache.cfg", ".godot/uid_cache.bin"]:
		return true
	var selection: Dictionary = request["selection"]
	return bool(selection.get("assets", true)) and (relative.begins_with(".godot/exported/") or relative.begins_with(".godot/imported/") or relative.begins_with(".import/"))

## 快照固定扫描脚本和内嵌脚本容器，与导出类别独立。
func _snapshot_selected(path: String) -> bool:
	if not bool(request.get("snapshot", false)):
		return false
	var relative: String = _relative(_source_path(path))
	if relative.begins_with(".godot/") or relative.begins_with(".import/"):
		return false
	return relative.get_extension().to_lower() in ["gd", "gdc", "gde", "tscn", "scn", "tres", "res"]

func _needed(path: String) -> bool:
	if _export_needed(path) or _snapshot_selected(path):
		return true
	var relative: String = _relative(path)
	return bool(request.get("snapshot", false)) and relative.get_extension().to_lower() in ["remap", "import", "uid"]

## 写入全量代码清单及对应的原始文件路径。
func _snapshot_catalog(catalog: Array, output: String) -> bool:
	if not bool(request.get("snapshot", false)):
		return true
	var records: Dictionary = {}
	for info: Object in catalog:
		var path: String = info.get_path()
		if not _snapshot_selected(path):
			continue
		var relative: String = _relative(_source_path(path))
		var extension: String = relative.get_extension().to_lower()
		var container: bool = extension in ["tscn", "scn", "tres", "res"]
		if extension in ["gdc", "gde"]:
			relative = relative.get_basename() + ".gd"
		elif extension == "scn":
			relative = relative.get_basename() + ".tscn"
		elif extension == "res":
			relative = relative.get_basename() + ".tres"
		if not records.has(relative):
			records[relative] = {"path": relative, "container": container, "originals": []}
		records[relative]["originals"].append(_relative(path))
	return _write_text(output.path_join("snapshot-catalog.json"), JSON.stringify(records.values()))

func _read_origins(infos: Array) -> void:
	for info: Object in infos:
		var path: String = info.get_path()
		if path.get_extension() not in ["remap", "import"] or info.get_size() > 2097152:
			continue
		var metadata: ConfigFile = ConfigFile.new()
		if metadata.load(path) != OK:
			continue
		var original: String = "res://" + _relative(path.get_basename())
		if metadata.has_section("remap"):
			for key: String in metadata.get_section_keys("remap"):
				var destination: Variant = metadata.get_value("remap", key)
				if destination is String and str(destination).begins_with("res://") and not _relative(destination).is_empty():
					original_paths[destination] = original
		var destinations: Variant = metadata.get_value("deps", "dest_files", PackedStringArray())
		if destinations is Array or destinations is PackedStringArray:
			for destination: String in destinations:
				if destination.begins_with("res://") and not _relative(destination).is_empty():
					original_paths[destination] = original

func _write_fallbacks(settings: Object, project: String, output: String) -> bool:
	var required: Dictionary = {}
	for import_info: Object in settings.get_import_files(false):
		var original: String = import_info.get_export_dest()
		if original.is_empty():
			original = import_info.get_source_file()
		if not _selected(original):
			continue
		var relative: String = _relative(original)
		if relative.is_empty() or FileAccess.file_exists(project.path_join(relative)) or FileAccess.file_exists(output.path_join("raw").path_join(relative)):
			continue
		for path: String in import_info.get_dest_files():
			var dependency: String = _relative(path)
			if not dependency.is_empty():
				required[dependency] = true
	return _write_text(output.path_join("resource-dependencies.json"), JSON.stringify(required.keys()))

func _run() -> void:
	if Engine.has_singleton("GodotToolBridge"):
		bridge = Engine.get_singleton("GodotToolBridge")
		request = JSON.parse_string(bridge.getJobJson())
	else:
		host_mode = true
		var args: PackedStringArray = OS.get_cmdline_user_args()
		if args.size() != 1:
			_fail("BAD_REQUEST", "Expected a request file")
			return
		request = JSON.parse_string(FileAccess.get_file_as_string(args[0]))
	# 在挂载目标资源前加载工具字节码，避免 res 路径被目标项目占用。
	var webp_script: Script = load("res://gdre/webp_recovery.gdc")
	if webp_script == null:
		_fail("ENGINE_INIT", "Could not load WebP recovery helper")
		return
	var settings: Object = Engine.get_singleton("GDRESettings")
	if settings == null:
		_fail("CORE_MISSING")
		return
	var config: Object = Engine.get_singleton("GDREConfig")
	if config != null:
		config.set_setting("download_plugins", false, true)
		config.set_setting("load_embedded_zips", false, true)
		config.set_setting("max_cores_to_use", -1, true)
	var output: String = request["output"]
	if DirAccess.make_dir_recursive_absolute(output) != OK:
		_fail("WRITE_FAILED", output)
		return
	settings.open_log_file(output)
	if not str(request.get("key", "")).is_empty():
		var key_error: int = settings.set_encryption_key_string(request["key"])
		if key_error != OK:
			_fail("BAD_KEY")
			return
	var raw_ready: bool = bool(request.get("rawReady", false))
	_emit({"type": "progress", "stage": "probe"})
	var paths: PackedStringArray = PackedStringArray(request["paths"])
	var error: int = settings.load_project(paths, true)
	if error != OK:
		_fail("BAD_KEY" if settings.had_encryption_error() else "OPEN_FAILED", settings.get_recent_error_string())
		return
	var catalog: Array = settings.get_file_info_array()
	if catalog.size() > 200000:
		_fail("ENTRY_LIMIT")
		return
	_read_origins(catalog)
	if not _read_model_dependencies(catalog):
		return
	if not _snapshot_catalog(catalog, output):
		return
	var infos: Array = []
	for info: Object in catalog:
		if _needed(info.get_path()):
			infos.append(info)
	var total: int = 0
	var names: Dictionary = {}
	var index: Array = []
	for info: Object in infos:
		var path: String = info.get_path()
		var rel: String = _relative(path)
		if info.is_malformed() or rel.is_empty() or names.has(rel):
			_fail("UNSAFE_PATH", path)
			return
		names[rel] = true
		var size: int = info.get_size()
		if size < 0 or total > 9223372036854775807 - size:
			_fail("SIZE_LIMIT")
			return
		total += size
		index.append({"path": rel, "size": size, "md5": info.get_md5().hex_encode(), "encrypted": info.is_encrypted()})
	var raw: String = output.path_join("raw")
	if not raw_ready and total > int(request.get("availableBytes", 9223372036854775807)):
		_fail("NO_SPACE")
		return
	var staging: String = output.path_join(".staging")
	DirAccess.make_dir_recursive_absolute(staging)
	if not raw_ready:
		var done: int = 0
		for position: int in range(infos.size()):
			if _cancelled():
				_finish()
				return
			var info: Object = infos[position]
			var item: Dictionary = index[position]
			var target: String = raw.path_join(item["path"])
			DirAccess.make_dir_recursive_absolute(target.get_base_dir())
			var input: FileAccess = FileAccess.open(info.get_path(), FileAccess.READ)
			var temporary: String = staging.path_join(str(position) + ".part")
			var file: FileAccess = FileAccess.open(temporary, FileAccess.WRITE)
			if input == null or file == null:
				_fail("READ_WRITE_FAILED", item["path"])
				return
			var digest: HashingContext = HashingContext.new()
			digest.start(HashingContext.HASH_MD5)
			var copied: int = 0
			while copied < int(item["size"]):
				if _cancelled():
					input.close()
					file.close()
					_finish()
					return
				var data: PackedByteArray = input.get_buffer(mini(131072, int(item["size"]) - copied))
				if data.is_empty():
					_fail("TRUNCATED", item["path"])
					return
				var stored: bool = file.store_buffer(data)
				if not stored or file.get_error() != OK:
					_fail("WRITE_FAILED", item["path"])
					return
				digest.update(data)
				copied += data.size()
				if Time.get_ticks_msec() - last_progress > 120:
					last_progress = Time.get_ticks_msec()
					_emit({"type": "progress", "stage": "extract", "path": item["path"], "bytes": done + copied, "totalBytes": total, "files": position, "totalFiles": infos.size()})
			input.close()
			file.flush()
			var written_size: int = file.get_length()
			var write_error: Error = file.get_error()
			file.close()
			if write_error != OK or written_size != copied:
				_fail("WRITE_FAILED", item["path"])
				return
			if info.has_md5() and digest.finish().hex_encode() != item["md5"]:
				_fail("CHECKSUM", item["path"])
				return
			if DirAccess.rename_absolute(temporary, target) != OK:
				_fail("WRITE_FAILED", item["path"])
				return
			done += copied
	else:
		# 专用适配的索引包含独立验证的原始大小与摘要。
		var previous_index: Variant = JSON.parse_string(FileAccess.get_file_as_string(output.path_join("raw-index.json")))
		if not (previous_index is Array) or previous_index.size() > 200000:
			_fail("READ_WRITE_FAILED", "raw-index.json")
			return
		index = previous_index
	if not _write_text(output.path_join("raw-index.json"), JSON.stringify(index)):
		return
	if not request.has("selection") and not bool(request.get("scripts", true)) and not bool(request.get("scenes", true)):
		var raw_version: String = settings.get_version_string()
		settings.close_log_file()
		settings.unload_project()
		_emit({"type": "result", "summary": {"engine": raw_version, "resources": index.size(), "scripts": 0}})
		_finish()
		return
	# 提取先于兼容加载，未知版本也保留已校验的原始资源。
	_emit({"type": "progress", "stage": "recover", "totalFiles": infos.size()})
	settings.unload_project(true)
	error = settings.load_project(paths, false)
	if error != OK:
		var diagnostic: String = settings.get_recent_error_string()
		if not _write_text(output.path_join("recovery-report.txt"), diagnostic):
			return
		_emit({"type": "result", "summary": {"resources": index.size(), "scripts": 0, "warning": "RECOVERY_UNSUPPORTED", "diagnostic": diagnostic}})
		_finish()
		return
	var project: String = output.path_join("recovered")
	DirAccess.make_dir_recursive_absolute(project)
	var recover_set: Dictionary = {}
	var snapshot_set: Dictionary = {}
	var loaded_set: Dictionary = {}
	var loaded_files: PackedStringArray = settings.get_file_list(PackedStringArray())
	for path: String in loaded_files:
		loaded_set[path] = true
		if _selected(path):
			recover_set[path] = true
		elif _snapshot_selected(path):
			snapshot_set[path] = true
	var imports: Array = settings.get_import_files(false)
	# 导入器按 dest_files 筛选，补齐所选源码对应的编译资源路径。
	for import_info: Object in imports:
		if _selected(import_info.get_source_file()):
			for path: String in import_info.get_dest_files():
				if loaded_set.has(path):
					recover_set[path] = true
					snapshot_set.erase(path)
		elif _snapshot_selected(import_info.get_source_file()):
			for path: String in import_info.get_dest_files():
				if loaded_set.has(path) and not recover_set.has(path):
					snapshot_set[path] = true
	var recover_files: PackedStringArray = PackedStringArray(recover_set.keys())
	var snapshot_files: PackedStringArray = PackedStringArray(snapshot_set.keys())
	# 校验导入元数据中的输出路径，防止资源转换写出工作区。
	for import_info: Object in imports:
		for import_path: String in [import_info.get_source_file(), import_info.get_export_dest(), import_info.get_export_lossless_copy()]:
			if not import_path.is_empty() and _relative(import_path).is_empty():
				_fail("UNSAFE_PATH", import_path)
				return
	var summary: Dictionary = {"engine": settings.get_version_string(), "resources": index.size(), "scripts": 0, "failedScripts": 0, "converted": 0, "failedConversions": 0}
	var direct_images: int = 0
	var owners: Dictionary = {}
	var source_owners: Dictionary = {}
	for info: Object in imports:
		var source: String = info.get_source_file()
		source_owners[source] = int(source_owners.get(source, 0)) + 1
		for path: String in info.get_dest_files():
			owners[path] = int(owners.get(path, 0)) + 1
	var webp_recovery: RefCounted = webp_script.new()
	var image_jobs: Array = []
	for info: Object in imports:
		if _cancelled():
			_finish()
			return
		if not _selected(info.get_source_file()) or int(owners.get(info.get_path(), 0)) != 1 or int(source_owners.get(info.get_source_file(), 0)) != 1 or not recover_set.has(info.get_path()):
			continue
		var source: String = info.get_source_file()
		if source.get_extension().to_lower() != "webp":
			continue
		var relative: String = _relative(source)
		var metadata_relative: String = _relative(info.get_import_md_path())
		if relative.is_empty() or metadata_relative.is_empty():
			_fail("UNSAFE_PATH", source)
			return
		image_jobs.append({"info": info, "relative": relative, "metadata": metadata_relative})
	var restored_images: Dictionary = webp_recovery.restore(image_jobs, project, _cancelled)
	if restored_images.has("canceled"):
		_finish()
		return
	if restored_images.has("code"):
		_fail(restored_images["code"], restored_images["detail"])
		return
	for image: Dictionary in restored_images["entries"]:
		recover_set.erase(image["cache"])
		snapshot_set.erase(image["cache"])
		direct_images += 1
	recover_files = PackedStringArray(recover_set.keys())
	snapshot_files = PackedStringArray(snapshot_set.keys())
	summary["directImages"] = direct_images
	if not recover_files.is_empty():
		var exporter: Object = ClassDB.instantiate("ImportExporter")
		error = exporter.export_imports(project, recover_files)
		var report: Object = exporter.get_report()
		if report != null:
			summary["scripts"] = report.get_decompiled_scripts().size()
			summary["failedScripts"] = report.get_failed_scripts().size()
			summary["converted"] = report.get_successes().size()
			summary["failedConversions"] = report.get_failed().size()
			if not _write_text(output.path_join("recovery-report.txt"), report.get_report_string()):
				return
		if error != OK:
			summary["warning"] = "RECOVERY_PARTIAL"
	# 额外恢复单独统计，避免快照分析污染用户导出结果。
	if not snapshot_files.is_empty():
		var snapshot_exporter: Object = ClassDB.instantiate("ImportExporter")
		snapshot_exporter.export_imports(project, snapshot_files)
	if direct_images > 0:
		if not _write_text(output.path_join("recovery-report.txt"), "\nDirectly restored lossless WebP images: %d (excluded from native conversion totals)\n" % direct_images, true):
			return
	# 缺少 .remap 的独立字节码不会进入导入器，按版本单独恢复。
	if bool(request.get("scripts", true)) or bool(request.get("snapshot", false)):
		var standalone_ok: int = 0
		var standalone_failed: int = 0
		for path: String in loaded_files:
			if not _selected(path) and not _snapshot_selected(path):
				continue
			if _cancelled():
				_finish()
				return
			var extension: String = path.get_extension().to_lower()
			if extension != "gdc" and extension != "gde":
				continue
			var relative: String = _relative(_source_path(path).get_basename() + ".gd")
			var target: String = project.path_join(relative)
			if FileAccess.file_exists(target):
				continue
			var decompiler: Object = ClassDB.class_call_static("GDScriptDecomp", "create_decomp_for_version", settings.get_version_string(), true)
			if decompiler == null:
				if _selected(path):
					standalone_failed += 1
				continue
			var script_error: int = decompiler.decompile_byte_code_encrypted(path, settings.get_encryption_key()) if extension == "gde" else decompiler.decompile_byte_code(path)
			if script_error != OK:
				if _selected(path):
					standalone_failed += 1
				continue
			DirAccess.make_dir_recursive_absolute(target.get_base_dir())
			if not _write_text(target, decompiler.get_script_text()):
				return
			if _selected(path):
				standalone_ok += 1
		summary["scripts"] = int(summary["scripts"]) + standalone_ok
		summary["failedScripts"] = maxi(int(summary["failedScripts"]), standalone_failed)
		if standalone_failed > 0:
			summary["warning"] = "RECOVERY_PARTIAL"
		if standalone_ok + standalone_failed > 0:
			if not _write_text(output.path_join("recovery-report.txt"), "\nStandalone script recovery: %d succeeded, %d failed\n" % [standalone_ok, standalone_failed], true):
				return
	if bool(request.get("selection", {}).get("config", request.get("scenes", true))) and settings.pack_has_project_config():
		var config_error: int = settings.save_project_config(project)
		if config_error != OK:
			summary["warning"] = "CONFIG_CONVERSION_FAILED"
	if request.has("selection"):
		if not _write_fallbacks(settings, project, output):
			return
	settings.reset_encryption_key()
	settings.close_log_file()
	settings.unload_project()
	_emit({"type": "result", "summary": summary})
	_finish()
