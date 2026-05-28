/***************************************************************************************
* Copyright (c) 2024 Axelera AI
*
* DiffTest is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "elfloader.h"
#include <algorithm>
#include <cstring>

enum class ElfLoaderArch {
  Native,
  LoongArch64,
  LoongArch32S,
  LoongArch32R,
};

static ElfLoaderArch elf_loader_arch = ElfLoaderArch::Native;

void setElfLoaderArch(const char *arch) {
  if (arch == nullptr || strcmp(arch, "native") == 0 || strcmp(arch, "auto") == 0) {
    elf_loader_arch = ElfLoaderArch::Native;
  } else if (strcmp(arch, "loongarch64") == 0) {
    elf_loader_arch = ElfLoaderArch::LoongArch64;
  } else if (strcmp(arch, "loongarch32") == 0 || strcmp(arch, "loongarch32s") == 0) {
    elf_loader_arch = ElfLoaderArch::LoongArch32S;
  } else if (strcmp(arch, "loongarch32r") == 0) {
    elf_loader_arch = ElfLoaderArch::LoongArch32R;
  } else {
    printf("[ERROR] unsupported ELF loader arch '%s'\n", arch);
    exit(EINVAL);
  }
}

static uint64_t normalizeElfLoadAddr(uint64_t addr) {
  switch (elf_loader_arch) {
    case ElfLoaderArch::LoongArch64: return addr & 0xffffffffULL;
    case ElfLoaderArch::LoongArch32S:
    case ElfLoaderArch::LoongArch32R: return addr & 0x1fffffffULL;
    case ElfLoaderArch::Native: return addr;
  }
  return addr;
}

static uint64_t elfAddrToRamOffset(uint64_t addr) {
  switch (elf_loader_arch) {
    case ElfLoaderArch::LoongArch64:
    case ElfLoaderArch::LoongArch32S:
    case ElfLoaderArch::LoongArch32R: return addr;
    case ElfLoaderArch::Native: break;
  }
  return addr >= PMEM_BASE ? addr - PMEM_BASE : addr;
}

static bool elfLoaderIsLoongArch() {
  return elf_loader_arch == ElfLoaderArch::LoongArch64 || elf_loader_arch == ElfLoaderArch::LoongArch32S ||
         elf_loader_arch == ElfLoaderArch::LoongArch32R;
}

static void writeWord(void *ptr, uint64_t offset, uint32_t value) {
  std::memcpy((uint8_t *)ptr + offset, &value, sizeof(value));
}

static size_t writeLoongArchLi(void *ptr, uint64_t offset, uint32_t rd, uint64_t value) {
  const uint32_t rj = rd;
  size_t words = 0;

  if (value == 0) {
    const uint32_t addi_d_zero = 0x02c00000U | rd;
    writeWord(ptr, offset + 4 * words++, addi_d_zero);
    return words * 4;
  }

  const uint32_t lu12i_w = 0x14000000U | (((value >> 12) & 0xfffffU) << 5) | rd;
  const uint32_t ori = 0x03800000U | ((value & 0xfffU) << 10) | (rj << 5) | rd;
  writeWord(ptr, offset + 4 * words++, lu12i_w);
  if ((value & 0xfffU) != 0) {
    writeWord(ptr, offset + 4 * words++, ori);
  }

  if (elf_loader_arch == ElfLoaderArch::LoongArch64 && (value >> 32) != 0) {
    const uint32_t lu32i_d = 0x16000000U | (((value >> 32) & 0xfffffU) << 5) | rd;
    const uint32_t lu52i_d = 0x03000000U | (((value >> 52) & 0xfffU) << 10) | (rj << 5) | rd;
    writeWord(ptr, offset + 4 * words++, lu32i_d);
    if ((value >> 52) != 0) {
      writeWord(ptr, offset + 4 * words++, lu52i_d);
    }
  }

  return words * 4;
}

static size_t writeLoongArchJump(void *ptr, uint64_t offset, uint64_t target, uint64_t cmdline_addr) {
  constexpr uint32_t reg_a0 = 4;
  constexpr uint32_t reg_a1 = 5;
  constexpr uint32_t reg_a2 = 6;
  constexpr uint32_t reg_t0 = 12;
  size_t bytes = 0;

  bytes += writeLoongArchLi(ptr, offset + bytes, reg_a0, 0);
  bytes += writeLoongArchLi(ptr, offset + bytes, reg_a1, cmdline_addr);
  bytes += writeLoongArchLi(ptr, offset + bytes, reg_a2, 0);
  bytes += writeLoongArchLi(ptr, offset + bytes, reg_t0, target);

  constexpr uint32_t jr_t0 = 0x4c000180U;
  size_t words = bytes / 4;
  writeWord(ptr, offset + 4 * words++, jr_t0);
  return bytes + 4;
}

void ElfBinary::load() {
  assert(size >= sizeof(Elf64_Ehdr));
  eh64 = (const Elf64_Ehdr *)raw;
  assert(IS_ELF32(*eh64) || IS_ELF64(*eh64));

  if (IS_ELF32(*eh64))
    parse(data32);
  else
    parse(data64);
}

template <typename ehdr_t, typename phdr_t, typename shdr_t, typename sym_t>
void ElfBinary::parse(ElfBinaryData<ehdr_t, phdr_t, shdr_t, sym_t> &data) {
  data.eh = (const ehdr_t *)raw;
  data.ph = (const phdr_t *)(raw + data.eh->e_phoff);
  entry = data.eh->e_entry;
  assert(size >= data.eh->e_phoff + data.eh->e_phnum * sizeof(*data.ph));
  for (unsigned i = 0; i < data.eh->e_phnum; i++) {
    if (data.ph[i].p_type == PT_LOAD && data.ph[i].p_memsz) {
      if (data.ph[i].p_filesz) {
        assert(size >= data.ph[i].p_offset + data.ph[i].p_filesz);
        sections.push_back({
          .data_src = (const uint8_t *)raw + data.ph[i].p_offset,
          .data_dst = data.ph[i].p_paddr,
          .data_len = data.ph[i].p_filesz,
          .zero_dst = data.ph[i].p_paddr + data.ph[i].p_filesz,
          .zero_len = data.ph[i].p_memsz - data.ph[i].p_filesz,
        });
      }
    }
  }
  std::sort(sections.begin(), sections.end(),
            [](const ElfSection &a, const ElfSection &b) { return a.data_dst < b.data_dst; });
}

ElfBinaryFile::ElfBinaryFile(const char *filename) : filename(filename) {
  int fd = open(filename, O_RDONLY);
  struct stat s;
  assert(fd != -1);
  assert(fstat(fd, &s) >= 0);
  size = s.st_size;

  raw = (uint8_t *)mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
  assert(raw != MAP_FAILED);
  close(fd);

  load();
}

ElfBinaryFile::~ElfBinaryFile() {
  if (raw)
    munmap((void *)raw, size);
}

bool isElfFile(const char *filename) {
  int fd = -1;

#ifdef NO_IMAGE_ELF
  return false;
#endif

  fd = open(filename, O_RDONLY);
  assert(fd);

  uint8_t buf[4];

  size_t sz = read(fd, buf, 4);
  if (!IS_ELF(*((const Elf64_Ehdr *)buf))) {
    close(fd);
    return false;
  }

  close(fd);
  return true;
}

long readFromElf(void *ptr, const char *file_name, long buf_size) {
  auto elf_file = ElfBinaryFile(file_name);

  if (elf_file.sections.size() < 1) {
    printf("The requested elf '%s' contains zero sections\n", file_name);
    return -1;
  }

  uint64_t len_written = 0;
  auto base_addr = normalizeElfLoadAddr(elf_file.sections[0].data_dst);
  auto entry_addr = normalizeElfLoadAddr(elf_file.entry);
  if (entry_addr == 0) {
    entry_addr = base_addr;
  }

  if (base_addr != PMEM_BASE) {
    printf(
        "The first address in the elf does not match the base of the physical memory.\n"
        "A jump stub will be installed at 0x%lx to enter 0x%lx.\n",
        PMEM_BASE, entry_addr);
  }

  for (auto section: elf_file.sections) {
    auto len = section.data_len + section.zero_len;
    auto load_addr = normalizeElfLoadAddr(section.data_dst);
    auto offset = elfAddrToRamOffset(load_addr);

    if (offset + len > buf_size) {
      printf("The size (%ld bytes) of the section at address 0x%lx offset 0x%lx is larger than buf_size!\n", len,
             load_addr, offset);
      return -1;
    }

    printf("Loading %ld bytes at address 0x%lx", len, load_addr);
    if (load_addr != section.data_dst) {
      printf(" (ELF address 0x%lx)", section.data_dst);
    }
    printf(" at offset 0x%lx\n", offset);
    std::memset((uint8_t *)ptr + offset, 0, len);
    std::memcpy((uint8_t *)ptr + offset, section.data_src, section.data_len);
    len_written = std::max<uint64_t>(len_written, offset + len);
  }

  if (base_addr != PMEM_BASE) {
    if (!elfLoaderIsLoongArch()) {
      printf("[ERROR] ELF load address 0x%lx needs a jump stub, but --arch is not a LoongArch mode\n", base_addr);
      return -1;
    }
    const uint64_t cmdline_addr = PMEM_BASE + 0x100;
    const uint64_t cmdline_offset = elfAddrToRamOffset(cmdline_addr);
    if (cmdline_offset >= (uint64_t)buf_size) {
      printf("[ERROR] LoongArch boot cmdline address 0x%lx is outside simulated RAM\n", cmdline_addr);
      return -1;
    }
    *((uint8_t *)ptr + cmdline_offset) = 0;

    auto jump_offset = elfAddrToRamOffset(PMEM_BASE);
    auto jump_size = writeLoongArchJump(ptr, jump_offset, entry_addr, cmdline_addr);
    len_written = std::max<uint64_t>(len_written, std::max(jump_offset + jump_size, cmdline_offset + 1));
    printf("Installed LoongArch jump stub at address 0x%lx offset 0x%lx to 0x%lx with cmdline at 0x%lx (%lu bytes)\n",
           PMEM_BASE, jump_offset, entry_addr, cmdline_addr, jump_size);
  }

  return len_written;
}
