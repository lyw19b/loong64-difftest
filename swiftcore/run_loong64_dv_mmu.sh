#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DIFFTEST_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd -- "${DIFFTEST_DIR}/../.." && pwd)"

DV_DIR="${REPO_ROOT}/verify/loong64-dv"
OUT_DIR="${OUT_DIR:-${REPO_ROOT}/build/loong64-dv-mmu}"
APP_DIR="${APP_DIR:-${REPO_ROOT}/verify/apps/rig-dv}"
VENV_DIR="${VENV_DIR:-${REPO_ROOT}/build/loong64-dv-venv}"
TESTLIST="${TESTLIST:-${DV_DIR}/yaml/swiftcore_rig_dv_mmu_hw_ptw_testlist.yaml}"
TEST_NAME="${TEST_NAME:-swiftcore_loongarch64_mmu_hw_ptw_1m}"
ITERATIONS="${ITERATIONS:-10}"
BATCH_SIZE="${BATCH_SIZE:-1}"
START_SEED="${START_SEED:-}"
SEED="${SEED:-}"
MAX_TIME="${MAX_TIME:-2000000}"
TIMEOUT_OK="${TIMEOUT_OK:-1}"
DIFF_COMMIT_TRACE="${DIFF_COMMIT_TRACE:-0}"

if [[ ! -d "${DV_DIR}" ]]; then
  echo "loong64-dv not found: ${DV_DIR}" >&2
  exit 1
fi

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  python3 -m venv "${VENV_DIR}"
fi

"${VENV_DIR}/bin/python" - <<'PY'
import importlib.util
import subprocess
import sys

missing = [
    pkg for pkg, mod in [
        ("PyYAML", "yaml"),
        ("bitstring", "bitstring"),
        ("pyvsc", "vsc"),
    ]
    if importlib.util.find_spec(mod) is None
]
if missing:
    subprocess.check_call([sys.executable, "-m", "pip", "install", *missing])
PY

mkdir -p "${OUT_DIR}" "${APP_DIR}"

DV_RUN_ARGS=(
  "${DV_DIR}/run.py"
  --target swiftcore_loongarch64
  --custom_target "${DV_DIR}/pygen/loongarch/pygen_src/target/swiftcore_loongarch64"
  --simulator pyflow
  --steps gen
  -tl "${TESTLIST}"
  -tn "${TEST_NAME}"
  -i "${ITERATIONS}"
  -bz "${BATCH_SIZE}"
  -o "${OUT_DIR}"
)

if [[ -n "${SEED}" ]]; then
  DV_RUN_ARGS+=(--seed "${SEED}")
elif [[ -n "${START_SEED}" ]]; then
  DV_RUN_ARGS+=(--start_seed "${START_SEED}")
fi

"${VENV_DIR}/bin/python" "${DV_RUN_ARGS[@]}"

for idx in $(seq 0 "$((ITERATIONS - 1))"); do
  ASM_IN="${OUT_DIR}/asm_test/${TEST_NAME}_${idx}.S"
  ASM_SWIFTCORE="${APP_DIR}/${TEST_NAME}_${idx}.S"
  ELF="${APP_DIR}/${TEST_NAME}_${idx}.elf"
  BIN="${APP_DIR}/${TEST_NAME}_${idx}.bin"
  HEX="${APP_DIR}/${TEST_NAME}_${idx}.hex"

  if [[ ! -f "${ASM_IN}" ]]; then
    echo "Generated assembly not found: ${ASM_IN}" >&2
    exit 1
  fi

  "${VENV_DIR}/bin/python" - "${ASM_IN}" "${ASM_SWIFTCORE}" <<'PY'
import sys

src, dst = sys.argv[1:3]
with open(src, "r", encoding="utf-8") as fin, open(dst, "w", encoding="utf-8") as fout:
    for line in fin:
        stripped = line.strip()
        if stripped == "syscall 0":
            fout.write("                        b           .\n")
            continue
        fout.write(line)
        if stripped.startswith("csrwr"):
            for _ in range(4):
                fout.write("                        nop\n")
PY

  loongarch64-linux-gnu-gcc \
    -static -nostdlib -nostartfiles -mcmodel=normal \
    -I "${DV_DIR}/user_extension" \
    -Ttext=0x1C000000 \
    "${ASM_SWIFTCORE}" \
    -o "${ELF}"

  loongarch64-linux-gnu-objcopy -O binary "${ELF}" "${BIN}"
  "${REPO_ROOT}/scripts/bin_to_readmemh.py" "${BIN}" "${HEX}"

  echo "Generated ${idx}: ${BIN}"
done

for idx in $(seq 0 "$((ITERATIONS - 1))"); do
  BIN="${APP_DIR}/${TEST_NAME}_${idx}.bin"
  echo "Running DiffTest ${idx}: ${BIN}"
  make -C "${DIFFTEST_DIR}" swiftcore-run \
    SWIFTCORE_IMAGE="${BIN}" \
    SWIFTCORE_MAX_CYCLES="${MAX_TIME}" \
    SWIFTCORE_DUMP_COMMIT_TRACE="${DIFF_COMMIT_TRACE}"
done

echo "RIG-DV artifacts: ${APP_DIR}"
