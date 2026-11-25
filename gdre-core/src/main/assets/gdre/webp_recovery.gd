extends RefCounted

## 复用 Godot 4.5 单层纹理中的无损 WebP，保留导入配置。
## 导入参数沿用 GDRE Tools 2.7.4 TextureExporter 的恢复规则。
## @author Tingdong
## @date 2026-11-25

const MAX_FILE_BYTES: int = 33554432
const MAX_PIXELS: int = 16777216

var _lock: Mutex = Mutex.new()
var _canceled: bool = false

## 最多两路检查并保存图片，结束后由调用线程汇总结果。
## jobs 为已校验的候选清单；check 用于调用线程检查取消。
func restore(jobs: Array, project: String, check: Callable) -> Dictionary:
	if jobs.is_empty():
		return {"entries": []}
	var results: Array = []
	results.resize(jobs.size())
	var group: int = WorkerThreadPool.add_group_task(_restore_one.bind(jobs, project, results), jobs.size(), 2, true, "RestoreWebP")
	while not WorkerThreadPool.is_group_task_completed(group):
		if check.call():
			_lock.lock()
			_canceled = true
			_lock.unlock()
		OS.delay_msec(10)
	WorkerThreadPool.wait_for_group_task_completion(group)
	if _canceled or check.call():
		return {"canceled": true}
	var entries: Array = []
	for result: Variant in results:
		if not (result is Dictionary):
			return {"code": "RECOVERY_FAILED", "detail": "WebP worker did not return a result"}
		if result.has("code"):
			return result
		if result.has("cache"):
			entries.append(result)
	return {"entries": entries}

## 在线程池中处理独立图片，不调用页面或桥接回调。
## index 为任务下标，results 是已固定大小的结果数组。
func _restore_one(index: int, jobs: Array, project: String, results: Array) -> void:
	_lock.lock()
	var canceled: bool = _canceled
	_lock.unlock()
	var result: Dictionary = {}
	if not canceled:
		var job: Dictionary = jobs[index]
		var entry: Dictionary = inspect(job["info"])
		if not entry.is_empty():
			_lock.lock()
			canceled = _canceled
			_lock.unlock()
			if not canceled:
				result = save(entry, project, job["relative"], job["metadata"], index)
				if result.is_empty():
					result = {"cache": entry["cache"], "source": entry["source"]}
	_lock.lock()
	results[index] = result
	_lock.unlock()

## 检查纹理布局和像素，未知布局交给 GDRE。
## info 为导入信息；返回已验证的载荷及配置，空字典表示不适用。
func inspect(info: Object) -> Dictionary:
	if info.get_ver_major() != 4 or info.get_ver_minor() != 5 or info.get_importer() != "texture":
		return {}
	if info.get_type() != "CompressedTexture2D" or bool(info.get_metadata_prop().get("has_editor_variant", false)):
		return {}
	var source: String = info.get_source_file()
	var metadata: String = info.get_import_md_path()
	var destinations: PackedStringArray = info.get_dest_files()
	if not source.begins_with("res://") or source.get_extension().to_lower() != "webp" or metadata != source + ".import" or destinations.size() != 1:
		return {}
	var path: String = destinations[0]
	if path != info.get_path() or path.get_extension().to_lower() != "ctex":
		return {}
	var file: FileAccess = FileAccess.open(path, FileAccess.READ)
	if file == null:
		return {}
	var length: int = file.get_length()
	if length < 82 or length > MAX_FILE_BYTES or file.get_buffer(4).get_string_from_ascii() != "GST2" or file.get_32() != 1:
		return {}
	var width: int = file.get_32()
	var height: int = file.get_32()
	var flags: int = file.get_32()
	var mipmap_limit: int = file.get_32()
	if mipmap_limit != 4294967295 or file.get_32() != 0 or file.get_32() != 0 or file.get_32() != 0:
		return {}
	if file.get_32() != 2:
		return {}
	var stored_width: int = file.get_16()
	var stored_height: int = file.get_16()
	var mipmaps: int = file.get_32()
	var format: int = file.get_32()
	var size: int = file.get_32()
	# 仅接管已验证的无缩放、无 mipmap、RGB/RGBA 布局。
	if width <= 0 or height <= 0 or width > 16384 or height > 16384 or width * height > MAX_PIXELS or width != stored_width or height != stored_height:
		return {}
	if flags != 0x0d000000 or mipmaps != 0 or format not in [Image.FORMAT_RGB8, Image.FORMAT_RGBA8] or size != length - 56:
		return {}
	var data: PackedByteArray = file.get_buffer(size)
	file.close()
	if data.size() != size or data.slice(0, 4).get_string_from_ascii() != "RIFF" or data.decode_u32(4) != size - 8:
		return {}
	if data.slice(8, 16).get_string_from_ascii() != "WEBPVP8L" or data[20] != 0x2f:
		return {}
	var chunk_size: int = data.decode_u32(16)
	var dimensions: int = data.decode_u32(21)
	if 20 + chunk_size + chunk_size % 2 != size or dimensions >> 29 != 0:
		return {}
	if (dimensions & 0x3fff) + 1 != width or ((dimensions >> 14) & 0x3fff) + 1 != height:
		return {}
	var image: Image = Image.new()
	if image.load_webp_from_buffer(data) != OK or image.get_width() != width or image.get_height() != height or image.get_format() != format:
		return {}
	var used_channels: int = image.detect_used_channels()
	var copy: Object = ClassDB.class_call_static("ImportInfo", "copy", info)
	var params: Dictionary = {}
	params["compress/mode"] = 0
	params["compress/high_quality"] = false
	params["compress/lossy_quality"] = 1.0
	params["compress/uastc_level"] = 2
	params["compress/rdo_quality_loss"] = 0
	params["compress/hdr_compression"] = 0
	params["compress/normal_map"] = 0
	params["compress/channel_pack"] = 1 if used_channels in [Image.USED_CHANNELS_R, Image.USED_CHANNELS_RG] else 0
	params["mipmaps/generate"] = false
	params["mipmaps/limit"] = -1
	params["roughness/mode"] = 0
	params["roughness/src_normal"] = ""
	params["process/channel_remap/red"] = 0
	params["process/channel_remap/green"] = 1
	params["process/channel_remap/blue"] = 2
	params["process/channel_remap/alpha"] = 3
	params["process/fix_alpha_border"] = false
	params["process/premult_alpha"] = false
	params["process/normal_map_invert_y"] = false
	params["process/hdr_as_srgb"] = false
	params["process/hdr_clamp_exposure"] = false
	params["process/size_limit"] = 0
	params["detect_3d/compress_to"] = 0
	copy.set_export_dest(source)
	copy.set_params(params)
	return {"source": source, "cache": path, "metadata": metadata, "data": data, "info": copy}

## 将已验证图片和导入配置提交到恢复目录。
## entry 为 inspect 的结果，relative 为安全相对路径；返回错误信息或空字典。
func save(entry: Dictionary, project: String, relative: String, metadata_relative: String, token: int) -> Dictionary:
	var target: String = project.path_join(relative)
	var metadata: String = project.path_join(metadata_relative)
	if FileAccess.file_exists(target) or FileAccess.file_exists(metadata):
		return {"code": "OUTPUT_EXISTS", "detail": relative}
	var stage: String = project.get_base_dir().path_join(".staging")
	if DirAccess.make_dir_recursive_absolute(stage) != OK or DirAccess.make_dir_recursive_absolute(target.get_base_dir()) != OK:
		return {"code": "WRITE_FAILED", "detail": relative}
	var image_part: String = stage.path_join("texture_%d.part" % token)
	var metadata_part: String = stage.path_join("texture_%d.import.part" % token)
	var result: Dictionary = _write(entry, image_part, metadata_part)
	if result.is_empty():
		if DirAccess.rename_absolute(metadata_part, metadata) != OK or DirAccess.rename_absolute(image_part, target) != OK:
			result = {"code": "WRITE_FAILED", "detail": relative}
	if FileAccess.file_exists(image_part):
		DirAccess.remove_absolute(image_part)
	if FileAccess.file_exists(metadata_part):
		DirAccess.remove_absolute(metadata_part)
	return result

## 写入并检查临时载荷与导入配置。
## entry 为已验证资源，两个 part 为工作区临时文件；返回错误或空字典。
func _write(entry: Dictionary, image_part: String, metadata_part: String) -> Dictionary:
	var file: FileAccess = FileAccess.open(image_part, FileAccess.WRITE)
	if file == null:
		return {"code": "WRITE_FAILED", "detail": entry["source"]}
	var stored: bool = file.store_buffer(entry["data"])
	file.flush()
	var valid: bool = stored and file.get_error() == OK and file.get_length() == entry["data"].size()
	file.close()
	if not valid or entry["info"].save_to(metadata_part) != OK:
		return {"code": "WRITE_FAILED", "detail": entry["source"]}
	return {}
