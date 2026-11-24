extends SceneTree

# 仅执行工具自带驱动，目标项目通过 GDRE 的兼容加载器解析。
var bridge: Object
var request: Dictionary
var last_progress: int = 0
var host_mode: bool = false
var stopped: bool = false
var original_paths: Dictionary = {}

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
	if relative.begins_with(".godot/") or relative.begins_with(".import/"):
		return false
	if relative.begins_with("addons/"):
		return bool(selection.get("plugins", true))
	if relative in ["project.godot", "project.binary", "engine.cfg", "export_presets.cfg"]:
		return bool(selection.get("config", true))
	var extension: String = relative.get_extension().to_lower()
	if extension in ["remap", "import", "uid"]:
		return _selected(relative.get_basename(), depth + 1)
	if extension in ["gd", "gdc", "gde", "cs"]:
		return bool(selection.get("scripts", true))
	if extension in ["tscn", "scn", "tres", "res"]:
		return bool(selection.get("scenes", true))
	return bool(selection.get("assets", true))

func _needed(path: String) -> bool:
	if not request.has("selection"):
		return true
	if _selected(path):
		return true
	var relative: String = _relative(path)
	if relative in ["project.godot", "project.binary", "engine.cfg", ".godot/global_script_class_cache.cfg", ".godot/uid_cache.bin"]:
		return true
	var selection: Dictionary = request["selection"]
	return bool(selection.get("assets", true)) and (relative.begins_with(".godot/exported/") or relative.begins_with(".godot/imported/") or relative.begins_with(".import/"))

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
	var settings: Object = Engine.get_singleton("GDRESettings")
	if settings == null:
		_fail("CORE_MISSING")
		return
	var config: Object = Engine.get_singleton("GDREConfig")
	if config != null:
		config.set_setting("download_plugins", false, true)
		config.set_setting("load_embedded_zips", false, true)
		config.set_setting("max_cores_to_use", 2, true)
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
	if not bool(request.get("rawReady", false)) and total > int(request.get("availableBytes", 9223372036854775807)):
		_fail("NO_SPACE")
		return
	var staging: String = output.path_join(".staging")
	DirAccess.make_dir_recursive_absolute(staging)
	if not bool(request.get("rawReady", false)):
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
		if not (previous_index is Array):
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
	_emit({"type": "progress", "stage": "recover", "totalFiles": infos.size()})
	# 提取先于兼容加载，未知版本也保留已校验的原始资源。
	settings.unload_project()
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
	var recover_files: PackedStringArray = PackedStringArray()
	var loaded_files: PackedStringArray = settings.get_file_list(PackedStringArray())
	for path: String in loaded_files:
		if _selected(path):
			recover_files.append(path)
	# 导入器按 dest_files 筛选，补齐所选源码对应的编译资源路径。
	for import_info: Object in settings.get_import_files(false):
		if _selected(import_info.get_source_file()):
			for path: String in import_info.get_dest_files():
				if path in loaded_files and path not in recover_files:
					recover_files.append(path)
	# 校验导入元数据中的输出路径，防止资源转换写出工作区。
	for import_info: Object in settings.get_import_files(false):
		for import_path: String in [import_info.get_source_file(), import_info.get_export_dest(), import_info.get_export_lossless_copy()]:
			if not import_path.is_empty() and _relative(import_path).is_empty():
				_fail("UNSAFE_PATH", import_path)
				return
	var summary: Dictionary = {"engine": settings.get_version_string(), "resources": index.size(), "scripts": 0, "failedScripts": 0, "converted": 0, "failedConversions": 0}
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
	# 缺少 .remap 的独立字节码不会进入导入器，按版本单独恢复。
	if bool(request.get("scripts", true)):
		var standalone_ok: int = 0
		var standalone_failed: int = 0
		for path: String in loaded_files:
			if not _selected(path):
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
				standalone_failed += 1
				continue
			var script_error: int = decompiler.decompile_byte_code_encrypted(path, settings.get_encryption_key()) if extension == "gde" else decompiler.decompile_byte_code(path)
			if script_error != OK:
				standalone_failed += 1
				continue
			DirAccess.make_dir_recursive_absolute(target.get_base_dir())
			if not _write_text(target, decompiler.get_script_text()):
				return
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
