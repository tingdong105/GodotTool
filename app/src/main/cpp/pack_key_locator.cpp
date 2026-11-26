#include <jni.h>
#include <capstone/capstone.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace {
constexpr size_t MAX_GROUPS = 256;

/** 读取一个小端 ARM64 指令字。 */
uint32_t wordAt(const uint8_t *bytes) {
    return uint32_t(bytes[0]) | uint32_t(bytes[1]) << 8 | uint32_t(bytes[2]) << 16 | uint32_t(bytes[3]) << 24;
}

/** 将 W/X 寄存器编号归一化，未知寄存器返回 -1。 */
int registerIndex(csh handle, unsigned int reg) {
    const char *name = cs_reg_name(handle, reg);
    if (!name || (name[0] != 'x' && name[0] != 'w') || name[1] < '0' || name[1] > '9') return -1;
    int index = name[1] - '0';
    if (name[2] >= '0' && name[2] <= '9') {
        index = index * 10 + name[2] - '0';
        if (name[3] != '\0') return -1;
    } else if (name[2] != '\0') return -1;
    return index < 31 ? index : -1;
}

/** 仅筛选需要交给 Capstone 分析的异或与字节写入窗口。 */
bool isCandidate(const uint8_t *code) {
    if ((wordAt(code) & 0xff200000) != 0x4a000000) return false;
    unsigned int xorCount = 0, storeCount = 0;
    for (size_t i = 0; i < 48; i += 4) {
        const uint32_t word = wordAt(code + i);
        xorCount += (word & 0xff200000) == 0x4a000000;
        storeCount += (word & 0x3b400000) == 0x38000000;
    }
    return xorCount >= 2 && storeCount >= 2;
}

/** 使用 Capstone 的操作数信息解析常量地址与字节读取引用。 */
std::vector<uint64_t> analyze(csh handle, const uint8_t *code, size_t size, uint64_t address, cs_insn *instruction) {
    std::array<uint64_t, 31> values{}, references{};
    std::array<bool, 31> known{}, referenced{};
    size_t xorCount = 0, storeCount = 0;
    while (cs_disasm_iter(handle, &code, &size, &address, instruction)) {
        const cs_arm64 &arm = instruction->detail->arm64;
        const auto *ops = arm.operands;
        if (instruction->id == ARM64_INS_RET) {
            if (xorCount >= 2 && storeCount >= 2) break;
            known.fill(false); referenced.fill(false);
            xorCount = storeCount = 0;
            continue;
        }
        if (instruction->id == ARM64_INS_BL || instruction->id == ARM64_INS_BLR ||
            (instruction->id == ARM64_INS_B && (arm.cc == ARM64_CC_INVALID || arm.cc == ARM64_CC_AL))) {
            known.fill(false); referenced.fill(false);
            xorCount = storeCount = 0;
            continue;
        }
        if (instruction->id == ARM64_INS_EOR) ++xorCount;
        if (instruction->id == ARM64_INS_STRB || instruction->id == ARM64_INS_STURB) ++storeCount;
        const int destination = arm.op_count > 0 && ops[0].type == ARM64_OP_REG ? registerIndex(handle, ops[0].reg) : -1;
        if ((instruction->id == ARM64_INS_ADRP || instruction->id == ARM64_INS_ADR) &&
            arm.op_count == 2 && destination >= 0 && ops[1].type == ARM64_OP_IMM) {
            known[destination] = true;
            values[destination] = uint64_t(ops[1].imm);
            continue;
        }
        if (instruction->id == ARM64_INS_ADD && arm.op_count == 3 && destination >= 0 &&
            ops[1].type == ARM64_OP_REG && ops[2].type == ARM64_OP_IMM) {
            const int source = registerIndex(handle, ops[1].reg);
            known[destination] = source >= 0 && known[source] && ops[2].shift.value < 64;
            if (known[destination]) values[destination] = values[source] + (uint64_t(ops[2].imm) << ops[2].shift.value);
            continue;
        }
        if (instruction->id == ARM64_INS_MOV && arm.op_count == 2 && destination >= 0) {
            if (ops[1].type == ARM64_OP_REG) {
                const int source = registerIndex(handle, ops[1].reg);
                known[destination] = source >= 0 && known[source];
                if (known[destination]) values[destination] = values[source];
            } else {
                known[destination] = ops[1].type == ARM64_OP_IMM;
                if (known[destination]) values[destination] = uint64_t(ops[1].imm);
            }
            continue;
        }
        if ((instruction->id == ARM64_INS_LDRB || instruction->id == ARM64_INS_LDURB) &&
            arm.op_count >= 2 && ops[1].type == ARM64_OP_MEM && ops[1].mem.index == ARM64_REG_INVALID) {
            const int base = registerIndex(handle, ops[1].mem.base);
            if (base >= 0 && known[base]) {
                const uint64_t value = values[base] + ops[1].mem.disp;
                references[base] = referenced[base] ? std::min(references[base], value) : value;
                referenced[base] = true;
            }
        }
        for (uint8_t i = 0; i < arm.op_count; ++i) {
            if (ops[i].type == ARM64_OP_REG && (ops[i].access & CS_AC_WRITE)) {
                const int index = registerIndex(handle, ops[i].reg);
                if (index >= 0) known[index] = false;
            }
            if (arm.writeback && ops[i].type == ARM64_OP_MEM) {
                const int index = registerIndex(handle, ops[i].mem.base);
                if (index >= 0) known[index] = false;
            }
        }
    }
    std::vector<uint64_t> result;
    if (xorCount < 2 || storeCount < 2) return result;
    for (size_t i = 0; i < referenced.size(); ++i) if (referenced[i]) result.push_back(references[i]);
    std::sort(result.begin(), result.end());
    result.erase(std::unique(result.begin(), result.end()), result.end());
    if (result.size() < 3 || result.size() > 8) result.clear();
    return result;
}

/** 分析有界映射窗口，不打开或执行目标 SO。 */
std::vector<jlong> scan(const uint8_t *code, uint64_t address, size_t start, size_t length) {
    csh handle;
    if (cs_open(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, &handle) != CS_ERR_OK) return {};
    cs_option(handle, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn *instruction = cs_malloc(handle);
    if (!instruction) { cs_close(&handle); return {}; }
    std::vector<jlong> output;
    std::vector<uint64_t> previous;
    size_t groupCount = 0;
    try {
        const size_t end = start + length;
        for (size_t position = start; position + 48 <= end && groupCount < MAX_GROUPS; position += 4) {
            if (!isCandidate(code + position)) continue;
            const size_t first = position > start + 96 ? position - 96 : start;
            const size_t last = std::min(end, position + 80);
            auto group = analyze(handle, code + first, last - first, address + first, instruction);
            if (group.empty() || group == previous) continue;
            output.push_back(jlong(group.size()));
            for (const auto value : group) output.push_back(jlong(value));
            previous = std::move(group);
            ++groupCount;
        }
    } catch (...) {
        cs_free(instruction, 1); cs_close(&handle);
        throw;
    }
    cs_free(instruction, 1);
    cs_close(&handle);
    return output;
}
}

/** 将 Capstone 的地址引用组返回 Kotlin 校验器。 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_winter_godot_tool_feature_compatibility_CapstoneLocator_nativeReferences(
        JNIEnv *env, jobject, jobject buffer, jlong address, jint start, jint length) {
    const auto *bytes = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!bytes || start < 0 || start % 4 != 0 || length < 0 || jlong(start) + length > capacity) return env->NewLongArray(0);
    try {
        auto output = scan(bytes, uint64_t(address), size_t(start), size_t(length));
        jlongArray result = env->NewLongArray(jsize(output.size()));
        if (result && !output.empty()) env->SetLongArrayRegion(result, 0, jsize(output.size()), output.data());
        return result;
    } catch (const std::bad_alloc &) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Capstone allocation failed");
        return nullptr;
    }
}
